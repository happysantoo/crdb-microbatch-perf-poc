# Vortex 0.0.4 Upgrade - Implementation Summary

## Overview

Refactored the application to use Vortex 0.0.4's built-in backpressure features, replacing custom application-level backpressure handling with library-provided capabilities.

## Changes Made

### 1. Dependency Update

**File**: `build.gradle.kts`

- Updated Vortex dependency from `0.0.3` to `0.0.4`

```kotlin
implementation("com.vajrapulse:vortex:0.0.4")
```

### 2. New Backpressure Provider

**File**: `src/main/java/com/crdb/microbatch/backpressure/VortexHikariCPBackpressureProvider.java`

- Created new `BackpressureProvider` implementation for Vortex library
- Implements `com.vajrapulse.vortex.backpressure.BackpressureProvider` (separate from VajraPulse's `BackpressureProvider`)
- Monitors HikariCP connection pool metrics:
  - Active connections
  - Total connections
  - Idle connections
  - Threads awaiting connection
- Uses aggressive backpressure calculation to prevent connection pool exhaustion
- Reports backpressure level (0.0-1.0) based on pool utilization and queue depth

**Key Features**:
- Reports `1.0` immediately when `threadsAwaiting >= total`
- Uses logarithmic scaling for early detection
- Logs warnings when backpressure >= 0.7

### 3. Updated CrdbInsertTask

**File**: `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`

**Changes**:
1. **Added Vortex Backpressure Imports**:
   - `QueueDepthBackpressureProvider` - Monitors internal queue depth
   - `CompositeBackpressureProvider` - Combines multiple providers
   - `RejectStrategy` - Rejects items when backpressure threshold exceeded

2. **Updated Constructor**:
   - Added `VortexHikariCPBackpressureProvider` parameter
   - Injected via Spring dependency injection

3. **Refactored `initializeBatcher()` Method**:
   - Uses `MicroBatcher.withBackpressure()` factory method
   - Creates `QueueDepthBackpressureProvider` with max queue size (20 batches)
   - Creates `CompositeBackpressureProvider` combining:
     - Queue depth provider
     - HikariCP connection pool provider
   - Uses `RejectStrategy` with threshold 0.7 (70% capacity)
   - Implements `MutableQueueDepthSupplier` to access batcher queue depth

**Backpressure Configuration**:
- **Queue Max Size**: `BATCH_SIZE * 20` (1000 items = 20 batches)
- **Backpressure Threshold**: `0.7` (70% capacity)
- **Strategy**: `RejectStrategy` - Rejects items with `BackpressureException` when threshold exceeded

## Architecture

### Dual Backpressure System

The application now uses **two separate backpressure systems**:

1. **Vortex Backpressure** (Queue Level):
   - Monitors MicroBatcher internal queue depth
   - Monitors HikariCP connection pool
   - Rejects items at submission time when backpressure >= 0.7
   - Prevents queue growth and connection pool exhaustion

2. **VajraPulse Backpressure** (Load Pattern Level):
   - Uses `HikariCPBackpressureProvider` (VajraPulse interface)
   - Used by `AdaptiveLoadPattern` to adjust TPS
   - Works at load pattern level (every 5 seconds)
   - Provides gradual TPS adjustments

**Why Both?**:
- **Vortex backpressure**: Immediate, item-level rejection (prevents queue growth)
- **VajraPulse backpressure**: Gradual, load pattern adjustments (finds sustainable TPS)

### Backpressure Flow

```
Item Submission
    ↓
Vortex Backpressure Check (immediate)
    ├─ Queue Depth Check
    ├─ HikariCP Pool Check
    └─ Composite (max of both)
    ↓
If backpressure >= 0.7:
    └─ Reject with BackpressureException
Else:
    └─ Accept and queue
    ↓
VajraPulse AdaptiveLoadPattern (every 5s)
    ├─ Checks HikariCP BackpressureProvider
    └─ Adjusts TPS based on backpressure level
```

## Key Benefits

1. **Library-Managed Backpressure**:
   - No custom application-level backpressure handling needed
   - Uses Vortex's built-in, tested backpressure mechanisms

2. **Comprehensive Detection**:
   - Queue depth monitoring (prevents memory growth)
   - Connection pool monitoring (prevents database exhaustion)
   - Composite provider ensures both are checked

3. **Immediate Response**:
   - Items rejected at submission time (before queuing)
   - Prevents queue growth and system overload

4. **Separation of Concerns**:
   - Vortex handles queue-level backpressure
   - VajraPulse handles load pattern adjustments
   - Each system optimized for its use case

## Testing Checklist

- [ ] Verify Vortex 0.0.4 dependency resolves correctly
- [ ] Test queue depth backpressure (fill queue to threshold)
- [ ] Test HikariCP backpressure (exhaust connection pool)
- [ ] Test composite backpressure (both sources)
- [ ] Verify items are rejected when backpressure >= 0.7
- [ ] Verify items are accepted when backpressure < 0.7
- [ ] Check metrics: `vortex.backpressure.rejected` counter
- [ ] Verify AdaptiveLoadPattern still works with VajraPulse backpressure
- [ ] Test recovery when backpressure decreases

## Expected Behavior

### Normal Operation (Backpressure < 0.7)
- Items accepted and queued normally
- Batches processed as usual
- No rejections

### High Backpressure (Backpressure >= 0.7)
- Items rejected with `BackpressureException`
- `vortex.backpressure.rejected` counter increments
- Queue depth or connection pool at capacity
- AdaptiveLoadPattern should ramp down TPS

### Recovery (Backpressure Decreases < 0.7)
- Items accepted again
- Queue depth decreases
- Connection pool recovers
- AdaptiveLoadPattern should ramp up TPS

## Migration Notes

### Removed Components

- **EmergencyBackpressureLoadPattern**: **REMOVED** - No longer needed since Vortex 0.0.4 handles backpressure at queue level
  - This was a workaround to provide immediate TPS reduction when backpressure was detected
  - Vortex now handles this automatically by rejecting items at submission time
  - Load pattern adjustments (via AdaptiveLoadPattern) still work for gradual TPS changes

### Backward Compatibility

- **VajraPulse Backpressure**: Still uses `HikariCPBackpressureProvider` (VajraPulse interface)
- **PhaseLoggingLoadPattern**: Still works (logs phase transitions), now wraps AdaptiveLoadPattern directly

### Removed Components

- No components removed - all existing functionality preserved
- Added new Vortex backpressure layer on top

### Configuration Changes

- **New**: `VortexHikariCPBackpressureProvider` bean (Spring component)
- **New**: Backpressure configuration in `CrdbInsertTask.initializeBatcher()`
- **Unchanged**: All other configuration remains the same

## References

- [Vortex 0.0.4 Release Notes](https://github.com/happysantoo/vortex/blob/main/documents/releases/RELEASE_NOTES_0.0.4.md)
- Vortex Backpressure API: `com.vajrapulse.vortex.backpressure.*`
- VajraPulse Backpressure API: `com.vajrapulse.api.BackpressureProvider`

