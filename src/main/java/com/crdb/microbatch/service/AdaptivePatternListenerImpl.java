package com.crdb.microbatch.service;

import com.vajrapulse.api.pattern.adaptive.AdaptivePatternListener;
import com.vajrapulse.api.pattern.adaptive.PhaseTransitionEvent;
import com.vajrapulse.api.pattern.adaptive.TpsChangeEvent;
import com.vajrapulse.api.pattern.adaptive.StabilityDetectedEvent;
import com.vajrapulse.api.pattern.adaptive.RecoveryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Implementation of AdaptivePatternListener for VajraPulse 0.9.11.
 * 
 * <p>This listener receives events directly from AdaptiveLoadPattern and provides
 * continuous console updates for monitoring ramp up, ramp down, and other phase transitions.
 * 
 * <p>Events logged:
 * <ul>
 *   <li>Phase transitions (RAMP_UP, RAMP_DOWN, SUSTAIN)</li>
 *   <li>TPS changes (increases, decreases, sustains)</li>
 *   <li>Stability detection events</li>
 *   <li>Recovery events</li>
 * </ul>
 */
public class AdaptivePatternListenerImpl implements AdaptivePatternListener {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptivePatternListenerImpl.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private final MeterRegistry meterRegistry;
    
    // Track last logged phase to prevent duplicate logging
    private volatile String lastLoggedPhase = null;
    private volatile long lastPhaseLogTime = 0;
    private static final long PHASE_LOG_THROTTLE_MS = 1000; // Only log phase changes once per second
    
    // Track last diagnostic log time
    private volatile long lastDiagnosticLogTime = 0;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 5000; // Log diagnostics every 5 seconds
    
    /**
     * Constructor for AdaptivePatternListenerImpl.
     * 
     * @param meterRegistry the Micrometer meter registry (optional, for metrics display)
     */
    public AdaptivePatternListenerImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Default constructor (no metrics display).
     */
    public AdaptivePatternListenerImpl() {
        this.meterRegistry = null;
    }
    
    @Override
    public void onPhaseTransition(PhaseTransitionEvent event) {
        String fromPhase = event.from().toString();
        String toPhase = event.to().toString();
        
        // Only log if phase actually changed (from != to)
        if (fromPhase.equals(toPhase)) {
            // Phase didn't change - skip logging to avoid spam
            return;
        }
        
        // Create a unique key for this transition
        String transitionKey = fromPhase + "→" + toPhase;
        long now = System.currentTimeMillis();
        
        // Throttle: Only log the same transition once per second
        if (transitionKey.equals(lastLoggedPhase) && 
            (now - lastPhaseLogTime) < PHASE_LOG_THROTTLE_MS) {
            // Same transition logged recently - skip
            return;
        }
        
        // Log the phase transition
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String phaseIcon = getPhaseIcon(event.to());
        String transitionType = getTransitionType(event.from(), event.to());
        
        log.info("{} [{}] {} PHASE TRANSITION: {} → {} | Current TPS: {}", 
            phaseIcon,
            timestamp,
            transitionType,
            fromPhase,
            toPhase,
            String.format("%.2f", event.tps()));
        
        // Update tracking
        lastLoggedPhase = transitionKey;
        lastPhaseLogTime = now;
    }
    
    @Override
    public void onTpsChange(TpsChangeEvent event) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        double delta = event.newTps() - event.previousTps();
        double percentChange = event.previousTps() > 0 ? (delta / event.previousTps()) * 100.0 : 0.0;

        String direction;
        String icon;
        if (delta > 0) {
            direction = "RAMP UP";
            icon = "⬆️";
        } else if (delta < 0) {
            direction = "RAMP DOWN";
            icon = "⬇️";
        } else {
            direction = "SUSTAIN";
            icon = "➡️";
        }

        // Get current failure rate if metrics provider is available
        String failureRateInfo = getFailureRateInfo();

        // Enhanced logging for ramp down events to help debug why TPS isn't adjusting
        if (delta < 0) {
            log.warn("{} [{}] 🚨 {} TPS: {} → {} ({}, {}%) {} - INVESTIGATING RAMP DOWN",
                icon,
                timestamp,
                direction,
                String.format("%.2f", event.previousTps()),
                String.format("%.2f", event.newTps()),
                String.format("%+.2f", delta),
                String.format("%+.2f", percentChange),
                failureRateInfo);
        } else if (delta > 0) {
            log.info("{} [{}] {} TPS: {} → {} ({}, {}%) {}",
                icon,
                timestamp,
                direction,
                String.format("%.2f", event.previousTps()),
                String.format("%.2f", event.newTps()),
                String.format("%+.2f", delta),
                String.format("%+.2f", percentChange),
                failureRateInfo);
        } else {
            log.debug("{} [{}] {} TPS: {} → {} ({}, {}%) {}",
                icon,
                timestamp,
                direction,
                String.format("%.2f", event.previousTps()),
                String.format("%.2f", event.newTps()),
                String.format("%+.2f", delta),
                String.format("%+.2f", percentChange),
                failureRateInfo);
        }

