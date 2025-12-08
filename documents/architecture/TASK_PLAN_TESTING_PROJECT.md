# Testing Project Integration Task Plan

## Overview

This document provides a detailed implementation plan for integrating the simplified backpressure and adaptive load pattern design into the CRDB Microbatch Performance POC testing project.

**Target:** Current testing project  
**Estimated Effort:** 1 week  
**Priority:** High  
**Dependencies:** VajraPulse 0.9.7, Vortex 0.0.5

## Goals

1. **Use submitSync() for Immediate Rejection Visibility** - Replace manual backpressure checks with `submitSync()`
2. **Use submitWithCallback() for Eventual Batch Results** - Track batch processing failures via metrics
3. **Remove Workaround Wrappers** - Delete `MinimumTpsLoadPattern` (no longer needed)
4. **Simplify Code** - Remove hybrid blocking logic and manual checks

## Current State Analysis

### Current Implementation

**Current Flow:**
1. Manual backpressure check in `CrdbInsertTask.execute()`
2. Hybrid blocking logic (severe/moderate pressure thresholds)
3. `submitWithCallback()` for async batch results
4. `MinimumTpsLoadPattern` wrapper to prevent 0 TPS

**Problems:**
1. Manual backpressure checks (workaround)
2. Complex hybrid blocking logic
3. Multiple wrapper layers
4. Fragile recovery mechanisms

## Task Breakdown

### Task 1: Update CrdbInsertTask to Use submitSync()

**Priority:** Critical  
**Estimated Effort:** 1 day

#### Subtasks

1.1. **Remove Manual Backpressure Checks**
- Remove hybrid blocking logic
- Remove manual `HikariPoolMXBean` access
- Remove blocking thresholds and timeouts

**Files to Modify:**
- `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`

**Code to Remove:**
```java
// REMOVE: Manual backpressure checks
com.zaxxer.hikari.HikariPoolMXBean poolBean = hikariBackpressureProvider.getPoolBean();

if (poolBean != null) {
    int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
    int total = poolBean.getTotalConnections();
    
    // REMOVE: Severe pressure rejection
    // REMOVE: Moderate pressure blocking
    // REMOVE: Blocking timeout logic
}
```

1.2. **Replace with submitSync()**
- Use `batcher.submitSync()` for immediate rejection check
- Return `TaskResult.failure()` if rejected immediately

**Code Changes:**
```java
@Override
public TaskResult execute(long iteration) throws Exception {
    submitCounter.increment();
    Timer.Sample sample = Timer.start(meterRegistry);
    long submitStartTime = System.currentTimeMillis();
    
    try {
        TestInsert testInsert = generateTestData();
        
        // 1. Check immediate rejection (backpressure/queue full)
        ItemResult<TestInsert> immediateResult = batcher.submitSync(testInsert);
        
        if (immediateResult instanceof ItemResult.Failure<TestInsert> failure) {
            // Immediate rejection - VajraPulse sees this as failure
            sample.stop(submitLatencyTimer);  // Short latency (just rejection check)
            submitFailureCounter.increment();
            return TaskResult.failure(failure.error());
        }
        
        // 2. Item accepted and queued - track eventual batch result
        // Use callback to track when batch actually processes
        batcher.submitWithCallback(testInsert, (item, batchResult) -> {
            // This callback fires when batch is actually processed
            sample.stop(submitLatencyTimer);  // Actual latency (queue + batch processing)
            
            if (batchResult instanceof ItemResult.Failure<TestInsert> batchFailure) {
                // Batch processing failed (e.g., database error)
                submitFailureCounter.increment();
            } else {
                submitSuccessCounter.increment();
            }
        });
        
        // Return success - item was accepted and queued
        // Actual batch result tracked via callback (affects metrics)
        return TaskResult.success();
        
    } catch (Exception e) {
        submitFailureCounter.increment();
        sample.stop(submitLatencyTimer);
        long totalDuration = System.currentTimeMillis() - submitStartTime;
        log.error("Task submission failed at iteration: {}, duration: {}ms", iteration, totalDuration, e);
        return TaskResult.failure(e);
    }
}
```

1.3. **Remove Unused Metrics**
- Remove `submitBlockedCounter`
- Remove `submitBlockedTimeoutCounter`
- Remove `submitBlockingTimer`
- Keep `submitLatencyTimer` (used in callback)

**Code Changes:**
```java
// REMOVE these metrics:
// private Counter submitBlockedCounter;
// private Counter submitBlockedTimeoutCounter;
// private Timer submitBlockingTimer;

// KEEP:
private Counter submitCounter;
private Counter submitSuccessCounter;
private Counter submitFailureCounter;
private Timer submitLatencyTimer;
```

