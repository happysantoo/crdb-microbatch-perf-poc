package com.crdb.microbatch.backend;

import com.crdb.microbatch.model.TestInsert;
import com.crdb.microbatch.repository.TestInsertRepository;
import com.vajrapulse.vortex.Backend;
import com.vajrapulse.vortex.BatchResult;
import com.vajrapulse.vortex.FailureEvent;
import com.vajrapulse.vortex.SuccessEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend implementation for CockroachDB batch inserts using Vortex MicroBatcher.
 * 
 * <p>This backend processes batches of TestInsert records and inserts them
 * into CockroachDB using JDBC batch updates.
 */
@Component
public class CrdbBatchBackend implements Backend<TestInsert> {

    private static final Logger log = LoggerFactory.getLogger(CrdbBatchBackend.class);
    
    private final TestInsertRepository repository;
    private final MeterRegistry meterRegistry;
    
    private Counter batchCounter;
    private Counter batchSuccessCounter;
    private Counter batchFailureCounter;
    private Counter rowCounter;
    private Counter rowSuccessCounter;
    private Counter rowFailureCounter;
    private Timer batchTimer;

    /**
     * Constructor for CrdbBatchBackend.
     * 
     * @param repository the test insert repository
     * @param meterRegistry the Micrometer meter registry
     */
    public CrdbBatchBackend(TestInsertRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        initializeMetrics();
    }

    /**
     * Initializes all metrics counters and timers.
     */
    private void initializeMetrics() {
        batchCounter = Counter.builder("crdb.batches.total")
            .description("Total number of batches dispatched")
            .register(meterRegistry);
        
        batchSuccessCounter = Counter.builder("crdb.batches.success")
            .description("Total number of successful batches")
            .register(meterRegistry);
        
        batchFailureCounter = Counter.builder("crdb.batches.failure")
            .description("Total number of failed batches")
            .register(meterRegistry);
        
        rowCounter = Counter.builder("crdb.batch.rows.total")
            .description("Total number of rows in batches")
            .register(meterRegistry);
        
        rowSuccessCounter = Counter.builder("crdb.batch.rows.success")
            .description("Total number of successfully inserted rows")
            .register(meterRegistry);
        
        rowFailureCounter = Counter.builder("crdb.batch.rows.failure")
            .description("Total number of failed rows")
            .register(meterRegistry);
        
        batchTimer = Timer.builder("crdb.batch.duration")
            .description("Batch insert operation duration")
            .register(meterRegistry);
    }

    @Override
    public BatchResult<TestInsert> dispatch(List<TestInsert> batch) throws Exception {
        if (batch.isEmpty()) {
            log.debug("Batch is empty, returning empty result");
            return new BatchResult<>(List.of(), List.of());
        }
        
        batchCounter.increment();
        rowCounter.increment(batch.size());
        
        long batchStartTime = System.currentTimeMillis();
        
        // Log batching issues at DEBUG level to reduce noise (only RAMP_UP/RAMP_DOWN should be visible)
        if (batch.size() == 1) {
            log.debug("⚠️ BATCHING ISSUE: Batch contains only 1 item! Items are not being accumulated.");
        } else if (batch.size() < 10) {
            log.debug("⚠️ Small batch: {} items (expected ~50). Batching may not be working efficiently.", batch.size());
        }
        
        return batchTimer.recordCallable(() -> {
            try {
                long beforeInsert = System.currentTimeMillis();
                int[] updateCounts = repository.insertBatch(batch);
                long afterInsert = System.currentTimeMillis();
                long insertDuration = afterInsert - beforeInsert;
                
                validateUpdateCounts(updateCounts, batch.size());
                BatchResult<TestInsert> result = mapToBatchResult(batch, updateCounts);
                
                // Removed batch dispatch duration warning - too noisy
                // Metrics are tracked via Micrometer timers for monitoring
                
                return result;
            } catch (Exception e) {
                long totalDuration = System.currentTimeMillis() - batchStartTime;
                log.error("Batch insert failed for {} items after {}ms: {}", 
                    batch.size(), totalDuration, e.getMessage(), e);
                return createFailureResult(batch, e);
            }
        });
    }

    /**
     * Validates that update counts match batch size.
     * 
     * @param updateCounts the update counts from JDBC
     * @param expectedSize the expected batch size
     * @throws IllegalStateException if validation fails
     */
    private void validateUpdateCounts(int[] updateCounts, int expectedSize) {
        if (updateCounts == null || updateCounts.length != expectedSize) {
            throw new IllegalStateException(
                String.format("Invalid update counts: expected %d, got %s",
                    expectedSize, updateCounts == null ? "null" : String.valueOf(updateCounts.length)));
        }
    }

    /**
     * Maps update counts to batch result with success/failure events.
     * 
     * @param batch the batch of items
     * @param updateCounts the JDBC update counts
     * @return the batch result with successes and failures
     */
    private BatchResult<TestInsert> mapToBatchResult(List<TestInsert> batch, int[] updateCounts) {
        int batchSize = batch.size();
        List<SuccessEvent<TestInsert>> successes = new ArrayList<>(batchSize);
        List<FailureEvent<TestInsert>> failures = new ArrayList<>();
        
        for (int i = 0; i < batchSize; i++) {
            int rowsAffected = updateCounts[i];
            
            if (isSuccess(rowsAffected)) {
                successes.add(new SuccessEvent<>(batch.get(i)));
                rowSuccessCounter.increment();
            } else {
                Exception error = new IllegalStateException(
                    String.format("Insert failed for item %d: rowsAffected=%d", i, rowsAffected));
                log.warn("Item {} in batch failed: rowsAffected={}", i, rowsAffected);
                failures.add(new FailureEvent<>(batch.get(i), error));
                rowFailureCounter.increment();
            }
        }
        
        updateBatchMetrics(failures, batch.size());
        return new BatchResult<>(successes, failures);
    }

    /**
     * Checks if update count indicates success.
     * 
     * @param rowsAffected the rows affected count
     * @return true if successful
     */
    private boolean isSuccess(int rowsAffected) {
        return rowsAffected > 0 || rowsAffected == java.sql.Statement.SUCCESS_NO_INFO;
    }

    /**
     * Updates batch-level metrics based on failures.
     * 
     * @param failures the list of failures
     * @param batchSize the total batch size
     */
    private void updateBatchMetrics(List<FailureEvent<TestInsert>> failures, int batchSize) {
        if (failures.isEmpty()) {
            batchSuccessCounter.increment();
        } else {
            batchFailureCounter.increment();
            log.warn("Batch had {} failures out of {} items", failures.size(), batchSize);
        }
    }

    /**
     * Creates a failure result for all items in the batch.
     * 
     * @param batch the batch of items
     * @param error the exception that caused the failure
     * @return batch result with all items marked as failed
     */
    private BatchResult<TestInsert> createFailureResult(List<TestInsert> batch, Exception error) {
        batchFailureCounter.increment();
        rowFailureCounter.increment(batch.size());
        
        List<FailureEvent<TestInsert>> failures = new ArrayList<>(batch.size());
        for (TestInsert item : batch) {
            failures.add(new FailureEvent<>(item, error));
        }
        
        return new BatchResult<>(List.of(), failures);
    }
}

