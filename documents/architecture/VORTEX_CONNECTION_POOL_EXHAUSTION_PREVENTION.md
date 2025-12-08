# Vortex Connection Pool Exhaustion Prevention Design

## Problem Statement

**Current Issue:** Even with queue-based backpressure, the system can still experience connection pool exhaustion, leading to:
- Messages being dropped when queue fills up
- Connection timeouts when pool is exhausted
- Cascading failures

## Root Cause Analysis

### Current Flow

```
VajraPulse (10K TPS)
    ↓
CrdbInsertTask.execute() → submitSync()
    ↓
Vortex Queue (1000 items max)
    ↓
Batch Ready (50 items OR 50ms) → dispatchBatch()
    ↓
CrdbBatchBackend.dispatch() [HOLDS CONNECTION]
    ↓
HikariCP Connection Pool (10 connections)
    ↓
CRDB Database
```

### The Problem: Queue Depth ≠ Connection Usage

**Scenario: Connection Pool Exhaustion with Empty Queue**

1. **Initial State:**
   - Queue: 0 items (empty)
   - Connections: 10 active, 0 idle
   - Backpressure: 0.0 (queue empty)

2. **Load Increases:**
   - 10K TPS incoming
   - Batches form quickly (50ms linger)
   - 200 batches/sec need processing (10K ÷ 50)

3. **Batches Start Processing:**
   - 10 batches dispatched concurrently (one per connection)
   - Each batch holds connection for ~50-100ms
   - Queue depth: 0 (items processed immediately)

4. **Connection Pool Exhaustion:**
   - All 10 connections busy
   - New batches try to acquire connections
   - Connection timeout: 30 seconds
   - Batches queue up waiting for connections
   - **Queue backpressure still shows 0.0** (queue is empty!)

5. **Cascading Failure:**
   - More batches dispatched (queue appears empty)
   - All waiting for connections
   - Connection pool exhausted
   - Timeouts occur
   - Items get dropped

### Why Queue-Only Backpressure Fails

**Queue depth measures:**
- Items waiting to be batched
- **NOT** items being processed
- **NOT** connections in use

**Connection pool exhaustion happens when:**
- Batches are being processed (queue is empty)
- But connections are all busy
- New batches can't get connections
- Queue fills up as batches wait

**Timeline:**
```
T0: Queue empty, 10 connections busy
T1: New batch ready, tries to get connection
T2: Connection unavailable, batch waits
T3: More batches ready, all waiting for connections
T4: Queue fills up (batches waiting, not items)
T5: Connection timeout (30s)
T6: Items dropped
```

## Design Solution: Multi-Level Backpressure

### Proposed Vortex Enhancement

Add **connection pool awareness** to Vortex's backpressure system:

#### 1. **Concurrent Batch Dispatch Limiter**

Limit the number of concurrent batch dispatches based on connection pool availability:

```java
public class MicroBatcher<T> {
    private final Semaphore dispatchSemaphore;
    private final int maxConcurrentBatches;
    
    public MicroBatcher(Backend<T> backend, BatcherConfig config, 
                       MeterRegistry meterRegistry,
                       BackpressureProvider backpressureProvider,
                       BackpressureStrategy<T> strategy,
                       int maxConcurrentBatches) {  // NEW: Limit concurrent dispatches
        this.maxConcurrentBatches = maxConcurrentBatches;
        this.dispatchSemaphore = new Semaphore(maxConcurrentBatches);
        // ... rest of initialization
    }
    
    private void dispatchBatch(List<T> batch) {
        // Try to acquire permit (non-blocking)
        if (!dispatchSemaphore.tryAcquire()) {
            // Can't dispatch - too many concurrent batches
            // Put batch back in queue or reject items
            handleDispatchRejection(batch);
            return;
        }
        
        try {
            executor.submit(() -> {
                try {
                    backend.dispatch(batch);
                } finally {
                    dispatchSemaphore.release();  // Release permit when done
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
// Limit concurrent batches to 80% of connection pool
int maxConcurrentBatches = (int)(connectionPoolSize * 0.8);
// For 10 connections: maxConcurrentBatches = 8
```

