# Batch Size Issue: Batches Contain Only 1 Item

## Problem

Batches are being dispatched with only 1 item instead of accumulating to 50 items (configured batch size).

## Symptoms

```
⚠️ BATCHING ISSUE: Batch contains only 1 item! Items are not being accumulated.
```

This happens continuously, meaning every item is being dispatched immediately instead of being batched.

## Root Cause Analysis

### Possible Causes

1. **submitSync() Behavior**
   - `submitSync()` might be causing immediate dispatch
   - If `submitSync()` waits for batch completion, it might trigger immediate dispatch
   - Need to verify Vortex behavior with `submitSync()`

2. **Linger Time Too Short**
   - Current: 50ms
   - If items arrive slowly, 50ms might not be enough to accumulate 50 items
   - But at 500-1000 TPS, items should arrive much faster than 50ms

3. **Batch Size Configuration Not Applied**
   - `BatcherConfig.batchSize(50)` might not be working
   - Need to verify Vortex is respecting the configuration

4. **Immediate Dispatch Trigger**
   - Something might be triggering immediate dispatch
   - Could be related to backpressure or queue management

## Investigation Steps

1. **Check Vortex Metrics**
   - `vortex.batches.dispatched` - Total batches
   - `vortex.requests.submitted` - Total requests
   - Ratio should be ~50:1 (50 requests per batch)
   - If ratio is ~1:1, items are being dispatched immediately

2. **Check Queue Depth**
   - If queue depth is always 0-1, items are being dispatched immediately
   - Queue should accumulate items before dispatch

3. **Check submitSync() Behavior**
   - Does `submitSync()` wait for batch completion?
   - Does it trigger immediate dispatch?
   - Need to verify Vortex documentation

4. **Check BatcherConfig**
   - Verify `batchSize(50)` is actually applied
   - Check if there's a default that overrides it

## Potential Fixes

### Fix 1: Increase Linger Time
If items arrive slowly, increase linger time to allow accumulation:
```java
.lingerTime(Duration.ofMillis(100))  // Increase from 50ms to 100ms
```

### Fix 2: Use submit() Instead of submitSync()
If `submitSync()` causes immediate dispatch, use `submit()` instead:
```java
// Instead of submitSync()
CompletableFuture<ItemResult<TestInsert>> future = batcher.submit(testInsert);
// Check if rejected immediately
if (future.isDone()) {
    ItemResult<TestInsert> result = future.get();
    if (result instanceof ItemResult.Failure) {
        return TaskResult.failure(result.error());
    }
}
// Otherwise, return success (item queued)
return TaskResult.success();
```

### Fix 3: Verify Batch Size Configuration
Add logging to verify batch size is applied:
```java
log.info("BatcherConfig: batchSize={}, lingerTime={}ms", 
    config.batchSize(), config.lingerTime().toMillis());
```

## Expected Behavior

With proper batching:
- Items accumulate in queue
- Batch dispatches when:
  - 50 items accumulated (size-based), OR
  - 50ms elapsed (time-based)
- Ratio: ~50 requests per batch

## Current Behavior

- Every item dispatched immediately (1 item per batch)
- No accumulation happening
- Queue depth likely stays at 0-1

## Next Steps

1. Add logging to verify batch size configuration
2. Check Vortex metrics to see batch/request ratio
3. Investigate `submitSync()` behavior
4. Consider using `submit()` instead if `submitSync()` causes immediate dispatch

