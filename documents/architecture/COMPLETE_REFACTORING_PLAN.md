# Complete Refactoring Plan - Backpressure and Adaptive Load

## Overview

This document provides a complete refactoring plan to restore system performance and implement proper backpressure handling with adaptive load patterns.

**Goal:** Restore 5,000+ TPS performance with robust backpressure handling and continuous adaptive operation.

## Principles

1. **Start from working baseline** - Revert to last known good state
2. **Incremental changes** - One change at a time, test each
3. **Preserve working patterns** - Don't remove code that works
4. **Complete metrics** - Track both immediate and eventual failures
5. **Robust recovery** - Ensure pattern can recover from any state

## Phase 1: Revert to Working Baseline

### Step 1.1: Restore Hybrid Backpressure Approach

**Goal:** Restore the working hybrid blocking approach that was removed.

**Changes:**
1. Remove `submitSync()` usage in `CrdbInsertTask`
2. Restore manual backpressure checks with three-tier strategy:
   - Severe pressure (2x pool size): Reject immediately
   - Moderate pressure (1x pool size): Block briefly (100ms timeout)
   - Low pressure: Submit normally
3. Use `submitWithCallback()` for all submissions

**Files to Modify:**
- `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`

**Code Structure:**
```java
@Override
public TaskResult execute(long iteration) throws Exception {
    TestInsert testInsert = generateTestData();
    
    // Three-tier backpressure strategy
    HikariPoolMXBean poolBean = hikariBackpressureProvider.getPoolBean();
    
    if (poolBean != null) {
        int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
        int total = poolBean.getTotalConnections();
        
        // Tier 1: Severe pressure - reject immediately
        if (threadsAwaiting >= total * 2) {
            return TaskResult.failure(new RuntimeException("Severe backpressure"));
        }
        
        // Tier 2: Moderate pressure - block briefly
        if (threadsAwaiting >= total) {
            long startTime = System.currentTimeMillis();
            long timeout = 100;  // 100ms timeout
            
            while (poolBean.getThreadsAwaitingConnection() >= total) {
                if (System.currentTimeMillis() - startTime > timeout) {
                    return TaskResult.failure(new RuntimeException("Blocking timeout"));
                }
                Thread.sleep(10);
            }
        }
    }
    
    // Tier 3: Low pressure - submit normally
    batcher.submitWithCallback(testInsert, (item, result) -> {
        // Track eventual batch results
        if (result instanceof ItemResult.Failure) {
            submitFailureCounter.increment();
        } else {
            submitSuccessCounter.increment();
        }
    });
    
    return TaskResult.success();
}
```

### Step 1.2: Restore MinimumTpsLoadPattern

**Goal:** Restore the working minimum TPS floor wrapper.

**Changes:**
1. Restore `MinimumTpsLoadPattern` class
2. Remove `RecoveryLoadPattern` (broken workaround)
3. Use `MinimumTpsLoadPattern` to enforce 100 TPS minimum

**Files to Create:**
- `src/main/java/com/crdb/microbatch/service/MinimumTpsLoadPattern.java`

**Files to Delete:**
- `src/main/java/com/crdb/microbatch/service/RecoveryLoadPattern.java`

### Step 1.3: Restore Complete Metrics Tracking

**Goal:** Restore individual item result tracking via callbacks.

**Changes:**
1. Restore `submitSuccessCounter` metric
2. Track both immediate rejections and eventual batch failures
3. Ensure AdaptiveLoadPattern sees complete error rate

**Files to Modify:**
- `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`

## Phase 2: Optimize and Improve

### Step 2.1: Tune Backpressure Thresholds

**Goal:** Optimize backpressure thresholds for better performance.

**Current Issues:**
- Vortex backpressure threshold: 0.7 (too aggressive)
- No gradual backpressure handling

**Optimizations:**
1. **Adjust Vortex threshold:** Increase from 0.7 to 0.85 (85% capacity)
2. **Gradual backpressure:** Use logarithmic scaling for early detection
3. **Tier thresholds:** Fine-tune severe/moderate/low thresholds

**Configuration:**
```java
// Vortex backpressure threshold
RejectStrategy<TestInsert> strategy = new RejectStrategy<>(0.85);  // Increased from 0.7

// Manual backpressure tiers
int severeThreshold = total * 2;      // 2x pool size
int moderateThreshold = total;        // 1x pool size
int blockingTimeout = 100;            // 100ms
```

### Step 2.2: Improve AdaptiveLoadPattern Configuration

**Goal:** Optimize AdaptiveLoadPattern parameters for better ramp-up/ramp-down behavior.

**Current Configuration:**
```java
INITIAL_TPS = 1000.0
RAMP_INCREMENT = 500.0
RAMP_DECREMENT = 1000.0  // Too aggressive
RAMP_INTERVAL = 5 seconds
MAX_TPS = 20000.0
SUSTAIN_DURATION = 30 seconds
ERROR_THRESHOLD = 0.01  // 1%
```

