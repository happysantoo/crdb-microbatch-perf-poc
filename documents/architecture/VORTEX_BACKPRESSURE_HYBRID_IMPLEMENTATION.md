# Vortex Backpressure - Hybrid Implementation Proposal

## Executive Summary

This document provides a **detailed implementation proposal** for a **hybrid reactive/proactive backpressure design** in the Vortex MicroBatching Library. The design combines:

1. **Reactive handlers** (application controls decisions)
2. **Proactive strategies** (library provides sensible defaults)
3. **Multiple threshold levels** (warning, critical, critical+)
4. **Event notifications** (for observability)

**Goal**: Give applications maximum flexibility while keeping simple use cases simple.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    MicroBatcher<T>                           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         Queue Threshold Manager                        │  │
│  │  - Monitors queue depth                                │  │
│  │  - Checks thresholds (warning/critical/critical+)     │  │
│  │  - Invokes handlers/strategies                         │  │
│  │  - Fires events                                        │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ uses
        ┌───────────────────┼───────────────────┐
        │                   │                   │
┌───────┴──────┐  ┌──────────┴──────────┐  ┌────┴──────────────┐
│ Handler      │  │ Strategy            │  │ Event Listener    │
│ (Reactive)   │  │ (Proactive)         │  │ (Observability)   │
│              │  │                     │  │                   │
│ Application  │  │ Library-provided    │  │ Metrics/Logging  │
│ controls     │  │ defaults            │  │                   │
└──────────────┘  └─────────────────────┘  └───────────────────┘
```

## Core Interfaces

### 1. QueueThresholdHandler (Reactive)

**Purpose**: Application-provided handler that makes decisions when thresholds are breached.

```java
package com.vajrapulse.vortex.backpressure;

/**
 * Handler for queue threshold breaches.
 * 
 * <p>Applications implement this interface to control how items are handled
 * when queue depth reaches configured thresholds.
 * 
 * <p>The handler receives:
 * <ul>
 *   <li>The item that triggered the threshold</li>
 *   <li>Current queue depth</li>
 *   <li>The threshold that was breached</li>
 *   <li>Threshold level (WARNING, CRITICAL, CRITICAL_PLUS)</li>
 * </ul>
 * 
 * <p>The handler returns a decision:
 * <ul>
 *   <li>ACCEPT: Accept item and queue it</li>
 *   <li>REJECT: Reject item with failure callback</li>
 *   <li>DROP: Drop item silently (no callback)</li>
 *   <li>RETRY: Retry after delay</li>
 * </ul>
 * 
 * <p><strong>Performance Note:</strong> This handler is called synchronously
 * during item submission. It must return quickly to avoid blocking submission.
 * For expensive operations, consider using async patterns or delegating
 * to a separate thread pool.
 * 
 * @param <T> the item type
 */
public interface QueueThresholdHandler<T> {
    /**
     * Called when queue depth reaches a threshold.
     * 
     * @param context the threshold breach context
     * @return decision on how to handle the item
     */
    QueueDecision handle(QueueThresholdContext<T> context);
}

/**
 * Context provided to threshold handler.
 */
public record QueueThresholdContext<T>(
    /**
     * The item that triggered the threshold breach.
     */
    T item,
    
    /**
     * Current queue depth.
     */
    int queueDepth,
    
    /**
     * The threshold that was breached.
     */
    int threshold,
    
    /**
     * The level of threshold breached.
     */
    ThresholdLevel level,
    
    /**
     * Timestamp when threshold was breached.
     */
    long timestamp
) {
    public QueueThresholdContext(T item, int queueDepth, int threshold, ThresholdLevel level) {
        this(item, queueDepth, threshold, level, System.currentTimeMillis());
    }
}

/**
 * Threshold levels.
 */
public enum ThresholdLevel {
    /**
     * Warning threshold (typically 50% of max queue size).
     * System is approaching capacity but can still handle more.
     */
    WARNING,
    
    /**
     * Critical threshold (typically 100% of max queue size).
     * System is at capacity and should reject new items.
     */
    CRITICAL,
    
