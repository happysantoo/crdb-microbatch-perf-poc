# Grafana Batch Metrics Dashboard

## Overview

A new Grafana dashboard has been created that focuses on batch metrics while retaining all existing panels. The dashboard provides comprehensive visibility into microbatching performance.

## Dashboard Files

1. **`crdb-microbatch-dashboard-complete.json`** - Complete dashboard with all panels (recommended)
2. **`crdb-microbatch-dashboard-batch.json`** - Batch-focused dashboard (batch metrics only)

## Batch Metrics Panels

### 1. **Batch Throughput (Batches/sec)**
- **Metrics**: `rate(crdb_batches_total[1m])`
- **Shows**: Total batches/sec, successful batches/sec, failed batches/sec
- **Purpose**: Monitor batch processing rate

### 2. **Row Throughput (Rows/sec)**
- **Metrics**: `rate(crdb_batch_rows_total_total[1m])`
- **Shows**: Total rows/sec, successful rows/sec, failed rows/sec
- **Purpose**: Monitor row-level processing rate

### 3. **Batch Duration (Latency Percentiles)**
- **Metrics**: `histogram_quantile()` on `crdb_batch_duration_seconds_bucket`
- **Shows**: p50, p95, p99, and average batch duration
- **Purpose**: Monitor batch insert latency

### 4. **Batch Success Rate (%)**
- **Metrics**: `(rate(crdb_batches_success_total[1m]) / rate(crdb_batches_total[1m])) * 100`
- **Shows**: Percentage of successful batches
- **Purpose**: Monitor batch reliability

### 5. **Row Success Rate (%)**
- **Metrics**: `(rate(crdb_batch_rows_success_total[1m]) / rate(crdb_batch_rows_total_total[1m])) * 100`
- **Shows**: Percentage of successful rows
- **Purpose**: Monitor row-level reliability

### 6. **Submit Throughput (Submits/sec)**
- **Metrics**: `rate(crdb_submits_total_total[1m])`
- **Shows**: Total submits/sec, successful submits/sec, failed submits/sec
- **Purpose**: Monitor item submission rate to batcher

### 7. **Submit & Batch Wait Latency**
- **Metrics**: 
  - `histogram_quantile()` on `crdb_submit_latency_seconds_bucket`
  - `histogram_quantile()` on `crdb_batch_wait_seconds_bucket`
- **Shows**: Submit latency (p50, p95, p99) and batch wait time (p50, p95)
- **Purpose**: Monitor end-to-end latency from submit to completion

### 8. **Vortex Queue & Request Metrics**
- **Metrics**: 
  - `vortex_queue_depth` (gauge)
  - `rate(vortex_requests_submitted_total[1m])`
  - `rate(vortex_batches_dispatched_total[1m])`
- **Shows**: Queue depth, requests submitted/sec, batches dispatched/sec
- **Purpose**: Monitor Vortex library internal metrics

### 9. **Vortex Latency Metrics**
- **Metrics**: 
  - `histogram_quantile()` on `vortex_batch_dispatch_latency_seconds_bucket`
  - `histogram_quantile()` on `vortex_request_wait_latency_seconds_bucket`
- **Shows**: Batch dispatch latency and request wait latency (p50, p95)
- **Purpose**: Monitor Vortex library latency

### 10-13. **Summary Stats**
- **Total Batches**: Cumulative count of batches
- **Total Batch Failures**: Cumulative count of failed batches
- **Total Rows Processed**: Cumulative count of rows
- **Total Row Failures**: Cumulative count of failed rows

## Metric Names

### Batch Metrics (from CrdbBatchBackend)
- `crdb_batches_total` - Total batches dispatched
- `crdb_batches_success_total` - Successful batches
- `crdb_batches_failure_total` - Failed batches
- `crdb_batch_rows_total_total` - Total rows in batches
- `crdb_batch_rows_success_total` - Successful rows
- `crdb_batch_rows_failure_total` - Failed rows
- `crdb_batch_duration_seconds` - Batch insert duration (histogram)

### Submit Metrics (from CrdbInsertTask)
- `crdb_submits_total_total` - Total submits to batcher
- `crdb_submits_success_total` - Successful submits
- `crdb_submits_failure_total` - Failed submits
- `crdb_submit_latency_seconds` - Submit to completion latency (histogram)
- `crdb_batch_wait_seconds` - Batch wait time (histogram)

### Vortex Metrics (built-in)
- `vortex_requests_submitted_total` - Total requests submitted
- `vortex_batches_dispatched_total` - Total batches dispatched
- `vortex_requests_succeeded_total` - Total successful requests
- `vortex_requests_failed_total` - Total failed requests
- `vortex_queue_depth` - Current queue depth (gauge)
- `vortex_batch_dispatch_latency_seconds` - Batch dispatch latency (histogram)
- `vortex_request_wait_latency_seconds` - Request wait latency (histogram)

## Importing the Dashboard

### Option 1: Import Complete Dashboard (Recommended)
1. Open Grafana
2. Go to Dashboards → Import
3. Upload `grafana/dashboards/crdb-microbatch-dashboard-complete.json`
4. Select Prometheus datasource
5. Click "Import"

### Option 2: Import Batch-Only Dashboard
1. Open Grafana
2. Go to Dashboards → Import
3. Upload `grafana/dashboards/crdb-microbatch-dashboard-batch.json`
4. Select Prometheus datasource
5. Click "Import"

## Dashboard Organization

The complete dashboard is organized as follows:

1. **Batch Metrics Section** (Top - New panels)
   - Batch throughput
   - Row throughput
   - Batch duration/latency
   - Success rates
   - Submit metrics
   - Vortex metrics
   - Summary stats

2. **Existing Panels** (Below batch metrics)
   - CRDB insert metrics
   - VajraPulse metrics
   - JVM metrics
   - HikariCP metrics
   - GC metrics
   - Virtual threads metrics

## Key Insights

### Batch Efficiency
- **Batch Throughput**: Should be ~1/50th of row throughput (50 rows per batch)
- **Batch Success Rate**: Should be >99%
- **Batch Duration**: Should be <100ms for optimal performance

### Submit Efficiency
- **Submit Throughput**: Should match VajraPulse task throughput
- **Submit Latency**: Includes batch wait time (should be <50ms typically)
- **Batch Wait**: Time waiting for batch to form (should be <50ms)

### Vortex Library
- **Queue Depth**: Should be low (<100) for optimal performance
- **Request Wait**: Time in queue before batching (should be <50ms)
- **Batch Dispatch**: Time to dispatch batch (should be <10ms)

## Troubleshooting

### No Data in Panels
1. Check if metrics are being exported: `curl http://localhost:4317/metrics | grep crdb_batch`
2. Verify Prometheus is scraping: Check Prometheus targets
3. Check metric names match (Micrometer adds `_total` suffix to counters)

### Incorrect Values
1. Verify metric units (seconds vs milliseconds)
2. Check histogram bucket configuration
3. Verify rate() window matches data collection interval

### Missing Metrics
1. Check if Vortex library is exposing metrics
2. Verify Micrometer is properly configured
3. Check OpenTelemetry exporter configuration

