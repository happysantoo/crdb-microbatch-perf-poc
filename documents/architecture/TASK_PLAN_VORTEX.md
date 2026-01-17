# Vortex Enhancement Task Plan

## Overview

This document provides a detailed implementation plan for adding synchronous submission API to Vortex's `MicroBatcher` to enable immediate rejection visibility for load testing frameworks.

**Target Version:** 0.0.5  
**Estimated Effort:** 1-2 weeks  
**Priority:** High

## Goals

1. **Add Synchronous Submission API** - `submitSync()` method that returns immediate result
2. **Maintain Backward Compatibility** - Existing `submitWithCallback()` API continues to work
3. **Support Both Immediate and Eventual Results** - Enable hybrid approach for complete failure visibility

## Current State Analysis

### Current MicroBatcher Behavior

**Current APIs:**
- `submit(T item)` - Returns `CompletableFuture<BatchResult<T>>` (async)
- `submitWithCallback(T item, ItemCallback<T> callback)` - Async with callback

**Problems:**
1. Rejections happen asynchronously - not immediately visible
2. Load patterns can't react to rejections synchronously
3. No way to check immediate rejection status

## Task Breakdown

### Task 1: Add submitSync() Method

**Priority:** Critical  
**Estimated Effort:** 2-3 days

#### Subtasks

1.1. **Add submitSync() Method Signature**
- Add new public method `submitSync(T item)` that returns `ItemResult<T>`
- Method should be synchronous (returns immediately)

**Files to Modify:**
- `com/vajrapulse/vortex/MicroBatcher.java`

**Code Changes:**
```java
/**
 * Synchronously submits an item, returning result immediately.
 * 
 * <p>This method checks backpressure and queue capacity synchronously,
 * returning an immediate result. If the item is accepted, it is queued
 * for batch processing. If rejected, the rejection is returned immediately.
 * 
 * <p>Use this method when you need immediate visibility of rejections
 * (e.g., for load testing frameworks that need to track failures synchronously).
 * 
 * <p>For eventual batch processing results, use {@link #submitWithCallback(Object, ItemCallback)}
 * with a callback to track when the batch actually processes.
 * 
 * @param item the item to submit
 * @return ItemResult indicating success (queued) or failure (rejected)
 * @throws IllegalStateException if batcher is closed
 */
public ItemResult<T> submitSync(T item) {
    if (closed) {
        throw new IllegalStateException("MicroBatcher is closed");
    }
    
    // Check backpressure synchronously
    if (backpressureProvider != null && backpressureStrategy != null) {
        double backpressure = backpressureProvider.getBackpressureLevel();
        BackpressureContext<T> context = new BackpressureContext<>(
            item, backpressure, backpressureProvider
        );
        BackpressureResult<T> result = backpressureStrategy.handle(context);
        
        if (result.action() == BackpressureAction.REJECT) {
            // Reject immediately
            return ItemResult.failure(result.reason());
        }
    }
    
    // Check queue capacity
    int currentQueueSize = getQueueDepth();
    if (currentQueueSize >= maxQueueSize) {
        return ItemResult.failure(new RejectionException(
            "Queue full: " + currentQueueSize + "/" + maxQueueSize
        ));
    }
    
    // Accept and queue
    queue.offer(item);
    return ItemResult.success(item);
}
```

1.2. **Update submitWithCallback() to Use submitSync()**
- Refactor `submitWithCallback()` to use `submitSync()` internally
- Maintain backward compatibility

**Code Changes:**
```java
/**
 * Asynchronously submits an item with callback for batch result.
 * 
 * <p>This method uses {@link #submitSync(Object)} internally to check
 * immediate rejection. If rejected, the callback is invoked immediately
 * with the rejection. If accepted, the callback will be invoked when
 * the batch is processed.
 * 
 * @param item the item to submit
 * @param callback the callback to invoke when batch processes or immediately if rejected
 */
public void submitWithCallback(T item, ItemCallback<T> callback) {
    // Check immediate rejection using submitSync
    ItemResult<T> immediateResult = submitSync(item);
    
    if (immediateResult instanceof ItemResult.Failure<T> failure) {
        // Immediate rejection - invoke callback immediately
        callback.onComplete(item, failure);
        return;
    }
    
    // Item accepted and queued - callback will fire when batch processes
    pendingCallbacks.put(item, callback);
}
```

1.3. **Add getQueueDepth() Helper Method**
- Add method to get current queue depth
- Used by `submitSync()` to check queue capacity

