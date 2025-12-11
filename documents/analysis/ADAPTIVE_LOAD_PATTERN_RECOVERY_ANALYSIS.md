# AdaptiveLoadPattern Recovery and Sustaining Analysis

## Problem Statement

### Issue 1: Pattern Cannot Sustain at Intermediate TPS Levels

**Symptoms:**
- Pattern only transitions to `SUSTAIN` phase when reaching `MAX_TPS`
- Pattern doesn't find stable points at intermediate TPS levels (e.g., 5000 TPS, 8000 TPS)
- Pattern continuously ramps up and down without sustaining

**Root Cause:**
From AdaptiveLoadPattern behavior:
- `SUSTAIN` phase is only entered when:
  1. Pattern reaches `MAX_TPS` (treated as stable point)
  2. Pattern finds a stable point during `RAMP_UP` (but this requires specific conditions)
- Pattern doesn't have logic to sustain at intermediate TPS levels when conditions are stable
- Pattern is designed to find ONE stable point, not multiple stable points

**Current Behavior:**
```
RAMP_UP: 1000 → 1500 → 2000 → ... → 20000 (MAX_TPS)
    ↓
SUSTAIN at 20000 TPS (only stable point found)
    ↓
If backpressure → RAMP_DOWN: 20000 → 19000 → 18000 → ...
    ↓
Never transitions to SUSTAIN at intermediate levels
    ↓
Continues ramping down until COMPLETE
```

### Issue 2: No Recovery from COMPLETE Phase

**Symptoms:**
- Pattern enters `COMPLETE` phase when ramping down too far
- `MinimumTpsLoadPattern` masks this by returning 100 TPS
- Pattern stays in `COMPLETE` phase internally and never recovers
- Test continues at 100 TPS but never ramps back up

**Root Cause:**
- `COMPLETE` phase is a **terminal state** in AdaptiveLoadPattern
- AdaptiveLoadPattern doesn't have logic to transition from `COMPLETE` back to `RAMP_UP`
- Our wrapper masks the symptom (0 TPS) but doesn't fix the root cause (stuck in COMPLETE)
- When we return 100 TPS, the pattern still thinks it's in COMPLETE phase

**Current Behavior:**
```
RAMP_DOWN: 500 → 100 (minimum floor enforced)
    ↓
AdaptiveLoadPattern: TPS would be 0 → Transitions to COMPLETE
    ↓
MinimumTpsLoadPattern: Returns 100 TPS (masks 0 TPS)
    ↓
AdaptiveLoadPattern: Still in COMPLETE phase internally
    ↓
Pattern never checks if conditions improved
    ↓
Stuck at 100 TPS forever ❌
```

## Root Cause Analysis

### How AdaptiveLoadPattern Works

**Phase Transitions:**
1. **RAMP_UP**: Increases TPS by `RAMP_INCREMENT` every `RAMP_INTERVAL`
   - Transitions to `SUSTAIN` when:
     - Reaches `MAX_TPS` (treated as stable)
     - Finds stable point (error rate < threshold, backpressure < 0.3 for `SUSTAIN_DURATION`)
   - Transitions to `RAMP_DOWN` when:
     - Error rate ≥ `ERROR_THRESHOLD` OR backpressure ≥ 0.7

2. **RAMP_DOWN**: Decreases TPS by `RAMP_DECREMENT` every `RAMP_INTERVAL`
   - Transitions to `RAMP_UP` when:
     - Error rate < `ERROR_THRESHOLD` AND backpressure < 0.3
   - Transitions to `COMPLETE` when:
     - TPS reaches 0 or below minimum viable TPS
     - No stable point found after extended ramping

3. **SUSTAIN**: Holds TPS constant
   - Transitions to `RAMP_DOWN` when:
     - Error rate ≥ `ERROR_THRESHOLD` OR backpressure ≥ 0.7
   - Transitions to `RAMP_UP` when:
     - Error rate < `ERROR_THRESHOLD` AND backpressure < 0.3 (after sustain duration)

4. **COMPLETE**: Terminal state
   - **NO TRANSITIONS OUT** - this is the problem!
   - Pattern considers test complete and stops adjusting