    /**
     * Critical+ threshold (typically 150% of max queue size).
     * System is severely overloaded and must take immediate action.
     */
    CRITICAL_PLUS
}

/**
 * Decision returned by threshold handler.
 */
public record QueueDecision(
    /**
     * Action to take.
     */
    QueueAction action,
    
    /**
     * Rejection reason (for REJECT action).
     */
    Exception rejectionReason,
    
    /**
     * Retry delay (for RETRY action).
     */
    Duration retryDelay
) {
    /**
     * Accept the item and queue it.
     */
    public static <T> QueueDecision accept() {
        return new QueueDecision(QueueAction.ACCEPT, null, null);
    }
    
    /**
     * Reject the item with a failure callback.
     * 
     * @param reason the rejection reason
     */
    public static <T> QueueDecision reject(Exception reason) {
        return new QueueDecision(QueueAction.REJECT, reason, null);
    }
    
    /**
     * Drop the item silently (no callback).
     */
    public static <T> QueueDecision drop() {
        return new QueueDecision(QueueAction.DROP, null, null);
    }
    
    /**
     * Retry after a delay.
     * 
     * @param delay the delay before retry
     */
    public static <T> QueueDecision retry(Duration delay) {
        return new QueueDecision(QueueAction.RETRY, null, delay);
    }
}

/**
 * Actions that can be taken when threshold is breached.
 */
public enum QueueAction {
    /**
     * Accept item and queue it (ignore threshold).
     */
    ACCEPT,
    
    /**
     * Reject item with failure callback.
     */
    REJECT,
    
    /**
     * Drop item silently (no callback).
     */
    DROP,
    
    /**
     * Retry after delay.
     */
    RETRY
}
```

### 2. QueueThresholdStrategy (Proactive)

**Purpose**: Library-provided strategies for common use cases (convenience).

```java
package com.vajrapulse.vortex.backpressure;

/**
 * Pre-built strategies for common threshold handling scenarios.
 * 
 * <p>These strategies provide sensible defaults for applications that
 * don't need custom handling logic.
 */
public final class QueueThresholdStrategies {
    
    private QueueThresholdStrategies() {
        // Utility class
    }
    
    /**
     * Reject items when threshold is breached.
     * 
     * <p>Returns REJECT decision with QueueFullException.
     * 
     * @param <T> the item type
     * @return reject strategy
     */
    public static <T> QueueThresholdHandler<T> reject() {
        return context -> QueueDecision.reject(
            new QueueFullException(
                String.format("Queue full: depth=%d, threshold=%d, level=%s",
                    context.queueDepth(), context.threshold(), context.level())
            )
        );
    }
    
    /**
     * Drop items silently when threshold is breached.
     * 
     * <p>Returns DROP decision (no callback).
     * 
     * @param <T> the item type
     * @return drop strategy
     */
    public static <T> QueueThresholdHandler<T> drop() {
        return context -> QueueDecision.drop();
    }
    
    /**
     * Accept items even when threshold is breached.
     * 
     * <p>Returns ACCEPT decision (allows queue to grow beyond threshold).
     * 
     * @param <T> the item type
     * @return accept strategy
     */
    public static <T> QueueThresholdHandler<T> accept() {
        return context -> QueueDecision.accept();
    }
    
    /**
     * Retry with exponential backoff when threshold is breached.
     * 
     * @param initialDelay initial retry delay
     * @param maxDelay maximum retry delay
     * @param multiplier backoff multiplier
     * @param <T> the item type
     * @return retry strategy
     */
    public static <T> QueueThresholdHandler<T> retry(
            Duration initialDelay,
            Duration maxDelay,
            double multiplier) {
        return new RetryStrategy<>(initialDelay, maxDelay, multiplier);
    }
    
