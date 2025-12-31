package com.crdb.microbatch.service;

import com.vajrapulse.api.metrics.MetricsProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricsProvider implementation that provides execution metrics.
 * 
 * <p>This provider:
 * <ul>
 *   <li>Uses manual metrics tracking as primary source (most reliable)</li>
 *   <li>Falls back to Micrometer metrics if available</li>
 *   <li>Provides time-windowed failure rate for recovery decisions</li>
 * </ul>
 * 
 * <p>Used by AdaptiveLoadPattern to make TPS adjustment decisions based on failure rate.
 */
public class BackpressureMetricsProvider implements MetricsProvider {
    
    private static final Logger log = LoggerFactory.getLogger(BackpressureMetricsProvider.class);
    
    private final MeterRegistry meterRegistry;
    private final ManualMetricsTracker manualTracker;  // Fallback manual tracker
    private final AtomicLong lastTotalExecutions = new AtomicLong(0);
    private final AtomicLong lastFailureCount = new AtomicLong(0);
    private long lastSnapshotTime = System.currentTimeMillis();
    
    /**
     * Manual metrics tracker interface for fallback when VajraPulse metrics aren't available.
     */
    public interface ManualMetricsTracker {
        long getTotalExecutions();
        long getSuccessCount();
        long getFailureCount();
    }
    
    // Metric names - using VajraPulse execution metrics
    private static final String EXECUTION_COUNT = "vajrapulse.execution.count";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    
    /**
     * Constructor for BackpressureMetricsProvider.
     * 
     * @param meterRegistry the Micrometer meter registry
     * @param ignored unused parameter (kept for API compatibility)
     * @param manualTracker manual metrics tracker (primary source for metrics)
     */
    public BackpressureMetricsProvider(
            MeterRegistry meterRegistry,
            Object ignored,
            ManualMetricsTracker manualTracker) {
        this.meterRegistry = meterRegistry;
        this.manualTracker = manualTracker;
    }
    
    /**
     * Constructor for BackpressureMetricsProvider (without manual tracker).
     * 
     * @param meterRegistry the Micrometer meter registry
     * @param ignored unused parameter (kept for API compatibility)
     */
    public BackpressureMetricsProvider(
            MeterRegistry meterRegistry,
            Object ignored) {
        this(meterRegistry, ignored, null);
    }
    
    @Override
    public double getFailureRate() {
        try {
            long total = getTotalExecutions();
            long failures = getFailureCount();

            if (total == 0) {
                log.debug("No executions yet - failure rate: 0.0%");
                return 0.0;
            }

            // Return as decimal (0.0-1.0) - AdaptiveLoadPattern expects decimal, not percentage
            double failureRate = (double) failures / total;
            double failureRatePercent = failureRate * 100.0;

            // Log at ERROR level if failure rate is critical (for debugging)
            // This is called by AdaptiveLoadPattern's decision policy
            if (failureRate >= 0.03) {  // Updated to match new 3% threshold
                log.error("❌ CRITICAL FAILURE RATE: {} failures / {} total = {} ({:.2f}%) - EXCEEDS 3% THRESHOLD! AdaptiveLoadPattern MUST ramp down!",
                    failures, total, String.format("%.4f", failureRate), String.format("%.2f", failureRatePercent));
            } else if (failureRate >= 0.01) {  // Warn at 1% to give early warning
                log.warn("⚠️ ELEVATED FAILURE RATE: {} failures / {} total = {} ({:.2f}%) - Getting close to ramp down threshold!",
                    failures, total, String.format("%.4f", failureRate), String.format("%.2f", failureRatePercent));
            } else if (total > 100) {  // Only log success for established tests
                log.debug("✅ HEALTHY FAILURE RATE: {} failures / {} total = {} ({:.2f}%)",
                    failures, total, String.format("%.4f", failureRate), String.format("%.2f", failureRatePercent));
            }

            // Log every 100 calls instead of 1000 for better visibility
            long callCount = getFailureRateCallCount.incrementAndGet();
            if (callCount % 100 == 0) {
                log.info("📊 getFailureRate() called {} times - Current rate: {:.2f}% ({} failures / {} total) - AdaptiveLoadPattern is actively monitoring",
                    callCount, failureRatePercent, failures, total);
            }

            return failureRate;
        } catch (Exception e) {
            log.warn("Failed to calculate failure rate: {}", e.getMessage());
            return 0.0;
        }
    }
    
