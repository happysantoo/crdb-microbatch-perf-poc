# Detailed Task Breakdown: Adaptive Load Test Redesign
## Step-by-Step Implementation Guide

**Date:** 2025-12-05  
**Status:** Implementation Guide  
**Based on:** COMPLETE_REDESIGN_PRINCIPAL_ENGINEER.md

---

## Overview

This document provides detailed, actionable task items for implementing the adaptive load test redesign. Each task includes:
- **Objective:** What we're trying to achieve
- **Approach:** How to implement it
- **Code Changes:** Specific code to write/modify
- **Testing:** How to verify it works
- **Success Criteria:** When the task is complete

---

## Phase 0: Preparation (Day 1)

### Task 0.1: Create New Branch and Document Current State

**Objective:** Start with a clean slate and understand what we have

**Approach:**
1. Create new branch from main
2. Document current state
3. List all components

**Steps:**
```bash
# 1. Create branch
git checkout -b feature/adaptive-load-redesign

# 2. Document current state
# Create: documents/analysis/CURRENT_STATE_BASELINE.md
```

**Document to Create:**
```markdown
# Current State Baseline

## Components
- CrdbInsertTask: Task that submits items to Vortex MicroBatcher
- LoadTestService: Orchestrates load test
- AdaptiveLoadPattern: Load pattern from VajraPulse
- MinimumTpsLoadPattern: Wrapper to enforce minimum TPS
- PhaseLoggingLoadPattern: Wrapper to log phase transitions
- HikariCPBackpressureProvider: Backpressure from connection pool
- VortexHikariCPBackpressureProvider: Backpressure from Vortex queue

## Current Issues
1. Recovery phase gets stuck at 100 TPS
2. Error rate persists (4.15% → 10%)
3. Multiple wrappers add complexity
4. Backpressure signals conflict

## What Works
- Ramp up works (1000 → 5500 TPS)
- Ramp down works (5500 → 100 TPS)
- Backpressure detection works
- Microbatching works
```

**Success Criteria:**
- [ ] Branch created
- [ ] Current state documented
- [ ] All components listed

---

### Task 0.2: Remove All Wrappers

**Objective:** Simplify codebase by removing wrapper layers

**Approach:**
1. Remove `MinimumTpsLoadPattern.java`
2. Remove `PhaseLoggingLoadPattern.java` (or keep for debugging)
3. Update `LoadTestService` to use `AdaptiveLoadPattern` directly

**Code Changes:**

**Delete Files:**
- `src/main/java/com/crdb/microbatch/service/MinimumTpsLoadPattern.java`
- `src/main/java/com/crdb/microbatch/service/PhaseLoggingLoadPattern.java` (optional - keep for debugging)

**Modify: `LoadTestService.java`**
```java
// BEFORE:
AdaptiveLoadPattern adaptivePattern = new AdaptiveLoadPattern(...);
LoadPattern minTpsPattern = new MinimumTpsLoadPattern(adaptivePattern, MINIMUM_TPS);
LoadPattern loadPattern = new PhaseLoggingLoadPattern(minTpsPattern);

// AFTER:
AdaptiveLoadPattern adaptivePattern = new AdaptiveLoadPattern(...);
LoadPattern loadPattern = adaptivePattern;  // Use directly

// Optional: Keep PhaseLoggingLoadPattern for debugging only
// LoadPattern loadPattern = new PhaseLoggingLoadPattern(adaptivePattern);
```

**Testing:**
```bash
# Compile
./gradlew compileJava

# Run test (may not recover, but should ramp up/down)
./gradlew bootRun
```

