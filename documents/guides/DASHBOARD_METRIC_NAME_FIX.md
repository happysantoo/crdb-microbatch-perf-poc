# Dashboard Metric Name Fix Guide

## Issue

Row-level metrics and Vortex metrics are not appearing in Grafana dashboards.

## Root Cause

Micrometer converts metric names when exporting to Prometheus:
- Dots (`.`) become underscores (`_`)
- Counters get `_total` suffix
- If metric name already ends with `.total`, it becomes `_total_total` (double total)
- Timers get `_seconds` suffix and are exported as histograms

## Metric Name Mapping

### Batch Metrics (from CrdbBatchBackend)

**Code → Prometheus:**
- `crdb.batches.total` → `crdb_batches_total_total` ⚠️ (double total!)
- `crdb.batches.success` → `crdb_batches_success_total`
- `crdb.batches.failure` → `crdb_batches_failure_total`
- `crdb.batch.rows.total` → `crdb_batch_rows_total_total` ⚠️ (double total!)
- `crdb.batch.rows.success` → `crdb_batch_rows_success_total`
- `crdb.batch.rows.failure` → `crdb_batch_rows_failure_total`
- `crdb.batch.duration` → `crdb_batch_duration_seconds` (histogram with `_bucket`, `_sum`, `_count`)

### Submit Metrics (from CrdbInsertTask)

**Code → Prometheus:**
- `crdb.submits.total` → `crdb_submits_total_total` ⚠️ (double total!)
- `crdb.submits.success` → `crdb_submits_success_total`
- `crdb.submits.failure` → `crdb_submits_failure_total`
- `crdb.submit.latency` → `crdb_submit_latency_seconds` (histogram)
- `crdb.batch.wait` → `crdb_batch_wait_seconds` (histogram)

### Vortex Metrics (from Vortex Library)

**Note**: Vortex library metric names may vary. Check actual exported names.

**Expected:**
- `vortex.requests.submitted` → `vortex_requests_submitted_total`
- `vortex.batches.dispatched` → `vortex_batches_dispatched_total`
- `vortex.requests.succeeded` → `vortex_requests_succeeded_total`
- `vortex.requests.failed` → `vortex_requests_failed_total`
- `vortex.queue.depth` → `vortex_queue_depth` (gauge, no suffix)
- `vortex.batch.dispatch.latency` → `vortex_batch_dispatch_latency_seconds` (histogram)
- `vortex.request.wait.latency` → `vortex_request_wait_latency_seconds` (histogram)

## How to Verify Actual Metric Names

### 1. Check OpenTelemetry Collector Metrics

```bash
curl http://localhost:8889/metrics | grep -E "(crdb_batch|vortex)" | head -30
```

### 2. Check Prometheus

```bash
# List all metrics
curl 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep -E "(crdb_batch|vortex)"

# Query specific metric
curl 'http://localhost:9090/api/v1/query?query=crdb_batch_rows_total_total'
```

### 3. Use Prometheus UI

1. Open http://localhost:9090
2. Go to Graph
3. Type `crdb_batch` or `vortex` and use autocomplete
4. See actual metric names

## Dashboard Query Fixes

### Batch Metrics Queries

**Before (Wrong):**
```promql
rate(crdb_batches_total[1m])  # Missing _total suffix
```

**After (Correct):**
```promql
rate(crdb_batches_total_total[1m])  # Double total!
```

### Row Metrics Queries

**Before (Wrong):**
```promql
rate(crdb_batch_rows_total[1m])  # Missing _total suffix
```

**After (Correct):**
```promql
rate(crdb_batch_rows_total_total[1m])  # Double total!
```

### Timer/Histogram Queries

**Before (Wrong):**
```promql
rate(crdb_batch_duration[1m])  # Missing _seconds suffix
```

**After (Correct):**
```promql
histogram_quantile(0.95, rate(crdb_batch_duration_seconds_bucket[1m]))
```

## Quick Fix Script

If metrics still don't appear, use this to find actual names:

```bash
#!/bin/bash
echo "=== CRDB Batch Metrics ==="
curl -s http://localhost:8889/metrics 2>/dev/null | grep crdb_batch | head -20

echo ""
echo "=== Vortex Metrics ==="
curl -s http://localhost:8889/metrics 2>/dev/null | grep vortex | head -20

echo ""
echo "=== All CRDB Metrics ==="
curl -s http://localhost:8889/metrics 2>/dev/null | grep "^crdb" | head -30
```

## Common Issues

### Issue 1: Double `_total` Suffix
**Problem**: Metric name has `.total` in code, becomes `_total_total` in Prometheus
**Solution**: Use `_total_total` in queries

### Issue 2: Vortex Metrics Not Exposed
**Problem**: Vortex library might not expose metrics, or uses different names
**Solution**: Check Vortex library documentation or source code for actual metric names

### Issue 3: Timer vs Histogram
**Problem**: Timers are exported as histograms with `_seconds_bucket` suffix
**Solution**: Use `histogram_quantile()` with `_bucket` metrics

## Updated Dashboard

The dashboard has been updated with correct metric names. If metrics still don't appear:

1. Verify metrics are being exported (use curl commands above)
2. Check Prometheus is scraping OpenTelemetry collector
3. Verify metric names match between code and dashboard
4. Check if Vortex library is actually exposing metrics

