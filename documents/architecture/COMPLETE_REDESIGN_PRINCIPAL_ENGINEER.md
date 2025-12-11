# Complete Redesign: Adaptive Load Testing with Microbatching
## Principal Engineer Perspective

**Date:** 2025-12-05  
**Status:** Design Document  
**Author:** Principal Engineer Review

---

## Executive Summary

The current implementation has become overly complex with multiple wrapper layers, conflicting recovery strategies, and fundamental architectural issues. This document proposes a complete redesign from first principles, focusing on simplicity, testability, and incremental wins.

**Key Insight:** The problem isn't the recovery logic—it's that we're fighting against the framework's design rather than working with it.

---

## Root Cause Analysis

### Current Problems

1. **Error Rate Persistence**
   - Error rate stays at 4.15% → 10% even when system has capacity
   - Likely a rolling average that includes historical failures
   - Recovery logic waits for error rate to drop, but it never does

2. **Wrapper Complexity**
   - `MinimumTpsLoadPattern` → `PhaseLoggingLoadPattern` → `AdaptiveLoadPattern`
   - Each wrapper adds complexity and potential failure points
   - Recovery logic in wrapper fights against AdaptiveLoadPattern's internal state

3. **Backpressure Signal Confusion**
   - Multiple backpressure sources (HikariCP, Vortex queue)
   - Different thresholds and interpretations
   - No clear single source of truth

4. **Phase Transition Issues**
   - AdaptiveLoadPattern's RECOVERY phase may not be fully implemented in VajraPulse 0.9.7
   - Wrapper tries to force transitions, but internal state doesn't change
   - No way to reset or restart the pattern

5. **Metrics Timing Mismatch**
   - Task returns `TaskResult.success()` immediately (item queued)
   - Actual batch processing happens later (callback)
   - Error rate includes queued items that haven't been processed yet

---

## Design Principles

### 1. Simplicity First
- **One wrapper maximum** - Prefer framework changes over wrappers
- **Single source of truth** - One backpressure signal, one error rate calculation
- **Clear state machine** - Explicit phase transitions, no hidden state

### 2. Incremental Wins
- **Step 1:** Get basic adaptive load test working (no recovery needed)
- **Step 2:** Add recovery mechanism (simple, testable)
- **Step 3:** Optimize thresholds and tuning
- **Step 4:** Add advanced features (stability detection, etc.)

### 3. Framework Integration
- **Work with the framework, not against it**
- If framework doesn't support feature, enhance framework (VajraPulse/Vortex)
- Avoid workarounds and hacks

### 4. Testability
- Each step should be independently testable
- Clear success criteria
- Can roll back to previous step if needed

---

## Proposed Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                    VajraPulse Execution Engine                │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │         ContinuousAdaptiveLoadPattern                    │ │
│  │  (Enhanced AdaptiveLoadPattern with recovery)            │ │
│  │                                                           │ │
│  │  - RAMP_UP: Increase TPS every interval                  │ │
│  │  - RAMP_DOWN: Decrease TPS on backpressure/errors        │ │
│  │  - SUSTAIN: Hold TPS when stable                         │ │
│  │  - RECOVERY: Automatic recovery from low TPS             │ │
│  └─────────────────────────────────────────────────────────┘ │
│                          │                                     │
│                          ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │              MetricsProvider (Unified)                     │ │
│  │  - Failure rate: Recent window (last 10s, not all-time)    │ │
│  │  - Latency: P50, P95, P99                                │ │
│  └─────────────────────────────────────────────────────────┘ │
│                          │                                     │
│                          ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │         BackpressureProvider (Queue-Only)                  │ │
│  │  - Single source: Vortex queue depth                     │ │
│  │  - Normalized: 0.0 (empty) to 1.0 (full)                 │ │
│  │  - Threshold: 0.7 (70% capacity)                         │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    CrdbInsertTask                            │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  execute(iteration)                                      │ │
│  │    1. Generate test data                                 │ │
│  │    2. Check backpressure (HikariCP)                      │ │
│  │       - Severe: Reject immediately                        │ │
│  │       - Moderate: Block briefly (50ms)                   │ │
│  │       - Low: Submit                                      │ │
│  │    3. Submit to Vortex MicroBatcher                      │ │
│  │    4. Return TaskResult.success() (item queued)          │ │
│  └─────────────────────────────────────────────────────────┘ │
│                          │                                     │
│                          ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │         Vortex MicroBatcher                               │ │
│  │  - Batch size: 50                                         │ │
│  │  - Linger time: 50ms                                       │ │
│  │  - Queue size: 1000                                       │ │
│  │  - Backpressure: Reject when queue > 70% full            │ │
│  └─────────────────────────────────────────────────────────┘ │
│                          │                                     │
│                          ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │         CrdbBatchBackend                                  │ │
│  │  - Batch insert to CRDB                                    │ │
│  │  - Tracks batch-level success/failure                     │ │
│  │  - Updates metrics (batchSuccessCounter, etc.)           │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## Required Framework Changes

