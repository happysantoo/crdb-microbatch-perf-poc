package com.crdb.microbatch.service;

import com.crdb.microbatch.repository.TestInsertRepository;
import com.crdb.microbatch.task.CrdbInsertTask;
import com.vajrapulse.api.StaticLoad;
import com.vajrapulse.api.TaskIdentity;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter.Protocol;
import com.vajrapulse.exporter.report.HtmlReportExporter;
import com.vajrapulse.worker.pipeline.MetricsPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

/**
 * Service that orchestrates the load test execution using VajraPulse MetricsPipeline.
 * 
 * <p>This is a simplified, straightforward implementation that:
 * <ul>
 *   <li>Uses StaticLoad pattern for consistent load</li>
 *   <li>Provides comprehensive metrics and reporting</li>
 * </ul>
 */
@Service
public class LoadTestService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);
    
    // Static load pattern configuration
    private static final double TARGET_TPS = 10000.0;
    private static final Duration TEST_DURATION = Duration.ofMinutes(30);
    
    private static final int EXPORT_INTERVAL_SECONDS = 10;
    private static final String OTLP_ENDPOINT = "http://localhost:4317";
    private static final String REPORT_DIR = "reports";
    private static final String REPORT_FILE = "crdb-microbatch-load-test-report.html";
    
    private final CrdbInsertTask task;
    private final TestInsertRepository repository;
    private final MeterRegistry meterRegistry;
    
    private Object testResult;
    private volatile boolean testCompleted = false;

    /**
     * Constructor for LoadTestService.
     * 
     * @param task the CRDB insert task
     * @param repository the test insert repository
     * @param meterRegistry the Micrometer registry for metrics
     */
    public LoadTestService(
            CrdbInsertTask task, 
            TestInsertRepository repository, 
            MeterRegistry meterRegistry) {
        this.task = task;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
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
        log.info("=== Starting CRDB Microbatch Static Load Test ===");
        log.info("Configuration:");
        log.info("  Target TPS: {}", TARGET_TPS);
        log.info("  Duration: {} minutes", TEST_DURATION.toMinutes());
        
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
     * Executes the load test using MetricsPipeline with StaticLoad pattern.
     * 
     * <p>This method:
     * <ul>
     *   <li>Creates StaticLoad pattern for consistent load</li>
     *   <li>Runs at fixed TPS for specified duration</li>
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
            // Create StaticLoad pattern
            StaticLoad staticLoad = new StaticLoad(TARGET_TPS, TEST_DURATION);
            
            log.info("=== Starting Static Load Test ===");
            log.info("Load Pattern: StaticLoad");
            log.info("Target TPS: {}", TARGET_TPS);
            log.info("Duration: {} minutes", TEST_DURATION.toMinutes());
            log.info("Expected Total Operations: ~{}", 
                (long)(TARGET_TPS * TEST_DURATION.toSeconds()));
            
            // Run the test
            var result = pipeline.run(task, staticLoad);
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
            log.info("  Actual TPS: {} (target: {})", 
                String.format("%.2f", actualTps), TARGET_TPS);
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
