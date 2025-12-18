# Vortex Library Changes: Design Document
## For Vortex Developers

**Date:** 2025-12-05  
**Status:** Design Document  
**Target Version:** 0.0.6 (or next release)  
**Priority:** Low (Documentation Only)

---

## Executive Summary

This document outlines the changes needed in Vortex to support continuous adaptive load testing. **Good news:** Most of what we need already exists! The changes are primarily **documentation improvements** to clarify behavior, with **optional enhancements** for better integration.

**Key Finding:** Vortex's `QueueDepthBackpressureProvider` is exactly what we need for adaptive load testing. No code changes required - just clearer documentation.

---

## Problem Statement

### Current State

Vortex 0.0.5 already provides:
- ✅ `QueueDepthBackpressureProvider` - Perfect for adaptive load testing
- ✅ `submitSync()` - Immediate rejection on backpressure
- ✅ `submitWithCallback()` - Async batch processing results
- ✅ Backpressure integration - Works with `RejectStrategy`

### Documentation Gaps

1. **Unclear submitSync() Behavior**
   - When does it reject vs. accept?
   - Does it wait for batch processing?
   - What does the result mean?

2. **Unclear Callback Timing**
   - When does callback fire?
   - Immediate rejection vs. batch processing?
   - How to handle both cases?

3. **Backpressure Provider Usage**
   - How to use `QueueDepthBackpressureProvider` with AdaptiveLoadPattern?
   - What threshold should be used?
   - How does it relate to `RejectStrategy`?

---

## Proposed Changes

### Change 1: Clarify submitSync() Documentation (Priority: High)

**Current State:** Documentation may not clearly explain behavior

**Proposed:** Enhanced JavaDoc with clear examples

**Implementation:**
```java
/**
 * Submits an item synchronously and returns immediately with the result.
 * 
 * <p>This method checks backpressure <strong>before</strong> queuing the item.
 * The result indicates whether the item was accepted or rejected:
 * <ul>
 *   <li><strong>SUCCESS</strong>: Item was accepted and queued successfully.
 *       The item will be processed in a batch later (via batch processing).</li>
 *   <li><strong>REJECTED</strong>: Item was rejected due to backpressure
 *       (e.g., queue is full). The item will NOT be processed.</li>
 * </ul>
 * 
 * <p><strong>Important Notes:</strong>
 * <ul>
 *   <li>This method does <strong>NOT</strong> wait for batch processing.
 *       It only checks if the item can be queued.</li>
 *   <li>If you need to know the batch processing result (success/failure),
 *       use {@link #submitWithCallback(Object, BiConsumer)} instead.</li>
 *   <li>Rejections happen immediately based on backpressure threshold,
 *       not based on batch processing capacity.</li>
 * </ul>
 * 
 * <p><strong>Example Usage:</strong>
 * <pre>{@code
 * ItemResult<MyItem> result = batcher.submitSync(item);
 * 
 * if (result instanceof ItemResult.Success<MyItem>) {
 *     // Item queued successfully - will be processed in batch later
 *     // Use submitWithCallback() if you need batch processing result
 * } else if (result instanceof ItemResult.Failure<MyItem> failure) {
 *     // Item rejected due to backpressure
 *     // Handle rejection (retry, log, etc.)
 *     log.warn("Item rejected: {}", failure.error().getMessage());
 * }
 * }</pre>
 * 
 * @param item the item to submit
 * @return result indicating acceptance (SUCCESS) or rejection (FAILURE)
 * @throws NullPointerException if item is null
 * @since 0.0.5
 */
ItemResult<T> submitSync(T item);
```

**Success Criteria:**
- [ ] JavaDoc clearly explains immediate rejection behavior
- [ ] Examples show proper usage
- [ ] Distinction from `submitWithCallback()` is clear
- [ ] Backpressure behavior is documented

---

### Change 2: Clarify submitWithCallback() Documentation (Priority: High)

**Current State:** Documentation may not clearly explain callback timing

**Proposed:** Enhanced JavaDoc with timing details