**Success Criteria:**
- [ ] Wrappers removed
- [ ] Code compiles
- [ ] Test runs (may get stuck at low TPS, that's OK for now)
- [ ] No wrapper classes in codebase

---

### Task 0.3: Simplify CrdbInsertTask

**Objective:** Simplify task to basic functionality

**Approach:**
1. Remove complex backpressure logic
2. Use simple `submitWithCallback()` only
3. Return `TaskResult.success()` immediately
4. Track metrics in callback

**Code Changes: `CrdbInsertTask.java`**

**Remove:**
- Complex backpressure checking (severe/moderate thresholds)
- Blocking logic with timeouts
- `submitBlockedCounter`, `submitBlockedTimeoutCounter`, `submitBlockingTimer`

**Simplify `execute()` method:**
```java
@Override
public TaskResult execute(long iteration) throws Exception {
    submitCounter.increment();
    Timer.Sample sample = Timer.start(meterRegistry);
    
    try {
        TestInsert testInsert = generateTestData();
        
        // Simple: Submit to batcher, return success immediately
        // Backpressure is handled by Vortex MicroBatcher (rejects if queue full)
        batcher.submitWithCallback(testInsert, (item, itemResult) -> {
            sample.stop(submitLatencyTimer);
            
            if (itemResult instanceof ItemResult.Success<TestInsert>) {
                submitSuccessCounter.increment();
            } else {
                submitFailureCounter.increment();
                ItemResult.Failure<TestInsert> failure = (ItemResult.Failure<TestInsert>) itemResult;
                log.debug("Item rejected: {}", failure.error().getMessage());
            }
        });
        
        // Return success - item was accepted and queued
        return TaskResult.success();
        
    } catch (Exception e) {
        submitFailureCounter.increment();
        sample.stop(submitLatencyTimer);
        log.error("Task submission failed at iteration: {}", iteration, e);
        return TaskResult.failure(e);
    }
}
```

**Testing:**
```bash
# Compile
./gradlew compileJava

# Run test
./gradlew bootRun
```

**Success Criteria:**
- [ ] Complex backpressure logic removed
- [ ] Simple `submitWithCallback()` only
- [ ] Code compiles
- [ ] Test runs (may get stuck, that's OK)

---

## Phase 1: Basic Adaptive Load Test (Days 2-3)

### Task 1.1: Implement Recent Window MetricsProvider

**Objective:** Get failure rate for recent time window (not all-time)

**Approach:**
1. Create `RecentWindowMetricsProvider` wrapper
2. Track metrics with timestamps
3. Calculate failure rate for last N seconds

**Code: `RecentWindowMetricsProvider.java`**

**Create new file:**
```java
package com.crdb.microbatch.service;

import com.vajrapulse.api.MetricsProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricsProvider that calculates failure rate over a recent time window.
 * 
 * <p>This provider tracks executions and failures with timestamps,
 * allowing calculation of failure rate for the last N seconds rather
 * than all-time average.
 */
public class RecentWindowMetricsProvider implements MetricsProvider {
    
    private final MeterRegistry meterRegistry;
    private final int windowSeconds;
    
    // Track recent executions and failures
    private final ConcurrentLinkedQueue<ExecutionRecord> recentExecutions = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalExecutions = new AtomicLong(0);
    
    /**
     * Constructor for RecentWindowMetricsProvider.
     * 
     * @param meterRegistry the Micrometer registry
     * @param windowSeconds the time window in seconds (e.g., 10)
     */
    public RecentWindowMetricsProvider(MeterRegistry meterRegistry, int windowSeconds) {
        this.meterRegistry = meterRegistry;
        this.windowSeconds = windowSeconds;
    }
    
    @Override
    public double getFailureRate() {
        // Return all-time failure rate (for backward compatibility)
        Counter successCounter = meterRegistry.find("vajrapulse.execution.count")
            .tag("status", "success")
            .counter();
        Counter failureCounter = meterRegistry.find("vajrapulse.execution.count")
            .tag("status", "failure")
            .counter();
        
        long total = (long) (successCounter.count() + failureCounter.count());
        if (total == 0) return 0.0;
        
        return (failureCounter.count() / total) * 100.0;
    }
    
    /**
     * Gets the failure rate over a recent time window.
     * 
     * @param windowSeconds the time window in seconds
     * @return failure rate as percentage (0.0-100.0)
     */
    public double getRecentFailureRate(int windowSeconds) {
        long windowStart = System.currentTimeMillis() - (windowSeconds * 1000L);
        
        // Clean old records
        recentExecutions.removeIf(record -> record.timestamp < windowStart);
        
        // Count failures in window
        long failures = recentExecutions.stream()
            .filter(record -> record.timestamp >= windowStart && record.isFailure)
            .count();
        long total = recentExecutions.stream()
            .filter(record -> record.timestamp >= windowStart)
            .count();
        
        if (total == 0) return 0.0;
        return (failures / (double) total) * 100.0;
    }
    
    /**
     * Records an execution for recent window tracking.
     * 
     * @param isFailure true if execution failed, false otherwise
     */
    public void recordExecution(boolean isFailure) {
        long timestamp = System.currentTimeMillis();
        recentExecutions.offer(new ExecutionRecord(timestamp, isFailure));
        totalExecutions.incrementAndGet();
        
        // Clean old records periodically (every 1000 executions)
        if (totalExecutions.get() % 1000 == 0) {
            long cutoff = System.currentTimeMillis() - (windowSeconds * 2 * 1000L);  // Keep 2x window
            recentExecutions.removeIf(record -> record.timestamp < cutoff);
        }
    }
    
    @Override
    public long getTotalExecutions() {
        return totalExecutions.get();
    }
    
    private static class ExecutionRecord {
        final long timestamp;
        final boolean isFailure;
        
        ExecutionRecord(long timestamp, boolean isFailure) {
            this.timestamp = timestamp;
            this.isFailure = isFailure;
        }
    }
}
```

**Modify: `LoadTestService.java`**

**Add field:**
```java
private final RecentWindowMetricsProvider recentMetricsProvider;
```

**Initialize in constructor:**
```java
// Create recent window metrics provider (10 second window)
this.recentMetricsProvider = new RecentWindowMetricsProvider(meterRegistry, 10);
```

**Update MetricsProvider passed to AdaptiveLoadPattern:**
```java
// Create wrapper that uses recent window for recovery decisions
MetricsProvider adaptiveMetricsProvider = new MetricsProvider() {
    @Override
    public double getFailureRate() {
        // Use recent window for adaptive decisions
        return recentMetricsProvider.getRecentFailureRate(10);
    }
    
    @Override
    public long getTotalExecutions() {
        return recentMetricsProvider.getTotalExecutions();
    }
};
```

**Modify: `CrdbInsertTask.java`**

**Add field:**
```java
private final RecentWindowMetricsProvider recentMetricsProvider;
```

**Inject in constructor:**
```java
public CrdbInsertTask(
        CrdbBatchBackend backend,
        MeterRegistry meterRegistry,
        VortexHikariCPBackpressureProvider hikariBackpressureProvider,
        RecentWindowMetricsProvider recentMetricsProvider) {
    // ... existing code ...
    this.recentMetricsProvider = recentMetricsProvider;
}
```

**Record executions in callback:**
```java
batcher.submitWithCallback(testInsert, (item, itemResult) -> {
    sample.stop(submitLatencyTimer);
    
    boolean isFailure = itemResult instanceof ItemResult.Failure;
    recentMetricsProvider.recordExecution(isFailure);
    
    if (itemResult instanceof ItemResult.Success<TestInsert>) {
        submitSuccessCounter.increment();
    } else {
        submitFailureCounter.increment();
        // ... existing code ...
    }
});
```

**Testing:**
```java
// Unit test
@Test
void testRecentWindowFailureRate() {
    RecentWindowMetricsProvider provider = new RecentWindowMetricsProvider(meterRegistry, 10);
    
    // Record some failures
    provider.recordExecution(false);  // success
    provider.recordExecution(true);    // failure
    provider.recordExecution(true);    // failure
    
    double rate = provider.getRecentFailureRate(10);
    assertEquals(66.67, rate, 0.01);  // 2 failures / 3 total = 66.67%
}
```

**Success Criteria:**
- [ ] `RecentWindowMetricsProvider` created
- [ ] `getRecentFailureRate(10)` returns failure rate for last 10 seconds
- [ ] Old failures don't affect recent rate
- [ ] Unit tests pass
- [ ] Integrated into `LoadTestService` and `CrdbInsertTask`

---

### Task 1.2: Use Queue-Only Backpressure (SIMPLIFIED)

**Objective:** Use Vortex queue backpressure directly (no custom provider needed)

**Approach:**
1. Use `QueueDepthBackpressureProvider` from Vortex
2. Remove HikariCP backpressure provider from AdaptiveLoadPattern
3. Configure queue size and threshold

**Key Insight:**
- Queue depth is the bottleneck indicator
- If queue is full, system can't keep up (regardless of root cause)
- Simpler than monitoring connection pool

**Code Changes:**

**Modify: `CrdbInsertTask.java`**

**Already has queue depth supplier:**
```java
// This already exists in CrdbInsertTask
var queueDepthSupplier = new MutableQueueDepthSupplier<TestInsert>();
```

**Modify: `LoadTestService.java`**

**Use queue-only backpressure:**
```java
// Get queue depth supplier from CrdbInsertTask (or create shared instance)
// For now, we'll create it in LoadTestService and pass to both

// Create queue depth supplier
var queueDepthSupplier = new MutableQueueDepthSupplier<TestInsert>();

// Create queue backpressure provider (Vortex provides this)
int maxQueueSize = 1000;  // Tune based on batch size (e.g., 20 batches * 50 items)
BackpressureProvider backpressureProvider = new QueueDepthBackpressureProvider(
    queueDepthSupplier,
    maxQueueSize
);

// Pass supplier to CrdbInsertTask so it can set batcher reference
// (This requires refactoring CrdbInsertTask to accept supplier)

// Use in AdaptiveLoadPattern
AdaptiveLoadPattern pattern = new AdaptiveLoadPattern(
    ...,
    backpressureProvider  // Queue-only backpressure
);
```

**Alternative: Use Existing Composite Provider**

If `CrdbInsertTask` already creates a composite provider, we can extract just the queue provider:

```java
// In CrdbInsertTask, expose queue provider
public BackpressureProvider getQueueBackpressureProvider() {
    // Return just the queue provider (not composite)
    return queueProvider;  // QueueDepthBackpressureProvider
}

// In LoadTestService
BackpressureProvider backpressureProvider = crdbInsertTask.getQueueBackpressureProvider();
```

**Configuration:**
```java
// Queue size: Tune based on batch size
// Example: 20 batches * 50 items = 1000 items
int maxQueueSize = BATCH_SIZE * 20;

// Threshold: 70% (0.7) - triggers when queue > 70% full
// This is handled by Vortex's RejectStrategy, not AdaptiveLoadPattern
// AdaptiveLoadPattern uses backpressure level (0.0-1.0) directly
```

**Testing:**
```bash
# Run test and monitor queue depth
# Queue should fill up when system is overloaded
# Backpressure should trigger when queue > 70% full
```

**Success Criteria:**
- [ ] `QueueDepthBackpressureProvider` used directly (no custom provider)
- [ ] HikariCP backpressure provider removed from AdaptiveLoadPattern
- [ ] Single backpressure signal (queue depth only)
- [ ] Queue size configured appropriately (1000 items = 20 batches)
- [ ] Integrated into `LoadTestService`
- [ ] Test runs and backpressure triggers when queue fills

---

### Task 1.3: Configure AdaptiveLoadPattern

**Objective:** Use AdaptiveLoadPattern directly with unified providers

**Approach:**
1. Configure AdaptiveLoadPattern with optimal parameters
2. Use RecentWindowMetricsProvider and UnifiedBackpressureProvider
3. Test basic ramp up/down

**Code: `LoadTestService.java`**

**Configuration:**
```java
// Adaptive load pattern configuration
private static final double INITIAL_TPS = 1000.0;
private static final double RAMP_INCREMENT = 500.0;  // Increase by 500 TPS per interval
private static final double RAMP_DECREMENT = 1000.0;  // Decrease by 1000 TPS per interval
private static final Duration RAMP_INTERVAL = Duration.ofSeconds(5);
private static final double MAX_TPS = 20000.0;
private static final Duration SUSTAIN_DURATION = Duration.ofSeconds(30);
private static final double ERROR_THRESHOLD = 0.01;  // 1%

// Create adaptive load pattern
AdaptiveLoadPattern adaptivePattern = new AdaptiveLoadPattern(
    INITIAL_TPS,
    RAMP_INCREMENT,
    RAMP_DECREMENT,
    RAMP_INTERVAL,
    MAX_TPS,
    SUSTAIN_DURATION,
    ERROR_THRESHOLD,
    adaptiveMetricsProvider,  // Uses recent window
    backpressureProvider       // Queue-only backpressure
);

LoadPattern loadPattern = adaptivePattern;  // Use directly, no wrappers
```

**Testing:**
```bash
# Run test
./gradlew bootRun

# Monitor logs for:
# - RAMP_UP: TPS increases from 1000 → 1500 → 2000 → ...
# - RAMP_DOWN: TPS decreases when backpressure detected
# - May get stuck at low TPS (recovery not implemented yet)
```

**Success Criteria:**
- [ ] AdaptiveLoadPattern configured
- [ ] Uses RecentWindowMetricsProvider
- [ ] Uses QueueDepthBackpressureProvider (queue-only)
- [ ] Test ramps up from 1000 TPS
- [ ] Test ramps down when queue fills (backpressure triggers)
- [ ] No crashes or errors
- [ ] May get stuck at low TPS (that's OK, recovery comes next)

---

## Phase 2: Simple Recovery Mechanism (Days 4-5)

### Task 2.1: Enhance AdaptiveLoadPattern (VajraPulse Change)

**Objective:** Fix RECOVERY phase to transition to RAMP_UP

**Decision Point:** Can we modify VajraPulse, or do we need a wrapper?

**Option A: If VajraPulse is our codebase (can modify)**

**File: `AdaptiveLoadPattern.java` (in VajraPulse)**

**Modify `handleRecovery()` method:**
```java
private double handleRecovery(long elapsedMillis, AdaptiveState current) {
    double errorRate = metricsProvider.getFailureRate() / 100.0;  // Convert percentage to ratio
    double backpressure = backpressureProvider.getBackpressureLevel();
    
    // Recovery conditions: backpressure low OR (error rate low AND backpressure moderate)
    if (backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5)) {
        // Start recovery at 50% of last known good TPS
        double lastKnownGoodTps = current.stableTps() > 0 ? current.stableTps() : initialTps;
        double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
        
        // Transition to RAMP_UP
        transitionPhase(Phase.RAMP_UP, elapsedMillis, recoveryTps);
        return recoveryTps;
    }
    
    // Stay in recovery, maintain minimum TPS
    return minimumTps;
}
```

**Option B: If VajraPulse is external library (need wrapper)**

**Code: `RecoveryAdaptiveLoadPattern.java`**

**Create new file:**
```java
package com.crdb.microbatch.service;

import com.vajrapulse.api.AdaptiveLoadPattern;
import com.vajrapulse.api.BackpressureProvider;
import com.vajrapulse.api.LoadPattern;
import com.vajrapulse.api.MetricsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal wrapper around AdaptiveLoadPattern that handles RECOVERY phase transitions.
 * 
 * <p>This wrapper intercepts RECOVERY phase and forces transition to RAMP_UP
 * when conditions improve. Kept minimal to avoid complexity.
 */
public class RecoveryAdaptiveLoadPattern implements LoadPattern {
    
    private static final Logger log = LoggerFactory.getLogger(RecoveryAdaptiveLoadPattern.class);
    
    private final AdaptiveLoadPattern delegate;
    private final MetricsProvider metricsProvider;
    private final BackpressureProvider backpressureProvider;
    private final double errorThreshold;
    private final double minimumTps;
    
    private double lastKnownGoodTps = 1000.0;
    
    /**
     * Constructor for RecoveryAdaptiveLoadPattern.
     * 
     * @param delegate the AdaptiveLoadPattern to wrap
     * @param metricsProvider the metrics provider
     * @param backpressureProvider the backpressure provider
     * @param errorThreshold the error rate threshold (e.g., 0.01 for 1%)
     * @param minimumTps the minimum TPS floor (e.g., 100.0)
     */
    public RecoveryAdaptiveLoadPattern(
            AdaptiveLoadPattern delegate,
            MetricsProvider metricsProvider,
            BackpressureProvider backpressureProvider,
            double errorThreshold,
            double minimumTps) {
        this.delegate = delegate;
        this.metricsProvider = metricsProvider;
        this.backpressureProvider = backpressureProvider;
        this.errorThreshold = errorThreshold;
        this.minimumTps = minimumTps;
    }
    
    @Override
    public double calculateTps(long elapsedMillis) {
        double tps = delegate.calculateTps(elapsedMillis);
        AdaptiveLoadPattern.Phase phase = delegate.getCurrentPhase();
        
        // Track last known good TPS (before RECOVERY)
        if (phase != AdaptiveLoadPattern.Phase.RECOVERY && tps > 0) {
            lastKnownGoodTps = Math.max(lastKnownGoodTps, tps);
        }
        
        // Handle RECOVERY phase
        if (phase == AdaptiveLoadPattern.Phase.RECOVERY) {
            double errorRate = metricsProvider.getFailureRate() / 100.0;  // Convert percentage to ratio
            double backpressure = backpressureProvider.getBackpressureLevel();
            
            // Recovery conditions: backpressure low OR (error rate low AND backpressure moderate)
            if (backpressure < 0.3 || (errorRate < errorThreshold && backpressure < 0.5)) {
                // Return recovery TPS to trigger RAMP_UP
                double recoveryTps = Math.max(minimumTps, lastKnownGoodTps * 0.5);
                log.info("✅ RECOVERY: Conditions improved (errorRate={}%, backpressure={}), returning {} TPS",
                    String.format("%.2f", errorRate * 100.0), String.format("%.2f", backpressure), 
                    String.format("%.2f", recoveryTps));
                return recoveryTps;
            }
            
            // Stay in recovery
            return Math.max(minimumTps, tps);
        }
        
        return tps;
    }
    
    @Override
    public Duration getDuration() {
        return delegate.getDuration();
    }
}
```

**Modify: `LoadTestService.java`**

**Wrap AdaptiveLoadPattern:**
```java
AdaptiveLoadPattern adaptivePattern = new AdaptiveLoadPattern(...);

// Wrap with recovery handler
LoadPattern loadPattern = new RecoveryAdaptiveLoadPattern(
    adaptivePattern,
    adaptiveMetricsProvider,
    backpressureProvider,
    ERROR_THRESHOLD,
    100.0  // minimum TPS
);
```

**Testing:**
```bash
# Run test
./gradlew bootRun

# Monitor logs for:
# - RECOVERY phase entered
# - "✅ RECOVERY: Conditions improved" message
# - Phase transition from RECOVERY to RAMP_UP
# - TPS increases after recovery
```

**Success Criteria:**
- [ ] RECOVERY phase handled (either in VajraPulse or wrapper)
- [ ] Recovery triggers when backpressure < 0.3
- [ ] Recovery starts at 50% of last known good TPS
- [ ] Test recovers from low TPS
- [ ] Test ramps up after recovery
- [ ] Test can cycle: RAMP_UP → RAMP_DOWN → RECOVERY → RAMP_UP

---

### Task 2.2: Test Recovery Mechanism

**Objective:** Verify recovery works end-to-end

**Approach:**
1. Run test for extended period
2. Monitor recovery cycles
3. Verify TPS increases after recovery

**Test Scenario:**
```bash
# Run test for 10 minutes
./gradlew bootRun

# Expected behavior:
# 1. RAMP_UP: 1000 → 1500 → 2000 → ... → 5500 TPS
# 2. Backpressure detected → RAMP_DOWN: 5500 → 4500 → ... → 100 TPS
# 3. RECOVERY: Stuck at 100 TPS
# 4. Conditions improve → Recovery triggers
# 5. RAMP_UP: 2750 (50% of 5500) → 3250 → 3750 → ...
# 6. Cycle repeats
```

**Monitoring:**
- Watch logs for phase transitions
- Verify recovery triggers within 10 seconds of conditions improving
- Verify TPS increases after recovery
- Verify multiple recovery cycles work

**Success Criteria:**
- [ ] Test recovers from low TPS
- [ ] Test ramps up after recovery
- [ ] Test can cycle multiple times
- [ ] No getting stuck at 100 TPS
- [ ] Recovery happens within 10 seconds of conditions improving

---

## Phase 3: Optimize Thresholds and Tuning (Days 6-7)

### Task 3.1: Tune Recovery Conditions

**Objective:** Find optimal recovery thresholds

**Approach:**
1. Test different backpressure thresholds
2. Test different error rate thresholds
3. Find optimal combination

**Test Matrix:**
```java
// Test different combinations
double[] backpressureThresholds = {0.2, 0.3, 0.4, 0.5};
double[] errorRateThresholds = {0.005, 0.01, 0.02, 0.03};  // 0.5%, 1%, 2%, 3%

for (double bpThreshold : backpressureThresholds) {
    for (double errorThreshold : errorRateThresholds) {
        // Run test with these thresholds
        // Measure: recovery time, number of cycles, optimal TPS found
    }
}
```

**Metrics to Track:**
- Recovery time (how long until recovery triggers)
- Number of recovery cycles
- Optimal TPS found
- Stability (does it sustain at optimal TPS?)

**Success Criteria:**
- [ ] Optimal thresholds identified
- [ ] Recovery triggers at appropriate times
- [ ] Not too aggressive (doesn't recover too early)
- [ ] Not too conservative (doesn't get stuck)

---

### Task 3.2: Tune Ramp Increments

**Objective:** Find optimal ramp rates

**Approach:**
1. Test different ramp increments
2. Test different ramp decrements
3. Find optimal ramp rates

**Test Scenarios:**
```java
// Test different ramp increments
double[] rampIncrements = {250.0, 500.0, 1000.0, 2000.0};
double[] rampDecrements = {500.0, 1000.0, 2000.0, 4000.0};

for (double increment : rampIncrements) {
    for (double decrement : rampDecrements) {
        // Run test with these ramp rates
        // Measure: time to find optimal TPS, stability
    }
}
```

**Success Criteria:**
- [ ] Optimal ramp rates identified
- [ ] Ramp up is smooth (not too aggressive)
- [ ] Ramp down is responsive (reacts quickly to backpressure)
- [ ] Test finds optimal TPS efficiently

---

### Task 3.3: Add Stability Detection (Optional)

**Objective:** Sustain at intermediate TPS levels

**Approach:**
1. Detect when TPS is stable
2. Sustain at stable TPS
3. Transition to RAMP_UP when conditions improve

**Implementation:**
```java
// In RecoveryAdaptiveLoadPattern or AdaptiveLoadPattern
private boolean isStable(double currentTps, long elapsedMillis) {
    // Check if TPS has been stable for SUSTAIN_DURATION
    // Check if conditions are good (error rate low, backpressure low)
    // Return true if stable
}
```

**Success Criteria:**
- [ ] Pattern sustains at intermediate TPS levels
- [ ] Pattern doesn't continuously ramp up/down
- [ ] Test finds and maintains optimal TPS

---

## Phase 4: Advanced Features (Days 8-10, Optional)

### Task 4.1: Add Metrics Dashboard

**Objective:** Visualize adaptive load test metrics

**Approach:**
1. Create Grafana dashboard
2. Show TPS, backpressure, error rate over time
3. Show phase transitions

**Dashboard Panels:**
- TPS over time (line chart)
- Backpressure over time (line chart)
- Error rate over time (line chart)
- Phase transitions (annotations)
- Optimal TPS found (stat panel)

**Success Criteria:**
- [ ] Dashboard created
- [ ] All metrics visible
- [ ] Phase transitions annotated
- [ ] Easy to understand

---

### Task 4.2: Add Alerting

**Objective:** Alert on test issues

**Approach:**
1. Alert when test gets stuck
2. Alert when error rate is high
3. Alert when backpressure is severe

**Alerts:**
- Test stuck at low TPS for > 60 seconds
- Error rate > 5% for > 30 seconds
- Backpressure > 0.9 for > 10 seconds

**Success Criteria:**
- [ ] Alerts configured
- [ ] Alerts fire at appropriate times
- [ ] Alerts are actionable

---

### Task 4.3: Add Test Reports

**Objective:** Generate test reports

**Approach:**
1. Generate HTML report
2. Include optimal TPS found
3. Include recovery cycles
4. Include performance metrics

**Report Sections:**
- Test summary (duration, optimal TPS)
- Phase transitions timeline
- Recovery cycles
- Performance metrics (throughput, latency)
- Recommendations

**Success Criteria:**
- [ ] Report generated
- [ ] All metrics included
- [ ] Easy to understand
- [ ] Actionable insights

---

## Summary Checklist

### Phase 0: Preparation
- [ ] Task 0.1: Create branch and document state
- [ ] Task 0.2: Remove all wrappers
- [ ] Task 0.3: Simplify CrdbInsertTask

### Phase 1: Basic Adaptive Load Test
- [ ] Task 1.1: Implement RecentWindowMetricsProvider
- [ ] Task 1.2: Use Queue-Only Backpressure (SIMPLIFIED)
- [ ] Task 1.3: Configure AdaptiveLoadPattern

### Phase 2: Simple Recovery Mechanism
- [ ] Task 2.1: Enhance AdaptiveLoadPattern (or create wrapper)
- [ ] Task 2.2: Test recovery mechanism

### Phase 3: Optimize Thresholds
- [ ] Task 3.1: Tune recovery conditions
- [ ] Task 3.2: Tune ramp increments
- [ ] Task 3.3: Add stability detection (optional)

### Phase 4: Advanced Features (Optional)
- [ ] Task 4.1: Add metrics dashboard
- [ ] Task 4.2: Add alerting
- [ ] Task 4.3: Add test reports

---

## Timeline

- **Week 1:** Phase 0 + Phase 1 (Days 1-3)
- **Week 2:** Phase 2 + Phase 3 (Days 4-7)
- **Week 3:** Phase 4 (Days 8-10, optional) + Testing

**Total:** 2-3 weeks for complete implementation

---

## Next Steps

1. **Review this document** with team
2. **Start Phase 0** (clean slate, remove wrappers)
3. **Incremental implementation** (one phase at a time)
4. **Test after each phase** (verify success criteria)
5. **Iterate based on results** (adjust thresholds, etc.)

---

**Document Status:** Ready for Implementation  
**Last Updated:** 2025-12-05

