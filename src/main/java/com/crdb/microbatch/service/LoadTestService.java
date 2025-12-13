package com.crdb.microbatch.service;

import com.crdb.microbatch.backpressure.CompositeBackpressureProvider;
import com.crdb.microbatch.backpressure.ConnectionPoolBackpressureProvider;
import com.crdb.microbatch.repository.TestInsertRepository;
import com.crdb.microbatch.task.CrdbInsertTask;
import com.vajrapulse.api.pattern.adaptive.AdaptiveLoadPattern;
import com.vajrapulse.api.task.TaskIdentity;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter.Protocol;
import com.vajrapulse.exporter.report.HtmlReportExporter;
import com.vajrapulse.vortex.backpressure.QueueDepthBackpressureProvider;
import com.vajrapulse.worker.pipeline.MetricsPipeline;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Service that orchestrates the load test execution using VajraPulse MetricsPipeline.
 * 
 * <p>This implementation uses AdaptiveLoadPattern (VajraPulse 0.9.9) with:
 * <ul>
 *   <li>Adaptive TPS adjustment based on backpressure and failure rate</li>
 *   <li>Composite backpressure (queue depth + connection pool)</li>
 *   <li>Automatic recovery from low TPS</li>
 *   <li>Comprehensive metrics and reporting</li>
 * </ul>
 */
@Service
public class LoadTestService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);
    
    // Adaptive load pattern configuration
    private static final double MIN_TPS = 1000.0;
    private static final double MAX_TPS = 20000.0;
    private static final double INITIAL_TPS = 1000.0;
    private static final double RAMP_INCREMENT = 500.0;
    private static final double RAMP_DECREMENT = 1000.0;
    private static final Duration RAMP_INTERVAL = Duration.ofSeconds(5);
    private static final Duration SUSTAIN_DURATION = Duration.ofSeconds(10);
    private static final double ERROR_THRESHOLD = 0.01;  // 1% failure rate
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
     * @param dataSource the HikariCP data source (for connection pool backpressure)
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
     * Executes the load test using MetricsPipeline with AdaptiveLoadPattern.
     * 
     * <p>This method:
     * <ul>
     *   <li>Creates AdaptiveLoadPattern with backpressure-aware TPS adjustment</li>
     *   <li>Uses composite backpressure (queue + connection pool) for capacity detection</li>
     *   <li>Automatically adjusts TPS based on failure rate and backpressure</li>
     *   <li>Provides comprehensive metrics and reporting</li>
     * </ul>
     * 
     * @param otelExporter the OpenTelemetry exporter
     * @param htmlExporter the HTML report exporter
     * @throws Exception if execution fails
     */
    private void executeLoadTest(OpenTelemetryExporter otelExporter, HtmlReportExporter htmlExporter) 
            throws Exception {
        try (MetricsPipeline pipeline = createMetricsPipeline(otelExporter, htmlExporter)) {
            // Create composite backpressure provider
            CompositeBackpressureProvider backpressureProvider = createBackpressureProvider();
            
            // Create metrics provider that combines Micrometer metrics with backpressure
            BackpressureMetricsProvider metricsProvider = new BackpressureMetricsProvider(
                meterRegistry, 
                backpressureProvider
            );
            
            // Create listener for AdaptiveLoadPattern events (VajraPulse 0.9.9)
            AdaptivePatternListenerImpl patternListener = new AdaptivePatternListenerImpl();
            
            // Create AdaptiveLoadPattern using builder pattern (VajraPulse 0.9.9)
            AdaptiveLoadPattern adaptivePattern = AdaptiveLoadPattern.builder()
                .initialTps(INITIAL_TPS)
                .rampIncrement(RAMP_INCREMENT)
                .rampDecrement(RAMP_DECREMENT)
                .rampInterval(RAMP_INTERVAL)
                .maxTps(MAX_TPS)
                .minTps(MIN_TPS)
                .sustainDuration(SUSTAIN_DURATION)
                .errorThreshold(ERROR_THRESHOLD)
                .metricsProvider(metricsProvider)
                .listener(patternListener)
                .build();
            
            log.info("=== Starting Adaptive Load Test ===");
            log.info("Load Pattern: AdaptiveLoadPattern (VajraPulse 0.9.9)");
            log.info("TPS Range: {} - {}", MIN_TPS, MAX_TPS);
            log.info("Initial TPS: {}", INITIAL_TPS);
            log.info("Backpressure: Composite (Queue + Connection Pool)");
            log.info("Error Threshold: {}%", ERROR_THRESHOLD * 100.0);
            log.info("Duration: {} minutes", TEST_DURATION.toMinutes());
            
            // Run the test
            var result = pipeline.run(task, adaptivePattern);
            testResult = result;
            testCompleted = true;
            
            // Log final results
            logFinalResults(result);
            
            generateFinalReport(result);
        }
    }
    
    /**
     * Creates composite backpressure provider combining queue and connection pool metrics.
     * 
     * @return the composite backpressure provider
     */
    private CompositeBackpressureProvider createBackpressureProvider() {
        // Queue depth backpressure (using supplier from task)
        // Note: We need to get the queue depth supplier from the task
        // For now, we'll create a simple supplier that returns 0
        // The actual queue depth is tracked by Vortex internally
        Supplier<Integer> queueDepthSupplier = () -> {
            // Try to get queue depth from Vortex metrics
            try {
                var gauge = meterRegistry.find("vortex.queue.depth");
                if (gauge != null && gauge.gauge() != null) {
                    return (int) gauge.gauge().value();
                }
            } catch (Exception e) {
                // Ignore - return 0 if metric not available
            }
            return 0;
        };
        
        int maxQueueSize = 50 * 20;  // batchSize * 20 batches
        QueueDepthBackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
            queueDepthSupplier,
            maxQueueSize
        );
        
        // Connection pool backpressure
        ConnectionPoolBackpressureProvider poolProvider = new ConnectionPoolBackpressureProvider(dataSource);
        
        // Composite (uses maximum of both sources)
        return new CompositeBackpressureProvider(queueProvider, poolProvider);
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
     * Creates the metrics pipeline with exporters.
     * 
     * @param otelExporter the OpenTelemetry exporter
     * @param htmlExporter the HTML report exporter
     * @return the configured pipeline
     */
    private MetricsPipeline createMetricsPipeline(OpenTelemetryExporter otelExporter, HtmlReportExporter htmlExporter) {
        return MetricsPipeline.builder()
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
