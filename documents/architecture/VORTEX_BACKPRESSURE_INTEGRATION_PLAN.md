# Vortex Backpressure Integration Plan

## Executive Summary

This document outlines a **simplified plan** to integrate backpressure handling into the **Vortex MicroBatching Library** as a first-class, generic feature. The goal is to create a reusable, flexible backpressure system that can be used across any batching scenario, not just database operations or load testing.

### Key Simplifications (50% Code Reduction)

This plan has been **simplified** from the original design to reduce complexity by ~50%:

1. **Simplified BackpressureResult**: Enum + record instead of sealed interface (removes `Throttled` type)
2. **Deferred ThrottleStrategy**: Moved to Phase 2 (v0.2.0) - see `VORTEX_BACKPRESSURE_ENHANCEMENTS.md`
3. **Integrated into MicroBatcher**: No separate `BackpressureAwareMicroBatcher` class
4. **Linear Scaling**: Simple linear scaling in `QueueDepthBackpressureProvider` (logarithmic deferred to Phase 2)
5. **Varargs Constructor**: Simplified `CompositeBackpressureProvider` (no builder pattern)
6. **Use Existing Metrics**: Leverage MicroBatcher's existing metrics infrastructure

**Result**: ~400 lines of code vs ~800 lines in original design, while maintaining all core functionality.

**See Also**: `VORTEX_BACKPRESSURE_ENHANCEMENTS.md` for Phase 2 enhancements (ThrottleStrategy, logarithmic scaling, comprehensive metrics, etc.)

## Current State Analysis

### What We Have (Application-Specific)

1. **`HikariCPBackpressureProvider`** (Application Code)
   - ✅ Detects backpressure from HikariCP connection pool
   - ✅ Uses logarithmic scaling for early detection
   - ✅ Returns 0.0-1.0 scale
   - ❌ **Tied to HikariCP** - not reusable
   - ❌ **Tied to VajraPulse** - uses `com.vajrapulse.api.BackpressureProvider`

2. **`EmergencyBackpressureLoadPattern`** (Application Code)
   - ✅ Provides immediate TPS reduction
   - ✅ Gradual recovery mechanism
   - ❌ **Tied to VajraPulse** - wraps `LoadPattern`
   - ❌ **Not integrated with Vortex** - operates at load pattern level, not batching level

3. **Vortex MicroBatcher** (Library)
   - ✅ Handles batching efficiently
   - ✅ Provides callbacks for batch results
   - ❌ **No backpressure awareness** - accepts all items regardless of system state
   - ❌ **No flow control** - queue can grow unbounded
   - ❌ **No rejection mechanism** - items always queued

### Key Insights

1. **Backpressure Detection vs. Handling**: These are separate concerns that should be decoupled
2. **Multiple Backpressure Sources**: Connection pools, queue depth, memory, CPU, etc.
3. **Multiple Handling Strategies**: Drop, reject, throttle, degrade, circuit breaker
4. **Library Independence**: Vortex should not depend on VajraPulse or any specific framework
5. **Composability**: Multiple backpressure sources should be composable

## Design Principles

### 1. Separation of Concerns
- **Backpressure Detection**: Generic interface for reporting system pressure
- **Backpressure Handling**: Strategies for responding to pressure
- **Integration Points**: Clean hooks for external systems (VajraPulse, Spring, etc.)

### 2. Generic and Framework-Agnostic
- No dependencies on VajraPulse, Spring, or any specific framework
- Pure Java interfaces and implementations
- Can be used standalone or integrated with any framework

### 3. Flexibility and Extensibility
- Multiple built-in strategies (DROP, REJECT, THROTTLE, etc.)
- Custom strategies via strategy pattern
- Composable backpressure providers (multiple sources)
- Configurable thresholds and parameters

### 4. Simplicity
- Sensible defaults for common use cases
- Builder pattern for configuration
- Minimal boilerplate for basic usage
- Clear, descriptive API

