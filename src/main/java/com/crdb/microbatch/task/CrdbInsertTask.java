package com.crdb.microbatch.task;

import com.crdb.microbatch.backend.CrdbBatchBackend;
import com.crdb.microbatch.model.TestInsert;
import com.vajrapulse.api.TaskLifecycle;
import com.vajrapulse.api.TaskResult;
import com.vajrapulse.vortex.BatcherConfig;
import com.vajrapulse.vortex.ItemResult;
import com.vajrapulse.vortex.MicroBatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * VajraPulse task for inserting rows into CockroachDB using Vortex microbatching.
 * 
 * <p>This task uses Vortex MicroBatcher to batch inserts (50 rows or 10ms)
 * for improved throughput. Simplified for demo purposes.
 */
@Component
public class CrdbInsertTask implements TaskLifecycle {

    private static final int BATCH_SIZE = 50;
    private static final Duration LINGER_TIME = Duration.ofMillis(50);
    
    private final CrdbBatchBackend backend;
    private final MeterRegistry meterRegistry;
    private final Random random = new Random();
    
    private MicroBatcher<TestInsert> batcher;
    private Counter submitCounter;
    private Counter submitSuccessCounter;
    private Counter submitFailureCounter;
    private Timer submitLatencyTimer;

    /**
     * Constructor for CrdbInsertTask.
     * 
     * @param backend the CRDB batch backend
     * @param meterRegistry the Micrometer meter registry
     */
    public CrdbInsertTask(CrdbBatchBackend backend, MeterRegistry meterRegistry) {
        this.backend = backend;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void init() throws Exception {
        initializeMetrics();
        initializeBatcher();
    }

    /**
     * Initializes all metrics counters and timers.
     */
    private void initializeMetrics() {
        submitCounter = createCounter("crdb.submits.total", "Total number of submits to batcher");
        submitSuccessCounter = createCounter("crdb.submits.success", "Total number of successful submits");
        submitFailureCounter = createCounter("crdb.submits.failure", "Total number of failed submits");
        submitLatencyTimer = createTimer("crdb.submit.latency", "Time from submit to completion");
    }

    /**
     * Creates a counter metric.
     * 
     * @param name the metric name
     * @param description the metric description
     * @return the created counter
     */
    private Counter createCounter(String name, String description) {
        return Counter.builder(name)
            .description(description)
            .register(meterRegistry);
    }

    /**
     * Creates a timer metric.
     * 
     * @param name the metric name
     * @param description the metric description
     * @return the created timer
     */
    private Timer createTimer(String name, String description) {
        return Timer.builder(name)
            .description(description)
            .register(meterRegistry);
    }

    /**
     * Initializes the Vortex MicroBatcher with configuration.
     * 
     * <p>Passes MeterRegistry to enable Vortex library metrics export.
     */
    private void initializeBatcher() {
        BatcherConfig config = BatcherConfig.builder()
            .batchSize(BATCH_SIZE)
            .lingerTime(LINGER_TIME)
            .atomicCommit(false)  // Allow partial batch success
            .build();
        
        // Pass MeterRegistry to enable Vortex metrics
        batcher = new MicroBatcher<>(backend, config, meterRegistry);
        
        org.slf4j.LoggerFactory.getLogger(CrdbInsertTask.class)
            .info("MicroBatcher initialized: batchSize={}, lingerTime={}ms", 
                BATCH_SIZE, LINGER_TIME.toMillis());
    }

    @Override
    public TaskResult execute(long iteration) throws Exception {
        submitCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        long submitStartTime = System.currentTimeMillis();
        
        try {
            TestInsert testInsert = generateTestData();
            
            // NON-BLOCKING APPROACH: Submit item and return success immediately
            // This allows items to accumulate in the MicroBatcher queue for batching.
            // The callback will handle the actual result and update metrics.
            // VajraPulse will track success/failure through metrics, not per-task results.
            
            batcher.submitWithCallback(testInsert, (item, itemResult) -> {
                // Callback executes asynchronously when batch completes
                sample.stop(submitLatencyTimer);
                
                // Update metrics based on actual result
                if (itemResult instanceof ItemResult.Success<TestInsert>) {
                    submitSuccessCounter.increment();
                } else {
                    submitFailureCounter.increment();
                    ItemResult.Failure<TestInsert> failure = (ItemResult.Failure<TestInsert>) itemResult;
                    org.slf4j.LoggerFactory.getLogger(CrdbInsertTask.class)
                        .warn("Item failed in batch: {}", failure.error().getMessage());
                }
            });
            
            // Reduced logging - only log errors, not every execution
            
            // Return success immediately - allows items to accumulate for batching
            // Actual success/failure is tracked via metrics in the callback
            return TaskResult.success();
            
        } catch (Exception e) {
            // Only catch exceptions during submission (not batch processing)
            submitFailureCounter.increment();
            sample.stop(submitLatencyTimer);
            long totalDuration = System.currentTimeMillis() - submitStartTime;
            org.slf4j.LoggerFactory.getLogger(CrdbInsertTask.class)
                .error("Task submission failed at iteration: {}, duration: {}ms", iteration, totalDuration, e);
            return TaskResult.failure(e);
        }
    }

    /**
     * Converts Vortex ItemResult to VajraPulse TaskResult.
     * 
     * <p>This method is much simpler than the old findItemResult() because submitWithCallback
     * already provides the item-specific result directly via callback - no manual extraction needed!
     * Uses Java 21 pattern matching with switch expression for cleaner code.
     * 
     * @param itemResult the item result from Vortex library callback
     * @return the corresponding TaskResult
     */
    private TaskResult convertItemResult(ItemResult<TestInsert> itemResult) {
        return switch (itemResult) {
            case ItemResult.Success<TestInsert> s -> TaskResult.success();
            case ItemResult.Failure<TestInsert> f -> {
                Throwable error = f.error();
                Exception exception = error instanceof Exception 
                    ? (Exception) error 
                    : new RuntimeException(error);
                yield TaskResult.failure(exception);
            }
        };
    }

    @Override
    public void teardown() throws Exception {
        if (batcher != null) {
            batcher.close();
        }
    }

    /**
     * Generates random test data for insertion.
     * 
     * @return a TestInsert with random data
     */
    private TestInsert generateTestData() {
        return TestInsert.create(
            generateVarchar(),
            generateText(),
            random.nextInt(1000000),
            random.nextLong(1000000L),
            generateDecimal(),
            random.nextBoolean(),
            generateJson(),
            generateArray(),
            UUID.randomUUID(),
            generateDate(),
            LocalTime.now(),
            random.nextDouble() * 1000,
            random.nextFloat() * 100
        );
    }

    /**
     * Generates a random varchar value.
     * 
     * @return the generated varchar string
     */
    private String generateVarchar() {
        return "varchar_" + random.nextInt(1000000);
    }

    /**
     * Generates a random text value.
     * 
     * @return the generated text string
     */
    private String generateText() {
        return "text_content_" + random.nextInt(1000000);
    }

    /**
     * Generates a random decimal value.
     * 
     * @return the generated BigDecimal
     */
    private BigDecimal generateDecimal() {
        return BigDecimal.valueOf(random.nextDouble() * 10000);
    }

    /**
     * Generates a random JSON string.
     * 
     * @return the generated JSON string
     */
    private String generateJson() {
        return "{\"key\": \"value_" + random.nextInt(1000) + "\"}";
    }

    /**
     * Generates a random integer array.
     * 
     * @return the generated array
     */
    private List<Integer> generateArray() {
        return List.of(
            random.nextInt(100),
            random.nextInt(100),
            random.nextInt(100)
        );
    }

    /**
     * Generates a random date.
     * 
     * @return the generated LocalDate
     */
    private LocalDate generateDate() {
        return LocalDate.now().minusDays(random.nextInt(365));
    }
}
