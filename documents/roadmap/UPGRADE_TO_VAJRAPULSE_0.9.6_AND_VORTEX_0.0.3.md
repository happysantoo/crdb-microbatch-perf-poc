# Upgrade Plan: VajraPulse 0.9.6 & Vortex 0.0.3

## Executive Summary

This document outlines a comprehensive plan to upgrade the CRDB Microbatch Performance POC from:
- **VajraPulse 0.9.5 → 0.9.6**
- **Vortex 0.0.2 → 0.0.3**

The upgrade focuses on:
1. **Simplifying code** by leveraging new library features
2. **Implementing proper backpressure** with `AdaptiveLoadPattern`
3. **Removing workarounds** (manual metrics tracking)
4. **Improving maintainability** and reducing complexity

## New Features Analysis

### VajraPulse 0.9.6 Key Features

#### 1. **Backpressure Support** ⭐ CRITICAL
- **`BackpressureProvider`** interface for reporting system backpressure (0.0-1.0 scale)
- **`BackpressureHandler`** interface with multiple strategies:
  - `DROP` - Drop requests when backpressure is high
  - `REJECT` - Reject requests with error
  - `RETRY` - Retry requests after backoff
  - `DEGRADE` - Reduce quality/features
  - `THRESHOLD` - Reject when threshold exceeded
- **`QueueBackpressureProvider`** for queue depth-based backpressure
- **`CompositeBackpressureProvider`** for combining multiple backpressure signals
- **Integration with `AdaptiveLoadPattern`** - automatically ramps down when backpressure ≥ 0.7, ramps up when < 0.3
- **Integration with `ExecutionEngine`** - handles requests under backpressure
- **Metrics**: `vajrapulse.execution.backpressure.dropped`, `vajrapulse.execution.backpressure.rejected`
- **HikariCP backpressure example** (in examples, not committed to core)

**Impact on Our Project:**
- ✅ **Eliminates connection pool exhaustion** - backpressure will prevent overwhelming the pool
- ✅ **Enables true adaptive load testing** - system responds to actual capacity
- ✅ **No more manual TPS limiting** - AdaptiveLoadPattern handles it automatically

#### 2. **MetricsPipeline.getMetricsProvider()** ⭐ CRITICAL
- Direct access to `MetricsProvider` from pipeline
- Returns `MetricsProviderAdapter` wrapping the pipeline's `MetricsCollector`
- Eliminates need for manual `MetricsProviderAdapter` creation
- Clean API: `pipeline.getMetricsProvider()` instead of manual collector/provider setup

**Impact on Our Project:**
- ✅ **Removes manual metrics tracking workaround** (see `VAJRAPULSE_LIBRARY_IMPROVEMENT_METRICSPROVIDER.md`)
- ✅ **Simplifies `LoadTestService`** - no more `AtomicLong` counters and task wrapping
- ✅ **Single source of truth** - metrics come directly from pipeline

#### 3. **Adaptive Load Pattern Fixes**
- Fixed hanging issue where `AdaptiveLoadPattern` would hang after one iteration
- Improved loop termination logic in `ExecutionEngine` to handle patterns starting at 0.0 TPS
- Comprehensive test coverage added

**Impact on Our Project:**
- ✅ **Fixes previous issues** we encountered with AdaptiveLoadPattern
- ✅ **More reliable** adaptive behavior

#### 4. **Test Infrastructure Improvements**
- Migrated to Awaitility 4.3.0
- Replaced `Thread.sleep()` with Awaitility for state-waiting scenarios
- Improved test reliability and performance (10-30% faster execution)

**Impact on Our Project:**
- ✅ **Better test reliability** (if we add tests)

### VajraPulse 0.9.5 Features (Already Available)

#### 1. **Adaptive Load Pattern**
- Feedback-driven load pattern that dynamically adjusts TPS based on error rates
- Automatically ramps up TPS when error rates are low
- Ramps down TPS when error rates exceed threshold
- Supports configurable ramp increments/decrements, intervals, max TPS, and sustain duration

#### 2. **Metrics Caching**
- `MetricsProviderAdapter` includes built-in caching with configurable TTL (default 100ms)
- Reduces overhead from frequent `snapshot()` calls in adaptive load patterns
- Thread-safe double-check locking pattern

#### 3. **Load Pattern Factory**
- Centralized load pattern creation utility
- Reusable across CLI and programmatic usage

### VajraPulse 0.9.4 Features (Already Available)