**Optimized Configuration:**
```java
INITIAL_TPS = 1000.0
RAMP_INCREMENT = 500.0
RAMP_DECREMENT = 500.0   // Less aggressive (was 1000.0)
RAMP_INTERVAL = 5 seconds
MAX_TPS = 20000.0
SUSTAIN_DURATION = 30 seconds
ERROR_THRESHOLD = 0.02   // 2% (was 1% - too strict)
```

### Step 2.3: Add Stability Detection

**Goal:** Allow pattern to sustain at intermediate TPS levels.

**Implementation:**
1. Monitor TPS, error rate, and backpressure over time
2. Detect stability at current TPS level
3. Transition to SUSTAIN phase when stable

**Code Structure:**
```java
// In AdaptiveLoadPattern wrapper or new StabilityDetectionLoadPattern
private boolean isStableAtCurrentTps(double tps, long elapsedMillis) {
    double errorRate = metricsProvider.getFailureRate() / 100.0;
    double backpressure = backpressureProvider.getBackpressureLevel();
    
    // Check if conditions are good
    boolean conditionsGood = errorRate < errorThreshold && backpressure < 0.3;
    
    // Check if TPS is stable (hasn't changed significantly)
    boolean tpsStable = Math.abs(currentTps - tps) < 50.0;
    
    // Check if stable for required duration
    boolean durationStable = (elapsedMillis - stabilityStartTime) >= SUSTAIN_DURATION.toMillis();
    
    return conditionsGood && tpsStable && durationStable;
}
```

## Phase 3: Future Enhancements (Optional)

### Step 3.1: Integrate submitSync() Properly (If Available)

**Goal:** Use `submitSync()` for immediate rejection visibility, but keep hybrid approach.

**Approach:**
1. Use `submitSync()` to check immediate rejection
2. If rejected, return failure immediately
3. If accepted, use hybrid blocking approach for moderate pressure
4. Use `submitWithCallback()` for eventual batch result tracking

**Code Structure:**
```java
// 1. Check immediate rejection via submitSync()
ItemResult immediateResult = batcher.submitSync(testInsert);
if (immediateResult instanceof ItemResult.Failure) {
    return TaskResult.failure(immediateResult.error());
}

// 2. Item accepted - check moderate pressure with blocking
// (Hybrid approach for moderate pressure)

// 3. Submit with callback for eventual tracking
batcher.submitWithCallback(testInsert, callback);
```

### Step 3.2: Improve Recovery Mechanism

**Goal:** Make recovery more robust and automatic.

**Approach:**
1. Monitor conditions continuously in RECOVERY phase
2. Automatically transition to RAMP_UP when conditions improve
3. Start recovery at reasonable TPS (not minimum)

## Implementation Checklist

### Phase 1: Revert to Baseline
- [ ] Restore hybrid backpressure approach in `CrdbInsertTask`
- [ ] Restore `MinimumTpsLoadPattern` class
- [ ] Remove `RecoveryLoadPattern` class
- [ ] Restore `submitSuccessCounter` metric
- [ ] Restore `submitWithCallback()` for all submissions
- [ ] Update `LoadTestService` to use `MinimumTpsLoadPattern`
- [ ] Test: Verify system can reach 5,000+ TPS

### Phase 2: Optimize
- [ ] Tune Vortex backpressure threshold (0.7 → 0.85)
- [ ] Optimize AdaptiveLoadPattern parameters
- [ ] Add stability detection at intermediate TPS levels
- [ ] Test: Verify sustained performance at 5,000+ TPS

### Phase 3: Enhance (Optional)
- [ ] Integrate `submitSync()` properly (if available)
- [ ] Improve recovery mechanism
- [ ] Add comprehensive metrics

## Testing Strategy

### Performance Tests
1. **Baseline Test:** Verify 5,000+ TPS sustainable
2. **Ramp-up Test:** Verify pattern can ramp up to 10,000+ TPS
3. **Ramp-down Test:** Verify pattern ramps down under backpressure
4. **Recovery Test:** Verify pattern recovers and ramps up again
5. **Stability Test:** Verify pattern sustains at intermediate TPS levels

### Stress Tests
1. **High Load:** Test with 20,000+ TPS target
2. **Backpressure:** Test with limited connection pool
3. **Error Injection:** Test with database errors
4. **Recovery:** Test recovery from various failure states

## Success Criteria

1. **Performance:** System can sustain 5,000+ TPS
2. **Adaptation:** Pattern adapts to backpressure correctly
3. **Recovery:** Pattern recovers and ramps up when conditions improve
4. **Stability:** Pattern can sustain at intermediate TPS levels
5. **Metrics:** Complete metrics (immediate + eventual failures)

## Risk Mitigation

1. **Incremental Changes:** One change at a time, test each
2. **Baseline First:** Revert to working state before optimizing
3. **Comprehensive Testing:** Test all scenarios before moving to next phase
4. **Rollback Plan:** Keep working code in git history for easy rollback

## Timeline

- **Phase 1 (Revert):** 1-2 days
- **Phase 2 (Optimize):** 2-3 days
- **Phase 3 (Enhance):** 1-2 weeks (optional)

**Total:** 1 week for Phases 1-2, additional time for Phase 3