    // Track how often getFailureRate is called (for diagnostics)
    private final AtomicLong getFailureRateCallCount = new AtomicLong(0);
    
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
            
            // Return as decimal (0.0-1.0) - AdaptiveLoadPattern expects decimal, not percentage
            return (double) failureDiff / totalDiff;
        } catch (Exception e) {
            log.warn("Failed to calculate recent failure rate: {}", e.getMessage());
            return getFailureRate();
        }
    }
    
    @Override
    public long getTotalExecutions() {
        // Try manual tracker first (most reliable)
        if (manualTracker != null) {
            long total = manualTracker.getTotalExecutions();
            if (total > 0) {
                log.debug("Using manual tracker: {} total executions", total);
                return total;
            }
        }
        
        try {
            // Try multiple metric name variations
            var successCounter = findCounter(EXECUTION_COUNT, STATUS_SUCCESS);
            var failureCounter = findCounter(EXECUTION_COUNT, STATUS_FAILURE);
            
            // If not found, try alternative names
            if (successCounter == null && failureCounter == null) {
                successCounter = findCounter("vajrapulse.task.executions", "success");
                failureCounter = findCounter("vajrapulse.task.executions", "failure");
            }
            
            if (successCounter == null && failureCounter == null) {
                successCounter = findCounter("vajrapulse.task.success", null);
                failureCounter = findCounter("vajrapulse.task.failures", null);
            }
            
            long success = (long) (successCounter != null ? successCounter.count() : 0.0);
            long failure = (long) (failureCounter != null ? failureCounter.count() : 0.0);
            long total = success + failure;
            
            // Log warning if counters are null (metrics not being recorded)
            if (successCounter == null && failureCounter == null && total == 0 && manualTracker == null) {
                // Only log once to avoid spam
                if (lastTotalExecutions.get() == 0) {
                    log.warn("⚠️ METRICS NOT FOUND: VajraPulse execution metrics not available. " +
                        "Tried: vajrapulse.execution.count, vajrapulse.task.executions, vajrapulse.task.success/failures. " +
                        "Using manual tracking fallback. Listing available metrics...");
                    listAvailableMetrics();
                }
            }
            
            log.debug("Total executions: {} (success: {}, failure: {})", total, success, failure);
            return total;
        } catch (Exception e) {
            log.warn("Failed to get total executions: {}", e.getMessage());
            return 0L;
        }
    }
    
    /**
     * Finds a counter by name and tag.
     * 
     * @param metricName the metric name
     * @param tagValue the tag value (status)
     * @return the counter or null if not found
     */
    private io.micrometer.core.instrument.Counter findCounter(String metricName, String tagValue) {
        try {
            var meter = meterRegistry.find(metricName);
            if (meter == null) {
                return null;
            }
            
            if (tagValue != null) {
                return meter.tag("status", tagValue).counter();
            } else {
                return meter.counter();
            }
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Lists available metrics for debugging.
     */
    private void listAvailableMetrics() {
        try {
            meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().contains("vajrapulse"))
                .limit(20)  // Limit to first 20 to avoid spam
                .forEach(m -> log.info("Available VajraPulse metric: {} (type: {})", 
                    m.getId().getName(), m.getId().getType()));
        } catch (Exception e) {
            log.debug("Could not list metrics: {}", e.getMessage());
        }
    }
    
    /**
     * Gets the failure count from metrics.
     * 
     * @return the failure count
     */
    @Override
    public long getFailureCount() {
        // Try manual tracker first (most reliable)
        if (manualTracker != null) {
            long failures = manualTracker.getFailureCount();
            if (failures > 0 || manualTracker.getTotalExecutions() > 0) {
                return failures;
            }
        }
        
        try {
            // Try multiple metric name variations
            var failureCounter = findCounter(EXECUTION_COUNT, STATUS_FAILURE);
            
            // If not found, try alternative names
            if (failureCounter == null) {
                failureCounter = findCounter("vajrapulse.task.executions", "failure");
            }
            
            if (failureCounter == null) {
                failureCounter = findCounter("vajrapulse.task.failures", null);
            }
            
            return (long) (failureCounter != null ? failureCounter.count() : 0.0);
        } catch (Exception e) {
            log.warn("Failed to get failure count: {}", e.getMessage());
            return 0L;
        }
    }
    
}