### 5. Observability
- Built-in metrics for backpressure events
- Optional callbacks for backpressure events
- Integration with Micrometer (optional, not required)

## Architecture Design (Simplified)

### Design Philosophy

This design follows the **"make it work, make it right, make it fast"** philosophy:
- **Phase 1 (v0.1.0)**: Minimal viable backpressure - simple, focused, ~50% less code
- **Phase 2 (v0.2.0)**: Enhanced features - add sophistication based on real-world needs

### Core Components

```
┌─────────────────────────────────────────────────────────────┐
│                    Vortex MicroBatcher                       │
│  - Optional backpressure support (integrated, not separate)   │
│  - Checks backpressure before accepting items                │
│  - Applies backpressure strategy                             │
│  - Uses existing metrics infrastructure                      │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ uses
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              BackpressureProvider Interface                  │
│  - getBackpressureLevel(): double (0.0-1.0)                 │
│  - getSourceName(): String                                  │
│  - Optional: getDetails(): Map<String, Object>              │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │ implements
        ┌───────────────────┼───────────────────┐
        │                   │                   │
┌───────┴──────┐  ┌──────────┴──────────┐  ┌────┴──────────────┐
│ QueueDepth   │  │ ConnectionPool      │  │ Composite         │
│ Provider     │  │ Provider            │  │ Provider          │
│ (linear)     │  │ (optional module)   │  │ (varargs)        │
└─────────────┘  └─────────────────────┘  └───────────────────┘
                            │
                            │ uses
                            ▼
┌─────────────────────────────────────────────────────────────┐
│           BackpressureStrategy Interface                     │
│  - handle(BackpressureContext): BackpressureResult<T>       │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │ implements
                ┌───────────┴───────────┐
                │                       │
        ┌───────┴──────┐      ┌─────────┴──────────┐
        │ DROP        │      │ REJECT             │
        │ Strategy    │      │ Strategy           │
        └─────────────┘      └────────────────────┘
        
        (THROTTLE deferred to Phase 2)
```

### Component Details

#### 1. BackpressureProvider Interface

**Purpose**: Generic interface for reporting system backpressure

**Location**: `com.vajrapulse.vortex.backpressure.BackpressureProvider`

**API Design**:
```java
/**
 * Provides backpressure level from a system resource.
 * 
 * <p>Backpressure is reported on a scale of 0.0 to 1.0:
 * <ul>
 *   <li>0.0 - 0.3: Low pressure, system can accept more load</li>
 *   <li>0.3 - 0.7: Moderate pressure, system is approaching capacity</li>
 *   <li>0.7 - 1.0: High pressure, system is overloaded</li>
 * </ul>
 * 
 * <p>Implementations should be thread-safe and fast (avoid blocking operations).
 * 
 * @param <T> Optional context type for provider-specific details
 */
public interface BackpressureProvider {
    /**
     * Gets the current backpressure level.
     * 
     * @return backpressure level from 0.0 (no pressure) to 1.0 (maximum pressure)
     */
    double getBackpressureLevel();
    
    /**
     * Gets a human-readable name for this backpressure source.
     * 
     * @return source name (e.g., "HikariCP Connection Pool", "Queue Depth")
     */
    String getSourceName();
    
    /**
     * Gets optional details about the current backpressure state.
     * 
     * <p>Useful for debugging and monitoring. May return empty map if no details available.
     * 
     * @return map of detail key-value pairs (e.g., {"active": 10, "total": 20, "waiting": 5})
     */
    default Map<String, Object> getDetails() {
        return Map.of();
    }
}
```

**Key Design Decisions**:
- **0.0-1.0 Scale**: Standardized scale for all providers
- **Thread-Safe**: Must be safe to call from multiple threads
- **Fast**: Should not block or perform expensive operations
- **Optional Details**: Allows providers to expose diagnostic information

#### 2. Built-in Backpressure Providers