    /**
     * Reject at CRITICAL, drop at CRITICAL_PLUS.
     * 
     * <p>Useful for graceful degradation:
     * <ul>
     *   <li>WARNING: Accept (monitor only)</li>
     *   <li>CRITICAL: Reject (caller can retry)</li>
     *   <li>CRITICAL_PLUS: Drop (prevent queue growth)</li>
     * </ul>
     * 
     * @param <T> the item type
     * @return graceful degradation strategy
     */
    public static <T> QueueThresholdHandler<T> gracefulDegradation() {
        return context -> {
            return switch (context.level()) {
                case WARNING -> QueueDecision.accept();
                case CRITICAL -> QueueDecision.reject(
                    new QueueFullException("Queue at critical threshold")
                );
                case CRITICAL_PLUS -> QueueDecision.drop();
            };
        };
    }
    
    private static class RetryStrategy<T> implements QueueThresholdHandler<T> {
        private final Duration initialDelay;
        private final Duration maxDelay;
        private final double multiplier;
        
        private RetryStrategy(Duration initialDelay, Duration maxDelay, double multiplier) {
            this.initialDelay = initialDelay;
            this.maxDelay = maxDelay;
            this.multiplier = multiplier;
        }
        
        @Override
        public QueueDecision handle(QueueThresholdContext<T> context) {
            // Calculate delay based on how many times we've retried
            // (This is simplified - real implementation would track retry count)
            Duration delay = initialDelay;
            if (context.level() == ThresholdLevel.CRITICAL_PLUS) {
                delay = Duration.ofMillis((long) (delay.toMillis() * multiplier));
            }
            delay = Duration.ofMillis(Math.min(delay.toMillis(), maxDelay.toMillis()));
            
            return QueueDecision.retry(delay);
        }
    }
}

/**
 * Exception thrown when queue is full.
 */
public class QueueFullException extends RuntimeException {
    private final int queueDepth;
    private final int threshold;
    private final ThresholdLevel level;
    
    public QueueFullException(String message) {
        this(message, null, 0, 0, null);
    }
    
    public QueueFullException(String message, int queueDepth, int threshold, ThresholdLevel level) {
        this(message, null, queueDepth, threshold, level);
    }
    
    public QueueFullException(String message, Throwable cause, 
                             int queueDepth, int threshold, ThresholdLevel level) {
        super(message, cause);
        this.queueDepth = queueDepth;
        this.threshold = threshold;
        this.level = level;
    }
    
    public int getQueueDepth() {
        return queueDepth;
    }
    
    public int getThreshold() {
        return threshold;
    }
    
    public ThresholdLevel getLevel() {
        return level;
    }
}
```

### 3. QueueThresholdListener (Observability)

**Purpose**: Event listeners for observability (metrics, logging, monitoring).

```java
package com.vajrapulse.vortex.backpressure;

/**
 * Listener for queue threshold events.
 * 
 * <p>Listeners are notified when thresholds are breached, regardless of
 * the handler's decision. This allows applications to:
 * <ul>
 *   <li>Track metrics (threshold breaches, queue depth)</li>
 *   <li>Log events</li>
 *   <li>Notify external systems</li>
 *   <li>Monitor system health</li>
 * </ul>
 * 
 * <p><strong>Performance Note:</strong> Listeners are called asynchronously
 * and should not block. Slow listeners may be moved to a separate thread pool.
 * 
 * @param <T> the item type
 */
public interface QueueThresholdListener<T> {
    /**
     * Called when a threshold is breached.
     * 
     * @param event the threshold event
     */
    void onThresholdBreached(QueueThresholdEvent<T> event);
}

/**
 * Event fired when threshold is breached.
 */
public record QueueThresholdEvent<T>(
    /**
     * The item that triggered the threshold (may be null for aggregate events).
     */
    T item,
    
    /**
     * Current queue depth.
     */
    int queueDepth,
    
    /**
     * The threshold that was breached.
     */
    int threshold,
    
    /**
     * The level of threshold breached.
     */
    ThresholdLevel level,
    
    /**
     * The decision made by the handler (if any).
     */
    QueueDecision decision,
    
    /**
     * Timestamp when threshold was breached.
     */
    long timestamp
) {
    public QueueThresholdEvent(
            T item, int queueDepth, int threshold, ThresholdLevel level, QueueDecision decision) {
        this(item, queueDepth, threshold, level, decision, System.currentTimeMillis());
    }
}
```

## Configuration API

### QueueThresholdConfig

```java
package com.vajrapulse.vortex.backpressure;

