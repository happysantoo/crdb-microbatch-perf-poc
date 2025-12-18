package com.crdb.microbatch.service;

import com.crdb.microbatch.backpressure.CompositeBackpressureProvider;
import com.vajrapulse.api.metrics.MetricsProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricsProvider implementation that combines Micrometer metrics with backpressure.
 * 
 * <p>This provider:
 * <ul>
 *   <li>Uses Micrometer metrics for failure rate and total executions</li>
 *   <li>Uses CompositeBackpressureProvider for backpressure level (via MetricsProviderAdapter)</li>
 *   <li>Provides time-windowed failure rate for recovery decisions</li>
 * </ul>
 * 
 * <p>Used by AdaptiveLoadPattern to make TPS adjustment decisions based on:
 * <ul>
 *   <li>Failure rate (error threshold)</li>
 *   <li>Backpressure level (system capacity)</li>
 * </ul>
 */
public class BackpressureMetricsProvider implements MetricsProvider {
    
    private static final Logger log = LoggerFactory.getLogger(BackpressureMetricsProvider.class);
    
    private final MeterRegistry meterRegistry;
    private final CompositeBackpressureProvider backpressureProvider;
    private final AtomicLong lastTotalExecutions = new AtomicLong(0);
    private final AtomicLong lastFailureCount = new AtomicLong(0);
    private long lastSnapshotTime = System.currentTimeMillis();
    
    // Metric names - using VajraPulse execution metrics
    private static final String EXECUTION_COUNT = "vajrapulse.execution.count";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    
    /**
     * Constructor for BackpressureMetricsProvider.
     * 
     * @param meterRegistry the Micrometer meter registry
     * @param backpressureProvider the composite backpressure provider
     */
    public BackpressureMetricsProvider(
            MeterRegistry meterRegistry,
            CompositeBackpressureProvider backpressureProvider) {
        this.meterRegistry = meterRegistry;
        this.backpressureProvider = backpressureProvider;
    }
    
    @Override
    public double getFailureRate() {
        try {
            double total = getTotalExecutions();
            double failures = getFailureCount();
            
            if (total == 0) {
                return 0.0;
            }
            
            // Return as percentage (0.0-100.0) as per MetricsProvider contract
            return (failures / total) * 100.0;
        } catch (Exception e) {
            log.warn("Failed to calculate failure rate: {}", e.getMessage());
            return 0.0;
        }
    }
    
    @Override
    public double getRecentFailureRate(int windowSeconds) {
        try {
            long now = System.currentTimeMillis();
            long windowMillis = windowSeconds * 1000L;
            
            // If window is too large or not enough time has passed, use all-time rate
            if (windowSeconds > 60 || (now - lastSnapshotTime) < windowMillis) {
                return getFailureRate();
            }
            
            // Calculate difference since last snapshot
            long currentTotal = getTotalExecutions();
            long currentFailures = getFailureCount();
            
            long totalDiff = currentTotal - lastTotalExecutions.get();
            long failureDiff = currentFailures - lastFailureCount.get();
            
            // Update snapshot
            lastTotalExecutions.set(currentTotal);
            lastFailureCount.set(currentFailures);
            lastSnapshotTime = now;
            
            if (totalDiff == 0) {
                return 0.0;
            }
            
            // Return as percentage (0.0-100.0)
            return ((double) failureDiff / totalDiff) * 100.0;
        } catch (Exception e) {
            log.warn("Failed to calculate recent failure rate: {}", e.getMessage());
            return getFailureRate();
        }
    }
    
    @Override
    public long getTotalExecutions() {
        try {
            var successCounter = meterRegistry.find(EXECUTION_COUNT)
                .tag("status", STATUS_SUCCESS)
                .counter();
            var failureCounter = meterRegistry.find(EXECUTION_COUNT)
                .tag("status", STATUS_FAILURE)
                .counter();
            
            long success = (long) (successCounter != null ? successCounter.count() : 0.0);
            long failure = (long) (failureCounter != null ? failureCounter.count() : 0.0);
            
            return success + failure;
        } catch (Exception e) {
            log.warn("Failed to get total executions: {}", e.getMessage());
            return 0L;
        }
    }
    
    /**
     * Gets the failure count from metrics.
     * 
     * @return the failure count
     */
    @Override
    public long getFailureCount() {
        try {
            var failureCounter = meterRegistry.find(EXECUTION_COUNT)
                .tag("status", STATUS_FAILURE)
                .counter();
            
            return (long) (failureCounter != null ? failureCounter.count() : 0.0);
        } catch (Exception e) {
            log.warn("Failed to get failure count: {}", e.getMessage());
            return 0L;
        }
    }
    
    /**
     * Gets the current backpressure level (for logging/debugging).
     * 
     * @return backpressure level (0.0-1.0)
     */
    public double getBackpressureLevel() {
        return backpressureProvider.getBackpressureLevel();
    }
}

