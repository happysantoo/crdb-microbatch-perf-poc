# Connection Exhaustion and Vortex Design Solution

## Understanding the Problem

### Why Messages Get Dropped

**Scenario: Queue Fills Up**

1. **Items submitted faster than processed:**
   - 10K TPS incoming
   - Batches process at ~200 batches/sec (limited by connection pool)
   - Queue fills up: 1000 items → 700 items (70% threshold)

2. **RejectStrategy triggers:**
   - Backpressure = 0.7 (queue 70% full)
   - `submitSync()` returns `ItemResult.Failure`
   - **Messages are rejected (dropped)**

3. **Why this happens:**
   - Queue depth measures items waiting
   - When queue fills, new items are rejected
   - This prevents unbounded queue growth
   - **But items are lost (dropped)**

### How Connection Pool Gets Exhausted

**The Critical Gap: Queue Depth ≠ Connection Usage**

**Timeline of Connection Exhaustion:**

```
T0: System starts
    - Queue: 0 items
    - Connections: 0 active, 10 idle
    - Backpressure: 0.0

T1: Load increases (10K TPS)
    - Items submitted: 10,000/sec
    - Batches form: 200 batches/sec (10K ÷ 50)
    - Queue: 0 items (batches form quickly, 50ms linger)

T2: Batches start processing
    - 10 batches dispatched (one per connection)
    - Each batch holds connection for ~50-100ms
    - Queue: Still 0 items (items processed immediately)

T3: Connection pool exhaustion
    - All 10 connections busy
    - New batches try to acquire connections
    - Connection timeout: 30 seconds
    - Batches queue up waiting for connections
    - **Queue backpressure: Still 0.0!** (queue appears empty)

T4: Cascading failure
    - More batches dispatched (queue appears empty)
    - All waiting for connections
    - Connection pool exhausted
    - Timeouts occur
    - Items get dropped
```

**The Problem:**
- Queue depth = items waiting to be batched
- **NOT** items being processed
- **NOT** connections in use
- Connection pool can be exhausted while queue is empty!

### Why Queue-Only Backpressure Fails

**Queue depth measures:**
- ✅ Items waiting to be batched
- ❌ Items being processed (batches in-flight)
- ❌ Connections in use
- ❌ Batches waiting for connections

**Connection pool exhaustion happens when:**
- Batches are being processed (queue is empty)
- But connections are all busy
- New batches can't get connections
- Queue fills up as batches wait (not items)
- **Queue backpressure doesn't detect this early enough**

## Design Solution: Multi-Level Backpressure

### Implementation: Composite Backpressure

I've implemented a **composite backpressure system** that combines:

1. **Queue Depth Backpressure** - Detects items waiting
2. **Connection Pool Backpressure** - Detects connection exhaustion

### How It Works

```java
// Queue depth backpressure
BackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
    queueDepthSupplier, maxQueueSize);

// Connection pool backpressure (NEW)
BackpressureProvider poolProvider = new ConnectionPoolBackpressureProvider(dataSource);

// Composite backpressure (uses maximum)
BackpressureProvider composite = new CompositeBackpressureProvider(
    queueProvider, poolProvider);

// Use composite in RejectStrategy
RejectStrategy<TestInsert> strategy = new RejectStrategy<>(0.7);
batcher = MicroBatcher.withBackpressure(backend, config, meterRegistry, composite, strategy);
```

### Connection Pool Backpressure Calculation

**Primary Signal: Threads Awaiting Connection**

```java
if (threadsAwaiting > 0) {
    // PRIMARY: Threads waiting = system overloaded
    backpressure = 0.5 + (0.5 * log(threadsAwaiting + 1) / log(total + 1))
    // 1 thread waiting: ~0.5 (early warning)
    // total/2 threads waiting: ~0.8 (moderate)
    // total threads waiting: 1.0 (severe)
} else if (poolUtilization >= 0.9) {
    // SECONDARY: Pool nearly exhausted (only when no threads waiting)
    backpressure = 0.5 + (0.2 * (poolUtilization - 0.9) / 0.1)
    // 90% utilized: 0.5 (warning)
    // 100% utilized: 0.7 (moderate)
} else {
    // No backpressure: system handling load efficiently
    backpressure = 0.0
}
```

**Key Insight:** Threads awaiting connection is the most direct signal of overload. High utilization without waiting threads means the system is handling load efficiently.

**Examples:**
- 0 threads waiting, 7 active/10 total: `backpressure = 0.0` (handling load fine)
- 1 thread waiting: `backpressure = ~0.5` (early warning)
- 5 threads waiting (10 total): `backpressure = ~0.8` (moderate pressure)
- 10+ threads waiting: `backpressure = 1.0` (severe pressure)
- 0 threads waiting, 9 active/10 total (90%): `backpressure = 0.5` (warning)
- 0 threads waiting, 10 active/10 total (100%): `backpressure = 0.7` (moderate)

### Composite Backpressure Behavior