/**
 * Configuration for queue thresholds.
 */
public class QueueThresholdConfig {
    private int maxQueueSize = 1000;
    private int warningThreshold = 500;      // 50% of max
    private int criticalThreshold = 1000;     // 100% of max
    private int criticalPlusThreshold = 1500; // 150% of max
    
    private QueueThresholdHandler<?> warningHandler;
    private QueueThresholdHandler<?> criticalHandler;
    private QueueThresholdHandler<?> criticalPlusHandler;
    
    private QueueAction defaultAction = QueueAction.REJECT;
    
    private final List<QueueThresholdListener<?>> listeners = new CopyOnWriteArrayList<>();
    
    // Builder methods
    public QueueThresholdConfig maxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
        return this;
    }
    
    public QueueThresholdConfig warningThreshold(int threshold) {
        this.warningThreshold = threshold;
        return this;
    }
    
    public QueueThresholdConfig criticalThreshold(int threshold) {
        this.criticalThreshold = threshold;
        return this;
    }
    
    public QueueThresholdConfig criticalPlusThreshold(int threshold) {
        this.criticalPlusThreshold = threshold;
        return this;
    }
    
    public <T> QueueThresholdConfig warningHandler(QueueThresholdHandler<T> handler) {
        this.warningHandler = handler;
        return this;
    }
    
    public <T> QueueThresholdConfig criticalHandler(QueueThresholdHandler<T> handler) {
        this.criticalHandler = handler;
        return this;
    }
    
    public <T> QueueThresholdConfig criticalPlusHandler(QueueThresholdHandler<T> handler) {
        this.criticalPlusHandler = handler;
        return this;
    }
    
    public QueueThresholdConfig defaultAction(QueueAction action) {
        this.defaultAction = action;
        return this;
    }
    
    public <T> QueueThresholdConfig addListener(QueueThresholdListener<T> listener) {
        this.listeners.add(listener);
        return this;
    }
    
    // Getters
    public int getMaxQueueSize() { return maxQueueSize; }
    public int getWarningThreshold() { return warningThreshold; }
    public int getCriticalThreshold() { return criticalThreshold; }
    public int getCriticalPlusThreshold() { return criticalPlusThreshold; }
    public QueueThresholdHandler<?> getWarningHandler() { return warningHandler; }
    public QueueThresholdHandler<?> getCriticalHandler() { return criticalHandler; }
    public QueueThresholdHandler<?> getCriticalPlusHandler() { return criticalPlusHandler; }
    public QueueAction getDefaultAction() { return defaultAction; }
    public List<QueueThresholdListener<?>> getListeners() { return listeners; }
}
```

## MicroBatcher Integration

### Enhanced MicroBatcher API

```java
package com.vajrapulse.vortex;

/**
 * Enhanced MicroBatcher with queue threshold support.
 */
public class MicroBatcher<T> {
    private final Backend<T> backend;
    private final BatcherConfig config;
    private final QueueThresholdManager thresholdManager;
    
    // Existing constructor (backward compatible)
    public MicroBatcher(Backend<T> backend, BatcherConfig config) {
        this(backend, config, null);
    }
    
    // New constructor with threshold config
    public MicroBatcher(Backend<T> backend, BatcherConfig config, 
                       QueueThresholdConfig thresholdConfig) {
        this.backend = backend;
        this.config = config;
        this.thresholdManager = thresholdConfig != null 
            ? new QueueThresholdManager<>(thresholdConfig)
            : null;
    }
    
    // Factory method for convenience
    public static <T> MicroBatcher<T> withThresholds(
            Backend<T> backend,
            BatcherConfig config,
            QueueThresholdConfig thresholdConfig) {
        return new MicroBatcher<>(backend, config, thresholdConfig);
    }
    