##### 2.1 QueueDepthBackpressureProvider

**Purpose**: Reports backpressure based on internal queue depth

**Location**: `com.vajrapulse.vortex.backpressure.QueueDepthBackpressureProvider`

**Implementation (Simplified - Linear Scaling)**:
```java
/**
 * Backpressure provider based on queue depth.
 * 
 * <p>Calculates backpressure based on the ratio of queued items to maximum queue capacity.
 * Uses simple linear scaling (can be enhanced with logarithmic scaling in Phase 2).
 * 
 * <p>Backpressure calculation:
 * <ul>
 *   <li>queueDepth = 0: backpressure = 0.0</li>
 *   <li>queueDepth < maxCapacity: backpressure = queueDepth / maxCapacity (linear)</li>
 *   <li>queueDepth >= maxCapacity: backpressure = 1.0</li>
 * </ul>
 * 
 * <p>Note: Linear scaling is simpler and still effective. Logarithmic scaling can be
 * added as an optional feature in Phase 2 if early pressure detection is needed.
 */
public class QueueDepthBackpressureProvider implements BackpressureProvider {
    private final Supplier<Integer> queueDepthSupplier;
    private final int maxCapacity;
    
    public QueueDepthBackpressureProvider(
            Supplier<Integer> queueDepthSupplier,
            int maxCapacity) {
        this.queueDepthSupplier = queueDepthSupplier;
        this.maxCapacity = maxCapacity;
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0");
        }
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
        
        // Simple linear scaling
        return (double) queueDepth / maxCapacity;
    }
    
    @Override
    public String getSourceName() {
        return "Vortex Queue Depth";
    }
    
    @Override
    public Map<String, Object> getDetails() {
        int queueDepth = queueDepthSupplier.get();
        return Map.of(
            "queueDepth", queueDepth,
            "maxCapacity", maxCapacity,
            "utilization", String.format("%.2f", (double) queueDepth / maxCapacity * 100.0)
        );
    }
}
```

**Key Features**:
- **Simple Linear Scaling**: Easy to understand and maintain
- **Effective**: Linear scaling works well for most use cases
- **Extensible**: Can add logarithmic scaling option in Phase 2
- **Queue-Aware**: Uses actual queue depth from MicroBatcher

##### 2.2 CompositeBackpressureProvider

**Purpose**: Combines multiple backpressure sources

**Location**: `com.vajrapulse.vortex.backpressure.CompositeBackpressureProvider`

**Implementation (Simplified - Varargs Constructor)**:
```java
/**
 * Combines multiple backpressure providers into a single provider.
 * 
 * <p>Uses the maximum backpressure level from all providers (worst-case scenario).
 * This ensures that if any resource is under pressure, the system responds.
 * 
 * <p>Example: Combine connection pool and queue depth backpressure
 * <pre>{@code
 * BackpressureProvider composite = new CompositeBackpressureProvider(
 *     connectionPoolProvider,
 *     queueDepthProvider
 * );
 * }</pre>
 */
public class CompositeBackpressureProvider implements BackpressureProvider {
    private final List<BackpressureProvider> providers;
    
    public CompositeBackpressureProvider(BackpressureProvider... providers) {
        this.providers = List.of(providers);
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one provider required");
        }
    }
    
    @Override
    public double getBackpressureLevel() {
        return providers.stream()
            .mapToDouble(BackpressureProvider::getBackpressureLevel)
            .max()
            .orElse(0.0);
    }
    
    @Override
    public String getSourceName() {
        return "Composite (" + providers.size() + " sources)";
    }
    
    @Override
    public Map<String, Object> getDetails() {
        // Simplified: just return max level (can enhance with per-provider details in Phase 2)
        return Map.of("maxBackpressure", getBackpressureLevel());
    }
}
```

**Key Features**:
- **Maximum Aggregation**: Uses worst-case backpressure (most conservative)
- **Simple API**: Varargs constructor (no builder pattern)
- **Composable**: Can combine any number of providers
- **Extensible**: Can add detailed per-provider diagnostics in Phase 2

