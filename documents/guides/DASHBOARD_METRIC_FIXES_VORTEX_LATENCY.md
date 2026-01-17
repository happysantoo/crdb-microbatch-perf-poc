# Dashboard Metric Fixes - Vortex Individual Item Metrics (Latency)

## Issue

The "Vortex Individual Item Metrics (Latency)" panel was showing blank/no data.

## Root Cause

The PromQL queries were using incorrect syntax for histogram aggregation. The queries needed to use `sum by (le)` instead of `sum() by (le)`.

## Fixes Applied

### 1. Fixed Histogram Quantile Queries

**Before (Incorrect):**
```promql
histogram_quantile(0.50, sum(rate(vortex_request_wait_latency_milliseconds_bucket[1m])) by (le)) / 1000
```

**After (Correct):**
```promql
histogram_quantile(0.50, sum by (le) (rate(vortex_request_wait_latency_milliseconds_bucket[1m]))) / 1000
```

### 2. Fixed Average Calculation

**Before (Incorrect):**
```promql
rate(vortex_request_wait_latency_milliseconds_sum[1m]) / rate(vortex_request_wait_latency_milliseconds_count[1m]) / 1000
```

**After (Correct):**
```promql
sum(rate(vortex_request_wait_latency_milliseconds_sum[1m])) / sum(rate(vortex_request_wait_latency_milliseconds_count[1m])) / 1000
```

**Why:** The `sum()` aggregates across all label dimensions, ensuring we get the total across all instances.

## Updated Metrics in Panel

The "Vortex Individual Item Metrics (Latency)" panel now shows:

1. **Item Wait Time p50 (sec)** - 50th percentile wait time in queue
2. **Item Wait Time p95 (sec)** - 95th percentile wait time in queue
3. **Item Wait Time p99 (sec)** - 99th percentile wait time in queue
4. **Batch Dispatch p50 (sec)** - 50th percentile batch dispatch latency
5. **Batch Dispatch p95 (sec)** - 95th percentile batch dispatch latency
6. **Item Wait Time Avg (sec)** - Average wait time in queue

## Metric Names

All metrics use the correct names:
- `vortex_request_wait_latency_milliseconds_bucket` (histogram)
- `vortex_batch_dispatch_latency_milliseconds_bucket` (histogram)
- `vortex_request_wait_latency_milliseconds_sum` (sum)
- `vortex_request_wait_latency_milliseconds_count` (count)

**Note:** Metrics are in milliseconds, so we divide by 1000 to convert to seconds for display.

## Verification

To verify the metrics are working:

```bash
# Check if metrics exist
curl http://localhost:8889/metrics | grep vortex_request_wait_latency

# Test query in Prometheus
curl 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.50,sum%20by%20(le)%20(rate(vortex_request_wait_latency_milliseconds_bucket[1m])))'
```

## Dashboard Location

The panel is in:
- **Dashboard**: `crdb-batch-item-metrics-dashboard.json`
- **Panel Title**: "Vortex Individual Item Metrics (Latency)"
- **Panel ID**: 9
- **Position**: x: 12, y: 20

## After Fix

1. **Restart Grafana** to reload dashboards:
   ```bash
   docker restart crdb-microbatch-grafana
   ```

2. **Wait 10 seconds** for Grafana to start

3. **Refresh browser** (Ctrl+F5 or Cmd+Shift+R)

4. **Check panel** - Should now show latency metrics

## Expected Values

At 10K TPS with 50ms linger time:
- **Item Wait Time p50**: ~25-30ms (0.025-0.030 sec)
- **Item Wait Time p95**: ~50ms (0.050 sec)
- **Item Wait Time p99**: ~50-60ms (0.050-0.060 sec)
- **Batch Dispatch p50**: ~10-20ms (0.010-0.020 sec)
- **Batch Dispatch p95**: ~30-50ms (0.030-0.050 sec)

These values depend on:
- Batch size (50 items)
- Linger time (50ms)
- Database performance
- Connection pool size