    /**
     * Submits an item to the batcher.
     * 
     * <p>If threshold configuration is provided, checks queue depth and
     * invokes handlers/listeners before queuing.
     */
    @Override
    public CompletableFuture<ItemResult<T>> submit(T item) {
        // Check thresholds if configured
        if (thresholdManager != null) {
            QueueDecision decision = thresholdManager.checkThreshold(item);
            
            if (decision != null) {
                return applyDecision(item, decision);
            }
        }
        
        // Normal flow - queue item
        return super.submit(item);
    }
    
    private CompletableFuture<ItemResult<T>> applyDecision(T item, QueueDecision decision) {
        return switch (decision.action()) {
            case ACCEPT -> super.submit(item);
            case REJECT -> CompletableFuture.completedFuture(
                ItemResult.failure(decision.rejectionReason())
            );
            case DROP -> CompletableFuture.completedFuture(
                ItemResult.success(item)  // Success but no callback (silent drop)
            );
            case RETRY -> retryAfterDelay(item, decision.retryDelay());
        };
    }
    
    private CompletableFuture<ItemResult<T>> retryAfterDelay(T item, Duration delay) {
        CompletableFuture<ItemResult<T>> future = new CompletableFuture<>();
        
        // Schedule retry
        CompletableFuture.delayedExecutor(delay)
            .execute(() -> {
                // Retry submission
                submit(item).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                    } else {
                        future.complete(result);
                    }
                });
            });
        
        return future;
    }
}
```

### QueueThresholdManager Implementation

```java
package com.vajrapulse.vortex.backpressure;

/**
 * Manages queue threshold checking and handler invocation.
 */
class QueueThresholdManager<T> {
    private final QueueThresholdConfig config;
    private final Supplier<Integer> queueDepthSupplier;
    
    QueueThresholdManager(QueueThresholdConfig config) {
        this.config = config;
        // Queue depth supplier - will be injected by MicroBatcher
        this.queueDepthSupplier = () -> {
            // Get queue depth from MicroBatcher
            // This will be implemented when integrated
            return 0;
        };
    }
    
    /**
     * Checks if threshold is breached and invokes handler.
     * 
     * @param item the item being submitted
     * @return decision if threshold breached, null otherwise
     */
    QueueDecision checkThreshold(T item) {
        int queueDepth = queueDepthSupplier.get();
        
        // Check critical+ first (most severe)
        if (queueDepth >= config.getCriticalPlusThreshold()) {
            return handleThreshold(item, queueDepth, 
                config.getCriticalPlusThreshold(), 
                ThresholdLevel.CRITICAL_PLUS,
                config.getCriticalPlusHandler());
        }
        
        // Check critical
        if (queueDepth >= config.getCriticalThreshold()) {
            return handleThreshold(item, queueDepth,
                config.getCriticalThreshold(),
                ThresholdLevel.CRITICAL,
                config.getCriticalHandler());
        }
        
        // Check warning
        if (queueDepth >= config.getWarningThreshold()) {
            return handleThreshold(item, queueDepth,
                config.getWarningThreshold(),
                ThresholdLevel.WARNING,
                config.getWarningHandler());
        }
        
        // No threshold breached
        return null;
    }
    
    @SuppressWarnings("unchecked")
    private QueueDecision handleThreshold(
            T item, int queueDepth, int threshold, ThresholdLevel level,
            QueueThresholdHandler<?> handler) {
        
        QueueThresholdContext<T> context = new QueueThresholdContext<>(
            item, queueDepth, threshold, level
        );
        
        QueueDecision decision;
        
        if (handler != null) {
            // Use handler
            decision = ((QueueThresholdHandler<T>) handler).handle(context);
        } else {
            // Use default action
            decision = createDefaultDecision(context);
        }
        
        // Fire events to listeners (async, non-blocking)
        fireEvent(context, decision);
        
        return decision;
    }
    
