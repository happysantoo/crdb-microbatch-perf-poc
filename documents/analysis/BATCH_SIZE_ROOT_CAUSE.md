# Batch Size Root Cause Analysis

## Problem

**Every batch contains only 1 item** instead of accumulating to 50 items (configured batch size).

## Expected Behavior

With `batchSize(50)` and `lingerTime(50ms)`:
- Items should accumulate in queue
- Batch dispatches when:
  - **50 items accumulated** (size-based), OR
  - **50ms elapsed** (time-based)
- Ratio: ~50 requests per batch

## Actual Behavior

- **Every item dispatched immediately** (1 item per batch)
- No accumulation happening
- Queue depth likely stays at 0-1

## Root Cause Hypothesis

### Hypothesis 1: submitSync() Triggers Immediate Dispatch

**Theory:** `submitSync()` might be causing Vortex to dispatch items immediately instead of queuing them for batching.

**Evidence:**
- Documentation says `submitSync()` should queue items
- But every item is dispatched immediately
- Suggests `submitSync()` implementation might be wrong

**Test:**
- Check if using `submit()` or `submitWithCallback()` instead of `submitSync()` fixes batching
- If yes, then `submitSync()` is the problem

### Hypothesis 2: Configuration Not Applied

**Theory:** `BatcherConfig.batchSize(50)` might not be respected by Vortex.

**Evidence:**
- Configuration is set correctly in code
- But batches are always 1 item
- Suggests Vortex might be ignoring configuration

**Test:**
- Add logging to verify `config.batchSize()` returns 50
- Check Vortex source code to see if batch size is actually used

### Hypothesis 3: Queue Behavior Issue

**Theory:** Items might be getting dispatched immediately from queue instead of accumulated.

**Evidence:**
- Items are accepted (submitSync returns SUCCESS)
- But dispatched immediately (1 item per batch)
- Suggests queue might be dispatching on every offer()

**Test:**
- Check queue depth - if always 0-1, items are dispatched immediately
- Check Vortex internal queue behavior

## Diagnostic Steps

1. **Add Configuration Logging**
   - Log `config.batchSize()` and `config.lingerTime()` to verify configuration
   - Verify configuration matches expected values

2. **Check Queue Depth**
   - Monitor `vortex.queue.depth` metric
   - If always 0-1, items are dispatched immediately
   - If accumulates, then batching should work

3. **Check Batch/Request Ratio**
   - `vortex.batches.dispatched` vs `vortex.requests.submitted`
   - Should be ~50:1 (50 requests per batch)
   - If ~1:1, items are dispatched immediately

4. **Test with submit() Instead of submitSync()**
   - Try using `batcher.submit()` instead of `submitSync()`
   - See if batching works with async submission
   - If yes, then `submitSync()` is the problem

## Potential Fixes

### Fix 1: Use submit() Instead of submitSync()

If `submitSync()` is causing immediate dispatch, use `submit()` instead:

```java
CompletableFuture<ItemResult<TestInsert>> future = batcher.submit(testInsert);

// Check if rejected immediately
if (future.isDone()) {
    ItemResult<TestInsert> result = future.get();
    if (result instanceof ItemResult.Failure) {
        return TaskResult.failure(result.error());
    }
}

// Item accepted - return success
return TaskResult.success();
```

### Fix 2: Verify Vortex Version

Check if current Vortex version (0.0.5) has batching issues:
- Check Vortex release notes
- Check known issues
- Consider upgrading to 0.0.6 if available

### Fix 3: Increase Linger Time

If items arrive slowly, increase linger time:
```java
.lingerTime(Duration.ofMillis(100))  // Increase from 50ms
```

## Impact

**Critical:** This defeats the entire purpose of batching:
- No efficiency gains (single-row inserts instead of batch inserts)
- Connection pool exhaustion (each item uses a connection)
- Poor performance (50x more database operations than needed)

## Next Steps

1. Add diagnostic logging (already done)
2. Run test and check logs for configuration values
3. Check Vortex metrics for batch/request ratio
4. If `submitSync()` is the problem, switch to `submit()` or `submitWithCallback()`
5. Contact Vortex maintainers if this is a library bug

