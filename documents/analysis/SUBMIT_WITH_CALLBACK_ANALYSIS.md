# submitWithCallback Analysis

## Current Implementation

### CrdbInsertTask.execute()
- Uses `batcher.submit(item)` → returns `CompletableFuture<BatchResult>`
- Must manually extract item result from batch using `findItemResult()`
- Must handle batch result processing in callback
- Complex logic to find specific item in batch

### LoadTestService
- Doesn't directly interact with batching
- Relies on CrdbInsertTask to handle item results
- No direct visibility into item-by-item processing

## Proposed: submitWithCallback

### Method Signature (Expected)
```java
public CompletableFuture<Void> submitWithCallback(
    T item, 
    BiConsumer<T, ItemResult<T>> callback)
```

### Benefits

1. **Simpler CrdbInsertTask**
   - No need for `findItemResult()` method
   - Callback directly receives item-specific result
   - Cleaner code: submit → callback with item result

2. **Simpler LoadTestService**
   - Could potentially track items directly
   - Better visibility into item processing
   - No need to understand batch internals

3. **Better Separation of Concerns**
   - Task doesn't need to know about batch structure
   - Callback handles item result directly
   - Cleaner abstraction

## Implementation Analysis

### Current Flow
```
Task.execute()
  → batcher.submit(item)
  → CompletableFuture<BatchResult>
  → findItemResult(item, batchResult)  // Manual extraction
  → TaskResult
```

### With submitWithCallback Flow
```
Task.execute()
  → batcher.submitWithCallback(item, callback)
  → Callback receives ItemResult directly
  → Convert ItemResult to TaskResult
  → Return TaskResult
```

### Code Simplification

**Before (Current)**:
```java
CompletableFuture<TaskResult> itemResultFuture = batcher.submit(testInsert)
    .thenApply(batchResult -> {
        TaskResult itemResult = findItemResult(testInsert, batchResult);  // Manual
        // ... metrics
        return itemResult;
    });
```

**After (With submitWithCallback)**:
```java
CompletableFuture<Void> future = batcher.submitWithCallback(testInsert, (item, itemResult) -> {
    // itemResult is already the specific item's result
    TaskResult taskResult = convertItemResult(itemResult);
    // ... metrics
});
```

## LoadTestService Simplification

### Current State
- LoadTestService doesn't directly interact with batching
- All batching logic is in CrdbInsertTask
- Service just orchestrates the test

### Potential Simplifications
1. **Direct Item Tracking**: If we wanted to track items at service level, submitWithCallback makes it easier
2. **Better Metrics**: Could potentially get item-level metrics more directly
3. **Cleaner Architecture**: Less coupling between task and batch internals

### However
- LoadTestService is already simple - it just runs the pipeline
- The complexity is in CrdbInsertTask, not LoadTestService
- submitWithCallback mainly simplifies CrdbInsertTask, not LoadTestService

## Conclusion

**submitWithCallback would simplify:**
- ✅ CrdbInsertTask (eliminates findItemResult, cleaner callback)
- ✅ Item result handling (direct callback vs manual extraction)
- ❌ LoadTestService (already simple, minimal impact)

**Main Benefit**: Cleaner CrdbInsertTask code, not necessarily LoadTestService.

