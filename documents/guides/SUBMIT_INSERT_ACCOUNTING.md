# Submit vs Insert Accounting

## The Question
Why is there a difference between:
- **Item Submits**: 6.78M (`crdb_submits_total_total`)
- **Rows Inserted**: 5.09M (`crdb_batch_rows_success_total`)
- **Difference**: 1.69M items

## Accounting Formula

The difference can be accounted for by the following components:

**Using Vortex Metrics (Source of Truth)**:
```
vortex_requests_submitted_total = 
  crdb_batch_rows_success_total +           // Successfully inserted rows
  crdb_batch_rows_failure_total +            // Rows that failed during batch insert
  vortex_backpressure_rejected_total +      // Items rejected due to backpressure (Vortex source of truth)
  crdb_submits_rejected_other_total +       // Items rejected for other reasons
  vortex_queue_depth +                       // Items in Vortex queue (waiting to be batched)
  (crdb_submits_success_total - crdb_batch_rows_total_total)  // Items in-flight (batched but not yet committed)
```

**Using App Metrics (May Have Gaps)**:
```
crdb_submits_total_total = 
  crdb_batch_rows_success_total +           // Successfully inserted rows
  crdb_batch_rows_failure_total +            // Rows that failed during batch insert
  crdb_submits_rejected_backpressure_total + // Items rejected due to backpressure (App detection - may miss some)
  crdb_submits_rejected_other_total +       // Items rejected for other reasons
  vortex_queue_depth +                       // Items in Vortex queue (waiting to be batched)
  (crdb_submits_success_total - crdb_batch_rows_total_total)  // Items in-flight (batched but not yet committed)
```

**Key Insights**: 
1. **Use `vortex_requests_submitted_total` and `vortex_backpressure_rejected_total`** for accurate accounting - these are tracked directly by Vortex
2. The app-level `crdb_submits_rejected_backpressure_total` relies on exception detection which may miss some rejects if the exception type/message doesn't match
3. The difference is primarily due to items that were **successfully accepted** by Vortex but haven't been inserted yet:
   - Items waiting in the queue to be batched
   - Items in batches that are dispatched but not yet committed

## Components Breakdown

### 1. Successfully Inserted Rows
**Metric**: `crdb_batch_rows_success_total`
- **What it counts**: Rows that were successfully inserted into the database
- **When incremented**: After batch insert completes and JDBC returns `rowsAffected > 0`
- **Your value**: 5.09M

### 2. Failed Rows (Batch Insert Failures)
**Metric**: `crdb_batch_rows_failure_total`
- **What it counts**: Items that made it into a batch but failed during database insert
- **When incremented**: When JDBC returns `rowsAffected <= 0` or exception during batch insert
- **Check this value**: Should be relatively small if database is healthy

### 3. Rejected Items (Backpressure)
**Metrics**: 
- `vortex_backpressure_rejected_total` (Vortex source of truth) ✅ **USE THIS**
- `crdb_submits_rejected_backpressure_total` (App-level detection - may miss some)

- **What it counts**: Items rejected immediately when submitted due to backpressure
- **When incremented**: When Vortex's `RejectStrategy` rejects items (backpressure >= 0.7)
- **These items**: Never made it to a batch, rejected at submission time
- **Important**: Use `vortex_backpressure_rejected_total` for accurate accounting - it's tracked directly by Vortex and is the source of truth. The app-level metric relies on exception detection which may miss some rejects.

### 4. Rejected Items (Other Reasons)
**Metric**: `crdb_submits_rejected_other_total`
- **What it counts**: Items rejected for reasons other than backpressure
- **When incremented**: When submission fails for other reasons (queue full, etc.)
- **These items**: Never made it to a batch, rejected at submission time

### 5. Items in Queue (Pending Batching)
**Metric**: `vortex_queue_depth` (gauge)
- **What it represents**: Items accepted by Vortex but not yet batched
- **When incremented**: When item is accepted by Vortex but batch conditions not met yet
- **When decremented**: When items are collected into a batch
- **This is the PRIMARY source of difference** if test is still running

### 6. Items in Pending Batches (In-Flight)
**Metric**: `crdb_submits_success_total - crdb_batch_rows_total_total` (calculated)
- **What it represents**: Items in batches that are dispatched but not yet committed
- **How it works**:
  - `crdb_submits_success_total` = items accepted by Vortex
  - `crdb_batch_rows_total_total` = items that have been batched (success + failure)
  - Difference = items accepted but not yet in a completed batch
- **Includes**: Items in batches currently being processed by backend

## How to Verify the Accounting

### Prometheus Queries

1. **Total Submits**:
   ```promql
   crdb_submits_total_total
   ```

2. **Successfully Inserted**:
   ```promql
   crdb_batch_rows_success_total
   ```

3. **Failed Rows**:
   ```promql
   crdb_batch_rows_failure_total
   ```

4. **Rejected (Backpressure)** - **USE VORTEX METRIC**:
   ```promql
   vortex_backpressure_rejected_total  # Source of truth - use this!
   crdb_submits_rejected_backpressure_total  # App detection - may miss some
   ```

5. **Rejected (Other)**:
   ```promql
   crdb_submits_rejected_other_total
   ```

