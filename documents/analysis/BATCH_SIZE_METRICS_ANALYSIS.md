# Batch Size Metrics Analysis

## Understanding the Microbatching Principle

**Expected Behavior:**
- **Batch Size**: 50 items (configured)
- **Linger Time**: 50ms (configured)
- **Dispatch Trigger**: Whichever comes first:
  - 50 items accumulated, OR
  - 50ms elapsed

**This means:**
- At high TPS (>1000 TPS): Batches should be ~50 items (size-based)
- At low TPS (<1000 TPS): Batches might be <50 items (time-based, after 50ms)

## How to Calculate Average Batch Size

### From Prometheus Metrics

**Formula:**
```
Average Batch Size = Total Rows / Total Batches
```

**Metrics:**
- `crdb_batch_rows_total` - Total rows processed
- `crdb_batches_total_total` - Total batches dispatched (note: double `_total` due to Micrometer)

**Query:**
```promql
crdb_batch_rows_total / crdb_batches_total_total
```

### From Vortex Metrics

**Formula:**
```
Average Items per Batch = Vortex Requests / Vortex Batches
```

**Metrics:**
- `vortex_requests_submitted_total` - Total items submitted
- `vortex_batches_dispatched_total` - Total batches dispatched

**Query:**
```promql
vortex_requests_submitted_total / vortex_batches_dispatched_total
```

## Expected Values

### Normal Operation
- **Average Batch Size**: ~50 items (at high TPS)
- **Minimum Batch Size**: 1 item (at very low TPS, time-based)
- **Maximum Batch Size**: 50 items (batch size limit)

### Abnormal Values

#### 1. Average Batch Size = 1.0
**Problem:** Every item is dispatched immediately
**Cause:** 
- `submitSync()` might be triggering immediate dispatch
- Vortex not respecting `batchSize(50)` configuration
- Queue dispatching on every offer()

**Impact:**
- No batching benefits
- Connection pool exhaustion (1 connection per item)
- Poor performance (50x more database operations)

#### 2. Average Batch Size > 100 (e.g., 2000)
**Problem:** Batches are much larger than configured
**Possible Causes:**
1. **Multiple Batches Combined**: Backend receiving accumulated batches
2. **Metric Collection Error**: Metrics counting incorrectly
3. **Vortex Bug**: Not respecting batch size limit
4. **Queue Accumulation**: Items accumulating beyond batch size

**Investigation:**
- Check if `CrdbBatchBackend.dispatch()` receives batches > 50 items
- Check if multiple batches are being combined
- Verify metric collection (rowCounter.increment(batch.size()))

## How a Batch Can Contain 2000 Records

### Scenario 1: Multiple Batches Combined
If Vortex dispatches multiple batches and they get combined:
- Batch 1: 50 items
- Batch 2: 50 items
- ...
- Batch 40: 50 items
- **Combined**: 2000 items in one `dispatch()` call

**This would indicate:**
- Vortex is batching correctly (50 items per batch)
- But backend is receiving multiple batches combined
- Need to check if `dispatch()` is called with accumulated batches

### Scenario 2: Queue Accumulation Beyond Batch Size
If items accumulate in queue beyond batch size:
- Queue accumulates 2000 items
- Batch dispatches with all 2000 items
- **This violates the batch size limit**

**This would indicate:**
- Vortex not respecting `batchSize(50)` configuration
- Queue not being drained properly
- Need to check Vortex implementation

### Scenario 3: Metric Collection Error
If metrics are counted incorrectly:
- `rowCounter.increment(batch.size())` called multiple times
- Or batch.size() returns wrong value
- **Metrics show 2000, but actual batch is 50**

**This would indicate:**
- Backend metric collection issue
- Need to verify `batch.size()` value

## Diagnostic Steps

1. **Check Actual Batch Sizes in Logs**
   - Look for "Batch #X: Y items" logs
   - Verify if batches are actually 2000 items or if it's a metric issue

2. **Check Vortex Metrics**
   - `vortex_batches_dispatched_total` vs `vortex_requests_submitted_total`
   - Ratio should be ~50:1 (50 requests per batch)
   - If ratio is different, Vortex batching is wrong

3. **Check Backend Metrics**
   - `crdb_batches_total_total` vs `crdb_batch_rows_total`
   - Ratio should match Vortex ratio
   - If different, metric collection issue

4. **Check Logs for Batch Sizes**
   - First 10 batches logged with actual sizes
   - Look for warnings about batch sizes > 100

## Query Prometheus for Historical Data

```bash
# Get total rows and batches
curl 'http://localhost:9090/api/v1/query?query=crdb_batch_rows_total'
curl 'http://localhost:9090/api/v1/query?query=crdb_batches_total_total'

# Calculate average
# Average = crdb_batch_rows_total / crdb_batches_total_total

# Get rate-based average (current)
curl 'http://localhost:9090/api/v1/query?query=rate(crdb_batch_rows_total[5m])/rate(crdb_batches_total_total[5m])'
```

## Conclusion

**If average batch size = 1:**
- Items are dispatched immediately (no batching)
- This is the current issue we're seeing

**If average batch size = 2000:**
- Either multiple batches are combined, OR
- Queue is accumulating beyond batch size, OR
- Metrics are wrong

**Expected average batch size:**
- ~50 items at high TPS
- 1-50 items at low TPS (time-based)