**Implementation:**
```java
/**
 * Submits an item with a callback that fires when batch processing completes.
 * 
 * <p>This method accepts the item and queues it for batch processing.
 * The callback will be invoked with the result when:
 * <ul>
 *   <li><strong>Immediate Rejection</strong>: If the item is rejected due to
 *       backpressure (queue full), the callback fires immediately with a
 *       {@link ItemResult.Failure} containing a {@link BackpressureException}.</li>
 *   <li><strong>Batch Processing</strong>: If the item is accepted, the callback
 *       fires when the batch containing this item is processed (typically 10-50ms
 *       after submission, depending on batch size and linger time).</li>
 * </ul>
 * 
 * <p><strong>Callback Timing:</strong>
 * <ul>
 *   <li><strong>Immediate</strong>: If item rejected (backpressure >= threshold)</li>
 *   <li><strong>After Batch Processing</strong>: If item accepted (typically 10-50ms)</li>
 * </ul>
 * 
 * <p><strong>Important Notes:</strong>
 * <ul>
 *   <li>The callback may fire on a different thread (batch processing thread).</li>
 *   <li>If you need immediate rejection feedback, use {@link #submitSync(Object)}
 *       first to check if item will be rejected.</li>
 *   <li>For load testing frameworks, you may want to use `submitSync()` to get
 *       immediate rejection feedback, then use `submitWithCallback()` for
 *       tracking batch processing results.</li>
 * </ul>
 * 
 * <p><strong>Example Usage:</strong>
 * <pre>{@code
 * batcher.submitWithCallback(item, (submittedItem, result) -> {
 *     if (result instanceof ItemResult.Success<MyItem>) {
 *         // Item processed successfully in batch
 *         successCounter.increment();
 *     } else if (result instanceof ItemResult.Failure<MyItem> failure) {
 *         // Item failed (either rejected or batch processing failed)
 *         if (failure.error() instanceof BackpressureException) {
 *             // Immediate rejection due to backpressure
 *             rejectionCounter.increment();
 *         } else {
 *             // Batch processing failure
 *             failureCounter.increment();
 *         }
 *     }
 * });
 * }</pre>
 * 
 * @param item the item to submit
 * @param callback callback that receives the item and result when processing completes
 * @throws NullPointerException if item or callback is null
 * @since 0.0.5
 */
void submitWithCallback(T item, BiConsumer<T, ItemResult<T>> callback);
```

**Success Criteria:**
- [ ] JavaDoc clearly explains callback timing
- [ ] Examples show both rejection and batch processing cases
- [ ] Thread safety is documented
- [ ] Integration with load testing frameworks is explained

---

### Change 3: Document QueueDepthBackpressureProvider Usage (Priority: Medium)

**Current State:** May not have clear usage examples for AdaptiveLoadPattern integration

**Proposed:** Add usage guide and examples

