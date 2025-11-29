# Vortex Metric Name Fix

## Issue

Vortex latency panels were showing empty data even though metrics exist.

## Root Cause

The dashboard queries were using incorrect metric names:
- **Dashboard query**: `vortex_batch_dispatch_latency_seconds_bucket`
- **Actual metric**: `vortex_batch_dispatch_latency_milliseconds_bucket`

Vortex library exports metrics in **milliseconds**, not seconds!

## Actual Vortex Metrics

From OpenTelemetry collector:
```
vortex_batch_dispatch_latency_milliseconds_bucket{...}
vortex_batch_dispatch_latency_milliseconds_sum{...}
vortex_batch_dispatch_latency_milliseconds_count{...}

vortex_request_wait_latency_milliseconds_bucket{...}
vortex_request_wait_latency_milliseconds_sum{...}
vortex_request_wait_latency_milliseconds_count{...}
```

## Fix Applied

Updated dashboard queries to use correct metric names:

**Before (Incorrect):**
```promql
histogram_quantile(0.50, sum(rate(vortex_batch_dispatch_latency_seconds_bucket[1m])) by (le)) / 1000
```

**After (Correct):**
```promql
histogram_quantile(0.50, sum(rate(vortex_batch_dispatch_latency_milliseconds_bucket[1m])) by (le)) / 1000
```

## Fixed Queries

1. **Vortex Batch Dispatch Latency**
   - p50, p95 percentiles
   - Metric: `vortex_batch_dispatch_latency_milliseconds_bucket`

2. **Vortex Request Wait Latency**
   - p50, p95 percentiles
   - Metric: `vortex_request_wait_latency_milliseconds_bucket`

## Note on Division by 1000

The `/ 1000` at the end converts milliseconds to seconds for display, which is correct since:
- Metrics are in milliseconds
- We want to display in seconds
- `histogram_quantile()` returns milliseconds, so we divide by 1000

## Java Code Changes

**Note**: The following Java code changes were made but **require test restart**:

1. **LINGER_TIME**: Reduced from 50ms to 10ms
   - Location: `CrdbInsertTask.java`
   - Impact: Faster batch dispatch, lower latency

2. **Batch Processing Optimizations**
   - Pre-sized lists
   - Indexed access
   - Location: `CrdbBatchBackend.java`, `TestInsertRepository.java`

**To see performance improvements**: Restart the test.

**To see dashboard fixes**: Just refresh Grafana (no restart needed).

