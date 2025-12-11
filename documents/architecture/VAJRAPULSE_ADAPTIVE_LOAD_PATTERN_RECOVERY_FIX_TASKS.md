# VajraPulse AdaptiveLoadPattern RECOVERY Fix - Detailed Task Plan

## Overview

This document provides a detailed, step-by-step task plan for fixing the RECOVERY phase in `AdaptiveLoadPattern` to enable automatic recovery from low TPS without requiring wrappers.

**Target Version:** 0.9.8  
**Estimated Effort:** 1-2 weeks  
**Priority:** High

## Task Breakdown

### Task 1: Add Last Known Good TPS Tracking

**Priority:** Critical  
**Estimated Effort:** 1 day

#### Subtask 1.1: Update AdaptiveState Record

**File:** `AdaptiveState.java` (or equivalent state class)

**Changes:**
```java
public record AdaptiveState(
    Phase phase,
    double currentTps,
    double stableTps,
    long phaseStartTime,
    double lastKnownGoodTps  // NEW field
) {
    // Add withLastKnownGoodTps() method for immutability
    public AdaptiveState withLastKnownGoodTps(double tps) {
        return new AdaptiveState(phase, currentTps, stableTps, phaseStartTime, tps);
    }
}
```

**Acceptance Criteria:**
- [ ] `lastKnownGoodTps` field added to AdaptiveState
- [ ] Immutability maintained (withLastKnownGoodTps() method)
- [ ] Default value is `initialTps` (or 0 if not set)
- [ ] All existing tests pass

#### Subtask 1.2: Track Last Known Good TPS on Phase Transitions

**File:** `AdaptiveLoadPattern.java`

**Location:** `transitionPhase()` or equivalent method

**Changes:**
```java
private void transitionPhase(Phase newPhase, long elapsedMillis, double newTps) {
    AdaptiveState current = state;
    
    // Track last known good TPS when transitioning to RAMP_DOWN or RECOVERY
    double lastKnownGoodTps = current.lastKnownGoodTps();
    if (newPhase == Phase.RAMP_DOWN || newPhase == Phase.RECOVERY) {
        // Save current TPS if it's higher than last known good
        if (current.currentTps() > lastKnownGoodTps) {
            lastKnownGoodTps = current.currentTps();
        }
    }
    
    AdaptiveState newState = new AdaptiveState(
        newPhase,
        newTps,
        current.stableTps(),
        elapsedMillis,
        lastKnownGoodTps
    );
    
    state = newState;
}
```

**Acceptance Criteria:**
- [ ] Last known good TPS tracked when entering RAMP_DOWN or RECOVERY
- [ ] Only updates if current TPS is higher than last known good
- [ ] Preserves last known good TPS across other phase transitions
- [ ] Unit tests verify tracking logic

---

### Task 2: Fix handleRecovery() Method

**Priority:** Critical  
**Estimated Effort:** 2 days

#### Subtask 2.1: Add Recovery Condition Checking

**File:** `AdaptiveLoadPattern.java`

**Location:** `handleRecovery()` method

**Current Code (Assumed):**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    // Returns minimum TPS, never transitions
    return minimumTps;
}
```

**New Code:**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    // Check current conditions
    double errorRate = metricsProvider.getFailureRate() / 100.0;  // Convert percentage to ratio
    double backpressure = backpressureProvider.getBackpressureLevel();
    
    // Recovery conditions: backpressure low OR (error rate low AND backpressure moderate)
    // Following design document recommendations
    boolean canRecover = backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5);
    
    if (canRecover) {
        // Calculate recovery TPS: 50% of last known good TPS (conservative)
        double lastKnownGoodTps = current.lastKnownGoodTps() > 0 
            ? current.lastKnownGoodTps() 
            : initialTps;
        double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
        
        // Transition to RAMP_UP phase
        transitionPhase(Phase.RAMP_UP, elapsedMillis, recoveryTps);
        
        return recoveryTps;
    }
    
    // Conditions still poor, stay in recovery at minimum TPS
    return minimumTps;
}
```

