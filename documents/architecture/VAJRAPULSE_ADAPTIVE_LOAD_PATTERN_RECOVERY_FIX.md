# VajraPulse AdaptiveLoadPattern RECOVERY Phase Fix

## Problem Statement

**Current Issue:**
- `AdaptiveLoadPattern` enters RECOVERY phase when TPS ramps down to 0
- RECOVERY phase does not transition back to RAMP_UP even when conditions improve
- Wrappers (like `MinimumTpsLoadPattern`) cannot force the transition
- Result: System gets stuck at minimum TPS (100 TPS) even when system can handle more

**Evidence:**
- Logs show recovery attempts (1375 TPS suggested)
- But actual TPS stays at 100 TPS (minimum floor)
- Prometheus metrics confirm ~100 TPS throughput
- `AdaptiveLoadPattern` remains in RECOVERY phase indefinitely

## Root Cause Analysis

### Current RECOVERY Phase Behavior

**From VajraPulse 0.9.7 Analysis:**
- RECOVERY phase is entered when TPS reaches 0 or very low
- `handleRecovery()` method exists but may not properly transition to RAMP_UP
- Recovery conditions may not be checked correctly
- Phase transition logic may be missing or incomplete

### Why Wrappers Don't Work

**The Problem:**
```java
// In MinimumTpsLoadPattern
if (phase == AdaptiveLoadPattern.Phase.RECOVERY) {
    if (canRecover) {
        return 1375.0;  // Try to suggest higher TPS
    }
}

// But AdaptiveLoadPattern internally:
if (phase == RECOVERY) {
    return handleRecovery(...);  // Returns 0 or minimum TPS
    // Ignores wrapper's suggestion!
}
```

**The Issue:**
- Wrapper returns 1375 TPS
- But `AdaptiveLoadPattern` is still in RECOVERY phase internally
- It returns its own TPS (0 or minimum), ignoring the wrapper
- Execution engine uses `AdaptiveLoadPattern`'s TPS, not wrapper's

## Design Principles

1. **Simplicity First** - Fix in AdaptiveLoadPattern itself, no wrappers needed
2. **Use Existing Interfaces** - No new backpressure providers needed
3. **Clear State Machine** - Explicit RECOVERY→RAMP_UP transition
4. **Recent Window Metrics** - Use recent failure rate, not all-time average

## Proposed Solution

### Fix 1: Proper RECOVERY→RAMP_UP Transition

**Location:** `AdaptiveLoadPattern.handleRecovery()`

**Current Behavior (Assumed):**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    // Returns minimum TPS or 0
    // Never transitions to RAMP_UP
    return minimumTps;
}
```

**Fixed Behavior:**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    // Check if conditions improved
    double errorRate = metricsProvider.getFailureRate() / 100.0;  // Convert percentage to ratio
    double backpressure = backpressureProvider.getBackpressureLevel();
    
    // Recovery conditions: backpressure low OR (error rate low AND backpressure moderate)
    boolean canRecover = backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5);
    
    if (canRecover) {
        // Transition to RAMP_UP
        double recoveryTps = Math.max(minimumTps, current.lastKnownGoodTps() * 0.5);
        transitionPhase(Phase.RAMP_UP, elapsedMillis, recoveryTps);
        return recoveryTps;
    }
    
    // Stay in recovery at minimum TPS
    return minimumTps;
}
```

**Key Changes:**
1. Check recovery conditions (backpressure and error rate)
2. If conditions improved, transition to RAMP_UP phase
3. Start recovery at 50% of last known good TPS (conservative)
4. Use existing `transitionPhase()` method

### Fix 2: Track Last Known Good TPS

**Location:** `AdaptiveState` record

**Add Field:**
```java
public record AdaptiveState(
    Phase phase,
    double currentTps,
    double stableTps,
    long phaseStartTime,
    double lastKnownGoodTps  // NEW: Track last good TPS for recovery
) {
    // ...
}
```

**Update Logic:**
- When transitioning to RAMP_DOWN or RECOVERY, save current TPS as `lastKnownGoodTps`
- Use this for recovery TPS calculation (50% of last good TPS)

### Fix 3: Use Recent Window Failure Rate (Optional Enhancement)

**Location:** `MetricsProvider` interface

**Current:**
```java
double getFailureRate();  // All-time average
```

**Enhanced (Optional):**
```java
double getFailureRate();  // Keep for backward compatibility
double getRecentFailureRate(int windowSeconds);  // NEW: Recent window
```