    private QueueDecision createDefaultDecision(QueueThresholdContext<T> context) {
        return switch (config.getDefaultAction()) {
            case ACCEPT -> QueueDecision.accept();
            case REJECT -> QueueDecision.reject(
                new QueueFullException(
                    String.format("Queue full: depth=%d, threshold=%d, level=%s",
                        context.queueDepth(), context.threshold(), context.level()),
                    context.queueDepth(), context.threshold(), context.level()
                )
            );
            case DROP -> QueueDecision.drop();
            case RETRY -> QueueDecision.retry(Duration.ofMillis(100));
        };
    }
    
    @SuppressWarnings("unchecked")
    private void fireEvent(QueueThresholdContext<T> context, QueueDecision decision) {
        QueueThresholdEvent<T> event = new QueueThresholdEvent<>(
            context.item(),
            context.queueDepth(),
            context.threshold(),
            context.level(),
            decision
        );
        
        // Fire to all listeners (async)
        for (QueueThresholdListener<?> listener : config.getListeners()) {
            try {
                ((QueueThresholdListener<T>) listener).onThresholdBreached(event);
            } catch (Exception e) {
                // Log but don't fail
                // (Use proper logging framework)
                System.err.println("Listener failed: " + e.getMessage());
            }
        }
    }
}
```

## Usage Examples

### Example 1: Simple Reject Strategy (Proactive)

```java
// Use library-provided reject strategy
QueueThresholdConfig config = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .criticalThreshold(1000)
    .criticalHandler(QueueThresholdStrategies.reject());

MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, batcherConfig, config
);
```

### Example 2: Custom Handler (Reactive)

```java
// Application provides custom handler
QueueThresholdHandler<Item> handler = context -> {
    // Application logic
    if (isCritical(context.item())) {
        return QueueDecision.accept();  // Always accept critical
    } else if (context.level() == ThresholdLevel.CRITICAL_PLUS) {
        return QueueDecision.drop();  // Drop non-critical at critical+
    } else {
        return QueueDecision.reject(
            new QueueFullException("Queue full")
        );
    }
};

QueueThresholdConfig config = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .criticalThreshold(1000)
    .criticalHandler(handler);

MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, batcherConfig, config
);
```

### Example 3: Multiple Thresholds with Different Handlers

```java
QueueThresholdConfig config = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .warningThreshold(500)        // 50%
    .criticalThreshold(1000)      // 100%
    .criticalPlusThreshold(1500) // 150%
    .warningHandler(context -> {
        // Warning: Log but accept
        log.warn("Queue depth {} reached warning threshold {}", 
            context.queueDepth(), context.threshold());
        return QueueDecision.accept();
    })
    .criticalHandler(context -> {
        // Critical: Reject
        return QueueDecision.reject(
            new QueueFullException("Queue at critical threshold")
        );
    })
    .criticalPlusHandler(context -> {
        // Critical+: Drop to prevent queue growth
        return QueueDecision.drop();
    });

MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, batcherConfig, config
);
```

### Example 4: Priority-Based Handling

```java
QueueThresholdHandler<Item> handler = context -> {
    Item item = context.item();
    
    if (item.getPriority() == Priority.CRITICAL) {
        return QueueDecision.accept();  // Always accept critical
    } else if (item.getPriority() == Priority.HIGH) {
        if (context.level() == ThresholdLevel.CRITICAL_PLUS) {
            return QueueDecision.retry(Duration.ofMillis(100));
        }
        return QueueDecision.accept();
    } else {
        // Low priority: drop at critical+
        if (context.level() == ThresholdLevel.CRITICAL_PLUS) {
            return QueueDecision.drop();
        }
        return QueueDecision.reject(
            new QueueFullException("Queue full")
        );
    }
};

QueueThresholdConfig config = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .criticalThreshold(1000)
    .criticalHandler(handler);

MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, batcherConfig, config
);
```

### Example 5: Dead-Letter Queue Integration

```java
QueueThresholdHandler<Item> handler = context -> {
    if (context.level() == ThresholdLevel.CRITICAL_PLUS) {
        // Route to DLQ
        deadLetterQueue.send(context.item());
        return QueueDecision.drop();  // Drop after routing
    }
    return QueueDecision.reject(
        new QueueFullException("Queue full")
    );
};

QueueThresholdConfig config = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .criticalPlusThreshold(1500)
    .criticalPlusHandler(handler);

MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, batcherConfig, config
);
```

### Example 6: Metrics and Observability

```java
// Add metrics listener
QueueThresholdListener<Item> metricsListener = event -> {
    meterRegistry.counter("vortex.queue.threshold.breached",
        "level", event.level().name())
        .increment();
    
    meterRegistry.gauge("vortex.queue.depth", event.queueDepth());
    
    meterRegistry.gauge("vortex.queue.threshold",
        event.threshold());
};

// Add logging listener
QueueThresholdListener<Item> loggingListener = event -> {
    log.warn("Queue threshold breached: level={}, depth={}, threshold={}, decision={}",
        event.level(), event.queueDepth(), event.threshold(), event.decision().action());
};

QueueThresholdConfig config = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .criticalThreshold(1000)
    .criticalHandler(QueueThresholdStrategies.reject())
    .addListener(metricsListener)
    .addListener(loggingListener);

MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, batcherConfig, config
);
```

## Implementation Steps

### Phase 1: Core Interfaces (v0.1.0)

1. **Create Core Interfaces**
   - [ ] `QueueThresholdHandler<T>`
   - [ ] `QueueThresholdContext<T>`
   - [ ] `QueueDecision`
   - [ ] `QueueAction` enum
   - [ ] `ThresholdLevel` enum
   - [ ] `QueueFullException`

2. **Create Configuration**
   - [ ] `QueueThresholdConfig` class
   - [ ] Builder methods
   - [ ] Default values

3. **Create Strategies**
   - [ ] `QueueThresholdStrategies` utility class
   - [ ] `reject()` strategy
   - [ ] `drop()` strategy
   - [ ] `accept()` strategy
   - [ ] `retry()` strategy
   - [ ] `gracefulDegradation()` strategy

4. **Create Manager**
   - [ ] `QueueThresholdManager` class
   - [ ] Threshold checking logic
   - [ ] Handler invocation
   - [ ] Default decision creation

### Phase 2: MicroBatcher Integration (v0.1.1)

1. **Integrate into MicroBatcher**
   - [ ] Add `QueueThresholdConfig` parameter to constructor
   - [ ] Add `withThresholds()` factory method
   - [ ] Integrate `QueueThresholdManager`
   - [ ] Implement `checkThreshold()` in `submit()`
   - [ ] Implement `applyDecision()`
   - [ ] Implement `retryAfterDelay()`

2. **Queue Depth Access**
   - [ ] Add `getQueueDepth()` method to `MicroBatcher`
   - [ ] Wire queue depth supplier to `QueueThresholdManager`

### Phase 3: Event Listeners (v0.1.2)

1. **Create Listener Interface**
   - [ ] `QueueThresholdListener<T>`
   - [ ] `QueueThresholdEvent<T>`

2. **Integrate Listeners**
   - [ ] Add listener list to `QueueThresholdConfig`
   - [ ] Fire events in `QueueThresholdManager`
   - [ ] Async event firing (non-blocking)

### Phase 4: Testing (v0.1.3)

1. **Unit Tests**
   - [ ] Test `QueueThresholdManager` threshold checking
   - [ ] Test handler invocation
   - [ ] Test default decisions
   - [ ] Test strategies
   - [ ] Test event firing

2. **Integration Tests**
   - [ ] Test with `MicroBatcher`
   - [ ] Test ACCEPT decision
   - [ ] Test REJECT decision
   - [ ] Test DROP decision
   - [ ] Test RETRY decision
   - [ ] Test multiple thresholds
   - [ ] Test listeners

3. **Performance Tests**
   - [ ] Handler overhead measurement
   - [ ] Event firing overhead
   - [ ] Queue depth check overhead

## Backward Compatibility

### Existing Code Continues to Work

```java
// Existing code (no thresholds) - still works
MicroBatcher<Item> batcher = new MicroBatcher<>(backend, config);
```

### Migration Path

```java
// Step 1: Add threshold config (optional)
QueueThresholdConfig thresholdConfig = new QueueThresholdConfig()
    .maxQueueSize(1000)
    .criticalThreshold(1000)
    .criticalHandler(QueueThresholdStrategies.reject());