#### 3. BackpressureStrategy Interface

**Purpose**: Defines how to handle items when backpressure is detected

**Location**: `com.vajrapulse.vortex.backpressure.BackpressureStrategy`

**API Design (Simplified)**:
```java
/**
 * Strategy for handling items when backpressure is detected.
 * 
 * <p>Different strategies provide different behaviors:
 * <ul>
 *   <li>ACCEPT: Accept the item and proceed normally</li>
 *   <li>REJECT: Reject with failure callback</li>
 *   <li>DROP: Silently drop the item (no callback, no error)</li>
 * </ul>
 * 
 * <p>Note: THROTTLE strategy deferred to Phase 2.
 */
public interface BackpressureStrategy<T> {
    /**
     * Handles an item when backpressure is detected.
     * 
     * @param context the backpressure context
     * @return result indicating how the item was handled
     */
    BackpressureResult<T> handle(BackpressureContext<T> context);
}

/**
 * Context for backpressure handling.
 */
public record BackpressureContext<T>(
    T item,
    double backpressureLevel,
    BackpressureProvider provider
) {}

/**
 * Result of backpressure handling (simplified - enum + record).
 */
public enum BackpressureAction {
    ACCEPT,    // Item accepted, proceed normally
    REJECT,    // Item rejected, return failure callback
    DROP       // Item dropped, no callback (silent)
}

public record BackpressureResult<T>(
    BackpressureAction action,
    T item,
    Exception reason  // null for ACCEPT and DROP
) {
    public static <T> BackpressureResult<T> accept(T item) {
        return new BackpressureResult<>(BackpressureAction.ACCEPT, item, null);
    }
    
    public static <T> BackpressureResult<T> reject(T item, Exception reason) {
        return new BackpressureResult<>(BackpressureAction.REJECT, item, reason);
    }
    
    public static <T> BackpressureResult<T> drop(T item) {
        return new BackpressureResult<>(BackpressureAction.DROP, item, null);
    }
}
```

**Key Design Decisions**:
- **Strategy Pattern**: Allows different behaviors
- **Enum + Record**: Simpler than sealed interface, easier pattern matching
- **No Timestamp**: Removed from context (can add in Phase 2 if needed for metrics)
- **No Strategy Name**: Removed from interface (can use class name)
- **No Throttle**: Deferred to Phase 2 (reduces complexity by ~100 lines)

#### 4. Built-in Backpressure Strategies

##### 4.1 DropStrategy

**Purpose**: Silently drop items when backpressure is high

**Location**: `com.vajrapulse.vortex.backpressure.DropStrategy`

**Implementation**:
```java
/**
 * Drops items silently when backpressure exceeds threshold.
 * 
 * <p>Useful when:
 * <ul>
 *   <li>Items are not critical (e.g., metrics, logs)</li>
 *   <li>Dropping is preferable to queuing (prevents memory growth)</li>
 *   <li>System should prioritize stability over completeness</li>
 * </ul>
 * 
 * <p>Items are dropped without calling failure callbacks.
 */
public class DropStrategy<T> implements BackpressureStrategy<T> {
    private final double threshold;  // Default: 0.7
    
    public DropStrategy(double threshold) {
        this.threshold = threshold;
    }
    
    @Override
    public BackpressureResult<T> handle(BackpressureContext<T> context) {
        if (context.backpressureLevel() >= threshold) {
            return BackpressureResult.drop(context.item());
        }
        return BackpressureResult.accept(context.item());
    }
}
```

##### 4.2 RejectStrategy

**Purpose**: Reject items with failure callback when backpressure is high

**Location**: `com.vajrapulse.vortex.backpressure.RejectStrategy`