1.4. **Update Metrics Initialization**
- Remove initialization of blocked/blocking metrics
- Keep existing metrics initialization

#### Acceptance Criteria

- [ ] Manual backpressure checks removed
- [ ] Hybrid blocking logic removed
- [ ] `submitSync()` used for immediate rejection check
- [ ] `submitWithCallback()` used for eventual batch results
- [ ] `TaskResult.failure()` returned on immediate rejection
- [ ] `TaskResult.success()` returned on acceptance
- [ ] Latency tracked in callback (accurate)
- [ ] Unused metrics removed
- [ ] All tests pass

#### Testing Requirements

**Unit Tests:**
- Test immediate rejection path (backpressure high)
- Test immediate rejection path (queue full)
- Test acceptance path (item queued)
- Test eventual batch failure path (callback)
- Test eventual batch success path (callback)

**Integration Tests:**
- Test full flow: submitSync() → submitWithCallback()
- Test VajraPulse sees immediate rejections
- Test metrics track eventual batch results
- Test latency tracking in callback

### Task 2: Remove MinimumTpsLoadPattern

**Priority:** High  
**Estimated Effort:** 0.5 day

#### Subtasks

2.1. **Delete MinimumTpsLoadPattern.java**
- Delete the file entirely
- No longer needed (VajraPulse 0.9.7 handles recovery)

**Files to Delete:**
- `src/main/java/com/crdb/microbatch/service/MinimumTpsLoadPattern.java`

2.2. **Update LoadTestService**
- Remove `MinimumTpsLoadPattern` wrapper
- Use `AdaptiveLoadPattern` directly (wrapped only by `PhaseLoggingLoadPattern`)

**Files to Modify:**
- `src/main/java/com/crdb/microbatch/service/LoadTestService.java`

**Code Changes:**
```java
// Before
LoadPattern minTpsPattern = new MinimumTpsLoadPattern(adaptivePattern, MINIMUM_TPS);
LoadPattern loadPattern = new PhaseLoggingLoadPattern(minTpsPattern);

// After
LoadPattern loadPattern = new PhaseLoggingLoadPattern(adaptivePattern);
```

2.3. **Remove MINIMUM_TPS Constant**
- Remove `MINIMUM_TPS` constant (no longer needed)
- Update logging to remove minimum TPS references

**Code Changes:**
```java
// REMOVE:
// private static final double MINIMUM_TPS = 100.0;

// UPDATE logging:
// Remove: log.info("Minimum TPS: {} (floor to prevent test from stopping)", MINIMUM_TPS);
```

2.4. **Update PhaseLoggingLoadPattern**
- Remove `MinimumTpsLoadPattern` unwrapping logic
- Simplify `getAdaptivePattern()` method

**Files to Modify:**
- `src/main/java/com/crdb/microbatch/service/PhaseLoggingLoadPattern.java`

**Code Changes:**
```java
// Before
public AdaptiveLoadPattern getAdaptivePattern() {
    if (delegate instanceof AdaptiveLoadPattern adaptive) {
        return adaptive;
    }
    
    if (delegate instanceof MinimumTpsLoadPattern minTps) {
        LoadPattern innerDelegate = minTps.getDelegate();
        if (innerDelegate instanceof AdaptiveLoadPattern adaptive) {
            return adaptive;
        }
    }
    
    return null;
}

// After (simplified)
public AdaptiveLoadPattern getAdaptivePattern() {
    if (delegate instanceof AdaptiveLoadPattern adaptive) {
        return adaptive;
    }
    return null;
}
```

#### Acceptance Criteria

- [ ] `MinimumTpsLoadPattern.java` deleted
- [ ] `LoadTestService` no longer uses `MinimumTpsLoadPattern`
- [ ] `MINIMUM_TPS` constant removed
- [ ] `PhaseLoggingLoadPattern` simplified
- [ ] All references to `MinimumTpsLoadPattern` removed
- [ ] All tests pass

#### Testing Requirements

**Unit Tests:**
- Test `LoadTestService` creates pattern without `MinimumTpsLoadPattern`
- Test `PhaseLoggingLoadPattern` works without `MinimumTpsLoadPattern`

**Integration Tests:**
- Test pattern recovers from RECOVERY phase (VajraPulse 0.9.7 feature)
- Test pattern doesn't stop at 0 TPS

### Task 3: Update Dependencies

**Priority:** High  
**Estimated Effort:** 0.5 day

#### Subtasks

3.1. **Update build.gradle.kts**
- Update VajraPulse to 0.9.7
- Update Vortex to 0.0.5

**Files to Modify:**
- `build.gradle.kts`

