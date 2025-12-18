package com.crdb.microbatch.service;

import com.vajrapulse.api.pattern.adaptive.AdaptivePatternListener;
import com.vajrapulse.api.pattern.adaptive.PhaseTransitionEvent;
import com.vajrapulse.api.pattern.adaptive.TpsChangeEvent;
import com.vajrapulse.api.pattern.adaptive.StabilityDetectedEvent;
import com.vajrapulse.api.pattern.adaptive.RecoveryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of AdaptivePatternListener for VajraPulse 0.9.9.
 * 
 * <p>This listener receives events directly from AdaptiveLoadPattern:
 * <ul>
 *   <li>Phase transitions (RAMP_UP, RAMP_DOWN, SUSTAIN)</li>
 *   <li>TPS changes (increases, decreases, sustains)</li>
 *   <li>Stability detection events</li>
 *   <li>Recovery events</li>
 * </ul>
 * 
 * <p>This is the proper way to monitor AdaptiveLoadPattern behavior according to
 * VajraPulse 0.9.9 changelog.
 */
public class AdaptivePatternListenerImpl implements AdaptivePatternListener {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptivePatternListenerImpl.class);
    
    @Override
    public void onPhaseTransition(PhaseTransitionEvent event) {
        log.info("🔄 PHASE TRANSITION: {} → {} | TPS: {}", 
            event.from(),
            event.to(),
            String.format("%.2f", event.tps()));
    }
    
    @Override
    public void onTpsChange(TpsChangeEvent event) {
        double delta = event.newTps() - event.previousTps();
        String direction = delta > 0 ? "⬆️ INCREASE" : delta < 0 ? "⬇️ DECREASE" : "➡️ SUSTAIN";
        
        log.info("{} TPS: {} → {} ({})", 
            direction,
            String.format("%.2f", event.previousTps()),
            String.format("%.2f", event.newTps()),
            String.format("%+.2f", delta));
    }
    
    @Override
    public void onStabilityDetected(StabilityDetectedEvent event) {
        log.info("✅ STABILITY DETECTED: TPS {} sustained", 
            String.format("%.2f", event.stableTps()));
    }
    
    @Override
    public void onRecovery(RecoveryEvent event) {
        log.info("🔄 RECOVERY: Ramping up to {} TPS | Last Known Good: {}", 
            String.format("%.2f", event.recoveryTps()),
            String.format("%.2f", event.lastKnownGoodTps()));
    }
}