**Implementation**:
```java
/**
 * Rejects items with failure callback when backpressure exceeds threshold.
 * 
 * <p>Useful when:
 * <ul>
 *   <li>Caller needs to know item was rejected</li>
 *   <li>Caller can retry or handle rejection</li>
 *   <li>Rejection is better than silent dropping</li>
 * </ul>
 * 
 * <p>Items are rejected with a BackpressureException.
 */
public class RejectStrategy<T> implements BackpressureStrategy<T> {
    private final double threshold;  // Default: 0.7
    
    public RejectStrategy(double threshold) {
        this.threshold = threshold;
    }
    
    @Override
    public BackpressureResult<T> handle(BackpressureContext<T> context) {
        if (context.backpressureLevel() >= threshold) {
            Exception reason = new BackpressureException(
                String.format("Backpressure too high: %.2f (threshold: %.2f, source: %s)",
                    context.backpressureLevel(), threshold, context.provider().getSourceName())
            );
            return BackpressureResult.reject(context.item(), reason);
        }
        return BackpressureResult.accept(context.item());
    }
}
```

**Note**: `ThrottleStrategy` is **deferred to Phase 2** (v0.2.0) to reduce initial complexity. See `VORTEX_BACKPRESSURE_ENHANCEMENTS.md` for Phase 2 features.

#### 5. MicroBatcher Integration (Simplified)

**Purpose**: Add backpressure as optional feature to MicroBatcher itself (no separate class)

**Location**: `com.vajrapulse.vortex.MicroBatcher` (enhanced)

**API Design**:
```java
/**
 * MicroBatcher with optional backpressure support.
 * 
 * <p>Backpressure is integrated directly into MicroBatcher as an optional feature.
 * This maintains backward compatibility while adding backpressure capabilities.
 * 
 * <p>Example usage:
 * <pre>{@code
 * BackpressureProvider provider = new QueueDepthBackpressureProvider(
 *     () -> batcher.getQueueDepth(),
 *     1000
 * );
 * 
 * BackpressureStrategy<Item> strategy = new RejectStrategy<>(0.7);
 * 
 * MicroBatcher<Item> batcher = MicroBatcher.withBackpressure(
 *     backend,
 *     config,
 *     provider,
 *     strategy
 * );
 * }</pre>
 */
public class MicroBatcher<T> {
    private final Backend<T> backend;
    private final BatcherConfig config;
    private final BackpressureProvider backpressureProvider;  // Optional
    private final BackpressureStrategy<T> backpressureStrategy;  // Optional
    
    // Existing constructor (backward compatible)
    public MicroBatcher(Backend<T> backend, BatcherConfig config) {
        this(backend, config, null, null);
    }
    
    // New constructor with backpressure
    public MicroBatcher(
            Backend<T> backend,
            BatcherConfig config,
            BackpressureProvider backpressureProvider,
            BackpressureStrategy<T> backpressureStrategy) {
        this.backend = backend;
        this.config = config;
        this.backpressureProvider = backpressureProvider;
        this.backpressureStrategy = backpressureStrategy;
    }
    
    // Factory method for convenience
    public static <T> MicroBatcher<T> withBackpressure(
            Backend<T> backend,
            BatcherConfig config,
            BackpressureProvider provider,
            BackpressureStrategy<T> strategy) {
        return new MicroBatcher<>(backend, config, provider, strategy);
    }
    
    @Override
    public CompletableFuture<ItemResult<T>> submit(T item) {
        // Check backpressure if configured
        if (backpressureProvider != null && backpressureStrategy != null) {
            double backpressure = backpressureProvider.getBackpressureLevel();
            BackpressureContext<T> context = new BackpressureContext<>(
                item, backpressure, backpressureProvider
            );
            BackpressureResult<T> result = backpressureStrategy.handle(context);
            
            return switch (result.action()) {
                case ACCEPT -> super.submit(item);
                case REJECT -> CompletableFuture.completedFuture(
                    ItemResult.failure(result.reason())
                );
                case DROP -> CompletableFuture.completedFuture(
                    ItemResult.success(item)  // Success but no callback (silent drop)
                );
            };
        }
        
        // No backpressure - normal flow
        return super.submit(item);
    }
}
```