#### 1. **Report Exporters Module**
- `HtmlReportExporter` - Beautiful HTML reports with interactive charts using Chart.js
- `JsonReportExporter` - JSON format for programmatic analysis
- `CsvReportExporter` - CSV format for spreadsheet analysis
- All exporters support file-based report generation with automatic directory creation

**Current Status:** ✅ Already integrated in our project

### VajraPulse 0.9.3 Features (Already Available)

#### 1. **Queue Depth Tracking**
- `vajrapulse.execution.queue.size` gauge metric for pending executions
- `vajrapulse.execution.queue.wait_time` timer with percentiles (P50, P95, P99)
- Queue metrics exposed in `AggregatedMetrics` and all exporters

#### 2. **BOM (Bill of Materials) Module**
- Centralized dependency version management
- Usage: `implementation(platform("com.vajrapulse:vajrapulse-bom:0.9.6"))`
- All VajraPulse modules included in BOM for version consistency

**Impact on Our Project:**
- ✅ **Simplifies dependency management** - use BOM instead of individual versions

### Vortex 0.0.3 Features

Based on the changelog and current code usage:
- **`submitWithCallback`** - Already being used in our code
- **`ItemResult`** - Already being used in our code
- Additional improvements and bug fixes

**Current Status:** ✅ Already compatible (we're using these features)

## Simplification Opportunities

### 1. **Remove Manual Metrics Tracking** ⭐ HIGH IMPACT

**Current Code (LoadTestService.java):**
```java
// Workaround: Manual metrics tracking
private final AtomicLong totalExecutions = new AtomicLong(0);
private final AtomicLong totalFailures = new AtomicLong(0);

TaskLifecycle trackingTask = new TaskLifecycle() {
    @Override
    public TaskResult execute(long iteration) throws Exception {
        totalExecutions.incrementAndGet();
        // ... wrapping logic
    }
};

MetricsProvider metricsProvider = new MetricsProvider() {
    @Override
    public double getFailureRate() {
        // ... manual calculation
    }
};
```

**After Upgrade:**
```java
try (MetricsPipeline pipeline = createMetricsPipeline(otelExporter, htmlExporter)) {
    // Get MetricsProvider directly from pipeline - no manual tracking!
    MetricsProvider metricsProvider = pipeline.getMetricsProvider();
    
    LoadPattern loadPattern = new AdaptiveLoadPattern(..., metricsProvider);
    var result = pipeline.run(task, loadPattern);  // Use original task, no wrapping
}
```

**Benefits:**
- ✅ Removes ~50 lines of boilerplate code
- ✅ Single source of truth for metrics
- ✅ No risk of metrics inconsistency
- ✅ Cleaner, more maintainable code

### 2. **Implement HikariCP Backpressure** ⭐ HIGH IMPACT

**Current Problem:**
- Connection pool exhaustion at high TPS
- Manual TPS limiting required
- No automatic adaptation to connection pool capacity

**Solution:**
Create a `HikariCPBackpressureProvider` that reports backpressure based on:
- Active connections / Maximum pool size
- Pending connection requests
- Connection wait time

**Implementation:**
```java
@Component
public class HikariCPBackpressureProvider implements BackpressureProvider {
    private final HikariDataSource dataSource;
    
    @Override
    public double getBackpressureLevel() {
        HikariPoolMXBean poolBean = dataSource.getHikariPoolMXBean();
        int active = poolBean.getActiveConnections();
        int total = poolBean.getTotalConnections();
        int idle = poolBean.getIdleConnections();
        int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
        
        // Calculate backpressure: 0.0 (no pressure) to 1.0 (max pressure)
        double poolUtilization = (double) active / total;
        double queuePressure = Math.min(threadsAwaiting / 10.0, 1.0);  // Normalize
        
        return Math.max(poolUtilization, queuePressure);
    }
}
```

**Usage:**
```java
AdaptiveLoadPattern loadPattern = new AdaptiveLoadPattern(
    100.0,                          // initialTps
    10000.0,                        // maxTps
    500.0,                          // stepSize
    Duration.ofSeconds(10),         // stepDuration
    5.0,                           // cooldownSeconds
    Duration.ofSeconds(30),        // stabilityWindow
    0.01,                          // maxFailureRate
    metricsProvider,                // From pipeline.getMetricsProvider()
    hikariCPBackpressureProvider   // NEW: Backpressure support
);
```

**Benefits:**
- ✅ Automatic TPS adjustment based on connection pool capacity
- ✅ Prevents connection pool exhaustion
- ✅ Enables true adaptive load testing
- ✅ System finds optimal TPS automatically

### 3. **Use VajraPulse BOM** ⭐ MEDIUM IMPACT

**Current Code (build.gradle.kts):**
```kotlin
implementation("com.vajrapulse:vajrapulse-core:0.9.5")
implementation("com.vajrapulse:vajrapulse-api:0.9.5")
implementation("com.vajrapulse:vajrapulse-worker:0.9.5")
implementation("com.vajrapulse:vajrapulse-exporter-opentelemetry:0.9.5")
implementation("com.vajrapulse:vajrapulse-exporter-report:0.9.5")
```

**After Upgrade:**
```kotlin
// Use BOM for version management
implementation(platform("com.vajrapulse:vajrapulse-bom:0.9.6"))

// Dependencies without versions (managed by BOM)
implementation("com.vajrapulse:vajrapulse-core")
implementation("com.vajrapulse:vajrapulse-api")
implementation("com.vajrapulse:vajrapulse-worker")
implementation("com.vajrapulse:vajrapulse-exporter-opentelemetry")
implementation("com.vajrapulse:vajrapulse-exporter-report")
```

**Benefits:**
- ✅ Single version to update (BOM version)
- ✅ Ensures all VajraPulse modules use compatible versions
- ✅ Reduces dependency conflicts

### 4. **Simplify LoadTestService Architecture** ⭐ MEDIUM IMPACT

**Current Complexity:**
- Manual metrics tracking
- Task wrapping
- Reflection for result access
- Shutdown hook with null checks

**After Upgrade:**
- Direct `pipeline.getMetricsProvider()` usage
- No task wrapping needed
- Typed result access (no reflection)
- Cleaner shutdown handling

**Estimated Code Reduction:** ~100 lines → ~60 lines (40% reduction)

### 5. **Remove Unused Workarounds** ⭐ LOW IMPACT

**Files to Review:**
- `documents/analysis/VAJRAPULSE_LIBRARY_IMPROVEMENT_METRICSPROVIDER.md` - Can be archived (workaround no longer needed)
- `documents/analysis/ADAPTIVE_LOAD_PATTERN_DEBUG_REPORT.md` - Can be archived (issues fixed in 0.9.6)

## Implementation Plan

### Phase 1: Dependency Upgrade

1. **Update build.gradle.kts**
   - Upgrade VajraPulse to 0.9.6
   - Upgrade Vortex to 0.0.3
   - Add VajraPulse BOM
   - Remove individual version declarations

2. **Verify Dependencies**
   - Run `./gradlew dependencies` to verify versions
   - Check for any conflicts

### Phase 2: Backpressure Implementation

1. **Create HikariCPBackpressureProvider**
   - Implement `BackpressureProvider` interface
   - Calculate backpressure based on connection pool metrics
   - Add unit tests

2. **Integrate with AdaptiveLoadPattern**
   - Update `LoadTestService` to use backpressure provider
   - Configure `AdaptiveLoadPattern` with backpressure support

### Phase 3: Code Simplification

1. **Refactor LoadTestService**
   - Remove manual metrics tracking (`AtomicLong` counters)
   - Remove task wrapping logic
   - Use `pipeline.getMetricsProvider()` directly
   - Remove reflection code for result access

2. **Update CrdbInsertTask**
   - Verify compatibility with new library versions
   - Remove any workarounds if present

3. **Update CrdbBatchBackend**
   - Verify compatibility
   - No changes expected (already simplified)

### Phase 4: Testing & Validation

1. **Unit Tests**
   - Test `HikariCPBackpressureProvider` calculation logic
   - Test backpressure integration with `AdaptiveLoadPattern`

2. **Integration Tests**
   - Run load test with AdaptiveLoadPattern + Backpressure
   - Verify TPS adapts based on connection pool capacity
   - Verify no connection pool exhaustion

3. **Performance Validation**
   - Compare performance before/after upgrade
   - Verify metrics accuracy
   - Verify report generation

### Phase 5: Documentation & Cleanup

1. **Update Documentation**
   - Archive obsolete workaround documents
   - Update architecture diagrams if needed
   - Document backpressure implementation

2. **Code Cleanup**
   - Remove unused imports
   - Remove commented-out code
   - Update JavaDoc comments

## Detailed Implementation Steps

### Step 1: Upgrade Dependencies

**File: `build.gradle.kts`**

```kotlin
dependencies {
    // ... existing dependencies ...
    
    // VajraPulse BOM - manages all VajraPulse module versions
    implementation(platform("com.vajrapulse:vajrapulse-bom:0.9.6"))
    
    // VajraPulse modules (versions managed by BOM)
    implementation("com.vajrapulse:vajrapulse-core")
    implementation("com.vajrapulse:vajrapulse-api")
    implementation("com.vajrapulse:vajrapulse-worker") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("com.vajrapulse:vajrapulse-exporter-opentelemetry")
    implementation("com.vajrapulse:vajrapulse-exporter-report")
    
    // Vortex Micro-Batching Library
    implementation("com.vajrapulse:vortex:0.0.3")
    
    // ... rest of dependencies ...
}
```

### Step 2: Create HikariCPBackpressureProvider

**New File: `src/main/java/com/crdb/microbatch/backpressure/HikariCPBackpressureProvider.java`**

```java
package com.crdb.microbatch.backpressure;

import com.vajrapulse.api.BackpressureProvider;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.stereotype.Component;

/**
 * Backpressure provider based on HikariCP connection pool metrics.
 * 
 * <p>Reports backpressure level (0.0-1.0) based on:
 * <ul>
 *   <li>Connection pool utilization (active / total)</li>
 *   <li>Pending connection requests (threads awaiting connection)</li>
 * </ul>
 * 
 * <p>Backpressure levels:
 * <ul>
 *   <li>0.0 - 0.3: Low pressure, can ramp up TPS</li>
 *   <li>0.3 - 0.7: Moderate pressure, maintain current TPS</li>
 *   <li>0.7 - 1.0: High pressure, should ramp down TPS</li>
 * </ul>
 */
@Component
public class HikariCPBackpressureProvider implements BackpressureProvider {
    
    private final HikariDataSource dataSource;
    
    /**
     * Constructor for HikariCPBackpressureProvider.
     * 
     * @param dataSource the HikariCP data source
     */
    public HikariCPBackpressureProvider(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }
    
    @Override
    public double getBackpressureLevel() {
        HikariPoolMXBean poolBean = dataSource.getHikariPoolMXBean();
        
        if (poolBean == null) {
            return 0.0;  // No pool info available, assume no pressure
        }
        
        int active = poolBean.getActiveConnections();
        int total = poolBean.getTotalConnections();
        int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
        
        if (total == 0) {
            return 0.0;  // No connections available, assume no pressure
        }
        
        // Calculate pool utilization (0.0 to 1.0)
        double poolUtilization = (double) active / total;
        
        // Calculate queue pressure based on waiting threads
        // Normalize: 10 waiting threads = 1.0 pressure
        double queuePressure = Math.min(threadsAwaiting / 10.0, 1.0);
        
        // Return maximum of pool utilization and queue pressure
        // This ensures we report backpressure if either metric indicates pressure
        return Math.max(poolUtilization, queuePressure);
    }
}
```

### Step 3: Refactor LoadTestService

**File: `src/main/java/com/crdb/microbatch/service/LoadTestService.java`**

**Key Changes:**
1. Remove `AtomicLong` counters
2. Remove task wrapping logic
3. Use `pipeline.getMetricsProvider()` directly
4. Add `HikariCPBackpressureProvider` integration
5. Switch to `AdaptiveLoadPattern` with backpressure

**Simplified executeLoadTest method:**
```java
private void executeLoadTest(
        OpenTelemetryExporter otelExporter, 
        HtmlReportExporter htmlExporter,
        HikariCPBackpressureProvider backpressureProvider) 
        throws Exception {
    try (MetricsPipeline pipeline = createMetricsPipeline(otelExporter, htmlExporter)) {
        // Get MetricsProvider directly from pipeline - no manual tracking!
        MetricsProvider metricsProvider = pipeline.getMetricsProvider();
        
        // Create AdaptiveLoadPattern with backpressure support
        LoadPattern loadPattern = new AdaptiveLoadPattern(
            100.0,                          // initialTps
            10000.0,                        // maxTps
            500.0,                          // stepSize
            Duration.ofSeconds(10),         // stepDuration
            5.0,                           // cooldownSeconds
            Duration.ofSeconds(30),        // stabilityWindow
            0.01,                          // maxFailureRate
            metricsProvider,                // From pipeline
            backpressureProvider           // NEW: HikariCP backpressure
        );
        
        log.info("=== Starting Adaptive Load Test with Backpressure ===");
        log.info("Load Pattern: {}", loadPattern);
        log.info("Initial TPS: 100");
        log.info("Max TPS: 10,000");
        log.info("Step Size: 500 TPS");
        log.info("Step Duration: 10 seconds");
        log.info("Backpressure: Enabled (HikariCP connection pool)");
        log.info("Goal: Find maximum sustainable TPS with automatic backpressure handling");
        
        // Run with original task (no wrapping needed)
        var result = pipeline.run(task, loadPattern);
        testResult = result;
        testCompleted = true;
        
        log.info("=== Load Test Complete ===");
        log.info("Final metrics - Total executions: {}, Success: {}, Failures: {}, Success rate: {:.2f}%",
            result.totalExecutions(), result.successCount(), result.failureCount(),
            result.totalExecutions() > 0 ? result.successRate() * 100.0 : 0.0);
        
        generateFinalReport(result);
    }
}
```

### Step 4: Update Application Configuration

**File: `src/main/resources/application.yml`**

No changes needed - connection pool settings remain the same:
```yaml
hikari:
  maximum-pool-size: 10  # Backpressure will prevent exhaustion
  minimum-idle: 10
```

The backpressure provider will automatically prevent overwhelming the pool.

## Expected Benefits

### 1. **Code Simplification**
- **~40% reduction** in `LoadTestService` complexity
- **Removed ~50 lines** of manual metrics tracking
- **Removed ~30 lines** of task wrapping logic
- **Cleaner architecture** with single source of truth for metrics

### 2. **Improved Reliability**
- **No more connection pool exhaustion** - backpressure prevents it
- **Automatic TPS adaptation** - system finds optimal throughput
- **Fixed AdaptiveLoadPattern issues** - hanging bug fixed in 0.9.6

### 3. **Better Maintainability**
- **Less boilerplate code** - library handles complexity
- **Consistent metrics** - no risk of manual tracking errors
- **Easier to understand** - cleaner code flow

### 4. **Enhanced Functionality**
- **True adaptive load testing** - responds to actual system capacity
- **Backpressure metrics** - visibility into dropped/rejected requests
- **Better integration** - seamless library integration

## Risk Assessment

### Low Risk
- ✅ VajraPulse 0.9.6 is backward compatible (no breaking changes)
- ✅ Vortex 0.0.3 is backward compatible (we're already using new APIs)
- ✅ BOM usage is standard practice

### Medium Risk
- ⚠️ Backpressure calculation logic needs testing
- ⚠️ AdaptiveLoadPattern behavior may differ from previous tests

### Mitigation
- ✅ Comprehensive testing before production use
- ✅ Gradual rollout (test with low TPS first)
- ✅ Monitor metrics closely during initial runs

## Testing Strategy

### 1. **Unit Tests**
- Test `HikariCPBackpressureProvider` calculation logic
- Test edge cases (null pool, zero connections, etc.)

### 2. **Integration Tests**
- Run AdaptiveLoadPattern with backpressure
- Verify TPS adapts correctly
- Verify no connection pool exhaustion

### 3. **Performance Tests**
- Compare metrics accuracy before/after
- Verify report generation works
- Verify no performance regression

## Success Criteria

1. ✅ **Code compiles** without errors
2. ✅ **All tests pass** (unit + integration)
3. ✅ **AdaptiveLoadPattern works** with backpressure
4. ✅ **No connection pool exhaustion** at any TPS level
5. ✅ **Metrics are accurate** (no manual tracking needed)
6. ✅ **Reports generate correctly** (HTML + OpenTelemetry)
7. ✅ **Code is simpler** (40% reduction in complexity)

## Timeline Estimate

- **Phase 1 (Dependency Upgrade)**: 30 minutes
- **Phase 2 (Backpressure Implementation)**: 2-3 hours
- **Phase 3 (Code Simplification)**: 1-2 hours
- **Phase 4 (Testing & Validation)**: 2-3 hours
- **Phase 5 (Documentation & Cleanup)**: 1 hour

**Total Estimate**: 6-9 hours

## Next Steps

1. **Review this plan** with stakeholders
2. **Create feature branch**: `feature/upgrade-vajrapulse-0.9.6`
3. **Execute Phase 1**: Upgrade dependencies
4. **Execute Phase 2**: Implement backpressure
5. **Execute Phase 3**: Simplify code
6. **Execute Phase 4**: Test and validate
7. **Execute Phase 5**: Document and cleanup
8. **Merge to main** after validation

## References

- [VajraPulse 0.9.6 Changelog](https://github.com/happysantoo/vajrapulse/blob/main/CHANGELOG.md)
- [Vortex 0.0.3 Changelog](https://github.com/happysantoo/vortex/blob/main/CHANGELOG.md)
- [VajraPulse Backpressure Documentation](https://github.com/happysantoo/vajrapulse/blob/main/CHANGELOG.md#096---2025-01-xx)
- Current workaround documentation: `documents/analysis/VAJRAPULSE_LIBRARY_IMPROVEMENT_METRICSPROVIDER.md`