6. **Items in Queue**:
   ```promql
   vortex_queue_depth
   ```

7. **Items In-Flight** (batched but not committed):
   ```promql
   crdb_submits_success_total - crdb_batch_rows_total_total
   ```

### Verification Formula

**Using Vortex Metrics (Recommended)**:
```promql
# Total difference
vortex_requests_submitted_total - 
  (crdb_batch_rows_success_total + 
   crdb_batch_rows_failure_total + 
   vortex_backpressure_rejected_total +      # Use Vortex metric!
   crdb_submits_rejected_other_total + 
   vortex_queue_depth + 
   (crdb_submits_success_total - crdb_batch_rows_total_total))

# Should equal ~0 (or small number for items in-flight)
```

**Using App Metrics (May Have Gaps)**:
```promql
# Total difference
crdb_submits_total_total - 
  (crdb_batch_rows_success_total + 
   crdb_batch_rows_failure_total + 
   crdb_submits_rejected_backpressure_total +  # May miss some!
   crdb_submits_rejected_other_total)

# Should equal:
vortex_queue_depth + (crdb_submits_success_total - crdb_batch_rows_total_total)
```

**Breakdown**:
- `vortex_queue_depth` = items waiting to be batched
- `crdb_submits_success_total - crdb_batch_rows_total_total` = items in-flight (batched but not committed)

## Expected Scenarios

### Scenario 1: All Items Processed (Test Complete)
- **Queue depth**: 0 (or very small)
- **Pending batches**: 0
- **Difference**: Should equal rejected + failed rows
- **Formula**: `submits = inserted + rejected + failed`

### Scenario 2: Test Still Running
- **Queue depth**: > 0 (items waiting to be batched)
- **Pending batches**: > 0 (batches being processed)
- **Difference**: Includes items in queue + pending batches
- **Formula**: `submits = inserted + rejected + failed + in_queue + in_pending_batches`

### Scenario 3: High Rejection Rate
- **Rejected items**: High (backpressure or queue full)
- **Inserted rows**: Lower than expected
- **Difference**: Mostly rejected items
- **Action**: Check backpressure metrics and connection pool

## Your Current Situation

Based on your numbers:
- **Submits**: 6.78M (`crdb_submits_total_total`)
- **Inserted**: 5.09M (`crdb_batch_rows_success_total`)
- **Difference**: 1.69M

**Important**: The reject counters don't account for this difference because they only track items rejected at submission time. The difference is primarily items that were **successfully accepted** but not yet inserted.

To account for this difference, check:
1. **`vortex_backpressure_rejected_total`** - Rejected at submission due to backpressure (Vortex source of truth) ✅ **This is likely the missing 5.5M!**
2. `vortex_queue_depth` - Items waiting in queue to be batched
3. `crdb_submits_success_total - crdb_batch_rows_total_total` - Items in-flight (batched but not committed)
4. `crdb_batch_rows_failure_total` - Items that failed during insert (should be small)
5. `crdb_submits_rejected_other_total` - Rejected at submission for other reasons

**Key Finding**: The missing 5.5M is NOT in rejection metrics - it's in items that were **submitted but never succeeded**.

**The Real Accounting**:
```
vortex_requests_submitted_total (18M) = 
  vortex_requests_succeeded_total (12.5M) +  ← This matches inserted rows!
  (vortex_requests_submitted_total - vortex_requests_succeeded_total) (5.5M) ← Missing items
```

**Vortex Rejection Metrics** (these are small):
- `vortex_requests_rejected_total` = 4,980 (rejected at submission - queue full)
- `vortex_dispatch_rejected_total` = 109,045 (rejected during dispatch - concurrent limit)
- `vortex_backpressure_rejected_total` = 0 (rejected by RejectStrategy - not used)

**The 5.5M Missing Items**:
These are items that were:
- ✅ Accepted by Vortex (`vortex_requests_submitted_total` = 18M)
- ❌ Never completed successfully (`vortex_requests_succeeded_total` = 12.5M)
- ❓ Lost/dropped during processing (not in queue, not rejected, not succeeded)

**Possible Causes**:
1. Items accepted but never batched (queue overflow that wasn't tracked)
2. Batches dispatched but never completed (backend failures not tracked)
3. Items lost during shutdown/restart
4. Metric collection gap

**To Investigate**: Check if there are any backend failures or batch processing issues that aren't being tracked.

## Adding to Grafana Dashboard

A new panel showing the accounting breakdown would help visualize this:

**Panel Title**: "Submit vs Insert Accounting"

**Queries**:
1. Total Submits: `crdb_submits_total_total`
2. Successfully Inserted: `crdb_batch_rows_success_total`
3. Failed Rows: `crdb_batch_rows_failure_total`
4. Rejected (Backpressure): `crdb_submits_rejected_backpressure_total`
5. Rejected (Other): `crdb_submits_rejected_other_total`
6. Unaccounted (Difference): `crdb_submits_total_total - (crdb_batch_rows_success_total + crdb_batch_rows_failure_total + crdb_submits_rejected_backpressure_total + crdb_submits_rejected_other_total)`

This will show where the 1.69M difference is coming from.