**Key Features**:
- **No Separate Class**: Integrated into MicroBatcher directly
- **Backward Compatible**: Existing code continues to work
- **Optional**: Only active when provider and strategy are provided
- **Simpler API**: No builder pattern needed
- **Uses Existing Metrics**: Leverages MicroBatcher's existing metrics infrastructure

#### 6. Metrics (Simplified)

**Purpose**: Use existing MicroBatcher metrics infrastructure

**Approach**: Leverage MicroBatcher's existing Micrometer integration instead of creating a separate metrics class.

**Key Features**:
- **No Separate Metrics Class**: Use existing infrastructure
- **Optional Micrometer**: Works with or without Micrometer (already in MicroBatcher)
- **Can Enhance Later**: Add dedicated backpressure metrics in Phase 2 if needed

## Integration Points

### 1. VajraPulse Integration

**Goal**: Allow VajraPulse `BackpressureProvider` to be used with Vortex

**Approach**: Create adapter

**Location**: `com.vajrapulse.vortex.backpressure.VajraPulseBackpressureAdapter`

**Implementation**:
```java
/**
 * Adapter to use VajraPulse BackpressureProvider with Vortex.
 * 
 * <p>Allows existing VajraPulse backpressure providers (e.g., HikariCPBackpressureProvider)
 * to be used with Vortex MicroBatcher.
 */
public class VajraPulseBackpressureAdapter implements BackpressureProvider {
    private final com.vajrapulse.api.BackpressureProvider vajraPulseProvider;
    
    public VajraPulseBackpressureAdapter(
            com.vajrapulse.api.BackpressureProvider vajraPulseProvider) {
        this.vajraPulseProvider = vajraPulseProvider;
    }
    
    @Override
    public double getBackpressureLevel() {
        return vajraPulseProvider.getBackpressureLevel();
    }
    
    @Override
    public String getSourceName() {
        return "VajraPulse: " + vajraPulseProvider.getClass().getSimpleName();
    }
    
    @Override
    public Map<String, Object> getDetails() {
        return Map.of("adapter", "VajraPulse");
    }
}
```

### 2. HikariCP Integration

**Goal**: Provide built-in HikariCP backpressure provider

**Approach**: Create provider in Vortex (optional dependency)

**Location**: `com.vajrapulse.vortex.backpressure.hikaricp.HikariCPBackpressureProvider`

**Note**: This would be in a separate optional module (`vortex-backpressure-hikaricp`) to avoid requiring HikariCP dependency.

**Implementation**:
```java
/**
 * Backpressure provider for HikariCP connection pools.
 * 
 * <p>Reports backpressure based on:
 * <ul>
 *   <li>Connection pool utilization (active / total)</li>
 *   <li>Pending connection requests (threads awaiting connection)</li>
 * </ul>
 * 
 * <p>Uses logarithmic scaling to detect pressure early.
 * 
 * <p>Requires HikariCP dependency (optional module).
 */
public class HikariCPBackpressureProvider implements BackpressureProvider {
    private final HikariDataSource dataSource;
    
    public HikariCPBackpressureProvider(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }
    
    @Override
    public double getBackpressureLevel() {
        HikariPoolMXBean poolBean = dataSource.getHikariPoolMXBean();
        if (poolBean == null) {
            return 0.0;
        }
        
        int active = poolBean.getActiveConnections();
        int total = poolBean.getTotalConnections();
        int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
        
        if (total == 0) {
            return 0.0;
        }
        
        // Same logic as current HikariCPBackpressureProvider
        // (logarithmic scaling, immediate max when waiting >= total, etc.)
        // ...
    }
    
    @Override
    public String getSourceName() {
        return "HikariCP Connection Pool";
    }
    
    @Override
    public Map<String, Object> getDetails() {
        HikariPoolMXBean poolBean = dataSource.getHikariPoolMXBean();
        if (poolBean == null) {
            return Map.of();
        }
        return Map.of(
            "active", poolBean.getActiveConnections(),
            "total", poolBean.getTotalConnections(),
            "idle", poolBean.getIdleConnections(),
            "waiting", poolBean.getThreadsAwaitingConnection()
        );
    }
}
```

