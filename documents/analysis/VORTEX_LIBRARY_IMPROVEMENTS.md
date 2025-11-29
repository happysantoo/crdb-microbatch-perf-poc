# Vortex Library Improvement Suggestions

## Overview

This document outlines potential improvements to the Vortex microbatching library (`com.vajrapulse:vortex:0.0.1`) based on our experience integrating it with CockroachDB batch inserts. These suggestions aim to make the library easier to use, more robust, and better aligned with common use cases.

## 1. API Improvements

### 1.1 Item Result Tracking

**Current Issue:**
When a batch completes, the `BatchResult` contains all items in the batch, but there's no direct way to find a specific item's result without iterating through successes/failures.

**Suggestion:**
Add a method to `BatchResult` to find a specific item's result:

```java
public class BatchResult<T> {
    // ... existing code ...
    
    /**
     * Finds the result for a specific item.
     * 
     * @param item the item to find
     * @param equalityComparator optional comparator for item equality
     * @return Optional containing SuccessEvent or FailureEvent for the item
     */
    public Optional<ItemResult<T>> findItemResult(T item, 
                                                   BiPredicate<T, T> equalityComparator) {
        // Check successes first
        for (SuccessEvent<T> success : successes) {
            if (equalityComparator.test(success.getData(), item)) {
                return Optional.of(ItemResult.success(success));
            }
        }
        // Check failures
        for (FailureEvent<T> failure : failures) {
            if (equalityComparator.test(failure.getData(), item)) {
                return Optional.of(ItemResult.failure(failure));
            }
        }
        return Optional.empty();
    }
}
```

**Benefit:** Eliminates the need for manual iteration in application code.

### 1.2 Sealed ItemResult Type

**Suggestion:**
Introduce a sealed interface for item results:

```java
public sealed interface ItemResult<T> 
    permits ItemResult.Success<T>, ItemResult.Failure<T> {
    
    T getItem();
    
    record Success<T>(T item) implements ItemResult<T> {
        @Override
        public T getItem() { return item; }
    }
    
    record Failure<T>(T item, Throwable error) implements ItemResult<T> {
        @Override
        public T getItem() { return item; }
    }
}
```

**Benefit:** Type-safe result handling with pattern matching support.

### 1.3 Batch Completion Callbacks

**Current Issue:**
The library returns `CompletableFuture<BatchResult<T>>`, but there's no way to register callbacks that execute when a specific item's batch completes.

**Suggestion:**
Add a method to submit with a callback:

```java
public class MicroBatcher<T> {
    // ... existing code ...
    
    /**
     * Submits an item and registers a callback for when its batch completes.
     * 
     * @param item the item to submit
     * @param callback callback to execute when batch completes
     * @return CompletableFuture that completes when the callback finishes
     */
    public CompletableFuture<Void> submitWithCallback(
            T item, 
            BiConsumer<T, ItemResult<T>> callback) {
        CompletableFuture<BatchResult<T>> future = submit(item);
        return future.thenAccept(result -> {
            ItemResult<T> itemResult = result.findItemResult(item, 
                (a, b) -> Objects.equals(a, b)).orElseThrow();
            callback.accept(item, itemResult);
        });
    }
}
```

**Benefit:** Cleaner callback-based API for async operations.

## 2. Error Handling Improvements

### 2.1 Partial Batch Failure Details

**Current Issue:**
When a batch has partial failures, it's not immediately clear which items failed and why without iterating through failures.

**Suggestion:**
Add convenience methods to `BatchResult`:

```java
public class BatchResult<T> {
    // ... existing code ...
    
    /**
     * Returns true if all items in the batch succeeded.
     */
    public boolean isCompleteSuccess() {
        return failures.isEmpty();
    }
    
    /**
     * Returns true if all items in the batch failed.
     */
    public boolean isCompleteFailure() {
        return successes.isEmpty();
    }
    
    /**
     * Returns the failure rate (0.0 to 1.0).
     */
    public double getFailureRate() {
        int total = successes.size() + failures.size();
        return total == 0 ? 0.0 : (double) failures.size() / total;
    }
    
    /**
     * Gets a summary of failures grouped by error type.
     */
    public Map<Class<? extends Throwable>, List<FailureEvent<T>>> getFailuresByType() {
        return failures.stream()
            .collect(Collectors.groupingBy(
                f -> f.getError().getClass().asSubclass(Throwable.class)));
    }
}
```

**Benefit:** Easier error analysis and debugging.

### 2.2 Retry Support

**Suggestion:**
Add built-in retry support for failed items:

```java
public class BatcherConfig {
    // ... existing code ...
    
    /**
     * Maximum number of retries for failed items.
     */
    private int maxRetries = 0;
    
    /**
     * Retry delay between retry attempts.
     */
    private Duration retryDelay = Duration.ZERO;
    
    /**
     * Predicate to determine if an error is retryable.
     */
    private Predicate<Throwable> retryableErrorPredicate = t -> false;
}
```

**Benefit:** Automatic retry handling for transient failures.

## 3. Metrics Improvements

### 3.1 Per-Item Metrics

**Current Issue:**
Metrics are aggregated at the batch level, making it difficult to track individual item performance.

**Suggestion:**
Add optional per-item metrics:

```java
public class MicroBatcher<T> {
    // ... existing code ...
    
    /**
     * Enables per-item metrics tracking.
     * 
     * @param enabled true to enable per-item metrics
     * @return this builder
     */
    public MicroBatcher<T> withPerItemMetrics(boolean enabled) {
        this.perItemMetrics = enabled;
        return this;
    }
}
```

**Metrics to Add:**
- `vortex.item.submit.latency` - Time from submit to batch completion (per item)
- `vortex.item.wait.time` - Time item waits in queue before batching
- `vortex.item.batch.size` - Size of batch when item was processed

