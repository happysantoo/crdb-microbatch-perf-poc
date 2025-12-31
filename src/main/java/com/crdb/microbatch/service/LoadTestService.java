package com.crdb.microbatch.service;

import com.crdb.microbatch.repository.TestInsertRepository;
import com.crdb.microbatch.task.CrdbInsertTask;
import com.vajrapulse.api.pattern.adaptive.AdaptiveLoadPattern;
import com.vajrapulse.api.pattern.adaptive.DefaultRampDecisionPolicy;
import com.vajrapulse.api.task.TaskIdentity;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter.Protocol;
import com.vajrapulse.exporter.report.HtmlReportExporter;
import com.vajrapulse.worker.pipeline.LoadTestRunner;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import com.vajrapulse.api.task.TaskLifecycle;
import com.vajrapulse.api.task.TaskResult;
import com.vajrapulse.api.task.TaskResultFailure;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that orchestrates the load test execution using VajraPulse LoadTestRunner.
 * 
 * <p>This implementation uses AdaptiveLoadPattern (VajraPulse 0.9.11) with:
 * <ul>
 *   <li>Adaptive TPS adjustment based on failure rate</li>
 *   <li>Manual metrics tracking for accurate failure detection</li>
 *   <li>Automatic recovery from low TPS</li>
 *   <li>Comprehensive metrics and reporting</li>
 * </ul>
 */
