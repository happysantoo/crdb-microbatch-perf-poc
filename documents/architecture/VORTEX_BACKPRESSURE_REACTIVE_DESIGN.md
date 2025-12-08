# Vortex Backpressure - Reactive Design Analysis (Devil's Advocate)

## Executive Summary

This document challenges the **proactive backpressure design** (library handles backpressure before queuing) and explores a **reactive design** where the library detects threshold breaches and notifies the application, allowing the application to decide how to handle backpressure.

**Key Question**: Should Vortex be **opinionated** (proactive) or **agnostic** (reactive) about backpressure handling?

## Current Proactive Design (Challenged)

### How It Works
1. Library checks backpressure **before** accepting item
2. Library applies strategy (DROP/REJECT) **automatically**
3. Application has no say in the decision
4. Library makes the decision based on configured strategy

### Problems with Proactive Design

#### 1. **Library Makes Business Decisions** ❌
```java
// Library decides to reject - but what if application wants to:
// - Retry with exponential backoff?
// - Route to dead-letter queue?
// - Degrade quality instead of rejecting?
// - Notify external system?
MicroBatcher<Item> batcher = MicroBatcher.withBackpressure(
    backend, config, provider, new RejectStrategy<>(0.7)
);
// Library rejected - application never gets a chance to handle it
```

**Problem**: The library is making business decisions that should belong to the application.

#### 2. **One-Size-Fits-All Strategies** ❌
```java
// What if different items need different handling?
// - Critical items: Never drop, always retry
// - Non-critical items: Drop silently
// - Metrics items: Drop if queue > 80%
// - User requests: Reject with meaningful error

// Current design: One strategy for all items
BackpressureStrategy<Item> strategy = new RejectStrategy<>(0.7);
```

**Problem**: Real applications have different handling requirements for different item types.

#### 3. **Tight Coupling to Library** ❌
```java
// Application must understand library's backpressure concepts
// Application must configure library's strategies
// Application must adapt to library's API

// What if application wants to:
// - Use circuit breaker pattern?
// - Use rate limiting?
// - Use priority queues?
// - Use different strategies per item type?
```

**Problem**: Application is forced to work within library's constraints.

#### 4. **Limited Observability** ❌
```java
// Library handles backpressure silently
// Application doesn't know:
// - How many items were rejected?
// - Why were they rejected?
// - What was the backpressure level?
// - When did backpressure occur?

// Application must rely on library's metrics (if any)
```

**Problem**: Application loses visibility into backpressure events.

#### 5. **Testing Complexity** ❌
```java
// How do you test backpressure handling?
// - Must mock BackpressureProvider
// - Must configure BackpressureStrategy
// - Must verify library behavior
// - Can't test application's handling logic separately

// What if you want to test:
// - Application's retry logic?
// - Application's dead-letter queue?
// - Application's notification system?
```

**Problem**: Testing becomes complex because library and application logic are intertwined.

## Reactive Design (Proposed Alternative)

### Core Philosophy

**"Library detects, Application decides"**

- Library detects threshold breaches
- Library notifies application (exception/handler/event)
- Application decides what to do
- Library stays out of business logic

### Design Option 1: Exception-Based

#### How It Works
```java
public class MicroBatcher<T> {
    private final int maxQueueSize;
    
    public CompletableFuture<ItemResult<T>> submit(T item) {
        int currentQueueSize = getQueueDepth();
        
        if (currentQueueSize >= maxQueueSize) {
            // Throw exception - application handles it
            throw new QueueFullException(
                "Queue is full: " + currentQueueSize + " >= " + maxQueueSize,
                currentQueueSize,
                maxQueueSize
            );
        }
        
        // Normal flow
        return super.submit(item);
    }
}

// Application handles exception
try {
    batcher.submit(item);
} catch (QueueFullException e) {
    // Application decides:
    // - Retry with backoff?
    // - Route to dead-letter queue?
    // - Reject with custom error?
    // - Notify external system?
    handleQueueFull(item, e);
}
```

