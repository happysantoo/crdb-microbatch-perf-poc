# Batch Size Analysis: How Can a Batch Contain 2000 Records?

## The Microbatching Principle

**Expected Behavior:**
- **Batch Size**: 50 items (configured in `BatcherConfig.batchSize(50)`)
- **Linger Time**: 50ms (configured in `BatcherConfig.lingerTime(50ms)`)
- **Dispatch Rule**: **Whichever comes first**
  - 50 items accumulated → dispatch immediately
  - 50ms elapsed → dispatch whatever is queued (even if < 50 items)

**This means:**
- At high TPS (>1000 TPS): Batches should be ~50 items (size-based)
- At low TPS (<1000 TPS): Batches might be 1-50 items (time-based)

## How to Query Prometheus for Batch Sizes

### Method 1: Backend Metrics (Most Reliable)

```promql
# Average batch size from backend
crdb_batch_rows_total / crdb_batches_total
```

**Metrics:**
- `crdb_batch_rows_total` - Total rows processed (incremented by `batch.size()`)
- `crdb_batches_total` - Total batches dispatched

**This tells us:** Average items per batch as seen by the backend

### Method 2: Vortex Metrics

```promql
# Average items per batch from Vortex
vortex_requests_submitted_total / vortex_batches_dispatched_total
```

**Metrics:**
- `vortex_requests_submitted_total` - Total items submitted to Vortex
- `vortex_batches_dispatched_total` - Total batches dispatched by Vortex

**This tells us:** Average items per batch as seen by Vortex

### Method 3: Vortex Batch Size Histogram (Most Accurate)

```promql
# Average batch size from Vortex histogram
vortex_batch_size_sum / vortex_batch_size_count
```

**Metrics:**
- `vortex_batch_size_sum` - Sum of all batch sizes
- `vortex_batch_size_count` - Count of batches

**This tells us:** Actual average batch size from Vortex's internal tracking

## How Can a Batch Contain 2000 Records?

### Scenario 1: Multiple Batches Combined (Most Likely)

**What Happens:**
1. Vortex dispatches Batch 1: 50 items → `CrdbBatchBackend.dispatch([50 items])`
2. Vortex dispatches Batch 2: 50 items → `CrdbBatchBackend.dispatch([50 items])`
3. ...
4. Vortex dispatches Batch 40: 50 items → `CrdbBatchBackend.dispatch([50 items])`

**If batches get combined:**
- Backend receives: `dispatch([2000 items])` (all 40 batches combined)
- **Metrics show:** 2000 items in one batch
- **Reality:** 40 separate batches of 50 items each

**How to Verify:**
- Check `vortex_batches_dispatched_total` vs `crdb_batches_total`
- If Vortex shows 40 batches but backend shows 1 batch → batches are combined
- Check logs for "Batch #X: Y items" - should show multiple batches

**Is This a Problem?**
- **No** - Vortex is batching correctly (50 items per batch)
- **Yes** - Backend is receiving combined batches, which defeats batching benefits
- **Impact:** One large database operation instead of 40 smaller ones

### Scenario 2: Queue Accumulation Beyond Batch Size

**What Happens:**
1. Items accumulate in queue: 2000 items queued
2. Batch dispatches with all 2000 items
3. **This violates the batch size limit**

**How to Verify:**
- Check `vortex_queue_depth` - should never exceed batch size significantly
- Check if `batchSize(50)` is actually being respected
- Look for logs showing batches > 50 items

**Is This a Problem?**
- **Yes** - Vortex is not respecting `batchSize(50)` configuration
- **Impact:** Large batches cause:
  - Long database operations
  - Connection held for extended time
  - Poor latency for items at the end of the batch

### Scenario 3: Metric Collection Error

**What Happens:**
1. Backend receives batch of 50 items
2. `rowCounter.increment(batch.size())` is called
3. But `batch.size()` returns 2000 (wrong value)
4. **Metrics show 2000, but actual batch is 50**