@Service
public class LoadTestService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);
    
    // Adaptive load pattern configuration - Fixed for proper TPS adjustment
    private static final double MIN_TPS = 500.0;  // Lower minimum to allow more aggressive ramp down
    private static final double MAX_TPS = 8000.0;  // Reduced further to prevent queue overload
    private static final double INITIAL_TPS = 500.0;  // Start lower for gradual ramp up
    private static final double RAMP_INCREMENT = 250.0;  // Smaller increments for more control
    private static final double RAMP_DECREMENT = 2000.0;  // Aggressive reduction but not too extreme
    private static final Duration RAMP_INTERVAL = Duration.ofMillis(2000);  // Slower check for stability (was 500ms)
    private static final Duration SUSTAIN_DURATION = Duration.ofSeconds(5);  // Shorter sustain for faster adaptation
    private static final double ERROR_THRESHOLD = 0.03;  // 3% failure rate (more sensitive)
    private static final Duration TEST_DURATION = Duration.ofMinutes(30);
    
    private static final int EXPORT_INTERVAL_SECONDS = 10;
    private static final String OTLP_ENDPOINT = "http://localhost:4317";
    private static final String REPORT_DIR = "reports";
    private static final String REPORT_FILE = "crdb-microbatch-load-test-report.html";
    
    private final CrdbInsertTask task;
    private final TestInsertRepository repository;
    private final MeterRegistry meterRegistry;
    private final HikariDataSource dataSource;
    
    private Object testResult;
    private volatile boolean testCompleted = false;

    /**
     * Constructor for LoadTestService.
     * 
     * @param task the CRDB insert task
     * @param repository the test insert repository
     * @param meterRegistry the Micrometer registry for metrics
     * @param dataSource the HikariCP data source
     */
    public LoadTestService(
            CrdbInsertTask task, 
            TestInsertRepository repository, 
            MeterRegistry meterRegistry,
            HikariDataSource dataSource) {
        this.task = task;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.dataSource = dataSource;
        registerShutdownHook();
    }

    /**
     * Registers a JVM shutdown hook to ensure final reports are generated.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!testCompleted) {
                log.warn("=== Shutdown detected - Generating final report ===");
                generateFinalReport(testResult);
            }
        }, "load-test-shutdown-hook"));
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Starting CRDB Microbatch Adaptive Load Test ===");
        log.info("Configuration:");
        log.info("  Min TPS: {}", MIN_TPS);
        log.info("  Max TPS: {}", MAX_TPS);
        log.info("  Initial TPS: {}", INITIAL_TPS);
        log.info("  Ramp Increment: {}", RAMP_INCREMENT);
        log.info("  Ramp Decrement: {}", RAMP_DECREMENT);
        log.info("  Ramp Interval: {} seconds", RAMP_INTERVAL.getSeconds());
        log.info("  Sustain Duration: {} seconds", SUSTAIN_DURATION.getSeconds());
        log.info("  Error Threshold: {}%", ERROR_THRESHOLD * 100.0);
        log.info("  Test Duration: {} minutes", TEST_DURATION.toMinutes());
        
        initializeDatabase();
        task.init();
        
        TaskIdentity identity = createTaskIdentity();
        OpenTelemetryExporter otelExporter = createOpenTelemetryExporter(identity);
        HtmlReportExporter htmlExporter = createHtmlReportExporter();
        
        executeLoadTest(otelExporter, htmlExporter);
        
        task.teardown();
        
        long finalCount = repository.getCount();
        log.info("=== Load Test Complete ===");
        log.info("Final row count: {}", finalCount);
    }
    
    /**
     * Initializes the database by clearing existing data.
     */
    private void initializeDatabase() {
        try {
            long existingCount = repository.getCount();
            if (existingCount > 0) {
                log.info("Found {} existing rows, dropping and recreating table...", existingCount);
                repository.dropAndRecreateTable();
                log.info("Table recreated successfully");
            } else {
                log.info("Table is empty, no cleanup needed");
            }
        } catch (Exception e) {
            log.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Creates task identity for observability tagging.
     * 
     * @return the task identity
     */
    private TaskIdentity createTaskIdentity() {
        return new TaskIdentity(
            "crdb-insert-load-test",
            Map.of(
                "scenario", "microbatch-performance",
                "component", "cockroachdb-insert",
                "database", "cockroachdb"
            )
        );
    }

    /**
     * Creates and configures the OpenTelemetry exporter.
     * 
     * @param identity the task identity
     * @return the configured exporter
     */
    private OpenTelemetryExporter createOpenTelemetryExporter(TaskIdentity identity) {
        return OpenTelemetryExporter.builder()
            .endpoint(OTLP_ENDPOINT)
            .protocol(Protocol.GRPC)
            .exportInterval(EXPORT_INTERVAL_SECONDS)
            .taskIdentity(identity)
            .resourceAttributes(createResourceAttributes())
            .build();
    }

    /**
     * Creates resource attributes for OpenTelemetry.
     * 
     * @return map of resource attributes
     */
    private Map<String, String> createResourceAttributes() {
        return Map.of(
            "service.name", "crdb-microbatch-load-test",
            "service.version", "1.0.0",
            "environment", "test",
            "test.type", "microbatch-performance",
            "database", "cockroachdb"
        );
    }

    /**
     * Creates and configures the HTML report exporter.
     * 
     * @return the configured HTML report exporter
     */
    private HtmlReportExporter createHtmlReportExporter() {
        Path reportPath = Paths.get(REPORT_DIR, REPORT_FILE);
        return new HtmlReportExporter(reportPath, meterRegistry);
    }

    /**
     * Executes the load test using LoadTestRunner with AdaptiveLoadPattern.
     * 
     * <p>This method:
     * <ul>
     *   <li>Creates AdaptiveLoadPattern with failure-rate-aware TPS adjustment</li>
     *   <li>Uses manual metrics tracking for accurate failure detection</li>
     *   <li>Automatically adjusts TPS based on failure rate</li>
     *   <li>Provides comprehensive metrics and reporting</li>
     * </ul>
     * 
     * @param otelExporter the OpenTelemetry exporter
     * @param htmlExporter the HTML report exporter
     * @throws Exception if execution fails
     */
    private void executeLoadTest(OpenTelemetryExporter otelExporter, HtmlReportExporter htmlExporter) 
            throws Exception {
        // Create manual metrics tracker since VajraPulse metrics may not be in registry
        ManualMetricsTracker metricsTracker = new ManualMetricsTracker();
        
        // Wrap task to track metrics manually
        TaskLifecycle trackingTask = createTrackingTask(metricsTracker);
        
        try (LoadTestRunner runner = createLoadTestRunner(otelExporter, htmlExporter)) {
            // Create metrics provider that uses manual tracker
            BackpressureMetricsProvider metricsProvider = new BackpressureMetricsProvider(
                meterRegistry, 
                null,  // Backpressure provider no longer available in Vortex 0.0.12
                metricsTracker  // Manual metrics tracker as fallback
            );
            
            // Create listener for AdaptiveLoadPattern events (VajraPulse 0.9.11)
            // Pass meterRegistry so listener can display failure rates
            AdaptivePatternListenerImpl patternListener = new AdaptivePatternListenerImpl(meterRegistry);
            
            // Create decision policy with error threshold (VajraPulse 0.9.11 API change)
            // DefaultRampDecisionPolicy(errorThreshold) - ramps down when failure rate exceeds threshold
            // Using explicit threshold to ensure failure rate checking is enabled
            DefaultRampDecisionPolicy decisionPolicy = new DefaultRampDecisionPolicy(ERROR_THRESHOLD);

            // Log policy configuration to verify it's set up correctly
            log.info("🎯 Decision Policy Configuration:");
            log.info("   Error Threshold: {}% ({} as decimal)", ERROR_THRESHOLD * 100.0, ERROR_THRESHOLD);
            log.info("   Policy will trigger RAMP DOWN when failure rate exceeds {}%", ERROR_THRESHOLD * 100.0);
            log.info("   Metrics Provider: {} (with manual tracker fallback)", metricsProvider.getClass().getSimpleName());
            
            // Create AdaptiveLoadPattern using builder pattern (VajraPulse 0.9.11)
            // Note: errorThreshold is now configured via DefaultRampDecisionPolicy
            AdaptiveLoadPattern adaptivePattern = AdaptiveLoadPattern.builder()
                .initialTps(INITIAL_TPS)
                .rampIncrement(RAMP_INCREMENT)
                .rampDecrement(RAMP_DECREMENT)
                .rampInterval(RAMP_INTERVAL)
                .maxTps(MAX_TPS)
                .minTps(MIN_TPS)
                .sustainDuration(SUSTAIN_DURATION)
                .decisionPolicy(decisionPolicy)
                .metricsProvider(metricsProvider)
                .listener(patternListener)
                .build();
            
            log.info("=== Starting Adaptive Load Test ===");
            log.info("Load Pattern: AdaptiveLoadPattern (VajraPulse 0.9.11)");
            log.info("🎯 TPS Configuration:");
            log.info("   Initial TPS: {}", INITIAL_TPS);
            log.info("   Min TPS: {} (can ramp down to this level)", MIN_TPS);
            log.info("   Max TPS: {} (will not exceed this)", MAX_TPS);
            log.info("   Ramp Up: +{} TPS every {} ms", RAMP_INCREMENT, RAMP_INTERVAL.toMillis());
            log.info("   Ramp Down: -{} TPS every {} ms when failure rate > {}%", RAMP_DECREMENT, RAMP_INTERVAL.toMillis(), ERROR_THRESHOLD * 100.0);
            log.info("   Sustain Duration: {} seconds before next adjustment", SUSTAIN_DURATION.toSeconds());
            log.info("🔧 Queue Configuration (from CrdbInsertTask):");
            log.info("   Queue Size: 8,000 items (40 batches × 200 items)");
            log.info("   Rejection Threshold: 60% (4,800 items)");
            log.info("   Expected Rejection Response Time: 1-2 seconds at high TPS");
            log.info("⚠️ IMPORTANT: Manual metrics tracking active (VajraPulse metrics not in registry)");
            log.info("⚠️ Queue rejections (ItemRejectedException) are tracked as failures for TPS adjustment");
            log.info("⚠️ AdaptiveLoadPattern will RAMP DOWN when failure rate exceeds 3%");
            log.info("Duration: {} minutes", TEST_DURATION.toMinutes());
            
            // Run the test with tracking task
            var result = runner.run(trackingTask, adaptivePattern);
            testResult = result;
            testCompleted = true;
            
            // Log final results
            logFinalResults(result);
            
            generateFinalReport(result);
        }
    }
    

    /**
     * Logs final test results.
     */
    private void logFinalResults(Object result) {
        log.info("=== Load Test Complete ===");
        
        try {
            var totalExecutions = (long) result.getClass().getMethod("totalExecutions").invoke(result);
            var successCount = (long) result.getClass().getMethod("successCount").invoke(result);
            var failureCount = (long) result.getClass().getMethod("failureCount").invoke(result);
            var successRate = (double) result.getClass().getMethod("successRate").invoke(result);
            
            log.info("Execution Results:");
            log.info("  Total Executions: {}", totalExecutions);
            log.info("  Successful: {}", successCount);
            log.info("  Failed: {}", failureCount);
            log.info("  Success Rate: {}%", String.format("%.2f", successRate * 100.0));
            
            // Calculate actual throughput
            double actualTps = (double) totalExecutions / TEST_DURATION.toSeconds();
            log.info("  Actual TPS: {} (range: {} - {})", 
                String.format("%.2f", actualTps), MIN_TPS, MAX_TPS);
        } catch (Exception e) {
            log.warn("Could not extract test result metrics: {}", e.getMessage());
        }
    }

    /**
     * Creates a task wrapper that tracks execution metrics manually.
     * 
     * <p>This is needed because VajraPulse MetricsPipeline may not expose
     * metrics in the Micrometer registry in a way that's accessible to
     * AdaptiveLoadPattern's MetricsProvider.
     * 
     * @param tracker the manual metrics tracker
     * @return the tracking task wrapper
     */
    private TaskLifecycle createTrackingTask(ManualMetricsTracker tracker) {
        return new TaskLifecycle() {
            @Override
            public void init() throws Exception {
                task.init();
            }
            
            @Override
            public TaskResult execute(long iteration) throws Exception {
                tracker.incrementTotal();
                try {
                    TaskResult result = task.execute(iteration);
                    // Check if result is a failure
                    if (result instanceof TaskResultFailure) {
                        tracker.incrementFailures();
                    } else {
                        tracker.incrementSuccess();
                    }
                    return result;
                } catch (Exception e) {
                    tracker.incrementFailures();
                    throw e;
                }
            }
            
            @Override
            public void teardown() throws Exception {
                task.teardown();
            }
        };
    }
    
    /**
     * Manual metrics tracker for execution counts with enhanced timing accuracy.
     *
     * <p>This tracks metrics manually since VajraPulse MetricsPipeline
     * may not expose them in the Micrometer registry. Enhanced to provide
     * better synchronization with AdaptiveLoadPattern timing.
     */
    private static class ManualMetricsTracker implements BackpressureMetricsProvider.ManualMetricsTracker {
        private final AtomicLong totalExecutions = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private volatile long lastLogTime = System.currentTimeMillis();

        void incrementTotal() {
            long total = totalExecutions.incrementAndGet();
            // Log progress every 1000 executions to show activity
            if (total % 1000 == 0) {
                long now = System.currentTimeMillis();
                double rate = 1000.0 / (now - lastLogTime) * 1000.0;  // Items per second
                lastLogTime = now;
                log.debug("📈 Manual Tracker Progress: {} total executions (current rate: {:.0f} items/sec)", total, rate);
            }
        }

        void incrementSuccess() {
            successCount.incrementAndGet();
        }

        void incrementFailures() {
            long failures = failureCount.incrementAndGet();
            long total = totalExecutions.get();
            // Log immediately when failure rate gets concerning
            if (failures > 0 && total > 0) {
                double failureRate = (double) failures / total;
                if (failures % 100 == 0 && failureRate > 0.01) {  // Log every 100 failures if rate > 1%
                    log.warn("⚠️ Manual Tracker: {} failures out of {} total ({:.2f}% failure rate)",
                        failures, total, failureRate * 100.0);
                }
            }
        }

        @Override
        public long getTotalExecutions() {
            return totalExecutions.get();
        }

        @Override
        public long getSuccessCount() {
            return successCount.get();
        }

        @Override
        public long getFailureCount() {
            return failureCount.get();
        }
    }
    
    /**
     * Creates the load test runner with exporters.
     * 
     * @param otelExporter the OpenTelemetry exporter
     * @param htmlExporter the HTML report exporter
     * @return the configured runner
     */
    private LoadTestRunner createLoadTestRunner(OpenTelemetryExporter otelExporter, HtmlReportExporter htmlExporter) {
        return LoadTestRunner.builder()
            .addExporter(otelExporter)
            .addExporter(htmlExporter)
            .withRunId(otelExporter.getRunId())
            .withPeriodic(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
            .withPercentiles(0.5, 0.9, 0.95, 0.99)
            .build();
    }

    /**
     * Generates final report with test results.
     * 
     * @param result the pipeline execution result
     */
    private void generateFinalReport(Object result) {
        try {
            long finalCount = repository.getCount();
            printFinalSummary(finalCount, result);
            log.info("HTML report generated: {}/{}", REPORT_DIR, REPORT_FILE);
        } catch (Exception e) {
            log.error("Failed to generate final report", e);
        }
    }

    /**
     * Prints final summary to console.
     * 
     * @param finalCount the final row count
     * @param result the pipeline execution result
     */
    private void printFinalSummary(long finalCount, Object result) {
        log.info("========================================");
        log.info("=== FINAL TEST SUMMARY ===");
        log.info("========================================");
        log.info("Final Row Count: {}", finalCount);
        
        if (result != null) {
            try {
                var totalExecutions = (long) result.getClass().getMethod("totalExecutions").invoke(result);
                var successCount = (long) result.getClass().getMethod("successCount").invoke(result);
                var failureCount = (long) result.getClass().getMethod("failureCount").invoke(result);
                var successRate = (double) result.getClass().getMethod("successRate").invoke(result);
                
                log.info("Total Executions: {}", totalExecutions);
                log.info("Successful: {}", successCount);
                log.info("Failed: {}", failureCount);
                log.info("Success Rate: {}%", String.format("%.2f", successRate * 100.0));
            } catch (Exception e) {
                log.warn("Could not extract test result metrics: {}", e.getMessage());
            }
        }
        
        log.info("========================================");
    }
}
