# Metric Name Verification Guide

## Overview

When metrics are exported via OpenTelemetry to Prometheus, the metric names may differ from the Micrometer names due to naming conventions. This guide helps verify the actual metric names.

## How to Verify Metric Names

### 1. Check OpenTelemetry Collector Metrics Endpoint

```bash
curl http://localhost:8889/metrics | grep crdb
```

### 2. Check Prometheus Targets

1. Open Prometheus: http://localhost:9090
2. Go to Status → Targets
3. Verify `otel-collector` target is UP
4. Click on the target to see scraped metrics

### 3. Query Prometheus Directly

```bash
# List all metrics
curl 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep crdb

# Query specific metric
curl 'http://localhost:9090/api/v1/query?query=crdb_batches_total'
```

## Expected Metric Name Patterns

### Micrometer → OpenTelemetry → Prometheus

**Counters:**
- Micrometer: `crdb.batches.total`
- OpenTelemetry: `crdb_batches_total` (with `_total` suffix)
- Prometheus: `crdb_batches_total`

**Timers:**
- Micrometer: `crdb.batch.duration`
- OpenTelemetry: `crdb_batch_duration_seconds` (with `_seconds` suffix)
- Prometheus: `crdb_batch_duration_seconds` (histogram with `_bucket`, `_sum`, `_count`)

**Gauges:**
- Micrometer: `crdb.rows.inserted`
- OpenTelemetry: `crdb_rows_inserted`
- Prometheus: `crdb_rows_inserted`

## Common Naming Issues

### Issue 1: Missing `_total` Suffix
**Problem**: Dashboard shows "No data"
**Solution**: Check if counter metrics need `_total` suffix

**Example:**
```promql
# Wrong
rate(crdb_batches[1m])

# Correct
rate(crdb_batches_total[1m])
```

### Issue 2: Timer vs Histogram
**Problem**: Latency queries return no data
**Solution**: Timers are exported as histograms with `_seconds` suffix

**Example:**
```promql
# Wrong
rate(crdb_batch_duration[1m])

# Correct
rate(crdb_batch_duration_seconds_bucket[1m])
```

### Issue 3: Unit Mismatch
**Problem**: Values seem incorrect
**Solution**: Check if metrics are in seconds vs milliseconds

**Example:**
```promql
# If metric is in milliseconds
histogram_quantile(0.95, rate(crdb_batch_duration_milliseconds_bucket[1m])) / 1000

# If metric is in seconds
histogram_quantile(0.95, rate(crdb_batch_duration_seconds_bucket[1m]))
```

## Quick Fix Script

If metrics don't appear, use this script to find actual names:

```bash
#!/bin/bash
echo "Checking for CRDB batch metrics..."
curl -s http://localhost:8889/metrics | grep -E "crdb_(batch|submit)" | head -20

echo ""
echo "Checking for Vortex metrics..."
curl -s http://localhost:8889/metrics | grep -E "vortex_" | head -20
```

## Dashboard Panel Updates

If metric names differ, update the dashboard panels:

1. Open Grafana dashboard
2. Edit panel
3. Update query expression
4. Save dashboard

## Verification Checklist

- [ ] OpenTelemetry collector is running
- [ ] Prometheus is scraping collector (port 8889)
- [ ] Metrics appear in Prometheus query interface
- [ ] Dashboard datasource is configured correctly
- [ ] Metric names match between code and dashboard