        // Log diagnostic info periodically
        logDiagnostics();
    }
    
    @Override
    public void onStabilityDetected(StabilityDetectedEvent event) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        log.info("✅ [{}] STABILITY DETECTED: TPS {} has been sustained - system is stable", 
            timestamp,
            String.format("%.2f", event.stableTps()));
    }
    
    @Override
    public void onRecovery(RecoveryEvent event) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        double recoveryPercent = event.lastKnownGoodTps() > 0 
            ? (event.recoveryTps() / event.lastKnownGoodTps()) * 100.0 
            : 0.0;
        
        log.info("🔄 [{}] RECOVERY INITIATED: Ramping up to {} TPS ({}% of last known good: {})", 
            timestamp,
            String.format("%.2f", event.recoveryTps()),
            String.format("%.1f", recoveryPercent),
            String.format("%.2f", event.lastKnownGoodTps()));
    }
    
    /**
     * Gets the icon for a phase.
     * 
     * @param phase the phase
     * @return the icon string
     */
    private String getPhaseIcon(Object phase) {
        String phaseStr = phase.toString();
        if (phaseStr.contains("RAMP_UP")) {
            return "🔼";
        } else if (phaseStr.contains("RAMP_DOWN")) {
            return "🔽";
        } else if (phaseStr.contains("SUSTAIN")) {
            return "⏸️";
        } else if (phaseStr.contains("RECOVERY")) {
            return "🔄";
        }
        return "🔄";
    }
    
    /**
     * Gets a description of the transition type.
     * 
     * @param from the previous phase
     * @param to the new phase
     * @return the transition description
     */
    private String getTransitionType(Object from, Object to) {
        String fromStr = from.toString();
        String toStr = to.toString();
        
        if (fromStr.equals(toStr)) {
            return "PHASE CONTINUED";
        } else if (toStr.contains("RAMP_UP")) {
            return "STARTING RAMP UP";
        } else if (toStr.contains("RAMP_DOWN")) {
            return "STARTING RAMP DOWN";
        } else if (toStr.contains("SUSTAIN")) {
            return "ENTERING SUSTAIN";
        } else if (toStr.contains("RECOVERY")) {
            return "ENTERING RECOVERY";
        }
        return "PHASE CHANGE";
    }
    
    /**
     * Gets current failure rate information for logging.
     * 
     * @return failure rate info string
     */
    private String getFailureRateInfo() {
        if (meterRegistry == null) {
            return "";
        }
        
        try {
            var successCounter = meterRegistry.find("vajrapulse.execution.count")
                .tag("status", "success")
                .counter();
            var failureCounter = meterRegistry.find("vajrapulse.execution.count")
                .tag("status", "failure")
                .counter();
            
            long success = (long) (successCounter != null ? successCounter.count() : 0.0);
            long failures = (long) (failureCounter != null ? failureCounter.count() : 0.0);
            long total = success + failures;
            
            if (total == 0) {
                return "| Executions: 0";
            }
            
            double failureRatePercent = (failures / (double) total) * 100.0;
            return String.format("| Failure Rate: %.2f%% (%d/%d)", 
                failureRatePercent, failures, total);
        } catch (Exception e) {
            return "| Metrics: unavailable";
        }
    }
    
    /**
     * Logs diagnostic information periodically to help debug metrics issues.
     */
    private void logDiagnostics() {
        if (meterRegistry == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        if ((now - lastDiagnosticLogTime) < DIAGNOSTIC_LOG_INTERVAL_MS) {
            return;
        }
        lastDiagnosticLogTime = now;
        
        try {
            var successCounter = meterRegistry.find("vajrapulse.execution.count")
                .tag("status", "success")
                .counter();
            var failureCounter = meterRegistry.find("vajrapulse.execution.count")
                .tag("status", "failure")
                .counter();
            
            long success = (long) (successCounter != null ? successCounter.count() : 0.0);
            long failures = (long) (failureCounter != null ? failureCounter.count() : 0.0);
            long total = success + failures;
            
            if (total > 0) {
                double failureRatePercent = (failures / (double) total) * 100.0;
                String status = failureRatePercent >= 10.0 ? "⚠️ HIGH" : "OK";
                log.info("📊 [DIAGNOSTIC] {} Failure Rate: {}% | Total: {} | Success: {} | Failures: {} | " +
                    "Counters available: success={}, failure={}", 
                    status,
                    String.format("%.2f", failureRatePercent),
                    total,
                    success,
                    failures,
                    successCounter != null,
                    failureCounter != null);
            } else {
                // This is expected when using manual metrics tracking (VajraPulse metrics not in registry)
                // Only log at debug level to avoid confusion
                if (successCounter == null && failureCounter == null) {
                    log.debug("📊 [DIAGNOSTIC] Using manual metrics tracking (VajraPulse metrics not in registry)");
                } else {
                    log.debug("📊 [DIAGNOSTIC] No executions recorded yet in registry metrics | " +
                        "Counters available: success={}, failure={}", 
                        successCounter != null,
                        failureCounter != null);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to log diagnostics: {}", e.getMessage());
        }
    }
}
