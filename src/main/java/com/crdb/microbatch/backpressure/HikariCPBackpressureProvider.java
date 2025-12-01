package com.crdb.microbatch.backpressure;

import com.vajrapulse.api.BackpressureProvider;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Backpressure provider based on HikariCP connection pool metrics.
 * 
 * <p>Reports backpressure level (0.0-1.0) based on:
 * <ul>
 *   <li>Connection pool utilization (active / total)</li>
 *   <li>Pending connection requests (threads awaiting connection)</li>
 * </ul>
 * 
 * <p>Backpressure calculation is aggressive to prevent connection pool exhaustion:
 * <ul>
 *   <li>Reports 1.0 (max) immediately when waiting threads ≥ pool size</li>
 *   <li>Reports 0.7-1.0 when pool is fully utilized (active == total) with waiting threads</li>
 *   <li>Reports 0.5-0.7 when there are waiting threads but pool not fully utilized</li>
 *   <li>Uses logarithmic scale to detect pressure early</li>
 * </ul>
 * 
 * <p>Backpressure levels:
 * <ul>
 *   <li>0.0 - 0.3: Low pressure, can ramp up TPS</li>
 *   <li>0.3 - 0.7: Moderate pressure, maintain current TPS</li>
 *   <li>0.7 - 1.0: High pressure, should ramp down TPS immediately</li>
 * </ul>
 * 
 * <p>The AdaptiveLoadPattern uses these thresholds:
 * <ul>
 *   <li>Ramps down when backpressure ≥ 0.7</li>
 *   <li>Ramps up when backpressure < 0.3</li>
 * </ul>
 */
@Component
public class HikariCPBackpressureProvider implements BackpressureProvider {
    
    private static final Logger log = LoggerFactory.getLogger(HikariCPBackpressureProvider.class);
    
    private final HikariDataSource dataSource;
    private volatile long lastLogTime = 0;
    private static final long LOG_INTERVAL_MS = 5000;  // Log every 5 seconds
    
    /**
     * Constructor for HikariCPBackpressureProvider.
     * 
     * @param dataSource the HikariCP data source
     */
    public HikariCPBackpressureProvider(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }
    
    @Override
    public double getBackpressureLevel() {
        HikariPoolMXBean poolBean = dataSource.getHikariPoolMXBean();
        
        if (poolBean == null) {
            log.debug("Backpressure: Pool bean is null, returning 0.0");
            return 0.0;  // No pool info available, assume no pressure
        }
        
        int active = poolBean.getActiveConnections();
        int total = poolBean.getTotalConnections();
        int idle = poolBean.getIdleConnections();
        int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
        
        if (total == 0) {
            log.debug("Backpressure: Total connections is 0, returning 0.0");
            return 0.0;  // No connections available, assume no pressure
        }
        
        // Calculate pool utilization (0.0 to 1.0)
        double poolUtilization = (double) active / total;
        
        // Calculate queue pressure based on waiting threads
        // CRITICAL: Report maximum backpressure (1.0) immediately when:
        // 1. Pool is fully utilized (active == total) AND there are waiting threads
        // 2. Waiting threads exceed pool size (indicates severe contention)
        // 3. For smaller queue depths, use logarithmic scale to detect pressure earlier
        
        double queuePressure;
        if (threadsAwaiting >= total) {
            // Severe contention: waiting threads >= pool size
            // Report maximum backpressure immediately
            queuePressure = 1.0;
        } else if (threadsAwaiting > 0 && active == total) {
            // Pool fully utilized with waiting threads
            // Report high backpressure (0.8-1.0) based on queue depth
            // Use logarithmic scale: log(threadsAwaiting + 1) / log(total + 1)
            // This makes it sensitive to even small queue depths
            queuePressure = 0.7 + (0.3 * Math.log(threadsAwaiting + 1) / Math.log(total + 1));
            queuePressure = Math.min(queuePressure, 1.0);
        } else if (threadsAwaiting > 0) {
            // Some waiting threads but pool not fully utilized
            // Report moderate backpressure (0.5-0.7) to start ramping down early
            queuePressure = 0.5 + (0.2 * Math.log(threadsAwaiting + 1) / Math.log(total + 1));
            queuePressure = Math.min(queuePressure, 0.7);
        } else {
            // No waiting threads
            queuePressure = 0.0;
        }
        
        // Return maximum of pool utilization and queue pressure
        // This ensures we report backpressure if either metric indicates pressure
        // CRITICAL: If pool is 100% utilized OR there are waiting threads, report high backpressure
        double backpressure = Math.max(poolUtilization, queuePressure);
        
        // Additional safety: If waiting threads are excessive (>> pool size), force max backpressure
        if (threadsAwaiting > total * 2) {
            backpressure = 1.0;
        }
        
        // Log periodically to avoid spam, but log WARN if backpressure is high
        long now = System.currentTimeMillis();
        if (now - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = now;
            if (backpressure >= 0.7) {
                log.warn("⚠️ HIGH BACKPRESSURE: active={}/{}, idle={}, waiting={}, poolUtil={}, queuePressure={}, backpressure={}",
                    active, total, idle, threadsAwaiting, 
                    String.format("%.2f", poolUtilization), 
                    String.format("%.2f", queuePressure), 
                    String.format("%.2f", backpressure));
            } else {
                log.debug("Backpressure: active={}/{}, idle={}, waiting={}, poolUtil={}, queuePressure={}, backpressure={}",
                    active, total, idle, threadsAwaiting, 
                    String.format("%.2f", poolUtilization), 
                    String.format("%.2f", queuePressure), 
                    String.format("%.2f", backpressure));
            }
        }
        
        return backpressure;
    }
}