**How to Verify:**
- Check logs: "Batch #X: Y items" should show actual batch size
- Compare log batch sizes with metric batch sizes
- If logs show 50 but metrics show 2000 → metric collection error

**Is This a Problem?**
- **No** - Batching is working correctly
- **Yes** - Metrics are misleading
- **Impact:** Incorrect monitoring and analysis

### Scenario 4: Time-Based Batching at Very High TPS

**What Happens:**
1. At extremely high TPS (e.g., 40,000 TPS)
2. 50ms elapses → batch dispatches
3. But 2000 items arrived in 50ms (40,000 TPS * 0.05s = 2000 items)
4. **Batch contains 2000 items because time limit was hit**

**How to Verify:**
- Check TPS when large batches occur
- Calculate: TPS * 0.05s = items in 50ms
- If TPS is very high, this is expected behavior

**Is This a Problem?**
- **No** - This is correct time-based batching behavior
- **But** - Batch size should still be limited to 50 items
- **Impact:** If batch size limit is not enforced, batches can grow unbounded

## Are We Violating the Microbatching Principle?

### Current Issue: Batches with 1 Item

**From Logs:**
```
⚠️ BATCHING ISSUE: Batch contains only 1 item! Items are not being accumulated.
```

**This Violates the Principle:**
- Items should accumulate to 50 OR wait 50ms
- But items are dispatched immediately (1 item per batch)
- **This is a violation** - no accumulation happening

**Possible Causes:**
1. `submitSync()` triggers immediate dispatch
2. Vortex not respecting `batchSize(50)` configuration
3. Queue dispatching on every offer()

### Potential Issue: Batches with 2000 Items

**If This Occurs:**
- **Violates batch size limit** - should never exceed 50 items
- **Possible causes:**
  1. Multiple batches combined (Vortex working, backend issue)
  2. Queue accumulation beyond limit (Vortex not respecting config)
  3. Time-based batching at very high TPS (expected, but should be limited)

## Diagnostic Queries

### Check Average Batch Size

```promql
# Backend average
crdb_batch_rows_total / crdb_batches_total

# Vortex average
vortex_requests_submitted_total / vortex_batches_dispatched_total

# Vortex histogram average (most accurate)
vortex_batch_size_sum / vortex_batch_size_count
```

### Check if Batches Are Combined

```promql
# Compare batch counts
vortex_batches_dispatched_total
crdb_batches_total

# If Vortex shows more batches than backend, batches are being combined
```

### Check Batch Size Distribution

```promql
# Histogram buckets show distribution
vortex_batch_size_bucket
```

## Expected Values

### Normal Operation
- **Average Batch Size**: ~50 items (at high TPS)
- **Minimum**: 1 item (at very low TPS, time-based)
- **Maximum**: 50 items (batch size limit)

### Abnormal Values

| Average Batch Size | Interpretation | Problem? |
|-------------------|----------------|----------|
| 1.0 | Items dispatched immediately | ❌ Yes - No batching |
| 1-10 | Small batches (time-based) | ⚠️ Maybe - Low TPS or inefficient |
| 10-50 | Normal range | ✅ No - Working as expected |
| 50 | Perfect (size-based) | ✅ No - Optimal |
| 50-100 | Slightly large | ⚠️ Maybe - Check if multiple batches combined |
| > 100 | Very large | ❌ Yes - Violates batch size limit |

## Conclusion

**If you see 2000-item batches:**
1. **Check if multiple batches are combined** - Compare Vortex vs backend batch counts
2. **Check if queue is accumulating** - Monitor `vortex_queue_depth`
3. **Check if metrics are wrong** - Compare logs with metrics
4. **Check TPS** - Very high TPS might cause time-based batching to accumulate

**The microbatching principle is violated if:**
- Batches are consistently 1 item (no accumulation)
- Batches exceed 50 items (batch size limit not enforced)

**Use the diagnostic script:**
```bash
./scripts/query_batch_sizes.sh
```

This will show you the actual average batch sizes from Prometheus and help identify the issue.

