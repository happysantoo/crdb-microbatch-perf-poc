package com.crdb.microbatch.task;

import com.crdb.microbatch.backend.CrdbBatchBackend;
import com.crdb.microbatch.model.TestInsert;
import com.zaxxer.hikari.HikariDataSource;
import com.vajrapulse.api.task.TaskLifecycle;
import com.vajrapulse.api.task.TaskResult;
import com.vajrapulse.vortex.BatcherConfig;
import com.vajrapulse.vortex.results.ItemResult;
import com.vajrapulse.vortex.MicroBatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * VajraPulse task for inserting rows into CockroachDB using Vortex microbatching.
 * 
 * <p>This implementation uses Vortex 0.0.9 unified API:
 * <ul>
 *   <li>Uses submit() for immediate rejection visibility</li>
 *   <li>Uses queueRejectionThreshold in BatcherConfig for queue rejection</li>
 *   <li>Returns TaskResult immediately based on submission result</li>
 *   <li>Batch results are tracked via backend metrics</li>
 * </ul>
 */
@Component
public class CrdbInsertTask implements TaskLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CrdbInsertTask.class);
    
    private static final int BATCH_SIZE = 200;  // Increased from 100 to 200 for maximum throughput
    private static final Duration LINGER_TIME = Duration.ofMillis(50);
    
    private final CrdbBatchBackend backend;
    private final MeterRegistry meterRegistry;
    private final HikariDataSource dataSource;
    private final Random random = new Random();
    
    private MicroBatcher<TestInsert> batcher;
    private Counter submitCounter;
    private Counter submitSuccessCounter;
    private Counter submitFailureCounter;
    private Counter backpressureRejectedCounter;  // NEW: Tracks items rejected due to backpressure
    private Counter otherRejectedCounter;          // NEW: Tracks items rejected for other reasons
    private Timer submitLatencyTimer;
    
    // Queue depth supplier for backpressure
    private final MutableQueueDepthSupplier<TestInsert> queueDepthSupplier = new MutableQueueDepthSupplier<>();

    /**
     * Constructor for CrdbInsertTask.
     * 
     * @param backend the CRDB batch backend
     * @param meterRegistry the Micrometer meter registry
     * @param dataSource the HikariCP data source
     */
    public CrdbInsertTask(
            CrdbBatchBackend backend, 
            MeterRegistry meterRegistry,
            HikariDataSource dataSource) {
        this.backend = backend;
        this.meterRegistry = meterRegistry;
        this.dataSource = dataSource;
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
        submitCounter = Counter.builder("crdb.submits.total")
            .description("Total number of submits to batcher")
            .register(meterRegistry);
        
        submitSuccessCounter = Counter.builder("crdb.submits.success")
            .description("Total number of successful submits")
            .register(meterRegistry);
        
        submitFailureCounter = Counter.builder("crdb.submits.failure")
            .description("Total number of failed submits")
            .register(meterRegistry);
        
        backpressureRejectedCounter = Counter.builder("crdb.submits.rejected.backpressure")
            .description("Number of items rejected due to queue being full (ItemRejectedException)")
            .register(meterRegistry);
        
        otherRejectedCounter = Counter.builder("crdb.submits.rejected.other")
            .description("Number of items rejected for other reasons")
            .register(meterRegistry);
        
        submitLatencyTimer = Timer.builder("crdb.submit.latency")
            .description("Time from submit to acceptance/rejection")
            .register(meterRegistry);
    }

    /**
     * Initializes the Vortex MicroBatcher with optimized queue rejection configuration.
     *
     * <p>Uses Vortex 0.0.9 features:
     * <ul>
     *   <li>Smaller queue size to trigger faster backpressure detection</li>
     *   <li>Lower rejection threshold to give AdaptiveLoadPattern more time to react</li>
     *   <li>Concurrent batch dispatch limiter (maxConcurrentBatches = 8, 80% of 10 connections)</li>
     *   <li>Prevents connection pool exhaustion by limiting concurrent dispatches</li>
     * </ul>
     *
     * <p>Note: The backpressure package has been removed in Vortex 0.0.9.
     * Queue rejection is now handled via queueRejectionThreshold configuration.
     */
    private void initializeBatcher() {
        // Reduced queue size to trigger backpressure faster and give AdaptiveLoadPattern time to react
        // At 8,000 TPS: 8,000 items = 1 second buffer (gives time for TPS adjustment)
        // At 5,000 TPS: 8,000 items = 1.6 seconds buffer
        int maxQueueSize = BATCH_SIZE * 40;  // 40 batches = 8,000 items (reduced from 40,000)
        int maxConcurrentBatches = 8;  // Use 8 of 10 connections (80%) for stability
        double queueRejectionThreshold = 0.6;  // Reject at 60% capacity (4,800 items) - earlier rejection

        // Configure batcher (Vortex 0.0.12)
        // Note: The backpressure package has been removed since 0.0.9
        // Queue rejection is configured via maxQueueSize and queueRejectionThreshold
        BatcherConfig config = BatcherConfig.builder()
            .batchSize(BATCH_SIZE)
            .lingerTime(LINGER_TIME)
            .atomicCommit(false)
            .maxConcurrentBatches(maxConcurrentBatches)  // Prevents connection pool exhaustion
            .maxQueueSize(maxQueueSize)  // Smaller queue for faster backpressure
            .queueRejectionThreshold(queueRejectionThreshold)  // Earlier rejection at 60% capacity
            .build();

        // Create batcher using standard constructor
        batcher = new MicroBatcher<>(backend, config, meterRegistry);

        queueDepthSupplier.setBatcher(batcher);

        log.info("🔧 MicroBatcher initialized (Vortex 0.0.12): batchSize={}, lingerTime={}ms, maxQueueSize={}, queueRejectionThreshold={}%, maxConcurrentBatches={}",
            BATCH_SIZE, LINGER_TIME.toMillis(), maxQueueSize, queueRejectionThreshold * 100.0, maxConcurrentBatches);
        log.info("🎯 Queue configuration: Max size={} items, Rejection threshold={}% ({} items) - Items rejected when queue reaches {} items",
            maxQueueSize, queueRejectionThreshold * 100.0, (int)(maxQueueSize * queueRejectionThreshold), (int)(maxQueueSize * queueRejectionThreshold));
        log.info("⚡ Concurrent dispatch: {} batches max (80% of connection pool) - balanced throughput and stability", maxConcurrentBatches);
        log.info("📊 Expected throughput: ~{} items/sec ({} batches/sec × {} items/batch × {} concurrent batches)",
            maxConcurrentBatches * (1000 / LINGER_TIME.toMillis()) * BATCH_SIZE,
            maxConcurrentBatches * (1000 / LINGER_TIME.toMillis()),
            BATCH_SIZE,
            maxConcurrentBatches);
        log.info("🎯 AdaptiveLoadPattern Integration:");
        log.info("   Buffer Time at 8000 TPS: {} seconds", String.format("%.1f", maxQueueSize / 8000.0));
        log.info("   Buffer Time at 5000 TPS: {} seconds", String.format("%.1f", maxQueueSize / 5000.0));
        log.info("   Rejection triggers when queue depth > {} items", (int)(maxQueueSize * queueRejectionThreshold));
        log.info("   This gives AdaptiveLoadPattern time to detect failures and ramp down TPS");
    }
    
    /**
     * Mutable supplier for queue depth.
     */
    private static class MutableQueueDepthSupplier<T> implements Supplier<Integer> {
        private volatile MicroBatcher<T> batcher;
        
        public void setBatcher(MicroBatcher<T> batcher) {
            this.batcher = batcher;
        }
        
        @Override
        public Integer get() {
            if (batcher == null) {
                return 0;
            }
            try {
                return batcher.diagnostics().getQueueDepth();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /**
     * Executes the task for a single iteration.
     * 
     * <p>Uses submit() (Vortex 0.0.12 unified API) which:
     * <ul>
     *   <li>Returns immediately with SUCCESS if item is queued</li>
     *   <li>Returns immediately with FAILURE if item is rejected (queue full)</li>
     *   <li>Provides immediate visibility to AdaptiveLoadPattern</li>
     * </ul>
     * 
     * @param iteration the iteration number (0-based)
     * @return TaskResult indicating success or failure
     * @throws Exception if task execution fails
     */
    @Override
    public TaskResult execute(long iteration) throws Exception {
        submitCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            TestInsert testInsert = generateTestData();
            
            // Use submit() - unified API in Vortex 0.0.12
            // submit() returns ItemResult immediately (synchronous behavior)
            ItemResult<TestInsert> result = batcher.submit(testInsert);
            
            sample.stop(submitLatencyTimer);
            
            if (result instanceof ItemResult.Failure<TestInsert> failure) {
                // Rejected - check if it's due to queue being full
                Throwable error = failure.error();
                submitFailureCounter.increment();
                
                // Track queue rejection separately (ItemRejectedException in 0.0.9)
                if (isItemRejectedException(error)) {
                    backpressureRejectedCounter.increment();
                    log.debug("Item rejected due to queue being full: {}", error.getMessage());
                } else {
                    otherRejectedCounter.increment();
                    log.debug("Item rejected for other reason: {}", error.getClass().getSimpleName());
                }
                
                return TaskResult.failure(error);
            }
            
            // Accepted and queued - will be processed in batch later
            submitSuccessCounter.increment();
            return TaskResult.success();
            
        } catch (Exception e) {
            sample.stop(submitLatencyTimer);
            submitFailureCounter.increment();
            otherRejectedCounter.increment();  // Exception during submission is "other" rejection
            log.error("Task execution failed at iteration: {}", iteration, e);
            return TaskResult.failure(e);
        }
    }

    @Override
    public void teardown() throws Exception {
        if (batcher != null) {
            // Use built-in awaitCompletion() from Vortex 0.0.7
            // Waits for queue to drain and all in-flight batches to complete
            // Prevents RejectedExecutionException during shutdown
            // Note: If method doesn't exist, ensure dependency is refreshed: ./gradlew --refresh-dependencies
            try {
                boolean completed = batcher.awaitCompletion(5, java.util.concurrent.TimeUnit.SECONDS);
                if (completed) {
                    log.debug("All batches completed, closing batcher");
                } else {
                    log.warn("Timeout waiting for batches to complete, closing anyway");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for batches to complete");
            } catch (NoSuchMethodError e) {
                // Fallback if 0.0.7 not available yet - use custom wait
                log.warn("awaitCompletion() not available, using fallback wait");
                waitForQueueToDrainFallback(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            batcher.close();
        }
    }

    /**
     * Fallback method for waiting for queue to drain (if awaitCompletion() not available).
     * 
     * @param timeout the maximum time to wait
     * @param unit the time unit
     */
    private void waitForQueueToDrainFallback(long timeout, java.util.concurrent.TimeUnit unit) {
        if (batcher == null) {
            return;
        }
        
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            try {
                int queueDepth = batcher.diagnostics().getQueueDepth();
                if (queueDepth == 0) {
                    log.debug("Queue drained, closing batcher");
                    return;
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for queue to drain");
                break;
            } catch (Exception e) {
                log.debug("Could not check queue depth: {}", e.getMessage());
                break;
            }
        }
        log.warn("Queue did not drain within {} {}, closing anyway", timeout, unit);
    }

    /**
     * Generates random test data for insertion.
     * 
     * @return a new TestInsert instance with random data
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

    private String generateVarchar() {
        return "varchar_" + random.nextInt(1000000);
    }

    private String generateText() {
        return "text_content_" + random.nextInt(1000000);
    }

    private BigDecimal generateDecimal() {
        return BigDecimal.valueOf(random.nextDouble() * 10000);
    }

    private String generateJson() {
        return "{\"key\": \"value_" + random.nextInt(1000) + "\"}";
    }

    private List<Integer> generateArray() {
        return List.of(
            random.nextInt(100),
            random.nextInt(100),
            random.nextInt(100)
        );
    }

    private LocalDate generateDate() {
        return LocalDate.now().minusDays(random.nextInt(365));
    }
    
    /**
     * Checks if the error is an ItemRejectedException (Vortex 0.0.12).
     * 
     * <p>ItemRejectedException is thrown by Vortex when items are rejected due to
     * queue being full (queueRejectionThreshold exceeded). This method checks both
     * the direct exception type and the exception message to identify queue rejections.
     * 
     * @param error the error to check
     * @return true if the error is an ItemRejectedException, false otherwise
     */
    private boolean isItemRejectedException(Throwable error) {
        if (error == null) {
            return false;
        }
        
        // Check exception class name
        String className = error.getClass().getName();
        if (className.contains("ItemRejectedException")) {
            return true;
        }
        
        // Check exception message for rejection indicators
        String message = error.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("rejected") || 
                   lowerMessage.contains("queue full") ||
                   lowerMessage.contains("queue is full");
        }
        
        // Check cause recursively
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            return isItemRejectedException(cause);
        }
        
        return false;
    }
}
