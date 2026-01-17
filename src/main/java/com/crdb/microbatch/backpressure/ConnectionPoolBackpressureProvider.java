package com.crdb.microbatch.backpressure;

import com.vajrapulse.vortex.backpressure.BackpressureProvider;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.stereotype.Component;

/**
 * Backpressure provider based on HikariCP connection pool metrics.
 * 
 * <p>This provider calculates backpressure primarily based on threads awaiting
 * connections. This is the most direct signal that the connection pool cannot
 * keep up with demand. Pool utilization is used as a secondary indicator.
 * 
 * <p>Backpressure calculation prioritizes threads awaiting connection:
 * <ul>
 *   <li><strong>0.0</strong>: No threads waiting - system can handle load</li>
 *   <li><strong>0.5-0.7</strong>: Some threads waiting - early warning</li>
 *   <li><strong>0.7-1.0</strong>: Many threads waiting - severe pressure</li>
 * </ul>
 * 
 * <p>Key insight: If threads are waiting for connections, the system is
 * overloaded regardless of pool utilization. High utilization without waiting
 * threads indicates the system is handling load efficiently.
 * 
 * <p>This is used in combination with queue depth backpressure to provide
 * comprehensive backpressure signaling that prevents both queue overflow
 * and connection pool exhaustion.
 */
@Component
public class ConnectionPoolBackpressureProvider implements BackpressureProvider {
    
    private final HikariDataSource dataSource;
    
    /**
     * Constructor for ConnectionPoolBackpressureProvider.
     * 
     * @param dataSource the HikariCP data source
     */
    public ConnectionPoolBackpressureProvider(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }
    
    @Override
    public String getSourceName() {
        return "HikariCP Connection Pool";
    }
    
    @Override
    public double getBackpressureLevel() {
        HikariPoolMXBean poolBean = dataSource.getHikariPoolMXBean();
        
        if (poolBean == null) {
            return 0.0;
        }
        
        int active = poolBean.getActiveConnections();
        int total = poolBean.getTotalConnections();
        int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
        
        if (total == 0) {
            return 0.0;
        }
        
        // PRIMARY SIGNAL: Threads awaiting connection
        // If threads are waiting, the system is overloaded regardless of utilization
        if (threadsAwaiting > 0) {
            // Calculate backpressure based on threads waiting
            // Normalize to pool size for consistent scaling
            if (threadsAwaiting >= total) {
                // Severe pressure: as many or more threads waiting than connections available
                return 1.0;
            } else {
                // Logarithmic scale for early detection
                // When 1 thread waiting: ~0.5 pressure (early warning)
                // When total/2 threads waiting: ~0.8 pressure (moderate)
                // When total threads waiting: 1.0 pressure (severe)
                double waitPressure = 0.5 + (0.5 * Math.log(threadsAwaiting + 1) / Math.log(total + 1));
                return Math.min(waitPressure, 1.0);
            }
        }
        
        // SECONDARY SIGNAL: Pool utilization (only when no threads waiting)
        // High utilization without waiting threads means system is handling load efficiently
        // Only report backpressure if pool is nearly exhausted (>= 90%)
        double poolUtilization = (double) active / total;
        if (poolUtilization >= 0.9) {
            // Pool nearly exhausted - report moderate backpressure as warning
            // Scale from 0.5 (90% utilized) to 0.7 (100% utilized)
            return 0.5 + (0.2 * (poolUtilization - 0.9) / 0.1);
        }
        
        // No backpressure: no threads waiting and pool has capacity
        return 0.0;
    }
}