**Implementation:**
```java
/**
 * Backpressure provider that monitors the MicroBatcher's internal queue depth.
 * 
 * <p>This provider calculates backpressure based on queue utilization:
 * <ul>
 *   <li><strong>0.0</strong>: Queue is empty (no backpressure)</li>
 *   <li><strong>0.5</strong>: Queue is 50% full (moderate backpressure)</li>
 *   <li><strong>1.0</strong>: Queue is full (severe backpressure)</li>
 * </ul>
 * 
 * <p><strong>Backpressure Calculation:</strong>
 * <pre>{@code
 * backpressure = queueDepth / maxQueueSize
 * }</pre>
 * 
 * <p><strong>Usage with AdaptiveLoadPattern:</strong>
 * <pre>{@code
 * // Create queue depth supplier
 * Supplier<Integer> queueDepthSupplier = () -> batcher.getQueueDepth();
 * 
 * // Create backpressure provider
 * BackpressureProvider backpressureProvider = new QueueDepthBackpressureProvider(
 *     queueDepthSupplier,
 *     maxQueueSize  // e.g., 1000 items
 * );
 * 
 * // Use in AdaptiveLoadPattern
 * AdaptiveLoadPattern pattern = new AdaptiveLoadPattern(
 *     initialTps,
 *     rampIncrement,
 *     rampDecrement,
 *     rampInterval,
 *     maxTps,
 *     sustainDuration,
 *     errorThreshold,
 *     metricsProvider,
 *     backpressureProvider  // Queue-only backpressure
 * );
 * }</pre>
 * 
 * <p><strong>Relationship to RejectStrategy:</strong>
 * <ul>
 *   <li><strong>QueueDepthBackpressureProvider</strong>: Used by AdaptiveLoadPattern
 *       to adjust TPS gradually (every 5 seconds).</li>
 *   <li><strong>RejectStrategy</strong>: Used by MicroBatcher to reject items
 *       immediately when backpressure >= threshold (e.g., 0.7).</li>
 *   <li>Both use the same backpressure signal, but for different purposes:
 *       gradual TPS adjustment vs. immediate rejection.</li>
 * </ul>
 * 
 * <p><strong>Recommended Configuration:</strong>
 * <ul>
 *   <li><strong>Max Queue Size</strong>: 20-50 batches worth of items
 *       (e.g., 20 batches × 50 items = 1000 items)</li>
 *   <li><strong>RejectStrategy Threshold</strong>: 0.7 (70% capacity)
 *       - Rejects items when queue > 70% full</li>
 *   <li><strong>AdaptiveLoadPattern Threshold</strong>: 0.7 (70% capacity)
 *       - Ramps down TPS when backpressure >= 0.7</li>
 * </ul>
 * 
 * @param queueDepthSupplier supplier that provides current queue depth
 * @param maxQueueSize maximum queue size (used for normalization)
 * @since 0.0.5
 */
public class QueueDepthBackpressureProvider implements BackpressureProvider {
    // ... existing implementation ...
}
```

**Success Criteria:**
- [ ] JavaDoc explains backpressure calculation
- [ ] Examples show AdaptiveLoadPattern integration
- [ ] Relationship to RejectStrategy is documented
- [ ] Recommended configuration is provided

---

### Change 4: Optional Enhancement - Expose Queue Depth (Priority: Low)

**Current State:** Queue depth may not be easily accessible

**Proposed:** Add method to get current queue depth (if not already available)

**Implementation:**
```java
/**
 * Gets the current number of items in the queue waiting for batch processing.
 * 
 * <p>This method is useful for:
 * <ul>
 *   <li>Monitoring queue depth for metrics/dashboards</li>
 *   <li>Creating QueueDepthBackpressureProvider</li>
 *   <li>Debugging and troubleshooting</li>
 * </ul>
 * 
 * <p><strong>Note:</strong> This is a snapshot of the queue depth at the time
 * of the call. The actual queue depth may change immediately after this call
 * returns due to concurrent submissions and batch processing.
 * 
 * @return current queue depth (number of items waiting)
 * @since 0.0.6
 */
int getQueueDepth();
```

**Success Criteria:**
- [ ] Method added to MicroBatcher interface
- [ ] JavaDoc explains usage
- [ ] Thread-safe implementation
- [ ] Unit tests added

**Note:** This is optional - if queue depth is already accessible via supplier pattern, this may not be needed.

---

## API Changes Summary

### Documentation Changes (Required)

1. **Enhanced submitSync() JavaDoc**
   - Clear explanation of immediate rejection
   - Examples showing usage
   - Distinction from submitWithCallback()

2. **Enhanced submitWithCallback() JavaDoc**
   - Clear explanation of callback timing
   - Examples showing both cases
   - Thread safety documentation

3. **Enhanced QueueDepthBackpressureProvider JavaDoc**
   - Usage examples for AdaptiveLoadPattern
   - Relationship to RejectStrategy
   - Recommended configuration

### Optional Code Changes (Low Priority)

1. **Add getQueueDepth() method** (if not already available)
   - Convenience method for accessing queue depth
   - Useful for monitoring and metrics

### No Breaking Changes

- All changes are documentation or optional enhancements
- Existing code continues to work
- No API changes required

---

## Implementation Details

### Documentation Structure

**For submitSync():**
1. Clear statement: "Returns immediately, doesn't wait for batch processing"
2. Result meanings: SUCCESS = queued, REJECTED = backpressure
3. When to use: Immediate rejection feedback needed
4. Example: Load testing framework integration