### VajraPulse Changes

#### 1. Enhanced AdaptiveLoadPattern (Priority: High)

**Current State:** RECOVERY phase exists but may not transition properly

**Required Changes:**

1. **Fix RECOVERY → RAMP_UP Transition**
   ```java
   // In AdaptiveLoadPattern.handleRecovery()
   private double handleRecovery(long elapsedMillis, AdaptiveState current) {
       double errorRate = metricsProvider.getFailureRate() / 100.0;
       double backpressure = backpressureProvider.getBackpressureLevel();
       
       // Recovery conditions: backpressure low OR error rate improving
       if (backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5)) {
           // Start recovery at 50% of last known good TPS
           double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
           transitionPhase(Phase.RAMP_UP, elapsedMillis, recoveryTps);
           return recoveryTps;
       }
       
       // Stay in recovery, maintain minimum TPS
       return minimumTps;
   }
   ```

2. **Add Recent Window Failure Rate**
   ```java
   // New method in MetricsProvider interface
   /**
    * Gets the failure rate over a recent time window.
    * 
    * @param windowSeconds the time window in seconds (e.g., 10)
    * @return failure rate as percentage (0.0-100.0)
    */
   double getRecentFailureRate(int windowSeconds);
   ```

3. **Add Stability Detection**
   ```java
   // New method in AdaptiveLoadPattern
   /**
    * Detects if current TPS is stable (can sustain at intermediate levels).
    * 
    * @param currentTps the current TPS
    * @param elapsedMillis elapsed time
    * @return true if stable, false otherwise
    */
   private boolean isStable(double currentTps, long elapsedMillis);
   ```

**Acceptance Criteria:**
- [ ] RECOVERY phase transitions to RAMP_UP when backpressure < 0.3
- [ ] Pattern can sustain at intermediate TPS levels (not just MAX_TPS)
- [ ] Recent failure rate (10s window) used for recovery decisions
- [ ] All existing tests pass
- [ ] New tests for RECOVERY transitions

#### 2. MetricsProvider Enhancement (Priority: Medium)

**Current State:** `getFailureRate()` likely returns all-time average

**Required Changes:**

1. **Add Recent Window Support**
   ```java
   public interface MetricsProvider {
       // Existing
       double getFailureRate();
       
       // New: Recent window failure rate
       double getRecentFailureRate(int windowSeconds);
       
       // New: Get failure rate over specific time range
       double getFailureRate(long startTimeMillis, long endTimeMillis);
   }
   ```

2. **Default Implementation**
   ```java
   // In DefaultMetricsProvider
   @Override
   public double getRecentFailureRate(int windowSeconds) {
       long windowStart = System.currentTimeMillis() - (windowSeconds * 1000);
       return getFailureRate(windowStart, System.currentTimeMillis());
   }
   ```

**Acceptance Criteria:**
- [ ] `getRecentFailureRate(10)` returns failure rate for last 10 seconds
- [ ] Old failures don't affect recent rate
- [ ] Backward compatible (existing `getFailureRate()` still works)

### Vortex Changes

#### 1. Submit Result Clarity (Priority: Low)

**Current State:** `submitSync()` and `submitWithCallback()` have overlapping behavior

**Required Changes:**

1. **Clarify submitSync() Behavior**
   ```java
   /**
    * Submits an item synchronously.
    * 
    * <p>Returns immediately with result:
    * <ul>
    *   <li>SUCCESS: Item queued successfully</li>
    *   <li>REJECTED: Item rejected due to backpressure</li>
    * </ul>
    * 
    * <p>Note: This does NOT wait for batch processing.
    * Use submitWithCallback() to get batch processing results.
    */
   ItemResult<T> submitSync(T item);
   ```

2. **Document Callback Timing**
   ```java
   /**
    * Submits an item with callback for batch processing result.
    * 
    * <p>Callback fires when:
    * <ul>
    *   <li>Batch is processed (success or failure)</li>
    *   <li>Item is rejected immediately (if queue full)</li>
    * </ul>
    * 
    * <p>Callback may fire:
    * <ul>
    *   <li>Immediately: If item rejected</li>
    *   <li>After batch processing: When batch completes (typically 10-50ms)</li>
    * </ul>
    */
   void submitWithCallback(T item, BiConsumer<T, ItemResult<T>> callback);
   ```