**Benefit:** More granular performance insights.

### 3.2 Batch Size Distribution Metrics

**Suggestion:**
Add histogram metrics for batch sizes:

```java
// Metrics to add:
// - vortex.batch.size.histogram - Distribution of batch sizes
// - vortex.batch.size.avg - Average batch size
// - vortex.batch.size.min - Minimum batch size
// - vortex.batch.size.max - Maximum batch size
```

**Benefit:** Better understanding of batching efficiency.

### 3.3 Queue Depth Metrics

**Current Issue:**
Queue depth is exposed as a gauge, but there's no information about queue wait times.

**Suggestion:**
Add queue wait time metrics:

```java
// Metrics to add:
// - vortex.queue.wait.time.histogram - Distribution of queue wait times
// - vortex.queue.wait.time.p50 - 50th percentile wait time
// - vortex.queue.wait.time.p95 - 95th percentile wait time
// - vortex.queue.wait.time.p99 - 99th percentile wait time
```

**Benefit:** Better visibility into queue behavior.

## 4. Configuration Improvements

### 4.1 Dynamic Configuration

**Current Issue:**
Configuration is set at creation time and cannot be changed.

**Suggestion:**
Add support for dynamic configuration updates:

```java
public class MicroBatcher<T> {
    // ... existing code ...
    
    /**
     * Updates the batch size dynamically.
     * 
     * @param newBatchSize the new batch size
     */
    public void updateBatchSize(int newBatchSize) {
        // Update configuration and notify batcher
    }
    
    /**
     * Updates the linger time dynamically.
     * 
     * @param newLingerTime the new linger time
     */
    public void updateLingerTime(Duration newLingerTime) {
        // Update configuration and notify batcher
    }
}
```

**Benefit:** Runtime tuning without restart.

### 4.2 Adaptive Batching

**Suggestion:**
Add adaptive batching that adjusts batch size based on load:

```java
public class BatcherConfig {
    // ... existing code ...
    
    /**
     * Enables adaptive batching.
     * 
     * @param enabled true to enable adaptive batching
     * @param minBatchSize minimum batch size
     * @param maxBatchSize maximum batch size
     * @param targetLatency target latency for batches
     */
    public BatcherConfig withAdaptiveBatching(
            boolean enabled,
            int minBatchSize,
            int maxBatchSize,
            Duration targetLatency) {
        // Configure adaptive batching
    }
}
```

**Benefit:** Automatic optimization based on system load.

## 5. Testing and Debugging Improvements

### 5.1 Test Utilities

**Suggestion:**
Add test utilities for easier testing:

```java
public class MicroBatcherTestUtils {
    /**
     * Creates a test backend that records all batches.
     */
    public static <T> TestBackend<T> createTestBackend() {
        return new TestBackend<>();
    }
    
    /**
     * Waits for all pending batches to complete.
     */
    public static <T> void waitForBatches(MicroBatcher<T> batcher) {
        // Wait for all batches to complete
    }
}
```

**Benefit:** Easier unit testing and integration testing.

### 5.2 Debug Mode

**Suggestion:**
Add debug mode with detailed logging:

```java
public class BatcherConfig {
    // ... existing code ...
    
    /**
     * Enables debug mode with detailed logging.
     * 
     * @param enabled true to enable debug mode
     */
    public BatcherConfig withDebugMode(boolean enabled) {
        this.debugMode = enabled;
        return this;
    }
}
```

**Debug Information:**
- Batch formation events
- Item submission events
- Batch dispatch events
- Queue depth changes
- Timing information

**Benefit:** Easier troubleshooting and performance analysis.

## 6. Documentation Improvements

### 6.1 Usage Examples

**Suggestion:**
Add comprehensive usage examples:

- Basic usage
- Error handling
- Metrics integration
- Testing examples
- Performance tuning guide

**Benefit:** Faster onboarding for new users.

### 6.2 Best Practices Guide

**Suggestion:**
Add a best practices guide covering:

- When to use microbatching
- Optimal batch sizes
- Linger time tuning
- Error handling strategies
- Performance optimization

**Benefit:** Better adoption and optimal usage.

## 7. Integration Improvements

### 7.1 Spring Boot Auto-Configuration

**Suggestion:**
Add Spring Boot starter for automatic configuration:

```java
@Configuration
@ConditionalOnClass(MicroBatcher.class)
@EnableConfigurationProperties(VortexProperties.class)
public class VortexAutoConfiguration {
    // Auto-configure MicroBatcher beans
}
```

**Benefit:** Easier Spring Boot integration.

### 7.2 Micrometer Integration Enhancement

**Suggestion:**
Improve Micrometer integration:

- Support for custom tags
- Support for multiple MeterRegistry instances
- Better metric naming conventions
- Support for metric filtering

**Benefit:** Better observability integration.

## Priority Recommendations

### High Priority
1. **Item Result Tracking** (Section 1.1) - Eliminates manual iteration
2. **Error Handling Improvements** (Section 2.1) - Better debugging
3. **Queue Wait Time Metrics** (Section 3.3) - Better visibility

### Medium Priority
4. **Batch Completion Callbacks** (Section 1.3) - Cleaner API
5. **Per-Item Metrics** (Section 3.1) - Better insights
6. **Test Utilities** (Section 5.1) - Easier testing

### Low Priority
7. **Dynamic Configuration** (Section 4.1) - Runtime tuning
8. **Adaptive Batching** (Section 4.2) - Automatic optimization
9. **Spring Boot Auto-Configuration** (Section 7.1) - Easier integration

## Conclusion

These improvements would make the Vortex library more user-friendly, robust, and feature-rich. The highest priority items address immediate pain points we encountered during integration, while lower priority items would enhance the library's capabilities for advanced use cases.

