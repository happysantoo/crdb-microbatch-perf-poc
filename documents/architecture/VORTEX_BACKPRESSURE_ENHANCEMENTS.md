# Vortex Backpressure - Phase 2 Enhancements

## Overview

This document outlines enhancements planned for **Phase 2 (v0.2.0)** of the Vortex backpressure integration. These features were deferred from Phase 1 to reduce initial complexity and allow the core functionality to be validated in real-world usage first.

## Design Philosophy

Phase 2 follows the principle: **"Add sophistication based on real-world needs"**. Features are added only if:
1. Real-world usage demonstrates a clear need
2. The enhancement provides significant value
3. The complexity is justified by the benefit

## Phase 2 Enhancements

### 1. ThrottleStrategy ⭐ HIGH PRIORITY

**Status**: Deferred from Phase 1

**Purpose**: Block until backpressure reduces (with timeout)

**Use Cases**:
- Items must be processed (cannot drop or reject)
- System can wait for capacity
- Backpressure is temporary
- Critical data that cannot be lost

**Implementation**:
```java
/**
 * Throttles items by blocking until backpressure reduces.
 * 
 * <p>Useful when items must be processed and cannot be dropped or rejected.
 * Blocks with exponential backoff until backpressure < threshold or timeout.
 */
public class ThrottleStrategy<T> implements BackpressureStrategy<T> {
    private final double threshold;  // Default: 0.7
    private final Duration maxWaitTime;  // Default: 5 seconds
    private final Duration initialBackoff;  // Default: 10ms
    private final double backoffMultiplier;  // Default: 1.5
    
    public ThrottleStrategy(double threshold, Duration maxWaitTime) {
        this(threshold, maxWaitTime, Duration.ofMillis(10), 1.5);
    }
    
    public ThrottleStrategy(
            double threshold,
            Duration maxWaitTime,
            Duration initialBackoff,
            double backoffMultiplier) {
        this.threshold = threshold;
        this.maxWaitTime = maxWaitTime;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
    }
    
    @Override
    public BackpressureResult<T> handle(BackpressureContext<T> context) {
        if (context.backpressureLevel() < threshold) {
            return BackpressureResult.accept(context.item());
        }
        
        long startTime = System.currentTimeMillis();
        Duration currentBackoff = initialBackoff;
        
        while (System.currentTimeMillis() - startTime < maxWaitTime.toMillis()) {
            double currentBackpressure = context.provider().getBackpressureLevel();
            if (currentBackpressure < threshold) {
                return BackpressureResult.accept(context.item());
            }
            
            try {
                Thread.sleep(currentBackoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return BackpressureResult.reject(
                    context.item(),
                    new BackpressureException("Throttle interrupted", e)
                );
            }
            
            currentBackoff = Duration.ofMillis(
                (long) (currentBackoff.toMillis() * backoffMultiplier)
            );
        }
        
        // Timeout: reject the item
        return BackpressureResult.reject(
            context.item(),
            new BackpressureException(
                String.format("Throttle timeout after %s (backpressure: %.2f)",
                    maxWaitTime, context.provider().getBackpressureLevel())
            )
        );
    }
}
```

**Alternative: Async Throttling** (if blocking is not acceptable):
```java
/**
 * Async throttling using CompletableFuture.delayedExecutor().
 * 
 * <p>Non-blocking alternative to blocking ThrottleStrategy.
 */
public class AsyncThrottleStrategy<T> implements BackpressureStrategy<T> {
    // Implementation using CompletableFuture with delayed executor
    // ...
}
```

**Decision Criteria**:
- Add if users request blocking behavior
- Consider async alternative if blocking is problematic
- May need to add `THROTTLED` action to `BackpressureAction` enum

### 2. Logarithmic Scaling for QueueDepthBackpressureProvider ⭐ MEDIUM PRIORITY

**Status**: Deferred from Phase 1

**Purpose**: Detect pressure earlier with logarithmic scaling

**Use Case**: When early pressure detection is critical (e.g., connection pools)