**Acceptance Criteria:**
- [ ] Checks backpressure and error rate conditions
- [ ] Transitions to RAMP_UP when conditions improve
- [ ] Returns recovery TPS (50% of last known good)
- [ ] Stays in RECOVERY when conditions are poor
- [ ] Unit tests verify all scenarios

#### Subtask 2.2: Add Recovery Logging (Optional)

**File:** `AdaptiveLoadPattern.java`

**Changes:**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    // ... condition checking ...
    
    if (canRecover) {
        double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
        
        // Log recovery transition (once per interval to avoid spam)
        if (elapsedMillis - lastRecoveryLogTime > 5000) {
            log.info("🔄 RECOVERY→RAMP_UP: Conditions improved, recovering from {} to {} TPS", 
                String.format("%.2f", minimumTps),
                String.format("%.2f", recoveryTps));
            lastRecoveryLogTime = elapsedMillis;
        }
        
        transitionPhase(Phase.RAMP_UP, elapsedMillis, recoveryTps);
        return recoveryTps;
    }
    
    return minimumTps;
}
```

**Acceptance Criteria:**
- [ ] Recovery transition logged (optional)
- [ ] Logging throttled to avoid spam
- [ ] Log includes recovery TPS and conditions

---

### Task 3: Unit Tests

**Priority:** High  
**Estimated Effort:** 2 days

#### Subtask 3.1: Test RECOVERY→RAMP_UP Transition

**File:** `AdaptiveLoadPatternTest.java`

**Test:**
```java
@Test
void testRecoveryTransitionsToRampUpWhenConditionsImprove() {
    // Setup
    MetricsProvider metricsProvider = mock(MetricsProvider.class);
    BackpressureProvider backpressureProvider = mock(BackpressureProvider.class);
    
    // Set conditions: low backpressure, low error rate
    when(backpressureProvider.getBackpressureLevel()).thenReturn(0.2);
    when(metricsProvider.getFailureRate()).thenReturn(0.5);  // 0.5% error rate
    
    AdaptiveLoadPattern pattern = new AdaptiveLoadPattern(
        1000.0,  // initialTps
        500.0,   // rampIncrement
        1000.0,  // rampDecrement
        Duration.ofSeconds(5),
        20000.0,
        Duration.ofSeconds(30),
        0.01,    // errorThreshold (1%)
        metricsProvider,
        backpressureProvider
    );
    
    // Force into RECOVERY phase (by ramping down to 0)
    // ... trigger RAMP_DOWN until TPS reaches 0 ...
    
    // Verify in RECOVERY phase
    assertEquals(Phase.RECOVERY, pattern.getCurrentPhase());
    
    // Call calculateTps() - should transition to RAMP_UP
    double tps = pattern.calculateTps(60000);  // 60 seconds elapsed
    
    // Verify transition
    assertEquals(Phase.RAMP_UP, pattern.getCurrentPhase());
    assertTrue(tps > 100.0);  // Recovery TPS should be > minimum
    assertEquals(500.0, tps, 0.01);  // 50% of 1000 (last known good)
}
```

**Acceptance Criteria:**
- [ ] Test verifies RECOVERY→RAMP_UP transition
- [ ] Test verifies recovery TPS calculation (50% of last known good)
- [ ] Test covers both recovery conditions (backpressure < 0.3, error rate low)

#### Subtask 3.2: Test RECOVERY Stays in Recovery

**Test:**
```java
@Test
void testRecoveryStaysInRecoveryWhenConditionsPoor() {
    // Setup with poor conditions
    when(backpressureProvider.getBackpressureLevel()).thenReturn(0.8);  // High backpressure
    when(metricsProvider.getFailureRate()).thenReturn(5.0);  // 5% error rate
    
    // ... force into RECOVERY ...
    
    // Call calculateTps()
    double tps = pattern.calculateTps(60000);
    
    // Verify still in RECOVERY
    assertEquals(Phase.RECOVERY, pattern.getCurrentPhase());
    assertEquals(minimumTps, tps, 0.01);  // Should return minimum TPS
}
```

**Acceptance Criteria:**
- [ ] Test verifies RECOVERY phase persists when conditions are poor
- [ ] Test verifies minimum TPS is returned
- [ ] Test covers both poor condition scenarios (high backpressure, high error rate)

#### Subtask 3.3: Test Last Known Good TPS Tracking

**Test:**
```java
@Test
void testLastKnownGoodTpsTracking() {
    // Ramp up to 2000 TPS
    // ... trigger RAMP_UP ...
    assertEquals(2000.0, pattern.getCurrentTps(), 0.01);
    
    // Trigger RAMP_DOWN
    // ... trigger backpressure ...
    
    // Verify lastKnownGoodTps is 2000
    // (Access via reflection or getter if added)
    assertEquals(2000.0, pattern.getLastKnownGoodTps(), 0.01);
    
    // Enter RECOVERY
    // ... ramp down to 0 ...
    
    // Verify recovery TPS is 50% of last known good (1000)
    double recoveryTps = pattern.calculateTps(60000);
    assertEquals(1000.0, recoveryTps, 0.01);  // 50% of 2000
}
```

**Acceptance Criteria:**
- [ ] Test verifies lastKnownGoodTps is tracked correctly
- [ ] Test verifies recovery TPS uses lastKnownGoodTps
- [ ] Test verifies lastKnownGoodTps only updates when TPS is higher

---

### Task 4: Integration Tests

**Priority:** Medium  
**Estimated Effort:** 1 day

#### Subtask 4.1: End-to-End Recovery Test

**File:** `AdaptiveLoadPatternIntegrationTest.java`

**Test:**
```java
@Test
void testEndToEndRecovery() {
    // 1. Ramp up to high TPS (e.g., 5000 TPS)
    // 2. Trigger backpressure → RAMP_DOWN
    // 3. Ramp down to 0 → RECOVERY
    // 4. Conditions improve → RAMP_UP
    // 5. Verify TPS increases from minimum
    
    // Setup
    // ... create pattern with mock providers ...
    
    // Step 1: Ramp up
    // ... simulate good conditions ...
    double highTps = pattern.calculateTps(10000);
    assertTrue(highTps > 1000.0);
    
    // Step 2: Trigger backpressure
    when(backpressureProvider.getBackpressureLevel()).thenReturn(0.8);
    // ... wait for RAMP_DOWN ...
    
    // Step 3: Ramp down to 0
    // ... continue RAMP_DOWN until TPS reaches 0 ...
    assertEquals(Phase.RECOVERY, pattern.getCurrentPhase());
    
    // Step 4: Conditions improve
    when(backpressureProvider.getBackpressureLevel()).thenReturn(0.2);
    when(metricsProvider.getFailureRate()).thenReturn(0.5);
    
    // Step 5: Verify recovery
    double recoveryTps = pattern.calculateTps(60000);
    assertEquals(Phase.RAMP_UP, pattern.getCurrentPhase());
    assertTrue(recoveryTps > minimumTps);
}
```

**Acceptance Criteria:**
- [ ] Test covers full recovery cycle
- [ ] Test verifies phase transitions
- [ ] Test verifies TPS values at each stage

---

### Task 5: Documentation

**Priority:** Medium  
**Estimated Effort:** 0.5 days

#### Subtask 5.1: Update JavaDoc

**File:** `AdaptiveLoadPattern.java`

**Changes:**
```java
/**
 * Adaptive load pattern that automatically adjusts TPS based on error rate and backpressure.
 * 
 * <p>Phases:
 * <ul>
 *   <li><strong>RAMP_UP</strong>: Increases TPS when conditions are good</li>
 *   <li><strong>RAMP_DOWN</strong>: Decreases TPS when backpressure/errors detected</li>
 *   <li><strong>SUSTAIN</strong>: Maintains TPS at stable point</li>
 *   <li><strong>RECOVERY</strong>: Automatic recovery from low TPS when conditions improve</li>
 * </ul>
 * 
 * <p><strong>RECOVERY Phase:</strong>
 * The pattern enters RECOVERY when TPS ramps down to 0. It automatically transitions
 * back to RAMP_UP when conditions improve (backpressure < 0.3 OR error rate low).
 * Recovery starts at 50% of the last known good TPS for conservative recovery.
 * 
 * @since 0.9.8
 */