## Usage Examples

### Example 1: Basic Queue Depth Backpressure

```java
// Create queue depth provider
BackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
    () -> batcher.getQueueDepth(),  // Queue depth supplier
    1000  // Max capacity
);

// Use REJECT strategy (caller gets failure callback)
BackpressureStrategy<Item> strategy = new RejectStrategy<>(0.7);

// Create backpressure-aware batcher
MicroBatcher<Item> batcher = MicroBatcher.withBackpressure(
    backend,
    config,
    queueProvider,
    strategy
);
```

### Example 2: Composite Backpressure (Queue + Connection Pool)

```java
// Queue depth provider
BackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
    () -> batcher.getQueueDepth(),
    1000
);

// HikariCP provider (from optional module)
BackpressureProvider hikariProvider = new HikariCPBackpressureProvider(dataSource);

// Composite provider (uses maximum)
BackpressureProvider composite = new CompositeBackpressureProvider(
    queueProvider,
    hikariProvider
);

// Use DROP strategy (silently drop when backpressure high)
BackpressureStrategy<Item> strategy = new DropStrategy<>(0.7);

// Create batcher
MicroBatcher<Item> batcher = MicroBatcher.withBackpressure(
    backend,
    config,
    composite,
    strategy
);
```

**Note**: Throttle strategy example deferred to Phase 2. See `VORTEX_BACKPRESSURE_ENHANCEMENTS.md`.

### Example 4: Custom Backpressure Provider

```java
// Custom provider for memory pressure
public class MemoryBackpressureProvider implements BackpressureProvider {
    private final Runtime runtime = Runtime.getRuntime();
    
    @Override
    public double getBackpressureLevel() {
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double utilization = (double) usedMemory / maxMemory;
        
        // Report high backpressure if memory > 80% used
        if (utilization > 0.8) {
            return Math.min(1.0, (utilization - 0.8) / 0.2);  // 0.8 -> 0.0, 1.0 -> 1.0
        }
        return 0.0;
    }
    
    @Override
    public String getSourceName() {
        return "JVM Memory";
    }
}

// Use it
BackpressureProvider memoryProvider = new MemoryBackpressureProvider();
BackpressureStrategy<Item> strategy = new RejectStrategy<>(0.7);

MicroBatcher<Item> batcher = MicroBatcher.withBackpressure(
    backend,
    config,
    memoryProvider,
    strategy
);
```

## Migration Path

### Phase 1: Minimal Viable Backpressure (v0.1.0) - SIMPLIFIED

**Goal**: Get core backpressure working with ~50% less code

1. **Add Core Interfaces**
   - `BackpressureProvider` interface
   - `BackpressureStrategy` interface (simplified - no `getStrategyName()`)
   - `BackpressureContext` (simplified - no timestamp)
   - `BackpressureResult` (simplified - enum + record, no Throttled)

2. **Add Built-in Providers**
   - `QueueDepthBackpressureProvider` (simplified - linear scaling)
   - `CompositeBackpressureProvider` (simplified - varargs constructor)

3. **Add Built-in Strategies**
   - `DropStrategy`
   - `RejectStrategy`
   - ❌ `ThrottleStrategy` (deferred to Phase 2)

4. **Integrate into MicroBatcher**
   - Add optional backpressure support to `MicroBatcher` directly
   - No separate `BackpressureAwareMicroBatcher` class
   - Use existing metrics infrastructure

5. **Add Adapters**
   - `VajraPulseBackpressureAdapter`

