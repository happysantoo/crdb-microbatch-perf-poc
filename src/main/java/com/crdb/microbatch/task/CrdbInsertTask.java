package com.crdb.microbatch.task;

import com.crdb.microbatch.backend.CrdbBatchBackend;
import com.crdb.microbatch.backpressure.CompositeBackpressureProvider;
import com.crdb.microbatch.backpressure.ConnectionPoolBackpressureProvider;
import com.crdb.microbatch.model.TestInsert;
import com.zaxxer.hikari.HikariDataSource;
import com.vajrapulse.api.task.TaskLifecycle;
import com.vajrapulse.api.task.TaskResult;
import com.vajrapulse.vortex.BatcherConfig;
import com.vajrapulse.vortex.ItemResult;
import com.vajrapulse.vortex.MicroBatcher;
import com.vajrapulse.vortex.backpressure.BackpressureProvider;
import com.vajrapulse.vortex.backpressure.QueueDepthBackpressureProvider;
import com.vajrapulse.vortex.backpressure.RejectStrategy;
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
 * <p>This is a straightforward implementation that:
 * <ul>
 *   <li>Uses submitSync() for immediate rejection visibility</li>
 *   <li>Uses QueueDepthBackpressureProvider directly (no wrappers)</li>
 *   <li>Returns TaskResult immediately based on submission result</li>
 *   <li>Batch results are tracked via backend metrics</li>
 * </ul>
 */
@Component
public class CrdbInsertTask implements TaskLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CrdbInsertTask.class);
    
    private static final int BATCH_SIZE = 50;
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
     * @param dataSource the HikariCP data source (for connection pool backpressure)
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
            .description("Number of items rejected due to backpressure (BackpressureException)")
            .register(meterRegistry);
        
        otherRejectedCounter = Counter.builder("crdb.submits.rejected.other")
            .description("Number of items rejected for other reasons (queue full, etc.)")
            .register(meterRegistry);
        
        submitLatencyTimer = Timer.builder("crdb.submit.latency")
            .description("Time from submit to acceptance/rejection")
            .register(meterRegistry);
    }

    /**
     * Initializes the Vortex MicroBatcher with composite backpressure and concurrent dispatch limiting.
     * 
     * <p>Uses Vortex 0.0.7 features:
     * <ul>
     *   <li>Composite backpressure combining queue depth and connection pool</li>
     *   <li>Concurrent batch dispatch limiter (maxConcurrentBatches = 8, 80% of 10 connections)</li>
     *   <li>Prevents connection pool exhaustion by limiting concurrent dispatches</li>
     * </ul>
     * 
     * <p>This prevents both queue overflow and connection pool exhaustion
     * by providing early warning from either source and limiting concurrent batch dispatches.
     */
    private void initializeBatcher() {
        int maxQueueSize = BATCH_SIZE * 20;  // 20 batches = 1000 items
        int maxConcurrentBatches = 8;  // 80% of 10 connections - prevents pool exhaustion
        
        // Queue depth backpressure
        BackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
            queueDepthSupplier,
            maxQueueSize
        );
        
        // Connection pool backpressure (prevents connection exhaustion)
        BackpressureProvider poolProvider = new ConnectionPoolBackpressureProvider(dataSource);
        
        // Composite backpressure (uses maximum of both sources)
        // This ensures we react to the worst pressure source
        BackpressureProvider compositeProvider = new CompositeBackpressureProvider(
            queueProvider,
            poolProvider
        );
        
        // Reject when composite backpressure >= 0.7
        // This triggers when either queue > 70% OR pool > 70% utilized
        RejectStrategy<TestInsert> strategy = new RejectStrategy<>(0.7);
        
        // Configure batcher with backpressure and concurrent dispatch limiting (Vortex 0.0.7)
        // According to changelog: "All backpressure configuration now via BatcherConfig.builder()"
        // and "Removed MicroBatcher.withBackpressure() factory methods"
        BatcherConfig config = BatcherConfig.builder()
            .batchSize(BATCH_SIZE)
            .lingerTime(LINGER_TIME)
            .atomicCommit(false)
            // Note: These methods should be available in Vortex 0.0.7
            // If compilation fails, ensure dependency is refreshed: ./gradlew --refresh-dependencies
            .maxConcurrentBatches(maxConcurrentBatches)  // NEW in 0.0.7: Prevents connection pool exhaustion
            .backpressureProvider(compositeProvider)    // NEW in 0.0.7: Configure via BatcherConfig
            .backpressureStrategy(strategy)              // NEW in 0.0.7: Configure via BatcherConfig
            .build();
        
        // Create batcher using standard constructor (withBackpressure factory removed in 0.0.7)
        batcher = new MicroBatcher<>(backend, config, meterRegistry);
        
        queueDepthSupplier.setBatcher(batcher);
        
        log.info("MicroBatcher initialized (Vortex 0.0.7): batchSize={}, lingerTime={}ms, maxQueueSize={}, maxConcurrentBatches={}, backpressureThreshold=0.7", 
            BATCH_SIZE, LINGER_TIME.toMillis(), maxQueueSize, maxConcurrentBatches);
        log.info("Backpressure: Composite (Queue + Connection Pool) - prevents both queue overflow and connection exhaustion");
        log.info("Concurrent dispatch limiting: {} batches max (80% of connection pool) - prevents connection pool exhaustion", maxConcurrentBatches);
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
     * <p>Uses submitSync() which:
     * <ul>
     *   <li>Returns immediately with SUCCESS if item is queued</li>
     *   <li>Returns immediately with FAILURE if item is rejected (backpressure/queue full)</li>
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
            
            // Use submitSync() - returns immediately
            ItemResult<TestInsert> result = batcher.submitSync(testInsert);
            
            sample.stop(submitLatencyTimer);
            
            if (result instanceof ItemResult.Failure<TestInsert> failure) {
                // Rejected - check if it's due to backpressure
                Throwable error = failure.error();
                submitFailureCounter.increment();
                
                // Track backpressure rejections separately
                if (isBackpressureException(error)) {
                    backpressureRejectedCounter.increment();
                    log.debug("Item rejected due to backpressure: {}", error.getMessage());
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
    
    /**
     * Gets the queue depth supplier for use in AdaptiveLoadPattern.
     * 
     * @return the queue depth supplier
     */
    public Supplier<Integer> getQueueDepthSupplier() {
        return queueDepthSupplier;
    }
    
    /**
     * Gets the maximum queue size configured for the MicroBatcher.
     * 
     * @return the maximum queue size
     */
    public int getMaxQueueSize() {
        return BATCH_SIZE * 20;
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
     * Checks if the error is a BackpressureException.
     * 
     * <p>BackpressureException is thrown by Vortex when items are rejected due to
     * backpressure threshold being exceeded. This method checks both the direct
     * exception type and the exception message to identify backpressure rejections.
     * 
     * @param error the error to check
     * @return true if the error is a BackpressureException, false otherwise
     */
    private boolean isBackpressureException(Throwable error) {
        if (error == null) {
            return false;
        }
        
        // Check exception class name (works even if class not in classpath)
        String className = error.getClass().getName();
        if (className.contains("BackpressureException")) {
            return true;
        }
        
        // Check exception message for backpressure indicators
        String message = error.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("backpressure") || 
                   lowerMessage.contains("back pressure") ||
                   lowerMessage.contains("pressure level");
        }
        
        // Check cause recursively
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            return isBackpressureException(cause);
        }
        
        return false;
    }
}
