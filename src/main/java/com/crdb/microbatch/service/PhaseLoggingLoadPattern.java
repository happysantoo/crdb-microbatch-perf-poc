package com.crdb.microbatch.service;

import com.vajrapulse.api.AdaptiveLoadPattern;
import com.vajrapulse.api.LoadPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wrapper around AdaptiveLoadPattern that logs phase transitions and TPS changes.
 * 
 * <p>This wrapper logs RAMP_UP and RAMP_DOWN events synchronously when calculateTps()
 * is called, without requiring a separate monitoring thread.
 */
public class PhaseLoggingLoadPattern implements LoadPattern {
    
    private static final Logger log = LoggerFactory.getLogger(PhaseLoggingLoadPattern.class);
    
    private final LoadPattern delegate;  // Can be AdaptiveLoadPattern or EmergencyBackpressureLoadPattern
    private final AtomicReference<AdaptiveLoadPattern.Phase> lastPhase = new AtomicReference<>();
    private final AtomicReference<Double> lastTps = new AtomicReference<>();
    
    /**
     * Constructor for PhaseLoggingLoadPattern.
     * 
     * @param delegate the LoadPattern to wrap (can be AdaptiveLoadPattern or EmergencyBackpressureLoadPattern)
     */
    public PhaseLoggingLoadPattern(LoadPattern delegate) {
        this.delegate = delegate;
        // Initialize phase and TPS from underlying AdaptiveLoadPattern if available
        AdaptiveLoadPattern adaptivePattern = getAdaptivePattern();
        if (adaptivePattern != null) {
            this.lastPhase.set(adaptivePattern.getCurrentPhase());
            this.lastTps.set(adaptivePattern.getCurrentTps());
        }
    }
    
    /**
     * Gets the underlying AdaptiveLoadPattern.
     * 
     * <p>If this wraps an EmergencyBackpressureLoadPattern, extracts the AdaptiveLoadPattern
     * from it. If delegate is AdaptiveLoadPattern, returns it directly. Otherwise returns null.
     * 
     * @return the wrapped AdaptiveLoadPattern, or null if not available
     */
    public AdaptiveLoadPattern getAdaptivePattern() {
        if (delegate instanceof EmergencyBackpressureLoadPattern emergency) {
            return emergency.getAdaptivePattern();
        } else if (delegate instanceof AdaptiveLoadPattern adaptive) {
            return adaptive;
        }
        return null;
    }
    
    @Override
    public double calculateTps(long elapsedMillis) {
        double tps = delegate.calculateTps(elapsedMillis);
        
        // Check for phase transitions (only if we have an AdaptiveLoadPattern)
        AdaptiveLoadPattern adaptivePattern = getAdaptivePattern();
        if (adaptivePattern != null) {
            AdaptiveLoadPattern.Phase currentPhase = adaptivePattern.getCurrentPhase();
            AdaptiveLoadPattern.Phase previousPhase = lastPhase.getAndSet(currentPhase);
            
            // Check for TPS changes
            Double previousTps = lastTps.getAndSet(tps);
            
            // Log phase transitions
            if (previousPhase != currentPhase) {
                logPhaseTransition(previousPhase, currentPhase, tps);
            }
            
            // Log significant TPS changes (ramp up/down)
            if (previousTps != null && Math.abs(tps - previousTps) > 1.0) {
                logTpsChange(previousTps, tps, currentPhase);
            }
        }
        
        return tps;
    }
    
    /**
     * Logs phase transitions.
     */
    private void logPhaseTransition(AdaptiveLoadPattern.Phase from, AdaptiveLoadPattern.Phase to, double tps) {
        String tpsFormatted = String.format("%.2f", tps);
        switch (to) {
            case RAMP_UP -> log.info("🔼 RAMP_UP: Phase transition from {} to RAMP_UP, TPS: {}", from, tpsFormatted);
            case RAMP_DOWN -> log.info("🔽 RAMP_DOWN: Phase transition from {} to RAMP_DOWN, TPS: {}", from, tpsFormatted);
            case SUSTAIN -> log.info("⏸️ SUSTAIN: Phase transition from {} to SUSTAIN, TPS: {}", from, tpsFormatted);
            case COMPLETE -> log.info("✅ COMPLETE: Phase transition from {} to COMPLETE, TPS: {}", from, tpsFormatted);
        }
    }
    
    /**
     * Logs TPS changes during ramp up or ramp down.
     */
    private void logTpsChange(double fromTps, double toTps, AdaptiveLoadPattern.Phase phase) {
        String fromTpsFormatted = String.format("%.2f", fromTps);
        String toTpsFormatted = String.format("%.2f", toTps);
        double delta = phase == AdaptiveLoadPattern.Phase.RAMP_UP ? (toTps - fromTps) : (fromTps - toTps);
        String deltaFormatted = String.format("%.2f", delta);
        
        if (phase == AdaptiveLoadPattern.Phase.RAMP_UP) {
            log.info("🔼 RAMP_UP: TPS increased from {} to {} (+{})", 
                fromTpsFormatted, toTpsFormatted, deltaFormatted);
        } else if (phase == AdaptiveLoadPattern.Phase.RAMP_DOWN) {
            log.info("🔽 RAMP_DOWN: TPS decreased from {} to {} (-{})", 
                fromTpsFormatted, toTpsFormatted, deltaFormatted);
        }
    }
    
    @Override
    public Duration getDuration() {
        return delegate.getDuration();
    }
}

