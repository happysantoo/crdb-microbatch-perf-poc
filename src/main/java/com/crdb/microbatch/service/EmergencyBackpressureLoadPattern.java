package com.crdb.microbatch.service;

import com.vajrapulse.api.AdaptiveLoadPattern;
import com.vajrapulse.api.BackpressureProvider;
import com.vajrapulse.api.LoadPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Emergency backpressure handler that wraps AdaptiveLoadPattern to provide immediate TPS reduction
 * when backpressure is detected, without waiting for interval boundaries.
 * 
 * <p>This wrapper addresses the timing mismatch between backpressure detection and TPS adjustment.
 * The AdaptiveLoadPattern only adjusts TPS at interval boundaries (every 5-10 seconds), which can
 * allow connection pool exhaustion before TPS is reduced. This wrapper checks backpressure on
 * every calculateTps() call and immediately reduces TPS when backpressure is critical.
 * 
 * <p>Behavior:
 * <ul>
 *   <li>Checks backpressure on every calculateTps() call (not just at intervals)</li>
 *   <li>Immediately reduces TPS by 50% if backpressure ≥ 0.7</li>
 *   <li>Gradually recovers when backpressure < 0.3</li>
 *   <li>Delegates to AdaptiveLoadPattern for normal interval-based adjustments</li>
 * </ul>
 */
public class EmergencyBackpressureLoadPattern implements LoadPattern {
    
    private static final Logger log = LoggerFactory.getLogger(EmergencyBackpressureLoadPattern.class);
    
    private final AdaptiveLoadPattern delegate;
    private final BackpressureProvider backpressureProvider;
    private volatile double emergencyTps = Double.MAX_VALUE;  // MAX_VALUE = not in emergency mode
    private volatile long lastEmergencyTime = 0;
    private static final long RECOVERY_CHECK_INTERVAL_MS = 1000;  // Check recovery every 1 second
    
    // Emergency thresholds
    private static final double EMERGENCY_BACKPRESSURE_THRESHOLD = 0.7;  // Trigger emergency at 0.7
    private static final double RECOVERY_BACKPRESSURE_THRESHOLD = 0.3;   // Recover when < 0.3
    private static final double EMERGENCY_REDUCTION_FACTOR = 0.5;        // Reduce TPS by 50%
    private static final double RECOVERY_INCREASE_FACTOR = 1.1;         // Increase by 10% per second
    private static final double MIN_TPS = 100.0;                        // Minimum TPS floor
    
    /**
     * Constructor for EmergencyBackpressureLoadPattern.
     * 
     * @param delegate the AdaptiveLoadPattern to wrap
     * @param backpressureProvider the backpressure provider to check
     */
    public EmergencyBackpressureLoadPattern(AdaptiveLoadPattern delegate, BackpressureProvider backpressureProvider) {
        this.delegate = delegate;
        this.backpressureProvider = backpressureProvider;
    }
    
    /**
     * Gets the underlying AdaptiveLoadPattern.
     * 
     * @return the wrapped AdaptiveLoadPattern
     */
    public AdaptiveLoadPattern getAdaptivePattern() {
        return delegate;
    }
    
    @Override
    public double calculateTps(long elapsedMillis) {
        // Check backpressure on EVERY call (not just at intervals)
        double backpressure = backpressureProvider.getBackpressureLevel();
        
        // Get normal TPS from AdaptiveLoadPattern
        double normalTps = delegate.calculateTps(elapsedMillis);
        
        // Emergency mode: backpressure is critical
        if (backpressure >= EMERGENCY_BACKPRESSURE_THRESHOLD) {
            if (emergencyTps == Double.MAX_VALUE) {
                // Entering emergency mode for the first time
                emergencyTps = normalTps * EMERGENCY_REDUCTION_FACTOR;
                emergencyTps = Math.max(emergencyTps, MIN_TPS);  // Enforce minimum
                lastEmergencyTime = System.currentTimeMillis();
                log.warn("🚨 EMERGENCY BACKPRESSURE: TPS reduced from {} to {} (backpressure={})",
                    String.format("%.2f", normalTps), 
                    String.format("%.2f", emergencyTps),
                    String.format("%.2f", backpressure));
            } else {
                // Already in emergency mode - maintain reduced TPS
                // Don't let it go below minimum
                emergencyTps = Math.max(emergencyTps, MIN_TPS);
            }
            return emergencyTps;
        }
        
        // Recovery mode: backpressure is low, gradually recover
        if (emergencyTps < Double.MAX_VALUE && backpressure < RECOVERY_BACKPRESSURE_THRESHOLD) {
            long now = System.currentTimeMillis();
            if (now - lastEmergencyTime >= RECOVERY_CHECK_INTERVAL_MS) {
                // Gradually increase TPS towards normal TPS
                double recoveryTps = emergencyTps * RECOVERY_INCREASE_FACTOR;
                if (recoveryTps >= normalTps) {
                    // Recovered fully - exit emergency mode
                    log.info("✅ RECOVERED FROM EMERGENCY: TPS restored from {} to {} (backpressure={})",
                        String.format("%.2f", emergencyTps),
                        String.format("%.2f", normalTps),
                        String.format("%.2f", backpressure));
                    emergencyTps = Double.MAX_VALUE;  // Exit emergency mode
                    return normalTps;
                } else {
                    // Still recovering - gradually increase
                    emergencyTps = recoveryTps;
                    lastEmergencyTime = now;
                    log.info("🔄 RECOVERING: TPS increased from {} to {} (target: {}, backpressure={})",
                        String.format("%.2f", emergencyTps / RECOVERY_INCREASE_FACTOR),
                        String.format("%.2f", emergencyTps),
                        String.format("%.2f", normalTps),
                        String.format("%.2f", backpressure));
                    return emergencyTps;
                }
            } else {
                // Not time to check recovery yet
                return emergencyTps;
            }
        }
        
        // Normal operation: no emergency, return normal TPS
        if (emergencyTps < Double.MAX_VALUE) {
            // Still in emergency mode but backpressure is moderate (0.3-0.7)
            // Hold at current emergency TPS
            return emergencyTps;
        }
        
        return normalTps;
    }
    
    @Override
    public Duration getDuration() {
        return delegate.getDuration();
    }
}