**Implementation**:
```java
/**
 * Enhanced QueueDepthBackpressureProvider with optional logarithmic scaling.
 */
public class QueueDepthBackpressureProvider implements BackpressureProvider {
    private final Supplier<Integer> queueDepthSupplier;
    private final int maxCapacity;
    private final ScalingMode scalingMode;  // LINEAR or LOGARITHMIC
    
    public enum ScalingMode {
        LINEAR,      // Simple linear scaling (default)
        LOGARITHMIC  // Logarithmic scaling for early detection
    }
    
    public QueueDepthBackpressureProvider(
            Supplier<Integer> queueDepthSupplier,
            int maxCapacity) {
        this(queueDepthSupplier, maxCapacity, ScalingMode.LINEAR);
    }
    
    public QueueDepthBackpressureProvider(
            Supplier<Integer> queueDepthSupplier,
            int maxCapacity,
            ScalingMode scalingMode) {
        this.queueDepthSupplier = queueDepthSupplier;
        this.maxCapacity = maxCapacity;
        this.scalingMode = scalingMode;
    }
    
    @Override
    public double getBackpressureLevel() {
        int queueDepth = queueDepthSupplier.get();
        
        if (queueDepth == 0) {
            return 0.0;
        }
        
        if (queueDepth >= maxCapacity) {
            return 1.0;
        }
        
        double ratio = (double) queueDepth / maxCapacity;
        
        return switch (scalingMode) {
            case LINEAR -> ratio;
            case LOGARITHMIC -> calculateLogarithmicBackpressure(ratio);
        };
    }
    
    private double calculateLogarithmicBackpressure(double ratio) {
        // Logarithmic scaling with early detection
        // Similar to HikariCPBackpressureProvider logic
        if (ratio >= 0.8) {
            // 0.7 - 1.0 range
            double normalized = (ratio - 0.8) / 0.2;
            return 0.7 + (0.3 * Math.log(normalized + 1) / Math.log(2.0));
        } else if (ratio >= 0.5) {
            // 0.3 - 0.7 range
            double normalized = (ratio - 0.5) / 0.3;
            return 0.3 + (0.4 * Math.log(normalized + 1) / Math.log(2.0));
        } else {
            // 0.0 - 0.3 range
            return 0.3 * Math.log(ratio / 0.5 + 1) / Math.log(2.0);
        }
    }
}
```

**Decision Criteria**:
- Add if users report that linear scaling doesn't detect pressure early enough
- Make it optional (default to LINEAR for simplicity)
- Can be added without breaking changes

### 3. Comprehensive Backpressure Metrics ⭐ MEDIUM PRIORITY

**Status**: Deferred from Phase 1

**Purpose**: Dedicated metrics class for backpressure events

**Use Case**: When detailed backpressure observability is needed

**Implementation**:
```java
/**
 * Comprehensive metrics for backpressure events.
 * 
 * <p>Tracks:
 * <ul>
 *   <li>Items accepted (backpressure < threshold)</li>
 *   <li>Items rejected (backpressure >= threshold, REJECT strategy)</li>
 *   <li>Items dropped (backpressure >= threshold, DROP strategy)</li>
 *   <li>Items throttled (backpressure >= threshold, THROTTLE strategy)</li>
 *   <li>Current backpressure level (gauge)</li>
 *   <li>Backpressure level distribution (histogram)</li>
 *   <li>Backpressure duration (time spent in high backpressure)</li>
 * </ul>
 */
public class BackpressureMetrics {
    private final Counter acceptedCounter;
    private final Counter rejectedCounter;
    private final Counter droppedCounter;
    private final Counter throttledCounter;
    private final Gauge backpressureGauge;
    private final Timer backpressureTimer;
    private final Timer backpressureDurationTimer;
    
    private final MeterRegistry meterRegistry;  // Optional
    
    public BackpressureMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Initialize metrics if Micrometer available
        // Otherwise use no-op implementations
    }
    
    public void recordAccepted() {
        acceptedCounter.increment();
    }
    
    public void recordRejected() {
        rejectedCounter.increment();
    }
    
    public void recordDropped() {
        droppedCounter.increment();
    }
    
    public void recordThrottled() {
        throttledCounter.increment();
    }
    
    public void recordBackpressureLevel(double level) {
        backpressureGauge.set(level);
        backpressureTimer.record(level, TimeUnit.NANOSECONDS);
    }
    
    public void recordBackpressureDuration(Duration duration) {
        backpressureDurationTimer.record(duration);
    }
}
```

**Decision Criteria**:
- Add if existing MicroBatcher metrics are insufficient
- Only if users request more detailed backpressure observability
- Can be added as optional feature

### 4. Enhanced CompositeBackpressureProvider Diagnostics ⭐ LOW PRIORITY

**Status**: Deferred from Phase 1

**Purpose**: Per-provider diagnostics in composite provider

**Use Case**: When debugging which provider is causing backpressure