**Acceptance Criteria:**
- [ ] Documentation clarifies behavior
- [ ] No code changes needed (behavior is correct)
- [ ] Examples show proper usage

---

## Implementation Plan: Step-by-Step

### Phase 0: Preparation (Day 1)

**Goal:** Clean slate, understand current state

#### Task 0.1: Create New Branch
- [ ] Create branch: `feature/adaptive-load-redesign`
- [ ] Document current state (what works, what doesn't)
- [ ] List all wrappers and their purposes

#### Task 0.2: Remove All Wrappers
- [ ] Remove `MinimumTpsLoadPattern`
- [ ] Remove `PhaseLoggingLoadPattern` (or keep for debugging only)
- [ ] Remove `RecoveryLoadPattern` (if exists)
- [ ] Use `AdaptiveLoadPattern` directly

#### Task 0.3: Simplify CrdbInsertTask
- [ ] Remove complex backpressure logic
- [ ] Use simple `submitWithCallback()` only
- [ ] Return `TaskResult.success()` immediately (item queued)
- [ ] Track metrics in callback

**Success Criteria:**
- [ ] Code compiles
- [ ] Test runs (may not recover, but should ramp up/down)
- [ ] No wrappers in codebase

---

### Phase 1: Basic Adaptive Load Test (Days 2-3)

**Goal:** Get adaptive load test working without recovery

#### Task 1.1: Implement Unified MetricsProvider
- [ ] Create `RecentWindowMetricsProvider` that wraps existing MetricsProvider
- [ ] Implement `getRecentFailureRate(int windowSeconds)`
- [ ] Use 10-second window for recent failure rate
- [ ] Keep existing `getFailureRate()` for backward compatibility

**Code:**
```java
public class RecentWindowMetricsProvider implements MetricsProvider {
    private final MetricsProvider delegate;
    private final int windowSeconds;
    
    public RecentWindowMetricsProvider(MetricsProvider delegate, int windowSeconds) {
        this.delegate = delegate;
        this.windowSeconds = windowSeconds;
    }
    
    @Override
    public double getFailureRate() {
        return delegate.getFailureRate();
    }
    
    @Override
    public double getRecentFailureRate(int windowSeconds) {
        // Calculate failure rate for last N seconds
        // Implementation depends on MetricsProvider internals
        // May need to access internal metrics registry
    }
}
```

**Success Criteria:**
- [ ] `getRecentFailureRate(10)` returns failure rate for last 10 seconds
- [ ] Old failures don't affect recent rate
- [ ] Unit tests pass

#### Task 1.2: Use Queue-Only Backpressure (SIMPLIFIED)
- [ ] Use `QueueDepthBackpressureProvider` from Vortex directly
- [ ] No custom backpressure provider needed
- [ ] Remove HikariCP backpressure provider from AdaptiveLoadPattern
- [ ] Configure queue size and threshold appropriately

**Code:**

**No custom provider needed!** Use Vortex's `QueueDepthBackpressureProvider` directly.

**Modify: `LoadTestService.java`**

**Use queue-only backpressure:**
```java
// Get queue depth supplier from CrdbInsertTask (or create shared instance)
// Create queue depth supplier
var queueDepthSupplier = new MutableQueueDepthSupplier<TestInsert>();

// Create queue backpressure provider (Vortex provides this)
int maxQueueSize = 1000;  // Tune based on batch size (e.g., 20 batches * 50 items)
BackpressureProvider backpressureProvider = new QueueDepthBackpressureProvider(
    queueDepthSupplier,
    maxQueueSize
);

// Use in AdaptiveLoadPattern
AdaptiveLoadPattern pattern = new AdaptiveLoadPattern(
    ...,
    backpressureProvider  // Queue-only backpressure
);
```

**Success Criteria:**
- [ ] `QueueDepthBackpressureProvider` used directly (no custom provider)
- [ ] HikariCP backpressure provider removed from AdaptiveLoadPattern
- [ ] Single backpressure signal (queue depth only)
- [ ] Queue size configured appropriately (1000 items = 20 batches)
- [ ] Integrated into `LoadTestService`

#### Task 1.3: Configure AdaptiveLoadPattern
- [ ] Use `AdaptiveLoadPattern` directly (no wrappers)
- [ ] Configure with:
  - Initial TPS: 1000
  - Ramp increment: 500 TPS
  - Ramp decrement: 1000 TPS
  - Ramp interval: 5 seconds
  - Max TPS: 20000
  - Error threshold: 1%
  - Sustain duration: 30 seconds
- [ ] Use `RecentWindowMetricsProvider` and `QueueDepthBackpressureProvider` (queue-only)

**Success Criteria:**
- [ ] Test ramps up from 1000 TPS
- [ ] Test ramps down on backpressure
- [ ] Test may get stuck at low TPS (recovery not implemented yet)
- [ ] No crashes or errors

---

### Phase 2: Simple Recovery Mechanism (Days 4-5)

**Goal:** Add recovery from low TPS without complex wrappers

#### Task 2.1: Enhance AdaptiveLoadPattern (VajraPulse Change)

**If VajraPulse is our codebase:**
- [ ] Fix `handleRecovery()` method in `AdaptiveLoadPattern`
- [ ] Add transition logic: RECOVERY → RAMP_UP when conditions improve
- [ ] Use recent failure rate (10s window) for recovery decisions
- [ ] Recovery starts at 50% of last known good TPS

**If VajraPulse is external library:**
- [ ] Create minimal wrapper `RecoveryAdaptiveLoadPattern` that extends/wraps `AdaptiveLoadPattern`
- [ ] Override `calculateTps()` to handle RECOVERY phase
- [ ] Check conditions and force RAMP_UP transition
- [ ] Keep wrapper minimal (one class, < 100 lines)

**Code (if wrapper needed):**
```java
public class RecoveryAdaptiveLoadPattern implements LoadPattern {
    private final AdaptiveLoadPattern delegate;
    private final MetricsProvider metricsProvider;
    private final BackpressureProvider backpressureProvider;
    private final double errorThreshold;
    private double lastKnownGoodTps = 1000.0;
    
    @Override
    public double calculateTps(long elapsedMillis) {
        double tps = delegate.calculateTps(elapsedMillis);
        AdaptiveLoadPattern.Phase phase = delegate.getCurrentPhase();
        
        // Track last known good TPS
        if (phase != AdaptiveLoadPattern.Phase.RECOVERY && tps > 0) {
            lastKnownGoodTps = Math.max(lastKnownGoodTps, tps);
        }
        
        // Handle RECOVERY phase
        if (phase == AdaptiveLoadPattern.Phase.RECOVERY) {
            double errorRate = metricsProvider.getFailureRate() / 100.0;
            double backpressure = backpressureProvider.getBackpressureLevel();
            
            // Recovery condition: backpressure low OR (error rate low AND backpressure moderate)
            if (backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5)) {
                // Return recovery TPS to trigger RAMP_UP
                double recoveryTps = Math.max(100.0, lastKnownGoodTps * 0.5);
                return recoveryTps;
            }
            
            // Stay in recovery
            return Math.max(100.0, tps);
        }
        
        return tps;
    }
}
```

**Success Criteria:**
- [ ] Pattern recovers from RECOVERY phase
- [ ] Recovery triggers when backpressure < 0.3
- [ ] Recovery starts at 50% of last known good TPS
- [ ] Test runs continuously (no getting stuck)

#### Task 2.2: Test Recovery Mechanism
- [ ] Run test and verify recovery works
- [ ] Monitor logs for recovery transitions
- [ ] Verify TPS increases after recovery
- [ ] Test multiple recovery cycles

**Success Criteria:**
- [ ] Test recovers from low TPS
- [ ] Test ramps up after recovery
- [ ] Test can cycle: RAMP_UP → RAMP_DOWN → RECOVERY → RAMP_UP
- [ ] No getting stuck at 100 TPS

---

### Phase 3: Optimize Thresholds and Tuning (Days 6-7)

**Goal:** Fine-tune thresholds for optimal performance

#### Task 3.1: Tune Recovery Conditions
- [ ] Test different backpressure thresholds (0.2, 0.3, 0.4)
- [ ] Test different error rate thresholds (0.5%, 1%, 2%)
- [ ] Find optimal combination

**Success Criteria:**
- [ ] Recovery triggers at appropriate times
- [ ] Not too aggressive (doesn't recover too early)
- [ ] Not too conservative (doesn't get stuck)

#### Task 3.2: Tune Ramp Increments
- [ ] Test different ramp increments (250, 500, 1000 TPS)
- [ ] Test different ramp decrements (500, 1000, 2000 TPS)
- [ ] Find optimal ramp rates

**Success Criteria:**
- [ ] Ramp up is smooth (not too aggressive)
- [ ] Ramp down is responsive (reacts quickly to backpressure)
- [ ] Test finds optimal TPS efficiently

#### Task 3.3: Add Stability Detection (Optional)
- [ ] Detect when TPS is stable at intermediate levels
- [ ] Sustain at stable TPS (not just MAX_TPS)
- [ ] Transition to RAMP_UP when conditions improve

**Success Criteria:**
- [ ] Pattern sustains at intermediate TPS levels
- [ ] Pattern doesn't continuously ramp up/down
- [ ] Test finds and maintains optimal TPS

---

### Phase 4: Advanced Features (Days 8-10, Optional)

**Goal:** Add advanced features for production readiness

#### Task 4.1: Add Metrics Dashboard
- [ ] Create Grafana dashboard for adaptive load test
- [ ] Show TPS over time
- [ ] Show backpressure over time
- [ ] Show error rate over time
- [ ] Show phase transitions

#### Task 4.2: Add Alerting
- [ ] Alert when test gets stuck
- [ ] Alert when error rate is high
- [ ] Alert when backpressure is severe

#### Task 4.3: Add Test Reports
- [ ] Generate HTML report with test results
- [ ] Include optimal TPS found
- [ ] Include recovery cycles
- [ ] Include performance metrics

---

## Success Criteria (Overall)

### Must Have
- [ ] Test runs continuously (no getting stuck)
- [ ] Test recovers from low TPS automatically
- [ ] Test finds optimal TPS (ramps up to capacity)
- [ ] Test reacts to backpressure (ramps down when needed)
- [ ] No complex wrappers (max 1 simple wrapper)
- [ ] Code is maintainable and testable

### Nice to Have
- [ ] Test sustains at intermediate TPS levels
- [ ] Test finds optimal TPS efficiently
- [ ] Metrics dashboard and reporting
- [ ] Alerting for test issues

---

## Risk Mitigation

### Risk 1: VajraPulse RECOVERY Phase Not Working
**Mitigation:** Create minimal wrapper if needed (Task 2.1)

### Risk 2: Error Rate Persists
**Mitigation:** Use recent window failure rate (Task 1.1)

### Risk 3: Backpressure Signal Unclear (RESOLVED)
**Mitigation:** Use queue-only backpressure (Vortex provides this, Task 1.2)

### Risk 4: Recovery Too Aggressive/Conservative
**Mitigation:** Tune thresholds incrementally (Task 3.1)

---

## Timeline

- **Week 1:** Phase 0 (Preparation) + Phase 1 (Basic Adaptive Load Test)
- **Week 2:** Phase 2 (Recovery Mechanism) + Phase 3 (Optimization)
- **Week 3:** Phase 4 (Advanced Features, if needed) + Testing

**Total:** 2-3 weeks for complete implementation

---

## Next Steps

1. **Review this document** with team
2. **Decide on VajraPulse changes** (can we modify it, or need wrapper?)
3. **Start Phase 0** (clean slate, remove wrappers)
4. **Incremental implementation** (one phase at a time)
5. **Test after each phase** (verify success criteria)

---

## Appendix: Key Design Decisions

### Why Remove All Wrappers?
- **Simplicity:** Each wrapper adds complexity and potential failure points
- **Maintainability:** Harder to debug with multiple layers
- **Framework Integration:** Better to enhance framework than work around it

### Why Queue-Only Backpressure?
- **Simplicity:** One signal is easier to understand and tune
- **Direct Measurement:** Queue depth directly measures "can we keep up?"
- **Framework Integration:** Vortex already provides this
- **Less Coupling:** Doesn't depend on HikariCP internals
- **Bottleneck Indicator:** If queue is full, system can't keep up (regardless of root cause)

### Why Recent Window Failure Rate?
- **Responsiveness:** Recent failures matter more than old failures
- **Recovery:** System can recover even if historical error rate is high
- **Accuracy:** Better reflects current system state

### Why Queue-Only Backpressure?
- **Simplicity:** One signal instead of multiple sources
- **Direct Measurement:** Queue depth directly measures system capacity
- **Framework Integration:** Vortex already provides this, no custom code needed
- **Bottleneck Indicator:** Queue full = system can't keep up (regardless of root cause)
- **Less Coupling:** Doesn't depend on HikariCP internals or database-specific metrics

### Why Minimal Wrapper (if needed)?
- **Last Resort:** Only if framework can't be modified
- **Single Responsibility:** One class, one purpose
- **Testable:** Easy to test in isolation

---

**Document Status:** Ready for Review  
**Next Review:** After Phase 0 completion

