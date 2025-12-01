package com.crdb.microbatch.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostics component to monitor batching behavior.
 * 
 * <p>Periodically logs batching metrics to help diagnose why batching
 * might not be working as expected.
 */
@Component
public class BatchingDiagnostics {
    
    private static final Logger log = LoggerFactory.getLogger(BatchingDiagnostics.class);
    
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastBatchCount = new AtomicLong(0);
    private final AtomicLong lastSubmitCount = new AtomicLong(0);
    
    public BatchingDiagnostics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Logs batching diagnostics every 5 seconds.
     * 
     * <p>DISABLED: Removed @Scheduled annotation to reduce logging noise.
     * Metrics are available via Micrometer for monitoring.
     */
    // @Scheduled(fixedRate = 5000)  // Disabled - too noisy
    public void logBatchingDiagnostics() {
        try {
            // Get current metrics
            long currentBatches = getCounterValue("crdb.batches.total");
            long currentSubmits = getCounterValue("crdb.submits.total");
            
            // Calculate deltas
            long batchesDelta = currentBatches - lastBatchCount.get();
            long submitsDelta = currentSubmits - lastSubmitCount.get();
            
            // Calculate average batch size
            double avgBatchSize = batchesDelta > 0 ? (double) submitsDelta / batchesDelta : 0.0;
            
            // Get Vortex metrics if available
            Double queueDepth = getGaugeValue("vortex.queue.depth");
            
            log.info("=== Batching Diagnostics ===");
            log.info("Batches dispatched (last 5s): {}", batchesDelta);
            log.info("Items submitted (last 5s): {}", submitsDelta);
            log.info("Average batch size: {} items/batch", String.format("%.2f", avgBatchSize));
            if (queueDepth != null) {
                log.info("Vortex queue depth: {}", queueDepth);
            }
            
            // Warn if batching is not working
            if (batchesDelta > 0 && avgBatchSize < 2.0) {
                log.warn("⚠️ BATCHING ISSUE: Average batch size is {} (expected ~50). Items are not being accumulated!", 
                    String.format("%.2f", avgBatchSize));
            } else if (batchesDelta > 0 && avgBatchSize < 10.0) {
                log.warn("⚠️ Small batches: Average batch size is {} (expected ~50). Batching may not be efficient.", 
                    String.format("%.2f", avgBatchSize));
            } else if (batchesDelta > 0 && avgBatchSize >= 10.0) {
                log.info("✅ Batching working: Average batch size is {}", String.format("%.2f", avgBatchSize));
            }
            
            // Update last values
            lastBatchCount.set(currentBatches);
            lastSubmitCount.set(currentSubmits);
            
        } catch (Exception e) {
            log.debug("Error getting batching diagnostics: {}", e.getMessage());
        }
    }
    
    /**
     * Gets the current value of a counter metric.
     */
    private long getCounterValue(String metricName) {
        try {
            var counter = meterRegistry.find(metricName).counter();
            return counter != null ? (long) counter.count() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
    
    /**
     * Gets the current value of a gauge metric.
     */
    private Double getGaugeValue(String metricName) {
        try {
            var gauge = meterRegistry.find(metricName).gauge();
            return gauge != null ? gauge.value() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