#### Pros ✅
1. **Simple**: Standard exception handling
2. **Familiar**: Developers understand exceptions
3. **Flexible**: Application can do anything
4. **Testable**: Easy to test exception handling
5. **Observable**: Application can log/metrics exceptions

#### Cons ❌
1. **Synchronous**: Blocks calling thread
2. **No Async Support**: Can't handle async scenarios
3. **Exception Overhead**: Exception creation is expensive
4. **No Pre-Queuing Check**: Item might be queued before exception

### Design Option 2: Handler-Based (Callback)

#### How It Works
```java
public interface QueueThresholdHandler<T> {
    /**
     * Called when queue depth reaches threshold.
     * 
     * @param item the item that triggered the threshold
     * @param queueDepth current queue depth
     * @param threshold the threshold that was breached
     * @return action to take (ACCEPT, REJECT, DROP, RETRY)
     */
    QueueAction onThresholdBreached(T item, int queueDepth, int threshold);
}

public enum QueueAction {
    ACCEPT,    // Accept item anyway
    REJECT,    // Reject item (return failure)
    DROP,      // Drop item silently
    RETRY      // Retry after delay
}

public class MicroBatcher<T> {
    private final int maxQueueSize;
    private final QueueThresholdHandler<T> handler;
    
    public CompletableFuture<ItemResult<T>> submit(T item) {
        int currentQueueSize = getQueueDepth();
        
        if (currentQueueSize >= maxQueueSize && handler != null) {
            QueueAction action = handler.onThresholdBreached(
                item, currentQueueSize, maxQueueSize
            );
            
            return switch (action) {
                case ACCEPT -> super.submit(item);
                case REJECT -> CompletableFuture.completedFuture(
                    ItemResult.failure(new QueueFullException(...))
                );
                case DROP -> CompletableFuture.completedFuture(
                    ItemResult.success(item)  // Success but no callback
                );
                case RETRY -> retryAfterDelay(item, Duration.ofMillis(100));
            };
        }
        
        return super.submit(item);
    }
}

// Application implements handler
QueueThresholdHandler<Item> handler = (item, queueDepth, threshold) -> {
    // Application logic:
    if (isCritical(item)) {
        return QueueAction.RETRY;  // Never drop critical items
    } else if (queueDepth > threshold * 1.5) {
        return QueueAction.DROP;   // Drop non-critical if severely overloaded
    } else {
        return QueueAction.REJECT; // Reject with error
    }
};

MicroBatcher<Item> batcher = new MicroBatcher<>(
    backend, config, handler
);
```

#### Pros ✅
1. **Flexible**: Application controls decision
2. **Async-Friendly**: Works with CompletableFuture
3. **Item-Aware**: Handler can inspect item
4. **Context-Aware**: Handler knows queue depth, threshold
5. **Testable**: Easy to mock handler
6. **No Exception Overhead**: No exception creation

#### Cons ❌
1. **More Complex**: Requires handler implementation
2. **Synchronous Handler**: Handler must return quickly
3. **No Multiple Thresholds**: One handler per threshold

### Design Option 3: Event-Based (Observer Pattern)