**Breaking Changes**: None (all new APIs, backward compatible)

**Code Reduction**: ~50% less code than original design (~400 lines vs ~800 lines)

### Phase 2: Enhanced Features (v0.2.0)

See `VORTEX_BACKPRESSURE_ENHANCEMENTS.md` for detailed Phase 2 plan.

**Highlights**:
- Add `ThrottleStrategy`
- Add logarithmic scaling option to `QueueDepthBackpressureProvider`
- Add comprehensive backpressure metrics
- Add detailed diagnostics to `CompositeBackpressureProvider`

### Phase 3: Optional Modules (v0.3.0)

1. **Add Optional HikariCP Module**
   - `vortex-backpressure-hikaricp` module
   - `HikariCPBackpressureProvider`
   - Optional dependency on HikariCP

2. **Add Optional Spring Module**
   - `vortex-backpressure-spring` module
   - Spring Boot auto-configuration
   - Bean-based providers

**Breaking Changes**: None (optional modules)

## Benefits

### 1. Generic and Reusable

- **Framework-Agnostic**: No dependencies on VajraPulse, Spring, or any specific framework
- **Resource-Agnostic**: Works with connection pools, queues, memory, CPU, etc.
- **Strategy-Agnostic**: Multiple handling strategies (DROP, REJECT, THROTTLE, etc.)

### 2. Simple and Flexible

- **Sensible Defaults**: Works out of the box with minimal configuration
- **Extensible**: Easy to add custom providers and strategies
- **Composable**: Combine multiple backpressure sources

### 3. Promotes Reuse

- **Single Implementation**: One backpressure system for all use cases
- **No Duplication**: No need to reimplement for each application
- **Consistent API**: Same interface across all scenarios

### 4. Observability

- **Built-in Metrics**: Track all backpressure events
- **Optional Micrometer**: Integrate with existing metrics infrastructure
- **Diagnostic Details**: Providers can expose diagnostic information

## Implementation Checklist

### Core Library (vortex)

- [ ] Add `BackpressureProvider` interface
- [ ] Add `BackpressureStrategy` interface (simplified - no `getStrategyName()`)
- [ ] Add `BackpressureContext` (simplified - no timestamp)
- [ ] Add `BackpressureResult` (simplified - enum + record)
- [ ] Add `QueueDepthBackpressureProvider` (simplified - linear scaling)
- [ ] Add `CompositeBackpressureProvider` (simplified - varargs constructor)
- [ ] Add `DropStrategy`
- [ ] Add `RejectStrategy`
- [ ] ❌ `ThrottleStrategy` (deferred to Phase 2)
- [ ] ❌ `BackpressureMetrics` (use existing metrics infrastructure)
- [ ] Integrate backpressure into `MicroBatcher` directly (no separate class)
- [ ] Add unit tests for all components
- [ ] Add integration tests
- [ ] Update documentation

### Optional Modules

- [ ] Create `vortex-backpressure-hikaricp` module
- [ ] Add `HikariCPBackpressureProvider`
- [ ] Add `VajraPulseBackpressureAdapter`
- [ ] Add unit tests
- [ ] Update documentation

### Documentation

- [ ] API documentation (JavaDoc)
- [ ] User guide with examples
- [ ] Migration guide from application-specific code
- [ ] Architecture documentation
- [ ] Best practices guide

## Conclusion

This plan provides a comprehensive, generic, and flexible approach to integrating backpressure into the Vortex library. By separating concerns (detection vs. handling), using standard interfaces, and providing sensible defaults, we create a reusable system that can be used across any batching scenario.

The design promotes:
- **Reuse**: Single implementation for all use cases
- **Flexibility**: Multiple providers and strategies
- **Simplicity**: Easy to use with minimal configuration
- **Extensibility**: Easy to add custom providers and strategies

The migration path ensures backward compatibility while gradually introducing new features, making it easy for existing users to adopt the new capabilities.