**Code Changes:**
```java
/**
 * Gets the current queue depth.
 * 
 * @return the number of items currently in the queue
 */
public int getQueueDepth() {
    return queue.size();
}
```

#### Acceptance Criteria

- [ ] `submitSync()` method added to `MicroBatcher`
- [ ] `submitSync()` returns `ItemResult<T>` immediately
- [ ] `submitSync()` checks backpressure synchronously
- [ ] `submitSync()` checks queue capacity synchronously
- [ ] `submitSync()` returns rejection immediately if backpressure/queue full
- [ ] `submitSync()` returns success immediately if accepted
- [ ] `submitWithCallback()` uses `submitSync()` internally
- [ ] `submitWithCallback()` maintains backward compatibility
- [ ] All existing tests pass
- [ ] New tests added for `submitSync()`

#### Testing Requirements

**Unit Tests:**
- Test `submitSync()` returns success when item accepted
- Test `submitSync()` returns failure when backpressure high
- Test `submitSync()` returns failure when queue full
- Test `submitSync()` throws exception when batcher closed
- Test `submitWithCallback()` uses `submitSync()` internally
- Test `submitWithCallback()` callback invoked immediately on rejection
- Test `submitWithCallback()` callback invoked later on batch completion

**Integration Tests:**
- Test `submitSync()` with backpressure provider
- Test `submitSync()` with queue depth limits
- Test `submitSync()` with reject strategy
- Test hybrid approach: `submitSync()` + `submitWithCallback()`

### Task 2: Update Diagnostics to Include Queue Depth

**Priority:** Medium  
**Estimated Effort:** 1 day

#### Subtasks

2.1. **Update Diagnostics Interface**
- Ensure `diagnostics()` method includes queue depth
- Make queue depth easily accessible

**Code Changes:**
```java
// Diagnostics already includes queue depth, just ensure it's accessible
public Diagnostics diagnostics() {
    return new Diagnostics(
        getQueueDepth(),  // Make sure this is included
        // ... other diagnostics ...
    );
}
```

#### Acceptance Criteria

- [ ] `diagnostics()` includes queue depth
- [ ] Queue depth is easily accessible
- [ ] All existing tests pass

### Task 3: Documentation and Examples

**Priority:** Medium  
**Estimated Effort:** 1 day

#### Subtasks

3.1. **Update JavaDoc**
- Complete JavaDoc for `submitSync()` method
- Update JavaDoc for `submitWithCallback()` to mention `submitSync()`
- Document hybrid approach

3.2. **Add Usage Examples**
- Example: Using `submitSync()` for immediate rejection visibility
- Example: Hybrid approach (`submitSync()` + `submitWithCallback()`)
- Example: Migration from `submitWithCallback()` to `submitSync()`

3.3. **Update README**
- Document new `submitSync()` API
- Explain when to use `submitSync()` vs `submitWithCallback()`
- Document hybrid approach

#### Acceptance Criteria

- [ ] JavaDoc complete (no warnings)
- [ ] Usage examples added
- [ ] README updated
- [ ] CHANGELOG.md updated

## Implementation Details

### Code Structure

**Files to Modify:**
1. `com/vajrapulse/vortex/MicroBatcher.java`
   - Add `submitSync()` method
   - Update `submitWithCallback()` to use `submitSync()`
   - Add `getQueueDepth()` helper method

**New Methods:**
- `submitSync(T item)` - Synchronous submission with immediate result
- `getQueueDepth()` - Get current queue depth

**Modified Methods:**
- `submitWithCallback(T item, ItemCallback<T> callback)` - Refactored to use `submitSync()`

### Dependencies

**No New Dependencies Required**
- Uses existing `BackpressureProvider` and `BackpressureStrategy`
- No external libraries needed

### Backward Compatibility

**Breaking Changes:**
- ❌ None - All changes are additive

**Deprecations:**
- None

**Migration:**
- Existing code continues to work
- `submitWithCallback()` behavior unchanged
- New `submitSync()` API available for immediate rejection visibility

## Testing Strategy

### Unit Tests

**Test Coverage Requirements:**
- ≥90% code coverage (project requirement)
- All new methods must have tests
- All rejection scenarios must be tested

**Key Test Cases:**
1. `submitSync()` success path
2. `submitSync()` rejection path (backpressure)
3. `submitSync()` rejection path (queue full)
4. `submitSync()` exception path (closed)
5. `submitWithCallback()` with immediate rejection
6. `submitWithCallback()` with eventual batch result

