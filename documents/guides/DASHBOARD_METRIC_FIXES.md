# Dashboard Metric Name Fixes

## Issue

The Grafana dashboard was not showing item-level metrics and CRDB metrics because the metric names in the dashboard queries did not match the actual metric names exported by the application.

## Root Cause

1. **Counter Names**: The dashboard used `crdb_batches_total_total` but the actual metric is `crdb_batches_total` (no double `_total` suffix)
2. **Timer Names**: The dashboard used `_seconds` suffix but the actual metrics use `_milliseconds` suffix
3. **Unit Conversion**: Latency metrics needed to be divided by 1000 to convert from milliseconds to seconds

## Actual Metric Names

### Batch Metrics (from CrdbBatchBackend)
- `crdb_batches_total` ✅ (NOT `crdb_batches_total_total`)
- `crdb_batches_success_total`
- `crdb_batches_failure_total`
- `crdb_batch_rows_total` ✅ (NOT `crdb_batch_rows_total_total`)
- `crdb_batch_rows_success_total`
- `crdb_batch_rows_failure_total`
- `crdb_batch_duration_milliseconds` ✅ (NOT `crdb_batch_duration_seconds`)
  - Histogram with `_bucket`, `_sum`, `_count` suffixes

### Item Metrics (from CrdbInsertTask)
- `crdb_submits_total` ✅ (NOT `crdb_submits_total_total`)
- `crdb_submits_success_total`
- `crdb_submits_failure_total`
- `crdb_submit_latency_milliseconds` ✅ (NOT `crdb_submit_latency_seconds`)
  - Histogram with `_bucket`, `_sum`, `_count` suffixes

### Vortex Metrics (from Vortex Library)
- `vortex_requests_submitted_total`
- `vortex_requests_succeeded_total`
- `vortex_requests_failed_total`
- `vortex_batches_dispatched_total`
- `vortex_queue_depth` (gauge)
- `vortex_batch_dispatch_latency_milliseconds` ✅ (NOT `vortex_batch_dispatch_latency_seconds`)
  - Histogram with `_bucket`, `_sum`, `_count` suffixes
- `vortex_request_wait_latency_milliseconds` ✅ (NOT `vortex_request_wait_latency_seconds`)
  - Histogram with `_bucket`, `_sum`, `_count` suffixes

## Fixes Applied

### 1. Counter Metric Names
**Before:**
```promql
rate(crdb_batches_total_total[1m])
rate(crdb_batch_rows_total_total[1m])
rate(crdb_submits_total_total[1m])
```

**After:**
```promql
rate(crdb_batches_total[1m])
rate(crdb_batch_rows_total[1m])
rate(crdb_submits_total[1m])
```

### 2. Timer/Histogram Metric Names with Unit Conversion
**Before:**
```promql
histogram_quantile(0.50, rate(crdb_batch_duration_seconds_bucket[1m]))
rate(crdb_batch_duration_seconds_sum[1m]) / rate(crdb_batch_duration_seconds_count[1m])
```

**After:**
```promql
histogram_quantile(0.50, rate(crdb_batch_duration_milliseconds_bucket[1m])) / 1000
rate(crdb_batch_duration_milliseconds_sum[1m]) / rate(crdb_batch_duration_milliseconds_count[1m]) / 1000
```

### 3. Success Rate Calculations
**Before:**
```promql
rate(crdb_batches_success_total[1m]) / rate(crdb_batches_total_total[1m]) * 100
rate(crdb_submits_success_total[1m]) / rate(crdb_submits_total_total[1m]) * 100
rate(crdb_batch_rows_success_total[1m]) / rate(crdb_batch_rows_total_total[1m]) * 100
```

**After:**
```promql
rate(crdb_batches_success_total[1m]) / rate(crdb_batches_total[1m]) * 100
rate(crdb_submits_success_total[1m]) / rate(crdb_submits_total[1m]) * 100
rate(crdb_batch_rows_success_total[1m]) / rate(crdb_batch_rows_total[1m]) * 100
```

## Prometheus Scraping Configuration

### Verified Targets
Prometheus is configured to scrape from:
1. **OpenTelemetry Collector** (`otel-collector:8889`)
   - Scrapes application metrics exported via Micrometer → OpenTelemetry
   - Status: ✅ UP

2. **CockroachDB** (`cockroachdb:8080`)
   - Scrapes CockroachDB internal metrics from `/_status/vars` endpoint
   - Status: ✅ UP

### Verification Commands

```bash
# Check Prometheus targets
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job, health, scrapeUrl}'

# Check available metrics
curl -s 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep -E "(crdb|vortex)"

# Check OpenTelemetry collector metrics
curl -s http://localhost:8889/metrics | grep -E "(crdb|vortex)" | head -20

# Check CockroachDB metrics
curl -s http://localhost:8080/_status/vars | head -30
```

## Dashboard Status

✅ **Dashboard JSON is valid**
✅ **All metric names corrected**
✅ **Unit conversions applied (milliseconds → seconds)**
✅ **Prometheus targets verified**

## Next Steps

1. **Import the updated dashboard** into Grafana:
   - File: `grafana/dashboards/crdb-batch-item-metrics-dashboard.json`
   - Or reload Grafana if using provisioning

2. **Verify metrics are showing**:
   - Check that batch throughput panels show data
   - Check that item-level metrics panels show data
   - Check that Vortex metrics panels show data

3. **If metrics still don't appear**:
   - Verify the application is running and generating metrics
   - Check Prometheus targets: http://localhost:9090/targets
   - Query metrics directly: http://localhost:9090/graph?g0.expr=rate(crdb_batches_total[1m])

## Related Files

- `grafana/dashboards/crdb-batch-item-metrics-dashboard.json` - Updated dashboard
- `prometheus/prometheus.yml` - Prometheus configuration
- `documents/guides/BATCH_ITEM_METRICS_DASHBOARD.md` - Dashboard documentation