public class AdaptiveLoadPattern implements LoadPattern {
    // ...
}
```

**Acceptance Criteria:**
- [ ] JavaDoc explains RECOVERY phase behavior
- [ ] Recovery conditions documented
- [ ] Recovery TPS calculation explained

#### Subtask 5.2: Update User Guide

**File:** `documents/guides/ADAPTIVE_LOAD_PATTERN_GUIDE.md`

**Add Section:**
```markdown
## RECOVERY Phase

The RECOVERY phase is automatically entered when TPS ramps down to 0. The pattern
will automatically transition back to RAMP_UP when conditions improve.

### Recovery Conditions

The pattern recovers when:
- Backpressure < 0.3, OR
- Error rate < threshold AND backpressure < 0.5

### Recovery TPS

Recovery starts at 50% of the last known good TPS. This conservative approach
prevents overwhelming the system immediately after recovery.

### Example

```java
// Pattern automatically recovers - no wrappers needed!
AdaptiveLoadPattern pattern = new AdaptiveLoadPattern(...);

// If system enters RECOVERY:
// - Pattern monitors conditions
// - When conditions improve → automatically transitions to RAMP_UP
// - Starts at 50% of last known good TPS
```
```

**Acceptance Criteria:**
- [ ] User guide explains RECOVERY phase
- [ ] Examples show automatic recovery
- [ ] No wrappers needed mentioned

---

## Testing Checklist

### Unit Tests
- [ ] RECOVERY→RAMP_UP transition when conditions improve
- [ ] RECOVERY stays in recovery when conditions poor
- [ ] Recovery TPS is 50% of last known good
- [ ] Last known good TPS tracking works correctly
- [ ] Recovery conditions checked correctly (backpressure, error rate)

### Integration Tests
- [ ] End-to-end recovery cycle (RAMP_UP → RAMP_DOWN → RECOVERY → RAMP_UP)
- [ ] Multiple recovery cycles
- [ ] Recovery with different last known good TPS values

### Manual Testing
- [ ] Run load test and verify recovery works
- [ ] Verify no wrappers needed
- [ ] Verify Prometheus metrics show correct TPS after recovery

## Success Criteria

### Must Have
- [ ] RECOVERY phase transitions to RAMP_UP when conditions improve
- [ ] Recovery starts at 50% of last known good TPS
- [ ] No wrappers needed - works directly with AdaptiveLoadPattern
- [ ] Uses existing BackpressureProvider interface (no new providers)
- [ ] All existing tests pass
- [ ] New tests for RECOVERY transitions pass

### Nice to Have
- [ ] Recovery attempt logging
- [ ] Configurable recovery thresholds
- [ ] Recent window failure rate support (separate task)

## Risk Mitigation

### Risk 1: Breaking Existing Behavior
**Mitigation:** 
- All changes are additive (new logic, not replacing existing)
- Existing tests must pass
- Backward compatible

### Risk 2: Recovery Too Aggressive/Conservative
**Mitigation:**
- Start conservative (50% of last known good)
- Recovery conditions are configurable via errorThreshold
- Can be tuned based on feedback

### Risk 3: Performance Impact
**Mitigation:**
- Recovery checks are simple (backpressure + error rate)
- No expensive operations
- Logging throttled to avoid spam

## Timeline

- **Day 1-2:** Task 1 (Last Known Good TPS Tracking)
- **Day 3-4:** Task 2 (Fix handleRecovery())
- **Day 5-6:** Task 3 (Unit Tests)
- **Day 7:** Task 4 (Integration Tests)
- **Day 8:** Task 5 (Documentation)
- **Day 9-10:** Review, polish, final testing

**Total:** 1.5-2 weeks

## Dependencies

- VajraPulse 0.9.7 (current version)
- No external dependencies
- Uses existing MetricsProvider and BackpressureProvider interfaces

## Notes

- This fix makes wrappers unnecessary for recovery
- Existing wrappers will still work but are no longer needed
- Simple design - all logic in AdaptiveLoadPattern
- No new interfaces or providers needed