#### 2. **Composite Backpressure Provider**

Combine queue depth AND connection pool availability:

```java
public class CompositeBackpressureProvider implements BackpressureProvider {
    private final BackpressureProvider queueProvider;
    private final BackpressureProvider connectionPoolProvider;
    
    @Override
    public double getBackpressureLevel() {
        double queueBackpressure = queueProvider.getBackpressureLevel();
        double poolBackpressure = connectionPoolProvider.getBackpressureLevel();
        
        // Use maximum - if either source has pressure, report it
        return Math.max(queueBackpressure, poolBackpressure);
    }
}
```

**Usage:**
```java
// Queue backpressure
BackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
    queueDepthSupplier, maxQueueSize);

// Connection pool backpressure
BackpressureProvider poolProvider = new ConnectionPoolBackpressureProvider(
    dataSource);

// Composite (uses maximum)
BackpressureProvider composite = new CompositeBackpressureProvider(
    queueProvider, poolProvider);

// Use in RejectStrategy
RejectStrategy<TestInsert> strategy = new RejectStrategy<>(0.7, composite);
```

#### 3. **Connection Pool Backpressure Provider**

Monitor connection pool and report backpressure:

```java
public class ConnectionPoolBackpressureProvider implements BackpressureProvider {
    private final HikariDataSource dataSource;
    
    @Override
    public double getBackpressureLevel() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) return 0.0;
        
        int active = pool.getActiveConnections();
        int total = pool.getTotalConnections();
        int waiting = pool.getThreadsAwaitingConnection();
        
        if (total == 0) return 0.0;
        
        // Pool utilization
        double utilization = (double) active / total;
        
        // Waiting threads pressure (logarithmic scale for early detection)
        double waitPressure = 0.0;
        if (waiting > 0) {
            // Normalize: waiting >= total means severe pressure
            waitPressure = Math.min(1.0, 0.5 + (0.5 * Math.log(waiting + 1) / Math.log(total + 1)));
        }
        
        // Return maximum of utilization and wait pressure
        return Math.max(utilization, waitPressure);
    }
}
```

#### 4. **Enhanced RejectStrategy with Composite Backpressure**

```java
public class RejectStrategy<T> implements BackpressureStrategy<T> {
    private final double threshold;
    private final BackpressureProvider backpressureProvider;
    
    public RejectStrategy(double threshold, BackpressureProvider backpressureProvider) {
        this.threshold = threshold;
        this.backpressureProvider = backpressureProvider;
    }
    
    @Override
    public BackpressureResult<T> handle(BackpressureContext<T> context) {
        double backpressure = backpressureProvider.getBackpressureLevel();
        
        if (backpressure >= threshold) {
            return BackpressureResult.reject(
                new BackpressureException(
                    String.format("Backpressure %.2f >= threshold %.2f", 
                        backpressure, threshold)
                )
            );
        }
        
        return BackpressureResult.accept();
    }
}
```

## Implementation Strategy

### Phase 1: Add Concurrent Batch Limiter (Immediate Fix)

**File:** `CrdbInsertTask.java`

```java
private void initializeBatcher() {
    BatcherConfig config = BatcherConfig.builder()
        .batchSize(BATCH_SIZE)
        .lingerTime(LINGER_TIME)
        .atomicCommit(false)
        .maxConcurrentBatches(8)  // NEW: 80% of 10 connections
        .build();
    
    // ... rest of initialization
}
```

**Note:** This requires Vortex library enhancement. For now, we can implement a wrapper.

### Phase 2: Add Connection Pool Backpressure (Short-term)

**File:** `CrdbInsertTask.java`

```java
private void initializeBatcher() {
    // Queue backpressure
    BackpressureProvider queueProvider = new QueueDepthBackpressureProvider(
        queueDepthSupplier, maxQueueSize);
    
    // Connection pool backpressure (NEW)
    BackpressureProvider poolProvider = new ConnectionPoolBackpressureProvider(dataSource);
    
    // Composite backpressure
    BackpressureProvider composite = new CompositeBackpressureProvider(
        queueProvider, poolProvider);
    
    // Use composite in RejectStrategy
    RejectStrategy<TestInsert> strategy = new RejectStrategy<>(0.7, composite);
    
    batcher = MicroBatcher.withBackpressure(
        backend, config, meterRegistry, composite, strategy
    );
}
```