**Code Changes:**
```kotlin
// VajraPulse
implementation("com.vajrapulse:vajrapulse-core:0.9.7")
implementation("com.vajrapulse:vajrapulse-api:0.9.7")
// ... other VajraPulse modules ...

// Vortex
implementation("com.vajrapulse:vortex:0.0.5")
```

3.2. **Verify Compatibility**
- Ensure all APIs are compatible
- Check for any breaking changes
- Update imports if needed

#### Acceptance Criteria

- [ ] VajraPulse updated to 0.9.7
- [ ] Vortex updated to 0.0.5
- [ ] All imports resolve correctly
- [ ] Code compiles successfully
- [ ] All tests pass

#### Testing Requirements

**Build Tests:**
- Test project compiles
- Test all dependencies resolve
- Test no breaking changes

### Task 4: Update Documentation

**Priority:** Medium  
**Estimated Effort:** 0.5 day

#### Subtasks

4.1. **Update README.md**
- Document new simplified approach
- Remove references to `MinimumTpsLoadPattern`
- Document hybrid approach (`submitSync()` + `submitWithCallback()`)

4.2. **Update Analysis Documents**
- Update `VORTEX_0.0.4_UPGRADE.md` to reflect 0.0.5 changes
- Create `VORTEX_0.0.5_UPGRADE.md` if needed
- Update `VAJRAPULSE_0.9.7_UPGRADE.md` if needed

4.3. **Update Architecture Documents**
- Update design documents to reflect simplified approach
- Document latency skew considerations
- Document hybrid failure tracking approach

#### Acceptance Criteria

- [ ] README.md updated
- [ ] Analysis documents updated
- [ ] Architecture documents updated
- [ ] All references to old approach removed

## Implementation Details

### Code Structure

**Files to Modify:**
1. `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`
   - Replace manual checks with `submitSync()`
   - Update callback for eventual results
   - Remove unused metrics

2. `src/main/java/com/crdb/microbatch/service/LoadTestService.java`
   - Remove `MinimumTpsLoadPattern` wrapper
   - Remove `MINIMUM_TPS` constant

3. `src/main/java/com/crdb/microbatch/service/PhaseLoggingLoadPattern.java`
   - Simplify `getAdaptivePattern()` method

4. `build.gradle.kts`
   - Update VajraPulse to 0.9.7
   - Update Vortex to 0.0.5

**Files to Delete:**
1. `src/main/java/com/crdb/microbatch/service/MinimumTpsLoadPattern.java`

### Dependencies

**Required:**
- VajraPulse 0.9.7 (with RECOVERY phase)
- Vortex 0.0.5 (with `submitSync()` method)

**Blockers:**
- Cannot proceed until VajraPulse 0.9.7 and Vortex 0.0.5 are released

## Testing Strategy

### Unit Tests

**Test Coverage Requirements:**
- ≥90% code coverage (project requirement)
- All modified methods must have tests
- All new code paths must be tested

**Key Test Cases:**
1. `submitSync()` immediate rejection
2. `submitSync()` immediate acceptance
3. `submitWithCallback()` eventual batch result
4. Latency tracking in callback
5. Metrics tracking (success/failure)

### Integration Tests

**Test Scenarios:**
1. Full flow: immediate rejection → `TaskResult.failure()`
2. Full flow: acceptance → `TaskResult.success()` → eventual batch result
3. VajraPulse sees immediate rejections
4. Metrics track eventual batch failures
5. Pattern recovers from RECOVERY phase

### End-to-End Tests

**Test Scenarios:**
1. Run load test with new implementation
2. Verify pattern recovers from low TPS
3. Verify pattern sustains at intermediate levels
4. Verify immediate rejections visible to VajraPulse
5. Verify eventual failures tracked via metrics

## Documentation Requirements

### Code Documentation

**Required JavaDoc:**
- Update `CrdbInsertTask.execute()` JavaDoc
- Document hybrid approach
- Document latency tracking

**Example:**
```java
/**
 * Executes the task for a single iteration.
 * 
 * <p>This method uses a hybrid approach:
 * <ul>
 *   <li>{@link MicroBatcher#submitSync(Object)} for immediate rejection visibility</li>
 *   <li>{@link MicroBatcher#submitWithCallback(Object, ItemCallback)} for eventual batch results</li>
 * </ul>
 * 
 * <p>Immediate rejections (backpressure/queue full) are returned as
 * {@link TaskResult#failure(Exception)} so VajraPulse sees them immediately.
 * Eventual batch processing failures are tracked via metrics, affecting
 * AdaptiveLoadPattern's error rate calculation.
 * 
 * <p>Latency is tracked in the callback when the batch actually processes,
 * providing accurate latency metrics. VajraPulse's internal latency will
 * be low (queue time), but this is acceptable as AdaptiveLoadPattern
 * doesn't use latency for decisions.
 * 
 * @param iteration the iteration number (0-based)
 * @return TaskResult indicating success or failure
 * @throws Exception if task execution fails
 */
@Override
public TaskResult execute(long iteration) throws Exception {
    // ...
}
```