### Why It Doesn't Find Stable Points

**Stable Point Detection Logic:**
- Pattern only detects stable points during `RAMP_UP` phase
- Requires:
  - Error rate < `ERROR_THRESHOLD` (1%)
  - Backpressure < 0.3
  - Maintained for `SUSTAIN_DURATION` (30 seconds)
- If these conditions are met during `RAMP_UP`, it transitions to `SUSTAIN`

**Problem:**
- During `RAMP_UP`, pattern increases TPS every 5 seconds
- If conditions are good, it keeps ramping up (doesn't sustain)
- Only sustains when it hits `MAX_TPS` or conditions deteriorate
- Never sustains at intermediate levels where conditions are stable

## Solution Plan

### Phase 1: Enable Recovery from COMPLETE Phase

**Goal:** Allow pattern to recover from `COMPLETE` phase when conditions improve.

**Approach:** Create a wrapper that monitors conditions and forces phase transition.

**Implementation:**
1. **Create `RecoveryLoadPattern` wrapper**:
   - Monitors underlying `AdaptiveLoadPattern` phase
   - When phase is `COMPLETE`, checks if conditions improved:
     - Error rate < `ERROR_THRESHOLD`
     - Backpressure < 0.3
   - If conditions improved, forces transition back to `RAMP_UP`:
     - Resets pattern state (if possible via reflection)
     - OR creates new `AdaptiveLoadPattern` instance
     - OR intercepts `calculateTps()` to return increasing TPS

2. **Recovery Logic:**
   ```java
   if (phase == COMPLETE) {
       if (errorRate < ERROR_THRESHOLD && backpressure < 0.3) {
           // Force recovery: transition back to RAMP_UP
           // Start at minimum TPS and allow ramping
           return Math.max(minimumTps, lastKnownGoodTps * 0.5);
       }
   }
   ```

**Challenges:**
- AdaptiveLoadPattern doesn't expose methods to reset state
- May need to use reflection or create new instance
- Need to track "last known good TPS" before COMPLETE

### Phase 2: Enable Sustaining at Intermediate TPS Levels

**Goal:** Allow pattern to find and sustain at stable intermediate TPS levels.

**Approach:** Create a wrapper that detects stability and forces SUSTAIN phase.

**Implementation:**
1. **Create `StabilityDetectionLoadPattern` wrapper**:
   - Monitors TPS, error rate, and backpressure over time
   - Detects when conditions are stable at current TPS:
     - Error rate < `ERROR_THRESHOLD`
     - Backpressure < 0.3
     - TPS hasn't changed significantly
     - Maintained for `SUSTAIN_DURATION`
   - When stability detected, forces SUSTAIN behavior:
     - Returns constant TPS (no ramping)
     - Logs stability detection

2. **Stability Detection Logic:**
   ```java
   // Track metrics over time window
   if (stableConditionsForDuration(errorRate, backpressure, currentTps, SUSTAIN_DURATION)) {
       // Force sustain at current TPS
       return currentTps;  // Don't ramp up/down
   }
   ```

**Challenges:**
- Need to track metrics over time window
- Need to coordinate with AdaptiveLoadPattern's internal state
- May conflict with AdaptiveLoadPattern's own SUSTAIN logic

### Phase 3: Hybrid Approach - Smart Wrapper

**Goal:** Combine recovery and stability detection in a single smart wrapper.

**Approach:** Create `ContinuousAdaptiveLoadPattern` that wraps `AdaptiveLoadPattern` and adds:
1. Recovery from COMPLETE phase
2. Stability detection at intermediate levels
3. Continuous operation (never stops)

**Implementation:**
```java
public class ContinuousAdaptiveLoadPattern implements LoadPattern {
    private final AdaptiveLoadPattern delegate;
    private final double minimumTps;
    private final MetricsProvider metricsProvider;
    private final BackpressureProvider backpressureProvider;
    
    // State tracking
    private double lastKnownGoodTps = 1000.0;
    private long lastStableTime = 0;
    private double stableTps = 0;
    private AdaptiveLoadPattern.Phase lastPhase;
    
    @Override
    public double calculateTps(long elapsedMillis) {
        double tps = delegate.calculateTps(elapsedMillis);
        AdaptiveLoadPattern.Phase phase = delegate.getCurrentPhase();
        
        // 1. Handle COMPLETE phase recovery
        if (phase == AdaptiveLoadPattern.Phase.COMPLETE) {
            return handleCompleteRecovery(elapsedMillis);
        }
        
        // 2. Handle stability detection at intermediate levels
        if (phase == AdaptiveLoadPattern.Phase.RAMP_UP || phase == AdaptiveLoadPattern.Phase.RAMP_DOWN) {
            return handleStabilityDetection(tps, elapsedMillis);
        }
        
        // 3. Enforce minimum TPS floor
        return Math.max(tps, minimumTps);
    }
    
    private double handleCompleteRecovery(long elapsedMillis) {
        double errorRate = metricsProvider.getFailureRate() / 100.0;
        double backpressure = backpressureProvider.getBackpressureLevel();
        
        // Check if conditions improved
        if (errorRate < ERROR_THRESHOLD && backpressure < 0.3) {
            // Force recovery: start ramping from last known good TPS
            double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
            log.info("🔄 Recovery from COMPLETE: conditions improved, starting at {} TPS", recoveryTps);
            // TODO: Force AdaptiveLoadPattern to transition to RAMP_UP
            return recoveryTps;
        }
        
        // Conditions still poor, maintain minimum
        return minimumTps;
    }
    
    private double handleStabilityDetection(double currentTps, long elapsedMillis) {
        double errorRate = metricsProvider.getFailureRate() / 100.0;
        double backpressure = backpressureProvider.getBackpressureLevel();
        
        // Check if conditions are stable
        if (errorRate < ERROR_THRESHOLD && backpressure < 0.3) {
            if (stableTps == 0 || Math.abs(currentTps - stableTps) < 50) {
                // Same TPS level, check duration
                if (lastStableTime == 0) {
                    lastStableTime = elapsedMillis;
                    stableTps = currentTps;
                } else if (elapsedMillis - lastStableTime >= SUSTAIN_DURATION.toMillis()) {
                    // Stable for required duration, force sustain
                    log.info("⏸️ Stability detected at {} TPS, sustaining", currentTps);
                    return stableTps;  // Return constant TPS
                }
            } else {
                // TPS changed, reset stability tracking
                lastStableTime = 0;
                stableTps = 0;
            }
        } else {
            // Conditions not stable, reset tracking
            lastStableTime = 0;
            stableTps = 0;
        }
        
        return currentTps;
    }
}
```

## Detailed Implementation Plan

### Step 1: Create Recovery Mechanism

**File:** `src/main/java/com/crdb/microbatch/service/RecoveryLoadPattern.java`

**Responsibilities:**
- Monitor `AdaptiveLoadPattern` phase
- Detect when phase is `COMPLETE`
- Check if conditions improved (error rate, backpressure)
- Force recovery by returning increasing TPS
- Track last known good TPS before COMPLETE

**Key Methods:**
- `handleCompleteRecovery()`: Checks conditions and forces recovery
- `trackLastGoodTps()`: Tracks TPS before COMPLETE transition

### Step 2: Create Stability Detection

**File:** `src/main/java/com/crdb/microbatch/service/StabilityDetectionLoadPattern.java`

**Responsibilities:**
- Monitor TPS, error rate, backpressure over time
- Detect stability at intermediate TPS levels
- Force SUSTAIN behavior when stable
- Reset stability tracking when conditions change

**Key Methods:**
- `detectStability()`: Checks if conditions are stable
- `forceSustain()`: Returns constant TPS to force sustain

### Step 3: Combine into Single Wrapper

**File:** `src/main/java/com/crdb/microbatch/service/ContinuousAdaptiveLoadPattern.java`

**Responsibilities:**
- Combine recovery and stability detection
- Wrap `AdaptiveLoadPattern`
- Provide continuous operation
- Handle all phase transitions

**Integration:**
```java
AdaptiveLoadPattern adaptivePattern = new AdaptiveLoadPattern(...);
LoadPattern continuousPattern = new ContinuousAdaptiveLoadPattern(
    adaptivePattern,
    MINIMUM_TPS,
    metricsProvider,
    backpressureProvider
);
LoadPattern loadPattern = new PhaseLoggingLoadPattern(continuousPattern);
```

### Step 4: Remove MinimumTpsLoadPattern

**Rationale:**
- `ContinuousAdaptiveLoadPattern` will handle minimum TPS floor
- No need for separate wrapper
- Simplifies wrapper chain

### Step 5: Testing and Validation

**Test Scenarios:**
1. **Recovery from COMPLETE:**
   - Pattern enters COMPLETE
   - Conditions improve (backpressure decreases)
   - Verify pattern recovers and ramps up

2. **Stability at Intermediate Levels:**
   - Pattern ramps up to 5000 TPS
   - Conditions stable for 30 seconds
   - Verify pattern sustains at 5000 TPS

3. **Continuous Operation:**
   - Pattern ramps up and down multiple times
   - Verify it never stops
   - Verify it finds stable points

## Expected Behavior After Fix

### Scenario 1: Recovery from COMPLETE

```
RAMP_DOWN: 500 → 100 (minimum floor)
    ↓
AdaptiveLoadPattern: COMPLETE phase
    ↓
ContinuousAdaptiveLoadPattern: Detects COMPLETE
    ↓
Checks conditions: errorRate=0.5%, backpressure=0.2 (improved!)
    ↓
Forces recovery: Returns 500 TPS (50% of last known good)
    ↓
AdaptiveLoadPattern: Transitions to RAMP_UP (if possible)
    ↓
RAMP_UP: 500 → 1000 → 1500 → ...
```

### Scenario 2: Stability at Intermediate Level

```
RAMP_UP: 1000 → 1500 → 2000 → 2500 → 3000
    ↓
At 3000 TPS: errorRate=0.5%, backpressure=0.2 (stable)
    ↓
StabilityDetection: Tracks stability for 30 seconds
    ↓
After 30 seconds: Still stable
    ↓
Forces SUSTAIN: Returns constant 3000 TPS
    ↓
Pattern sustains at 3000 TPS (not MAX_TPS)
```

### Scenario 3: Continuous Operation

```
RAMP_UP: 1000 → 5000 (finds stable point)
    ↓
SUSTAIN: 5000 TPS (sustained)
    ↓
Backpressure increases → RAMP_DOWN: 5000 → 4000
    ↓
Conditions improve → RAMP_UP: 4000 → 5000
    ↓
SUSTAIN: 5000 TPS (sustained again)
    ↓
Cycle continues indefinitely ✅
```

## Implementation Priority

1. **High Priority:** Recovery from COMPLETE phase
   - Critical for continuous operation
   - Prevents test from getting stuck

2. **Medium Priority:** Stability detection at intermediate levels
   - Improves pattern behavior
   - Allows finding optimal TPS

3. **Low Priority:** Refactoring and cleanup
   - Combine wrappers
   - Simplify code

## Risks and Mitigations

### Risk 1: AdaptiveLoadPattern Internal State

**Risk:** Can't modify AdaptiveLoadPattern's internal phase state.

**Mitigation:**
- Intercept `calculateTps()` to return appropriate TPS
- Pattern will naturally transition based on returned TPS
- May need to track phase transitions manually

### Risk 2: Conflicting Logic

**Risk:** Our wrapper logic conflicts with AdaptiveLoadPattern's logic.

**Mitigation:**
- Test thoroughly with different scenarios
- Monitor phase transitions
- Adjust logic if conflicts arise

### Risk 3: Performance Impact

**Risk:** Additional wrappers add overhead.

**Mitigation:**
- Wrappers are lightweight (just method calls)
- No additional threads or polling
- Minimal performance impact

## Success Criteria

1. ✅ Pattern recovers from COMPLETE phase when conditions improve
2. ✅ Pattern finds and sustains at intermediate TPS levels
3. ✅ Test runs continuously without stopping
4. ✅ Pattern adapts to changing conditions
5. ✅ No performance degradation

