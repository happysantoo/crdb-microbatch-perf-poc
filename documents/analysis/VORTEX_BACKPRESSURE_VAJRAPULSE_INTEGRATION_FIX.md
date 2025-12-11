# Vortex Backpressure - VajraPulse Integration Fix

## Problem Identified

**Symptoms:**
- Total Item Submits = 8.77 Million
- Total Vortex Requests = 5.26 Million
- Gap: 3.51 Million items (40% rejection rate)
- Testing slowing down and flatlining
- Many requests waiting

**Root Cause:**
Vortex backpressure is rejecting items (working correctly), but VajraPulse doesn't see these rejections as failures, so `AdaptiveLoadPattern` doesn't reduce TPS.

## Why This Happens

### Current Flow (BROKEN)

```
VajraPulse Task.execute()
    ↓
batcher.submitWithCallback()  // Non-blocking, returns immediately
    ↓
Returns TaskResult.success()  // ❌ Always returns success
    ↓
Vortex rejects item (backpressure >= 0.7)
    ↓
Callback invoked with failure  // ❌ VajraPulse doesn't see this
    ↓
AdaptiveLoadPattern continues at same TPS  // ❌ No TPS reduction
```

**The Problem:**
- `submitWithCallback()` is non-blocking and always returns immediately
- Vortex rejections happen asynchronously in the callback
- VajraPulse's `TaskResult` is returned as `success()` before rejection is known
- `AdaptiveLoadPattern` doesn't see rejections as failures, so it doesn't reduce TPS

## Solution

### Fixed Flow

```
VajraPulse Task.execute()
    ↓
batcher.submit()  // Get CompletableFuture
    ↓
Check if future.isDone()  // Immediate rejection check
    ↓
If done (rejected):
    └─ Return TaskResult.failure()  // ✅ VajraPulse sees failure
    └─ AdaptiveLoadPattern reduces TPS  // ✅ TPS adjustment
If not done (accepted):
    └─ Use submitWithCallback() for async tracking
    └─ Return TaskResult.success()  // ✅ Item queued successfully
```

**The Fix:**
1. Call `batcher.submit()` first to get the `CompletableFuture`
2. Check if the future is already completed (immediate rejection)
3. If rejected, return `TaskResult.failure()` so VajraPulse sees it
4. If accepted, use `submitWithCallback()` for async batch result tracking

## Code Changes

### Before (BROKEN)

```java
batcher.submitWithCallback(testInsert, (item, itemResult) -> {
    // Callback handles result asynchronously
    if (itemResult instanceof ItemResult.Failure) {
        submitFailureCounter.increment();
    }
});
return TaskResult.success();  // ❌ Always returns success
```

### After (FIXED)

```java
CompletableFuture<ItemResult<TestInsert>> future = batcher.submit(testInsert);

// Check for immediate backpressure rejection
if (future.isDone()) {
    ItemResult<TestInsert> result = future.get();
    if (result instanceof ItemResult.Failure<TestInsert> failure) {
        // Vortex rejected due to backpressure - return failure
        return TaskResult.failure(failure.error());  // ✅ VajraPulse sees failure
    }
}

// Future not done - item was accepted and queued
batcher.submitWithCallback(testInsert, (item, itemResult) -> {
    // Async callback for batch result tracking
});
return TaskResult.success();  // ✅ Item queued successfully
```

## Expected Behavior After Fix

### When Backpressure < 0.7 (Normal Operation)
- Items accepted and queued
- `TaskResult.success()` returned
- Batches processed normally
- No rejections

### When Backpressure >= 0.7 (Rejection)
- Items rejected immediately by Vortex
- `TaskResult.failure()` returned
- VajraPulse sees failure
- `AdaptiveLoadPattern` reduces TPS
- System recovers

## Metrics Impact

**Before Fix:**
- `crdb.submits.total` = 8.77M (VajraPulse submissions)
- `vortex.requests.submitted` = 5.26M (Vortex accepts)
- Gap = 3.51M (rejections not visible to VajraPulse)

**After Fix:**
- `crdb.submits.total` = X (VajraPulse submissions)
- `crdb.submits.failure` = Y (includes Vortex rejections)
- `vortex.requests.submitted` = X - Y (only accepted items)
- VajraPulse failure rate increases → TPS reduces

## Benefits

1. **VajraPulse Sees Rejections**: `AdaptiveLoadPattern` can react to Vortex rejections
2. **Automatic TPS Reduction**: System self-regulates when backpressure is high
3. **Proper Failure Tracking**: Failures are visible in VajraPulse metrics
4. **Better Observability**: Can see rejection rate in VajraPulse reports

## Testing

After this fix, verify:
- [ ] Vortex rejections are returned as `TaskResult.failure()`
- [ ] `AdaptiveLoadPattern` reduces TPS when rejections occur
- [ ] Failure rate in VajraPulse metrics includes Vortex rejections
- [ ] System recovers when backpressure decreases
- [ ] No performance degradation (rejection check is fast)

## Related Issues

This fix addresses the disconnect between:
- **Vortex backpressure** (immediate, item-level rejection)
- **VajraPulse backpressure** (gradual, load pattern adjustments)

Both systems now work together:
- Vortex prevents queue growth (immediate rejection)
- VajraPulse adjusts TPS based on rejection rate (gradual adjustment)