### Integration Tests

**Test Scenarios:**
1. `submitSync()` with backpressure provider
2. `submitSync()` with queue depth limits
3. Hybrid approach: `submitSync()` + `submitWithCallback()`
4. Performance: `submitSync()` overhead

### Performance Tests

**Requirements:**
- `submitSync()` overhead < 1% of total submission time
- No performance regression in `submitWithCallback()`
- Queue depth check is O(1)

## Documentation Requirements

### JavaDoc

**Required JavaDoc:**
- `submitSync()` method (complete)
- `getQueueDepth()` method
- Updated `submitWithCallback()` JavaDoc

**Example:**
```java
/**
 * Synchronously submits an item, returning result immediately.
 * 
 * <p>This method checks backpressure and queue capacity synchronously,
 * returning an immediate result. If the item is accepted, it is queued
 * for batch processing. If rejected, the rejection is returned immediately.
 * 
 * <p>Use this method when you need immediate visibility of rejections
 * (e.g., for load testing frameworks that need to track failures synchronously).
 * 
 * <p>For eventual batch processing results, use {@link #submitWithCallback(Object, ItemCallback)}
 * with a callback to track when the batch actually processes.
 * 
 * <p>Example usage:
 * <pre>{@code
 * ItemResult<Item> result = batcher.submitSync(item);
 * if (result instanceof ItemResult.Failure<Item> failure) {
 *     // Immediate rejection - handle immediately
 *     handleRejection(failure.error());
 * } else {
 *     // Item accepted and queued
 *     // Use submitWithCallback() to track eventual batch result
 *     batcher.submitWithCallback(item, callback);
 * }
 * }</pre>
 * 
 * @param item the item to submit
 * @return ItemResult indicating success (queued) or failure (rejected)
 * @throws IllegalStateException if batcher is closed
 * @since 0.0.5
 */
public ItemResult<T> submitSync(T item) {
    // ...
}
```

### User Documentation

**Update:**
- `README.md` - Document `submitSync()` API
- `CHANGELOG.md` - Document changes
- Examples - Add examples for `submitSync()` and hybrid approach

## Release Checklist

### Pre-Release

- [ ] All tests pass (unit, integration, performance)
- [ ] Code coverage ≥90%
- [ ] Static analysis passes (SpotBugs)
- [ ] JavaDoc complete (no warnings)
- [ ] Documentation updated
- [ ] Examples updated
- [ ] CHANGELOG.md updated

### Release

- [ ] Version bumped to 0.0.5
- [ ] Tagged in git
- [ ] Published to Maven Central
- [ ] Release notes published

### Post-Release

- [ ] Monitor for issues
- [ ] Collect feedback
- [ ] Plan next iteration

## Success Criteria

### Functional

- [ ] `submitSync()` returns immediate result (success or rejection)
- [ ] `submitSync()` checks backpressure synchronously
- [ ] `submitSync()` checks queue capacity synchronously
- [ ] `submitWithCallback()` maintains backward compatibility
- [ ] Hybrid approach works (immediate rejection + eventual batch result)

### Non-Functional

- [ ] No performance regression
- [ ] Backward compatible (existing code works)
- [ ] Code coverage ≥90%
- [ ] All tests pass
- [ ] Documentation complete

## Risk Assessment

### Risk 1: Performance Impact

**Risk:** Synchronous backpressure check adds overhead

**Mitigation:**
- Backpressure check is simple (just method call)
- Measure performance before/after
- Optimize if needed

### Risk 2: Breaking Existing Code

**Risk:** Changes to `submitWithCallback()` might break existing code

**Mitigation:**
- Maintain backward compatibility
- Test all existing use cases
- Document migration path

### Risk 3: Queue Depth Check Thread Safety

**Risk:** `queue.size()` might not be thread-safe

**Mitigation:**
- Use thread-safe queue implementation
- Test concurrent access
- Add synchronization if needed

## Timeline

**Week 1:**
- Day 1-3: Task 1 (submitSync() method)
- Day 4: Task 2 (Diagnostics update)

**Week 2:**
- Day 1: Task 3 (Documentation)
- Day 2-3: Testing
- Day 4-5: Release prep

**Total:** 1-2 weeks

## Dependencies

**Blockers:**
- None

**Dependencies:**
- None (self-contained changes)

**Blocks:**
- Testing project integration (waits for this)
- Can proceed in parallel with VajraPulse 0.9.7