### Phase 3: Vortex Library Enhancement (Long-term)

Add to Vortex library:
1. `maxConcurrentBatches` configuration option
2. `ConnectionPoolBackpressureProvider` built-in
3. `CompositeBackpressureProvider` built-in
4. Enhanced `RejectStrategy` with composite support

## Recommended Configuration

### For 10K TPS with 10 Connections

```java
BATCH_SIZE = 50
LINGER_TIME = 50ms
maxQueueSize = 1000 items (20 batches)
maxConcurrentBatches = 8 (80% of 10 connections)
backpressureThreshold = 0.7

// Backpressure sources:
// 1. Queue depth (items waiting)
// 2. Connection pool (connections in use)
// 3. Concurrent batches (batches being processed)
```

### Calculation

**Connection Pool Capacity:**
- 10 connections
- Each batch takes ~50ms
- Throughput: 10 × (1000ms / 50ms) = 200 batches/sec
- Required: 10K TPS ÷ 50 = 200 batches/sec
- **At capacity!**

**With 8 Concurrent Batches:**
- 8 connections (80% utilization)
- Throughput: 8 × (1000ms / 50ms) = 160 batches/sec
- **Safety margin:** 20% headroom
- **Prevents exhaustion:** Queue backpressure triggers before pool exhausted

## Benefits

1. **Prevents Connection Exhaustion:**
   - Limits concurrent batches to 80% of pool
   - Queue backpressure triggers before pool exhausted
   - Composite backpressure provides early warning

2. **Better Resource Utilization:**
   - 80% utilization leaves 20% headroom
   - Prevents connection timeouts
   - Maintains stable latency

3. **Automatic Recovery:**
   - When connections free up, more batches can dispatch
   - System automatically recovers
   - No manual intervention needed

4. **Clear Backpressure Signal:**
   - Composite backpressure reflects both queue and pool
   - AdaptiveLoadPattern sees pressure early
   - Can reduce TPS before system overloads

## Migration Path

### Step 1: Add Connection Pool Backpressure (Now)

1. Create `ConnectionPoolBackpressureProvider`
2. Create `CompositeBackpressureProvider`
3. Update `CrdbInsertTask` to use composite backpressure
4. Test with 10K TPS

### Step 2: Add Concurrent Batch Limiter (Next)

1. Implement wrapper around MicroBatcher
2. Add semaphore-based dispatch limiting
3. Test with higher TPS (15K+)

### Step 3: Propose Vortex Enhancement (Future)

1. Document design for Vortex library
2. Propose `maxConcurrentBatches` configuration
3. Propose built-in connection pool backpressure
4. Submit enhancement request to Vortex maintainers

## Expected Behavior After Fix

### Before (Current):
```
T0: Queue empty, 10 connections busy
T1: New batch ready → tries connection → timeout
T2: Queue fills up (batches waiting)
T3: Items dropped
```

### After (With Fix):
```
T0: Queue empty, 8 connections busy (limited)
T1: New batch ready → queue backpressure = 0.7 (pool at 80%)
T2: Items rejected (backpressure >= 0.7)
T3: TPS reduced by AdaptiveLoadPattern
T4: Connections free up
T5: System recovers
```

## Summary

**Key Insight:** Queue depth alone doesn't prevent connection exhaustion because:
- Queue measures items waiting, not batches processing
- Connections can be exhausted while queue is empty
- Need multi-level backpressure: queue + connection pool + concurrent batches

**Solution:** Three-layer defense:
1. **Concurrent batch limiter** - Prevents too many batches at once
2. **Connection pool backpressure** - Detects pool exhaustion early
3. **Composite backpressure** - Combines both signals for early warning

This design ensures Vortex can handle high TPS without connection pool exhaustion.