#### How It Works
```java
public interface QueueThresholdListener<T> {
    void onThresholdBreached(QueueThresholdEvent<T> event);
}

public record QueueThresholdEvent<T>(
    T item,
    int queueDepth,
    int threshold,
    ThresholdLevel level  // WARNING, CRITICAL, CRITICAL_PLUS
) {
    public enum ThresholdLevel {
        WARNING,         // 50% of threshold
        CRITICAL,        // 100% of threshold
        CRITICAL_PLUS    // 150% of threshold
    }
}

public class MicroBatcher<T> {
    private final List<QueueThresholdListener<T>> listeners = new CopyOnWriteArrayList<>();
    private final int maxQueueSize;
    
    public void addThresholdListener(QueueThresholdListener<T> listener) {
        listeners.add(listener);
    }
    
    public CompletableFuture<ItemResult<T>> submit(T item) {
        int currentQueueSize = getQueueDepth();
        
        // Notify listeners (non-blocking)
        if (currentQueueSize >= maxQueueSize) {
            ThresholdLevel level = determineLevel(currentQueueSize, maxQueueSize);
            QueueThresholdEvent<T> event = new QueueThresholdEvent<>(
                item, currentQueueSize, maxQueueSize, level
            );
            
            // Fire event asynchronously (don't block submit)
            listeners.forEach(listener -> {
                try {
                    listener.onThresholdBreached(event);
                } catch (Exception e) {
                    // Log but don't fail
                    log.error("Listener failed", e);
                }
            });
        }
        
        // Always accept item - listeners decide what to do
        return super.submit(item);
    }
}

// Application subscribes to events
batcher.addThresholdListener(event -> {
    if (event.level() == ThresholdLevel.CRITICAL_PLUS) {
        // Application decides:
        // - Reject item?
        // - Drop item?
        // - Notify external system?
        // - Adjust load pattern?
        handleCriticalBackpressure(event);
    }
});
```

#### Pros ✅
1. **Decoupled**: Library and application are decoupled
2. **Multiple Listeners**: Multiple handlers can react
3. **Non-Blocking**: Events don't block submission
4. **Flexible**: Application can do anything
5. **Observable**: Easy to add logging/metrics listeners
6. **Extensible**: Easy to add new event types

#### Cons ❌
1. **No Direct Control**: Can't prevent item from being queued
2. **Race Conditions**: Item might be processed before listener reacts
3. **Complex**: More moving parts
4. **Async Complexity**: Must handle async event processing

### Design Option 4: Hybrid (Thresholds + Handlers)

#### How It Works
```java
public interface QueueThresholdHandler<T> {
    /**
     * Called when queue depth reaches threshold.
     * 
     * @param item the item that triggered the threshold
     * @param queueDepth current queue depth
     * @param threshold the threshold that was breached
     * @return decision (ACCEPT, REJECT, DROP, or null to use default)
     */
    QueueDecision onThresholdBreached(T item, int queueDepth, int threshold);
}

public record QueueDecision(
    QueueAction action,
    Exception rejectionReason,  // For REJECT action
    Duration retryDelay         // For RETRY action
) {
    public static QueueDecision accept() {
        return new QueueDecision(QueueAction.ACCEPT, null, null);
    }
    
    public static QueueDecision reject(Exception reason) {
        return new QueueDecision(QueueAction.REJECT, reason, null);
    }
    
    public static QueueDecision drop() {
        return new QueueDecision(QueueAction.DROP, null, null);
    }
    
    public static QueueDecision retry(Duration delay) {
        return new QueueDecision(QueueAction.RETRY, null, delay);
    }
}

public class MicroBatcher<T> {
    private final QueueThresholdConfig config;
    
    public static class QueueThresholdConfig {
        private int warningThreshold = 100;      // 50% of max
        private int criticalThreshold = 200;     // 100% of max
        private QueueThresholdHandler<?> warningHandler;
        private QueueThresholdHandler<?> criticalHandler;
        private QueueAction defaultAction = QueueAction.REJECT;  // Default if no handler
        
        // Builder methods...
    }
    
    public CompletableFuture<ItemResult<T>> submit(T item) {
        int currentQueueSize = getQueueDepth();
        
        if (currentQueueSize >= config.criticalThreshold) {
            QueueDecision decision = handleThreshold(
                item, currentQueueSize, config.criticalThreshold, 
                config.criticalHandler
            );
            return applyDecision(item, decision);
        } else if (currentQueueSize >= config.warningThreshold) {
            QueueDecision decision = handleThreshold(
                item, currentQueueSize, config.warningThreshold,
                config.warningHandler
            );
            return applyDecision(item, decision);
        }
        
        return super.submit(item);
    }
    
    private QueueDecision handleThreshold(
            T item, int queueDepth, int threshold, 
            QueueThresholdHandler<T> handler) {
        if (handler != null) {
            return handler.onThresholdBreached(item, queueDepth, threshold);
        }
        // Default action if no handler
        return new QueueDecision(config.defaultAction, null, null);
    }
}

// Application configures thresholds and handlers
MicroBatcher<Item> batcher = MicroBatcher.builder(backend, config)
    .withQueueThresholds(builder -> builder
        .warningThreshold(100, (item, depth, threshold) -> {
            // Log warning, but accept
            log.warn("Queue depth {} reached warning threshold {}", depth, threshold);
            return QueueDecision.accept();
        })
        .criticalThreshold(200, (item, depth, threshold) -> {
            // Application decides based on item type
            if (isCritical(item)) {
                return QueueDecision.retry(Duration.ofMillis(100));
            } else {
                return QueueDecision.reject(
                    new QueueFullException("Queue full: " + depth)
                );
            }
        })
        .defaultAction(QueueAction.REJECT)  // If no handler
    )
    .build();
```