**Default Implementation:**
```java
// In DefaultMetricsProvider
@Override
public double getRecentFailureRate(int windowSeconds) {
    long windowStart = System.currentTimeMillis() - (windowSeconds * 1000);
    return getFailureRate(windowStart, System.currentTimeMillis());
}
```

**Use in Recovery:**
```java
// Use recent window (10 seconds) for recovery decisions
double errorRate = metricsProvider.getRecentFailureRate(10) / 100.0;
```

**Why This Helps:**
- All-time average includes old failures
- Recent window reflects current system state
- Recovery can happen even if historical error rate is high

## Implementation Plan

### Phase 1: Core RECOVERY Fix (Priority: High)

**File:** `AdaptiveLoadPattern.java`

**Changes:**
1. Fix `handleRecovery()` method to check conditions and transition
2. Add `lastKnownGoodTps` tracking in `AdaptiveState`
3. Update phase transitions to track last known good TPS

**Code:**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    double errorRate = metricsProvider.getFailureRate() / 100.0;
    double backpressure = backpressureProvider.getBackpressureLevel();
    
    // Recovery conditions: backpressure low OR (error rate low AND backpressure moderate)
    boolean canRecover = backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5);
    
    if (canRecover) {
        // Start recovery at 50% of last known good TPS (conservative)
        double lastKnownGoodTps = current.lastKnownGoodTps() > 0 
            ? current.lastKnownGoodTps() 
            : initialTps;
        double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
        
        // Transition to RAMP_UP
        AdaptiveState newState = current.withPhase(Phase.RAMP_UP)
            .withCurrentTps(recoveryTps)
            .withPhaseStartTime(elapsedMillis);
        state = newState;
        
        return recoveryTps;
    }
    
    // Stay in recovery at minimum TPS
    return minimumTps;
}
```

**Update Phase Transitions:**
```java
// When transitioning to RAMP_DOWN or RECOVERY, save current TPS
if (newPhase == Phase.RAMP_DOWN || newPhase == Phase.RECOVERY) {
    if (current.currentTps() > current.lastKnownGoodTps()) {
        newState = newState.withLastKnownGoodTps(current.currentTps());
    }
}
```

### Phase 2: Recent Window Failure Rate (Priority: Medium)

**File:** `MetricsProvider.java` and `DefaultMetricsProvider.java`

**Changes:**
1. Add `getRecentFailureRate(int windowSeconds)` method
2. Implement in `DefaultMetricsProvider`
3. Use in `handleRecovery()` for better recovery decisions

**Code:**
```java
// In MetricsProvider interface
/**
 * Gets the failure rate over a recent time window.
 * 
 * @param windowSeconds the time window in seconds (e.g., 10)
 * @return failure rate as percentage (0.0-100.0)
 * @since 0.9.8
 */