### User Documentation

**Update:**
- `README.md` - Document simplified approach
- Remove references to workarounds
- Document new dependencies

## Release Checklist

### Pre-Release

- [ ] VajraPulse 0.9.7 available
- [ ] Vortex 0.0.5 available
- [ ] Dependencies updated
- [ ] Code changes complete
- [ ] All tests pass
- [ ] Code coverage ≥90%
- [ ] Static analysis passes
- [ ] JavaDoc complete
- [ ] Documentation updated

### Release

- [ ] Test with VajraPulse 0.9.7
- [ ] Test with Vortex 0.0.5
- [ ] Verify all functionality works
- [ ] Performance acceptable
- [ ] No regressions

### Post-Release

- [ ] Monitor for issues
- [ ] Collect feedback
- [ ] Document any issues

## Success Criteria

### Functional

- [ ] Immediate rejections visible to VajraPulse
- [ ] Eventual batch failures tracked via metrics
- [ ] Pattern recovers from RECOVERY phase automatically
- [ ] Pattern sustains at intermediate TPS levels
- [ ] No workaround wrappers needed
- [ ] Code is simpler and cleaner

### Non-Functional

- [ ] Code coverage ≥90%
- [ ] All tests pass
- [ ] No performance regression
- [ ] Documentation complete
- [ ] Code is maintainable

## Risk Assessment

### Risk 1: Dependency Availability

**Risk:** VajraPulse 0.9.7 or Vortex 0.0.5 not available

**Mitigation:**
- Wait for dependencies before starting
- Test with snapshot versions if needed
- Have fallback plan if dependencies delayed

### Risk 2: Breaking Changes

**Risk:** New library versions might have breaking changes

**Mitigation:**
- Review release notes carefully
- Test thoroughly before integration
- Have rollback plan

### Risk 3: Performance Regression

**Risk:** New implementation might be slower

**Mitigation:**
- Benchmark before/after
- Monitor performance metrics
- Optimize if needed

## Timeline

**Week 1:**
- Day 1: Wait for dependencies (VajraPulse 0.9.7, Vortex 0.0.5)
- Day 2: Task 1 (Update CrdbInsertTask)
- Day 3: Task 2 (Remove MinimumTpsLoadPattern)
- Day 4: Task 3 (Update dependencies) + Task 4 (Documentation)
- Day 5: Testing and validation

**Total:** 1 week (after dependencies available)

## Dependencies

**Blockers:**
- VajraPulse 0.9.7 must be released
- Vortex 0.0.5 must be released

**Dependencies:**
- VajraPulse 0.9.7 (RECOVERY phase, stability detection)
- Vortex 0.0.5 (`submitSync()` method)

**Blocks:**
- None (end of chain)

## Migration Path

### Step 1: Wait for Dependencies

**Action:**
- Wait for VajraPulse 0.9.7 release
- Wait for Vortex 0.0.5 release
- Verify both are available in Maven Central

### Step 2: Update Dependencies

**Action:**
- Update `build.gradle.kts`
- Sync dependencies
- Verify compilation

### Step 3: Update Code

**Action:**
- Update `CrdbInsertTask.execute()`
- Remove `MinimumTpsLoadPattern`
- Update `LoadTestService`
- Update `PhaseLoggingLoadPattern`

### Step 4: Test

**Action:**
- Run unit tests
- Run integration tests
- Run end-to-end tests
- Verify all functionality

### Step 5: Deploy

**Action:**
- Deploy to test environment
- Run load test
- Monitor metrics
- Verify behavior

## Rollback Plan

**If Issues Arise:**
1. Revert to previous VajraPulse/Vortex versions
2. Restore `MinimumTpsLoadPattern` if needed
3. Restore manual backpressure checks if needed
4. Document issues for future fixes

## Success Metrics

### Code Quality

- [ ] Code coverage ≥90%
- [ ] No linter errors
- [ ] No SpotBugs issues
- [ ] JavaDoc complete

### Functionality

- [ ] Pattern recovers from RECOVERY phase
- [ ] Pattern sustains at intermediate levels
- [ ] Immediate rejections visible
- [ ] Eventual failures tracked
- [ ] No workarounds needed

### Performance

- [ ] No performance regression
- [ ] Latency tracking accurate
- [ ] Throughput maintained
- [ ] Resource usage acceptable