#### Pros ✅
1. **Flexible**: Multiple thresholds, different handlers
2. **Default Behavior**: Can provide sensible defaults
3. **Item-Aware**: Handler can inspect item
4. **Context-Aware**: Handler knows queue depth, threshold
5. **Testable**: Easy to test handlers
6. **Backward Compatible**: Can provide default handlers

#### Cons ❌
1. **Complex**: More configuration
2. **Handler Overhead**: Handler must be fast
3. **Synchronous**: Handler blocks submission

## Comparison: Proactive vs Reactive

| Aspect | Proactive (Current) | Reactive (Proposed) |
|--------|-------------------|---------------------|
| **Decision Making** | Library decides | Application decides |
| **Flexibility** | Limited to library strategies | Unlimited (application logic) |
| **Complexity** | Simple (library handles it) | More complex (application handles it) |
| **Testing** | Must test library behavior | Test application logic |
| **Observability** | Library metrics only | Application can add own metrics |
| **Item-Aware** | No (strategy doesn't see item) | Yes (handler sees item) |
| **Context-Aware** | Limited (backpressure level only) | Full (queue depth, threshold, item) |
| **Performance** | Fast (no application code) | Slower (handler overhead) |
| **Coupling** | Tight (application depends on library) | Loose (library notifies application) |
| **Reusability** | Limited (library strategies) | High (application logic reusable) |

## Real-World Use Cases

### Use Case 1: Priority Queue
```java
// Application wants priority-based handling
QueueThresholdHandler<Item> handler = (item, depth, threshold) -> {
    if (item.getPriority() == Priority.CRITICAL) {
        return QueueDecision.accept();  // Always accept critical
    } else if (item.getPriority() == Priority.HIGH) {
        return QueueDecision.retry(Duration.ofMillis(50));  // Retry high priority
    } else {
        return QueueDecision.drop();  // Drop low priority
    }
};
```

**Proactive Design**: ❌ Can't do this - strategy doesn't see item priority
**Reactive Design**: ✅ Easy - handler inspects item

### Use Case 2: Dead-Letter Queue
```java
// Application wants to route rejected items to DLQ
QueueThresholdHandler<Item> handler = (item, depth, threshold) -> {
    if (depth >= threshold) {
        // Route to DLQ instead of rejecting
        deadLetterQueue.send(item);
        return QueueDecision.drop();  // Drop after routing to DLQ
    }
    return QueueDecision.accept();
};
```

**Proactive Design**: ❌ Can't do this - strategy can't route to DLQ
**Reactive Design**: ✅ Easy - handler routes to DLQ

### Use Case 3: Adaptive Rate Limiting
```java
// Application wants to adjust rate based on queue depth
QueueThresholdHandler<Item> handler = (item, depth, threshold) -> {
    if (depth >= threshold) {
        // Notify rate limiter to reduce rate
        rateLimiter.reduceRate();
        return QueueDecision.retry(Duration.ofMillis(100));
    }
    return QueueDecision.accept();
};
```

**Proactive Design**: ❌ Can't do this - strategy can't adjust rate limiter
**Reactive Design**: ✅ Easy - handler adjusts rate limiter

### Use Case 4: Circuit Breaker Integration
```java
// Application wants circuit breaker pattern
QueueThresholdHandler<Item> handler = (item, depth, threshold) -> {
    if (circuitBreaker.isOpen()) {
        return QueueDecision.reject(new CircuitBreakerOpenException());
    }
    if (depth >= threshold) {
        circuitBreaker.recordFailure();
        return QueueDecision.reject(new QueueFullException(...));
    }
    return QueueDecision.accept();
};
```

**Proactive Design**: ❌ Can't do this - strategy can't integrate with circuit breaker
**Reactive Design**: ✅ Easy - handler integrates with circuit breaker

### Use Case 5: Different Strategies Per Item Type
```java
// Application wants different handling for different item types
QueueThresholdHandler<Item> handler = (item, depth, threshold) -> {
    if (item instanceof MetricsItem) {
        // Metrics: drop if queue > 80%
        return depth > threshold * 0.8 
            ? QueueDecision.drop() 
            : QueueDecision.accept();
    } else if (item instanceof UserRequest) {
        // User requests: reject with error
        return QueueDecision.reject(new QueueFullException(...));
    } else {
        // Default: retry
        return QueueDecision.retry(Duration.ofMillis(100));
    }
};
```

**Proactive Design**: ❌ Can't do this - one strategy for all items
**Reactive Design**: ✅ Easy - handler inspects item type

## Recommended Approach: Hybrid Design

### Phase 1: Simple Thresholds with Handlers
```java
public class MicroBatcher<T> {
    private final int maxQueueSize;
    private final QueueThresholdHandler<T> handler;
    
    public CompletableFuture<ItemResult<T>> submit(T item) {
        int currentQueueSize = getQueueDepth();
        
        if (currentQueueSize >= maxQueueSize && handler != null) {
            QueueDecision decision = handler.onThresholdBreached(
                item, currentQueueSize, maxQueueSize
            );
            return applyDecision(item, decision);
        }
        
        return super.submit(item);
    }
}
```

### Phase 2: Multiple Thresholds
```java
public class MicroBatcher<T> {
    private final QueueThresholdConfig config;
    
    // Support warning (50%), critical (100%), critical+ (150%)
    // Each with its own handler
}
```

### Phase 3: Event-Based (Optional)
```java
// Add event listeners for observability
// But keep handlers for control
```

## Implementation Recommendations

### 1. **Start with Handler-Based (Option 2)**
- Simple to implement
- Flexible enough for most use cases
- Easy to test
- Good performance

### 2. **Add Exception Support (Option 1)**
- For synchronous use cases
- Familiar to developers
- Easy to migrate from

### 3. **Add Event-Based (Option 3) for Observability**
- For metrics/logging
- Non-blocking
- Multiple listeners

### 4. **Keep Proactive Design as Optional**
- Some applications want library to handle it
- Provide default handlers that mimic proactive strategies
- Best of both worlds

## Conclusion

**The reactive design has significant advantages**:
1. ✅ **Application controls business logic** (not library)
2. ✅ **Item-aware decisions** (handler sees item)
3. ✅ **Context-aware decisions** (handler knows queue depth, threshold)
4. ✅ **Flexible** (application can do anything)
5. ✅ **Testable** (test application logic, not library)
6. ✅ **Observable** (application can add own metrics/logging)
7. ✅ **Reusable** (application logic can be reused)

**The proactive design has advantages**:
1. ✅ **Simple** (library handles it)
2. ✅ **Fast** (no application code)
3. ✅ **Consistent** (same behavior everywhere)

**Recommendation**: **Hybrid approach** - Provide both:
- **Reactive by default** (handlers) - gives application control
- **Proactive as optional** (default handlers) - for simple use cases

This gives applications the flexibility they need while keeping simple use cases simple.