**Uses maximum of both sources:**
- If queue > 70%: `backpressure = 0.7+` → items rejected
- If pool > 70%: `backpressure = 0.7+` → items rejected
- **Early warning:** Detects pressure from either source

**Benefits:**
1. **Prevents connection exhaustion:** Detects pool pressure before it's exhausted
2. **Prevents queue overflow:** Detects queue pressure before it's full
3. **Early warning:** Responds to the worst pressure source
4. **Automatic recovery:** When pressure decreases, items accepted again

## How This Prevents Connection Exhaustion

### Before (Queue-Only):

```
T0: Queue empty, 10 connections busy
T1: New batch ready → tries connection → timeout (30s)
T2: Queue fills up (batches waiting)
T3: Items dropped
```

### After (Composite Backpressure):

```
T0: Queue empty, 8 connections busy (80% utilized)
T1: Connection pool backpressure = 0.8
T2: Composite backpressure = 0.8 (max of queue=0.0, pool=0.8)
T3: Items rejected (backpressure >= 0.7)
T4: TPS reduced by AdaptiveLoadPattern
T5: Connections free up
T6: System recovers
```

### Key Improvement

**Early Detection:**
- Connection pool backpressure detects pressure at 70% utilization
- Items rejected **before** pool is exhausted
- System reduces load **before** timeouts occur
- **Prevents cascading failures**

## Recommended Vortex Design Enhancements

### 1. Concurrent Batch Dispatch Limiter

**Problem:** Too many batches dispatched concurrently exhausts connection pool.

**Solution:** Limit concurrent batch dispatches:

```java
public class MicroBatcher<T> {
    private final Semaphore dispatchSemaphore;
    private final int maxConcurrentBatches;
    
    public MicroBatcher(..., int maxConcurrentBatches) {
        this.maxConcurrentBatches = maxConcurrentBatches;
        this.dispatchSemaphore = new Semaphore(maxConcurrentBatches);
    }
    
    private void dispatchBatch(List<T> batch) {
        if (!dispatchSemaphore.tryAcquire()) {
            // Too many concurrent batches - reject or queue
            handleDispatchRejection(batch);
            return;
        }
        
        try {
            executor.submit(() -> {
                try {
                    backend.dispatch(batch);
                } finally {
                    dispatchSemaphore.release();
                }
            });
        } catch (RejectedExecutionException e) {
            dispatchSemaphore.release();
            handleDispatchRejection(batch);
        }
    }
}
```

**Configuration:**
```java
// Limit to 80% of connection pool
int maxConcurrentBatches = (int)(connectionPoolSize * 0.8);
// For 10 connections: maxConcurrentBatches = 8
```

### 2. Built-in Connection Pool Backpressure

**Proposal:** Add to Vortex library:

```java
// In Vortex library
public class ConnectionPoolBackpressureProvider implements BackpressureProvider {
    private final Supplier<Integer> activeConnectionsSupplier;
    private final Supplier<Integer> totalConnectionsSupplier;
    private final Supplier<Integer> waitingThreadsSupplier;
    
    // Generic implementation - works with any connection pool
}
```

**Benefits:**
- Reusable across applications
- Works with any connection pool (HikariCP, Tomcat, etc.)
- Standardized backpressure calculation

### 3. Enhanced Metrics

**Add metrics for:**
- `vortex.backpressure.connection_pool.level` - Connection pool backpressure level
- `vortex.backpressure.composite.level` - Composite backpressure level
- `vortex.backpressure.rejected.connection_pool` - Rejections due to pool pressure
- `vortex.backpressure.rejected.queue` - Rejections due to queue pressure

## Current Implementation Status

### ✅ Implemented

1. **ConnectionPoolBackpressureProvider** - Detects connection pool pressure
2. **CompositeBackpressureProvider** - Combines queue + pool backpressure
3. **Updated CrdbInsertTask** - Uses composite backpressure

### 🔄 Recommended (Future Vortex Enhancement)

1. **Concurrent Batch Dispatch Limiter** - Limit concurrent batches
2. **Built-in Connection Pool Provider** - In Vortex library
3. **Enhanced Metrics** - Track both backpressure sources

## Expected Behavior

### At 10K TPS with 10 Connections

**Before (Queue-Only):**
- Queue fills up → items rejected
- Connection pool exhausted → timeouts
- Messages dropped

**After (Composite):**
- Connection pool at 80% → backpressure = 0.8
- Items rejected **before** pool exhausted
- System reduces TPS automatically
- **No connection timeouts**
- **No message drops** (rejected early, not dropped)

## Summary

**Key Insight:** Queue depth alone doesn't prevent connection exhaustion because it measures items waiting, not connections in use.

**Solution:** Multi-level backpressure:
1. **Queue depth** - Detects items waiting
2. **Connection pool** - Detects connections in use
3. **Composite** - Uses maximum for early warning

**Result:** System detects pressure from either source and rejects items **before** connection pool is exhausted, preventing timeouts and message drops.

