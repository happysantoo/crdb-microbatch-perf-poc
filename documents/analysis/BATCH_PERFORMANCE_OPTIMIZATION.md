# Batch Performance Optimization

## Issue

Batch throughput is slower than individual insert performance, which is unexpected.

## Root Causes Identified

### 1. **Metric Name Mismatches in Dashboard**
**Problem**: Dashboard queries use incorrect metric names, causing blank panels.

**Actual Metric Names:**
- `crdb_batches_total` (NOT `crdb_batches_total_total`)
- `crdb_batch_rows_total` (NOT `crdb_batch_rows_total_total`)
- `crdb_batch_duration_milliseconds` (NOT `crdb_batch_duration_seconds`)
- `crdb_submit_latency_milliseconds` (NOT `crdb_submit_latency_seconds`)
- `crdb_batch_wait_milliseconds` (NOT `crdb_batch_wait_seconds`)

**Fix**: Updated all dashboard queries to match actual metric names and convert milliseconds to seconds where needed.

### 2. **Excessive Linger Time**
**Problem**: 50ms linger time causes batches to wait too long, adding latency.

**Impact**: 
- Average batch wait: ~36ms per item
- Batches dispatched before reaching 50 rows (average ~26 rows per batch)
- Adds unnecessary latency to each insert

**Fix**: Reduced linger time from 50ms to 10ms to minimize latency while still allowing batching.

### 3. **Batch Processing Overhead**
**Problem**: Creating SuccessEvent/FailureEvent objects for every item adds overhead.

**Optimizations Applied:**
- Pre-size ArrayList to batch size (avoid reallocations)
- Use indexed access instead of enhanced for loop
- Minimize object allocations in hot path

## Optimizations Applied

### 1. Reduced Linger Time
```java
// Before
private static final Duration LINGER_TIME = Duration.ofMillis(50);

// After
private static final Duration LINGER_TIME = Duration.ofMillis(10);
```

**Expected Impact:**
- Reduced average wait time from ~36ms to ~10ms
- Faster batch dispatch
- Better throughput

### 2. Optimized Batch Result Processing
```java
// Before: Enhanced for loop, dynamic list growth
for (TestInsert item : batch) {
    successes.add(new SuccessEvent<>(item));
}

// After: Pre-sized list, indexed access
int batchSize = batch.size();
List<SuccessEvent<TestInsert>> successes = new ArrayList<>(batchSize);
for (int i = 0; i < batchSize; i++) {
    successes.add(new SuccessEvent<>(batch.get(i)));
}
```

**Benefits:**
- Pre-sized lists avoid reallocations
- Indexed access is slightly faster
- Reduced memory allocations

### 3. Optimized Repository Batch Insert
```java
// Before: Enhanced for loop
for (TestInsert testInsert : testInserts) {
    setParameters(ps, testInsert);
    ps.addBatch();
}

// After: Indexed access, size cached
int size = testInserts.size();
for (int i = 0; i < size; i++) {
    setParameters(ps, testInserts.get(i));
    ps.addBatch();
}
```

**Benefits:**
- Slightly faster iteration
- Cache list size to avoid repeated calls

## Expected Performance Improvements

### Before Optimizations:
- Average batch wait: ~36ms per item
- Average batch size: ~26 rows (due to 50ms linger)
- Throughput: Lower than single inserts (due to batching overhead)

### After Optimizations:
- Average batch wait: ~10ms per item (66% reduction)
- Average batch size: Should approach 50 rows more often
- Throughput: Should exceed single inserts (batch efficiency > wait overhead)

## Additional Considerations

### Batch Size vs Linger Time Trade-off
- **Larger batch size (50)**: Better database efficiency, but longer wait
- **Shorter linger time (10ms)**: Lower latency, but smaller batches if load is low
- **Optimal**: Balance between batch size and latency

### Connection Pool Utilization
- With 10 connections and 50-row batches, should see:
  - Better connection utilization
  - Higher throughput per connection
  - Reduced database round-trips

## Monitoring

After applying optimizations, monitor:
1. **Batch Size Distribution**: Should see more batches at 50 rows
2. **Batch Wait Time**: Should decrease from ~36ms to ~10ms
3. **Throughput**: Should exceed single-insert baseline (300-500 TPS)
4. **Latency**: Should remain low despite batching

## Next Steps

1. Run test with optimizations
2. Compare metrics:
   - Batch throughput vs single-insert throughput
   - Average batch size
   - Batch wait time
   - Overall latency
3. If still slower, consider:
   - Further reducing linger time (5ms)
   - Increasing batch size (100 rows)
   - Analyzing database-side performance

