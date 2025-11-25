package com.crdb.microbatch.service;

import com.crdb.microbatch.task.CrdbInsertTask;
import com.vajrapulse.api.LoadPattern;
import com.vajrapulse.api.RampUpLoad;
import com.vajrapulse.api.StaticLoad;
import com.vajrapulse.api.TaskIdentity;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter;
import com.vajrapulse.exporter.otel.OpenTelemetryExporter.Protocol;
import com.vajrapulse.worker.pipeline.MetricsPipeline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Service that orchestrates the load test execution using VajraPulse MetricsPipeline.
 * 
 * <p>Configures and runs a load test that ramps from 10 to 300 threads
 * and sustains until 1 million rows are inserted. Metrics are exported
 * to OpenTelemetry collector every 30 seconds.
 */
@Service
public class LoadTestService implements CommandLineRunner {

    private static final long TARGET_ROWS = 1_000_000L;
    private static final int EXPORT_INTERVAL_SECONDS = 10;  // Reduced from 30s to reduce memory pressure
    private static final String OTLP_ENDPOINT = "http://localhost:4317";
    
    private final CrdbInsertTask task;
    private final MeterRegistry meterRegistry;

    /**
     * Constructor for LoadTestService.
     * 
     * @param task the CRDB insert task
     * @param meterRegistry the Micrometer meter registry
     */
    public LoadTestService(
        CrdbInsertTask task,
        MeterRegistry meterRegistry
    ) {
        this.task = task;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(String... args) throws Exception {
        registerMetrics();
        task.setup();
        
        LoadPattern loadPattern = createLoadPattern();
        TaskIdentity identity = createTaskIdentity();
        OpenTelemetryExporter otelExporter = createOpenTelemetryExporter(identity);
        
        executeLoadTest(loadPattern, otelExporter);
        
        task.cleanup();
    }

    /**
     * Registers metrics gauges.
     */
    private void registerMetrics() {
        Gauge.builder("crdb.rows.inserted", task, CrdbInsertTask::getSuccessfulInsertCount)
            .description("Current number of rows inserted")
            .register(meterRegistry);
    }

    /**
     * Creates the load pattern for the test.
     * 
     * @return the configured load pattern
     */
    private LoadPattern createLoadPattern() {
        return new RampUpLoad(
            500.0,  // Reduced from 1000 to 500 to reduce memory pressure
            Duration.ofMinutes(5)
        );
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
     * Executes the load test using MetricsPipeline.
     * 
     * @param loadPattern the load pattern to use
     * @param otelExporter the OpenTelemetry exporter
     * @throws Exception if execution fails
     */
    private void executeLoadTest(LoadPattern loadPattern, OpenTelemetryExporter otelExporter) 
            throws Exception {
        try (MetricsPipeline pipeline = createMetricsPipeline(otelExporter)) {
            Thread pipelineThread = new Thread(() -> {
                try {
                    pipeline.run(task, loadPattern);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }, "RampUp-Pipeline");
            pipelineThread.start();
            
            Thread monitoringThread = startMonitoringThread(pipelineThread);
            
            try {
                pipelineThread.join();
                
                // If pipeline completed but target not reached, restart with sustained load
                long currentCount = task.getSuccessfulInsertCount();
                if (currentCount < TARGET_ROWS && !pipelineThread.isInterrupted()) {
                    restartWithSustainedLoad(pipeline, otelExporter);
                }
            } finally {
                monitoringThread.interrupt();
            }
        }
    }

    /**
     * Restarts the pipeline with a sustained static load until target is reached.
     * 
     * @param pipeline the metrics pipeline
     * @param otelExporter the OpenTelemetry exporter
     * @throws Exception if execution fails
     */
    private void restartWithSustainedLoad(MetricsPipeline pipeline, OpenTelemetryExporter otelExporter) 
            throws Exception {
        // Use same thread count as initial load pattern to avoid memory issues
        double sustainedLoad = 500.0;  // Reduced from 1000 to 500 to reduce memory pressure
        LoadPattern sustainedLoadPattern = new com.vajrapulse.api.StaticLoad(sustainedLoad, Duration.ofHours(24));
        Thread pipelineThread = new Thread(() -> {
            try {
                pipeline.run(task, sustainedLoadPattern);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
        pipelineThread.setName("SustainedLoad-Pipeline");
        pipelineThread.start();
        
        Thread monitoringThread = startMonitoringThread(pipelineThread);
        monitoringThread.setName("SustainedLoad-Monitor");
        
        try {
            pipelineThread.join();
        } finally {
            monitoringThread.interrupt();
        }
    }

    /**
     * Creates the metrics pipeline with exporter.
     * 
     * @param otelExporter the OpenTelemetry exporter
     * @return the configured pipeline
     */
    private MetricsPipeline createMetricsPipeline(OpenTelemetryExporter otelExporter) {
        return MetricsPipeline.builder()
            .addExporter(otelExporter)
            .withRunId(otelExporter.getRunId())
            .withPeriodic(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
            .withPercentiles(0.5, 0.9, 0.95, 0.99)
            .build();
    }

    /**
     * Starts a background thread to monitor row count and stop pipeline when target is reached.
     * 
     * @param pipelineThread the pipeline execution thread to interrupt
     * @return the monitoring thread
     */
    private Thread startMonitoringThread(Thread pipelineThread) {
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && pipelineThread.isAlive()) {
                    long currentCount = task.getSuccessfulInsertCount();
                    if (currentCount >= TARGET_ROWS) {
                        pipelineThread.interrupt();
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "LoadTest-Monitor");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