**For submitWithCallback():**
1. Callback timing: Immediate (rejection) vs. After batch (processing)
2. Thread safety: Callback may fire on different thread
3. When to use: Need batch processing results
4. Example: Tracking batch-level success/failure

**For QueueDepthBackpressureProvider:**
1. Backpressure calculation: queueDepth / maxQueueSize
2. Integration: How to use with AdaptiveLoadPattern
3. Relationship: How it relates to RejectStrategy
4. Configuration: Recommended thresholds and queue sizes

---

## Testing Requirements

### Documentation Testing

1. **Verify Examples Compile**
   - All code examples in JavaDoc should compile
   - Examples should be runnable (or clearly marked as snippets)

2. **Verify Examples Are Accurate**
   - Examples should reflect actual behavior
   - Test examples against actual implementation

### Optional Code Testing (if getQueueDepth() added)

1. **Unit Tests**
   ```java
   @Test
   void testGetQueueDepth() {
       // Submit items
       batcher.submitSync(item1);
       batcher.submitSync(item2);
       
       // Verify queue depth
       assertEquals(2, batcher.getQueueDepth());
   }
   ```

2. **Thread Safety Tests**
   ```java
   @Test
   void testGetQueueDepthThreadSafe() {
       // Submit from multiple threads
       // Verify queue depth is accurate
   }
   ```

---

## Backward Compatibility

### Guaranteed Compatibility

1. **Documentation Changes**
   - No code changes, only JavaDoc
   - Existing code continues to work
   - No breaking changes

2. **Optional getQueueDepth() Method**
   - New method, doesn't affect existing code
   - Backward compatible addition

---

## Migration Guide

### For Users

**No migration needed!** All changes are documentation or optional enhancements.

**Optional Benefits:**
- Clearer understanding of submitSync() vs. submitWithCallback()
- Better integration examples for AdaptiveLoadPattern
- Easier queue depth monitoring (if getQueueDepth() added)

### For Integrators

**AdaptiveLoadPattern Integration:**
- Use QueueDepthBackpressureProvider (already exists)
- Follow examples in documentation
- Configure queue size and thresholds appropriately

---

## Success Criteria

### Documentation Requirements

- [ ] submitSync() JavaDoc clearly explains behavior
- [ ] submitWithCallback() JavaDoc clearly explains timing
- [ ] QueueDepthBackpressureProvider JavaDoc includes integration examples
- [ ] All examples compile and are accurate
- [ ] Documentation is clear and comprehensive

### Optional Code Requirements (if implemented)

- [ ] getQueueDepth() method added (if not already available)
- [ ] Method is thread-safe
- [ ] Unit tests added
- [ ] JavaDoc added

---

## Timeline

**Estimated Effort:** 0.5-1 day

- **0.5 day:** Documentation updates (required)
- **0.5 day:** Optional getQueueDepth() method (if needed)

**Total:** 0.5-1 day

---

## Questions and Clarifications

### Q1: Is getQueueDepth() already available?
**A:** Check current implementation. If queue depth is accessible via supplier pattern, this may not be needed.

### Q2: Should we add more backpressure providers?
**A:** No, QueueDepthBackpressureProvider is sufficient. Adding more would add complexity without clear benefit.

### Q3: Should we change RejectStrategy behavior?
**A:** No, current behavior is correct. Just need better documentation.

### Q4: Should we add metrics/monitoring hooks?
**A:** Optional enhancement for future. Not required for current use case.

---

## References

- **Current Implementation:** Vortex 0.0.5
- **Related Documents:**
  - `VORTEX_QUEUE_ONLY_BACKPRESSURE_ANALYSIS.md` (why queue-only is sufficient)
  - `COMPLETE_REDESIGN_PRINCIPAL_ENGINEER.md` (overall redesign)
  - `VAJRAPULSE_LIBRARY_CHANGES_DESIGN.md` (VajraPulse changes)

---

**Document Status:** Ready for Implementation  
**Next Steps:** See `VORTEX_LIBRARY_CHANGES_TASKS.md` for detailed task list