**Implementation**:
```java
/**
 * Enhanced CompositeBackpressureProvider with per-provider diagnostics.
 */
public class CompositeBackpressureProvider implements BackpressureProvider {
    private final List<NamedProvider> providers;
    
    // Enhanced constructor with named providers
    public CompositeBackpressureProvider(NamedProvider... providers) {
        this.providers = List.of(providers);
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one provider required");
        }
    }
    
    // Factory method for convenience
    public static CompositeBackpressureProvider of(
            BackpressureProvider... providers) {
        return new CompositeBackpressureProvider(
            Arrays.stream(providers)
                .map(p -> new NamedProvider(p, p.getSourceName()))
                .toArray(NamedProvider[]::new)
        );
    }
    
    @Override
    public Map<String, Object> getDetails() {
        Map<String, Object> details = new HashMap<>();
        for (NamedProvider named : providers) {
            double level = named.provider().getBackpressureLevel();
            details.put(named.name() + ".level", level);
            details.put(named.name() + ".source", named.provider().getSourceName());
            // Add per-provider details
            named.provider().getDetails().forEach((key, value) ->
                details.put(named.name() + "." + key, value)
            );
        }
        details.put("maxBackpressure", getBackpressureLevel());
        return details;
    }
    
    public record NamedProvider(BackpressureProvider provider, String name) {}
}
```

**Decision Criteria**:
- Add if users need to debug which provider is causing backpressure
- Can be added without breaking changes (enhancement only)

### 5. BackpressureContext with Timestamp ⭐ LOW PRIORITY

**Status**: Deferred from Phase 1

**Purpose**: Add timestamp to context for metrics and debugging

**Use Case**: When tracking backpressure duration or timing is needed

**Implementation**:
```java
/**
 * Enhanced BackpressureContext with timestamp.
 */
public record BackpressureContext<T>(
    T item,
    double backpressureLevel,
    BackpressureProvider provider,
    long timestamp  // System.currentTimeMillis()
) {
    public BackpressureContext(T item, double backpressureLevel, BackpressureProvider provider) {
        this(item, backpressureLevel, provider, System.currentTimeMillis());
    }
}
```

**Decision Criteria**:
- Add if metrics need timing information
- Can be added without breaking changes (backward compatible constructor)

### 6. Strategy Name in Interface ⭐ LOW PRIORITY

**Status**: Deferred from Phase 1

**Purpose**: Add `getStrategyName()` back to interface

**Use Case**: When strategy name is needed for metrics or logging

**Implementation**:
```java
public interface BackpressureStrategy<T> {
    BackpressureResult<T> handle(BackpressureContext<T> context);
    
    /**
     * Gets the strategy name for metrics and logging.
     * 
     * @return strategy name (e.g., "DROP", "REJECT", "THROTTLE")
     */
    default String getStrategyName() {
        return this.getClass().getSimpleName();
    }
}
```

**Decision Criteria**:
- Add if metrics or logging need strategy names
- Default implementation uses class name (no breaking changes)

## Implementation Priority

### Must Have (Phase 2.0)
1. **ThrottleStrategy** - If users request blocking behavior

### Should Have (Phase 2.1)
2. **Logarithmic Scaling** - If users report linear scaling insufficient
3. **Comprehensive Metrics** - If existing metrics insufficient

### Nice to Have (Phase 2.2+)
4. **Enhanced Composite Diagnostics** - If debugging needs arise
5. **Timestamp in Context** - If metrics need timing
6. **Strategy Name** - If logging needs names

## Decision Framework

For each enhancement:

1. **User Demand**: Is there clear user demand or use case?
2. **Value vs. Complexity**: Does the value justify the complexity?
3. **Breaking Changes**: Can it be added without breaking changes?
4. **Backward Compatibility**: Does it maintain backward compatibility?

**Rule**: Only add if all criteria are met.

## Migration from Phase 1

All Phase 2 enhancements are **additive only**:
- No breaking changes to Phase 1 APIs
- All Phase 1 code continues to work
- Enhancements are optional features

## Testing Strategy

For each enhancement:
1. **Unit Tests**: Test the enhancement in isolation
2. **Integration Tests**: Test with MicroBatcher
3. **Backward Compatibility Tests**: Ensure Phase 1 code still works
4. **Performance Tests**: Ensure no performance regression

## Documentation Updates

For each enhancement:
1. **API Documentation**: Update JavaDoc
2. **User Guide**: Add usage examples
3. **Migration Guide**: Document how to adopt (if needed)
4. **Changelog**: Document new features

## Conclusion

Phase 2 enhancements are designed to be:
- **Additive**: No breaking changes
- **Optional**: Can be adopted incrementally
- **Value-Driven**: Only added if there's clear need
- **Backward Compatible**: Phase 1 code continues to work

This approach ensures that Phase 1 remains simple and focused, while Phase 2 adds sophistication based on real-world validation.