default double getRecentFailureRate(int windowSeconds) {
    // Default implementation uses all-time average
    // Subclasses can override for better accuracy
    return getFailureRate();
}
```

**In DefaultMetricsProvider:**
```java
@Override
public double getRecentFailureRate(int windowSeconds) {
    long windowStart = System.currentTimeMillis() - (windowSeconds * 1000);
    // Calculate failure rate for window
    // Implementation depends on metrics storage
    return calculateFailureRate(windowStart, System.currentTimeMillis());
}
```

**Use in Recovery:**
```java
// Use recent window (10 seconds) for recovery decisions
double errorRate;
if (metricsProvider instanceof DefaultMetricsProvider) {
    errorRate = metricsProvider.getRecentFailureRate(10) / 100.0;
} else {
    errorRate = metricsProvider.getFailureRate() / 100.0;
}
```

## Success Criteria

### Must Have
- [ ] RECOVERY phase transitions to RAMP_UP when conditions improve
- [ ] Recovery starts at 50% of last known good TPS
- [ ] No wrappers needed - works directly with AdaptiveLoadPattern
- [ ] Uses existing BackpressureProvider interface
- [ ] All existing tests pass
- [ ] New tests for RECOVERY→RAMP_UP transitions

### Nice to Have
- [ ] Recent window failure rate support
- [ ] Configurable recovery thresholds
- [ ] Recovery attempt logging

## Testing Requirements

### Unit Tests

**Test 1: RECOVERY→RAMP_UP Transition**
```java
@Test
void testRecoveryTransitionsToRampUpWhenConditionsImprove() {
    // Setup: AdaptiveLoadPattern in RECOVERY phase
    // Conditions: backpressure < 0.3, error rate < threshold
    // Expected: Transitions to RAMP_UP, returns recovery TPS
}
```

**Test 2: RECOVERY Stays in Recovery When Conditions Poor**
```java
@Test
void testRecoveryStaysInRecoveryWhenConditionsPoor() {
    // Setup: AdaptiveLoadPattern in RECOVERY phase
    // Conditions: backpressure >= 0.3, error rate >= threshold
    // Expected: Stays in RECOVERY, returns minimum TPS
}
```

**Test 3: Recovery TPS is 50% of Last Known Good**
```java
@Test
void testRecoveryTpsIs50PercentOfLastKnownGood() {
    // Setup: Last known good TPS = 2000
    // Expected: Recovery TPS = 1000 (50% of 2000)
}
```

### Integration Tests

**Test: End-to-End Recovery**
```java
@Test
void testEndToEndRecovery() {
    // 1. Ramp up to high TPS
    // 2. Trigger backpressure/errors → RAMP_DOWN
    // 3. Ramp down to 0 → RECOVERY
    // 4. Conditions improve → RAMP_UP
    // 5. Verify TPS increases from minimum
}
```

## Migration Guide

### For Users of AdaptiveLoadPattern

**No Changes Required:**
- Existing code continues to work
- RECOVERY phase now works correctly
- No wrappers needed

**Optional Enhancements:**
- If using custom MetricsProvider, implement `getRecentFailureRate()` for better recovery
- Recovery thresholds can be tuned via existing errorThreshold parameter

### For Wrapper Implementations

**Can Remove Wrappers:**
- `MinimumTpsLoadPattern` no longer needed for recovery
- `AdaptiveLoadPattern` handles recovery internally
- Wrappers can be removed or simplified

**If Keeping Wrappers:**
- They will still work, but recovery logic in AdaptiveLoadPattern takes precedence
- Wrappers can focus on other concerns (logging, additional constraints)

## Implementation Details

### File Structure

```
vajrapulse-core/
  src/main/java/com/vajrapulse/core/pattern/
    AdaptiveLoadPattern.java          # Main fix here
    AdaptiveState.java                 # Add lastKnownGoodTps field
    
  src/main/java/com/vajrapulse/api/
    MetricsProvider.java               # Add getRecentFailureRate() (optional)
    
  src/main/java/com/vajrapulse/core/metrics/
    DefaultMetricsProvider.java        # Implement getRecentFailureRate() (optional)
```

### Key Methods to Modify

1. **`AdaptiveLoadPattern.handleRecovery()`**
   - Add condition checking
   - Add phase transition logic
   - Return recovery TPS

2. **`AdaptiveLoadPattern.transitionPhase()`**
   - Track lastKnownGoodTps when transitioning to RAMP_DOWN/RECOVERY

3. **`AdaptiveState` record**
   - Add `lastKnownGoodTps` field

4. **`MetricsProvider` interface (optional)**
   - Add `getRecentFailureRate()` method

## Timeline

- **Week 1:** Phase 1 (Core RECOVERY fix)
- **Week 2:** Phase 2 (Recent window failure rate, optional)
- **Week 3:** Testing and documentation

## Benefits

1. **No Wrappers Needed** - AdaptiveLoadPattern works correctly out of the box
2. **Simple Design** - Fix is contained in AdaptiveLoadPattern
3. **Uses Existing Interfaces** - No new backpressure providers needed
4. **Backward Compatible** - Existing code continues to work
5. **Better Recovery** - System recovers automatically when conditions improve

## Example Usage (After Fix)

```java
// No wrappers needed!
AdaptiveLoadPattern pattern = new AdaptiveLoadPattern(
    INITIAL_TPS,
    RAMP_INCREMENT,
    RAMP_DECREMENT,
    RAMP_INTERVAL,
    MAX_TPS,
    SUSTAIN_DURATION,
    ERROR_THRESHOLD,
    metricsProvider,
    backpressureProvider
);

// Pattern will automatically:
// 1. Ramp up when conditions are good
// 2. Ramp down when backpressure/errors detected
// 3. Enter RECOVERY when TPS reaches 0
// 4. Transition back to RAMP_UP when conditions improve ✅
// 5. No wrappers needed! ✅
```

## Conclusion

This fix makes `AdaptiveLoadPattern` self-contained and eliminates the need for wrappers. The recovery mechanism is simple, uses existing interfaces, and follows the design document's principle of simplicity first.

