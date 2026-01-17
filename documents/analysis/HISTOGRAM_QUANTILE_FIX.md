# Histogram Quantile Query Fix

## Issue

The "Submit & Batch Wait Latency" panel (and potentially other histogram panels) were showing empty data.

## Root Cause

The `histogram_quantile()` function in Prometheus requires histogram buckets to be aggregated by the `le` (less than or equal) label. The queries were missing the required `sum() by (le)` aggregation.

**Incorrect Query:**
```promql
histogram_quantile(0.50, rate(crdb_submit_latency_milliseconds_bucket[1m]))
```

**Problem:**
- `histogram_quantile()` expects buckets grouped by `le` label
- Without `sum() by (le)`, Prometheus can't calculate percentiles
- Results in empty/no data

## Fix

Added `sum() by (le)` aggregation to all histogram_quantile queries:

**Correct Query:**
```promql
histogram_quantile(0.50, sum(rate(crdb_submit_latency_milliseconds_bucket[1m])) by (le)) / 1000
```

## Why This Works

1. **Histogram Buckets**: Each bucket has a `le` label indicating the upper bound
2. **Aggregation Required**: `histogram_quantile()` needs buckets grouped by `le`
3. **Sum by (le)**: Aggregates all buckets with the same `le` value across different label combinations
4. **Result**: Proper percentile calculation

## Fixed Panels

1. **Submit & Batch Wait Latency**
   - `crdb_submit_latency_milliseconds_bucket` - Fixed
   - `crdb_batch_wait_milliseconds_bucket` - Fixed

2. **Batch Duration (Latency Percentiles)**
   - `crdb_batch_duration_milliseconds_bucket` - Fixed

3. **Vortex Latency Metrics** (if applicable)
   - `vortex_batch_dispatch_latency_seconds_bucket` - Fixed
   - `vortex_request_wait_latency_seconds_bucket` - Fixed

## Query Pattern

**Before:**
```promql
histogram_quantile(0.50, rate(METRIC_bucket[1m])) / 1000
```

**After:**
```promql
histogram_quantile(0.50, sum(rate(METRIC_bucket[1m])) by (le)) / 1000
```

## Verification

After the fix, the panels should display:
- Submit Latency p50, p95, p99
- Batch Wait Latency p50, p95
- Batch Duration p50, p95, p99

All values should be in seconds (milliseconds / 1000).