// Step 2: Use new constructor (optional)
MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
    backend, config, thresholdConfig
);

// Step 3: Add custom handlers (optional)
thresholdConfig.criticalHandler(customHandler);

// Step 4: Add listeners (optional)
thresholdConfig.addListener(metricsListener);
```

## Performance Considerations

### Handler Performance

**Critical**: Handlers are called synchronously during `submit()`. They must return quickly.

**Recommendations**:
1. Keep handler logic simple
2. Avoid blocking operations
3. Use async patterns for expensive operations
4. Consider thread pool for complex handlers

### Event Listener Performance

**Non-Critical**: Listeners are called asynchronously and don't block submission.

**Recommendations**:
1. Use async event firing
2. Consider separate thread pool for listeners
3. Batch events if needed

### Queue Depth Check Performance

**Critical**: Queue depth check happens on every `submit()`.

**Recommendations**:
1. Cache queue depth (update periodically)
2. Use atomic operations
3. Minimize synchronization

## Testing Strategy

### Unit Tests

```java
@Test
void testThresholdCheck() {
    QueueThresholdConfig config = new QueueThresholdConfig()
        .maxQueueSize(1000)
        .criticalThreshold(1000);
    
    QueueThresholdManager<Item> manager = new QueueThresholdManager<>(config);
    
    // Mock queue depth supplier
    AtomicInteger queueDepth = new AtomicInteger(0);
    // ... set queue depth supplier
    
    // Test no threshold breached
    queueDepth.set(500);
    assertNull(manager.checkThreshold(item));
    
    // Test critical threshold breached
    queueDepth.set(1000);
    QueueDecision decision = manager.checkThreshold(item);
    assertNotNull(decision);
    assertEquals(QueueAction.REJECT, decision.action());
}
```

### Integration Tests

```java
@Test
void testMicroBatcherWithThresholds() {
    QueueThresholdConfig config = new QueueThresholdConfig()
        .maxQueueSize(100)
        .criticalThreshold(100)
        .criticalHandler(QueueThresholdStrategies.reject());
    
    MicroBatcher<Item> batcher = MicroBatcher.withThresholds(
        backend, batcherConfig, config
    );
    
    // Fill queue to threshold
    for (int i = 0; i < 100; i++) {
        batcher.submit(item);
    }
    
    // Next submit should be rejected
    CompletableFuture<ItemResult<Item>> future = batcher.submit(item);
    ItemResult<Item> result = future.join();
    assertTrue(result.isFailure());
    assertInstanceOf(QueueFullException.class, result.getException());
}
```

## Documentation Requirements

1. **API Documentation**
   - JavaDoc for all public interfaces and classes
   - Usage examples
   - Performance notes

2. **User Guide**
   - Getting started guide
   - Common patterns
   - Best practices
   - Migration guide

3. **Architecture Documentation**
   - Design decisions
   - Performance considerations
   - Extension points

## Conclusion

This hybrid implementation provides:

1. ✅ **Flexibility**: Applications control decisions via handlers
2. ✅ **Simplicity**: Library provides sensible defaults via strategies
3. ✅ **Observability**: Event listeners for metrics/logging
4. ✅ **Backward Compatibility**: Existing code continues to work
5. ✅ **Performance**: Minimal overhead, async event firing
6. ✅ **Testability**: Easy to test handlers and strategies

The design balances **reactive control** (handlers) with **proactive convenience** (strategies), giving applications maximum flexibility while keeping simple use cases simple.

