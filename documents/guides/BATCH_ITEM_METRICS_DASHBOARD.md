# Batch & Item Level Metrics Dashboard Guide

## Overview

The **CRDB Batch & Item Level Metrics Dashboard** provides comprehensive visibility into batch processing operations, individual item metrics, and Vortex library performance. This dashboard is specifically designed to show:

- **Batch-level metrics**: Throughput, latencies, and success rates for batches
- **Item-level metrics**: Individual item submit throughput and latencies
- **Vortex metrics**: Library-level individual item metrics and queue behavior
- **Comparative analysis**: Batch vs Item vs Row metrics side-by-side

## Dashboard Structure

The dashboard contains **17 panels** organized into logical sections:

### 1. Batch-Level Metrics (Panels 1-5)

#### Panel 1: Batch Throughput
- **Metrics**: 
  - `rate(crdb_batches_total_total[1m])` - Total batches/sec
  - `rate(crdb_batches_success_total[1m])` - Successful batches/sec
  - `rate(crdb_batches_failure_total[1m])` - Failed batches/sec
- **Shows**: Real-time batch processing rate
- **Purpose**: Monitor batch dispatch frequency and success/failure rates

#### Panel 2: Batch Latency
- **Metrics**:
  - `histogram_quantile(0.50, rate(crdb_batch_duration_seconds_bucket[1m]))` - p50
  - `histogram_quantile(0.95, rate(crdb_batch_duration_seconds_bucket[1m]))` - p95
  - `histogram_quantile(0.99, rate(crdb_batch_duration_seconds_bucket[1m]))` - p99
  - `rate(crdb_batch_duration_seconds_sum[1m]) / rate(crdb_batch_duration_seconds_count[1m])` - Average
- **Shows**: Batch insert operation latency percentiles
- **Purpose**: Monitor batch processing time from start to completion

#### Panel 3: Batch Row Throughput
- **Metrics**:
  - `rate(crdb_batch_rows_total_total[1m])` - Total rows/sec
  - `rate(crdb_batch_rows_success_total[1m])` - Successful rows/sec
  - `rate(crdb_batch_rows_failure_total[1m])` - Failed rows/sec
- **Shows**: Row-level throughput within batches
- **Purpose**: Monitor row insertion rate and identify row-level failures

#### Panel 4: Total Batches (Stat)
- **Metric**: `crdb_batches_total_total`
- **Shows**: Cumulative count of batches dispatched
- **Purpose**: Track total batch operations

#### Panel 5: Total Rows Inserted (Stat)
- **Metric**: `crdb_batch_rows_success_total`
- **Shows**: Cumulative count of successfully inserted rows
- **Purpose**: Track total successful row insertions

### 2. Individual Item Metrics (Panels 6-7)

#### Panel 6: Individual Item Throughput (Submits)
- **Metrics**:
  - `rate(crdb_submits_total_total[1m])` - Item submits/sec
  - `rate(crdb_submits_success_total[1m])` - Successful submits/sec
  - `rate(crdb_submits_failure_total[1m])` - Failed submits/sec
- **Shows**: Individual item submission rate
- **Purpose**: Monitor per-item throughput (each VajraPulse task execution submits one item)

#### Panel 7: Individual Item Latency (Submit to Completion)
- **Metrics**:
  - `histogram_quantile(0.50, rate(crdb_submit_latency_seconds_bucket[1m]))` - p50
  - `histogram_quantile(0.95, rate(crdb_submit_latency_seconds_bucket[1m]))` - p95
  - `histogram_quantile(0.99, rate(crdb_submit_latency_seconds_bucket[1m]))` - p99
  - `rate(crdb_submit_latency_seconds_sum[1m]) / rate(crdb_submit_latency_seconds_count[1m])` - Average
- **Shows**: End-to-end latency from item submit to batch completion
- **Purpose**: Monitor individual item processing time

### 3. Vortex Individual Item Metrics (Panels 8-10)

#### Panel 8: Vortex Individual Item Metrics (Throughput)
- **Metrics**:
  - `rate(vortex_requests_submitted_total[1m])` - Requests submitted/sec
  - `rate(vortex_requests_succeeded_total[1m])` - Requests succeeded/sec
  - `rate(vortex_requests_failed_total[1m])` - Requests failed/sec
  - `rate(vortex_batches_dispatched_total[1m])` - Batches dispatched/sec
- **Shows**: Vortex library-level item metrics
- **Purpose**: Monitor Vortex library performance at the item level

#### Panel 9: Vortex Individual Item Metrics (Latency)
- **Metrics**:
  - `histogram_quantile(0.50, rate(vortex_request_wait_latency_seconds_bucket[1m]))` - Item wait time p50
  - `histogram_quantile(0.95, rate(vortex_request_wait_latency_seconds_bucket[1m]))` - Item wait time p95
  - `histogram_quantile(0.99, rate(vortex_request_wait_latency_seconds_bucket[1m]))` - Item wait time p99
  - `histogram_quantile(0.50, rate(vortex_batch_dispatch_latency_seconds_bucket[1m]))` - Batch dispatch p50
  - `histogram_quantile(0.95, rate(vortex_batch_dispatch_latency_seconds_bucket[1m]))` - Batch dispatch p95
- **Shows**: Vortex library latency metrics
- **Purpose**: Monitor item wait time in queue and batch dispatch latency

#### Panel 10: Vortex Queue Depth
- **Metric**: `vortex_queue_depth`
- **Shows**: Current number of items waiting in Vortex queue
- **Purpose**: Monitor queue backlog and identify potential bottlenecks

### 4. Comparative Analysis (Panels 11-13)

#### Panel 11: Success Rates (Batch vs Item vs Row)
- **Metrics**:
  - `rate(crdb_batches_success_total[1m]) / rate(crdb_batches_total_total[1m]) * 100` - Batch success rate
  - `rate(crdb_submits_success_total[1m]) / rate(crdb_submits_total_total[1m]) * 100` - Item submit success rate
  - `rate(crdb_batch_rows_success_total[1m]) / rate(crdb_batch_rows_total_total[1m]) * 100` - Row success rate
  - `rate(vortex_requests_succeeded_total[1m]) / rate(vortex_requests_submitted_total[1m]) * 100` - Vortex request success rate
- **Shows**: Success rates across different levels (batch, item, row, vortex)
- **Purpose**: Compare success rates and identify where failures occur

#### Panel 12: Throughput Comparison (Batches vs Items vs Rows)
- **Metrics**:
  - `rate(crdb_batch_duration_seconds_count[1m])` - Batches/sec (from duration count)
  - `rate(crdb_submit_latency_seconds_count[1m])` - Items/sec (from latency count)
  - `rate(crdb_batch_rows_success_total[1m])` - Rows/sec
- **Shows**: Throughput at different levels on the same scale
- **Purpose**: Understand the relationship between batches, items, and rows

#### Panel 13: Batch Efficiency (Rows/Items per Batch)
- **Metrics**:
  - `rate(crdb_batch_rows_success_total[1m]) / rate(crdb_batches_total_total[1m])` - Avg rows per batch
  - `rate(crdb_submits_total_total[1m]) / rate(crdb_batches_total_total[1m])` - Avg items per batch
- **Shows**: Average batch size in terms of rows and items
- **Purpose**: Monitor batching efficiency and batch size distribution

### 5. Summary Statistics (Panels 14-17)

#### Panel 14: Total Item Submits (Stat)
- **Metric**: `crdb_submits_total_total`
- **Shows**: Cumulative count of item submits
- **Purpose**: Track total item operations

#### Panel 15: Total Batch Failures (Stat)
- **Metric**: `crdb_batches_failure_total`
- **Shows**: Cumulative count of failed batches
- **Purpose**: Track total batch failures

#### Panel 16: Total Row Failures (Stat)
- **Metric**: `crdb_batch_rows_failure_total`
- **Shows**: Cumulative count of failed rows
- **Purpose**: Track total row failures

#### Panel 17: Total Vortex Requests (Stat)
- **Metric**: `vortex_requests_submitted_total`
- **Shows**: Cumulative count of Vortex requests
- **Purpose**: Track total Vortex operations

## Metric Naming Conventions

### Batch Metrics (from CrdbBatchBackend)
- `crdb_batches_total_total` - Total batches dispatched (double `_total` due to Micrometer conversion)
- `crdb_batches_success_total` - Successful batches
- `crdb_batches_failure_total` - Failed batches
- `crdb_batch_rows_total_total` - Total rows in batches
- `crdb_batch_rows_success_total` - Successful rows
- `crdb_batch_rows_failure_total` - Failed rows
- `crdb_batch_duration_seconds` - Batch duration histogram (with `_bucket`, `_sum`, `_count`)

### Item Metrics (from CrdbInsertTask)
- `crdb_submits_total_total` - Total item submits (double `_total` due to Micrometer conversion)
- `crdb_submits_success_total` - Successful submits
- `crdb_submits_failure_total` - Failed submits
- `crdb_submit_latency_seconds` - Submit latency histogram (with `_bucket`, `_sum`, `_count`)

### Vortex Metrics (from Vortex Library)
- `vortex_requests_submitted_total` - Total requests submitted
- `vortex_requests_succeeded_total` - Total successful requests
- `vortex_requests_failed_total` - Total failed requests
- `vortex_batches_dispatched_total` - Total batches dispatched
- `vortex_queue_depth` - Current queue depth (gauge)
- `vortex_batch_dispatch_latency_seconds` - Batch dispatch latency histogram
- `vortex_request_wait_latency_seconds` - Request wait latency histogram

## Key Insights

### Understanding the Metrics Hierarchy

1. **VajraPulse Task Execution** → Submits 1 item → **Item Metrics**
2. **Vortex Library** → Batches items → **Vortex Metrics**
3. **CrdbBatchBackend** → Processes batches → **Batch Metrics**
4. **CockroachDB** → Inserts rows → **Row Metrics**

### Expected Relationships

- **Items/sec ≈ Vortex Requests/sec**: Each item submit becomes a Vortex request
- **Batches/sec < Items/sec**: Multiple items are batched together
- **Rows/sec ≈ Items/sec**: Each item typically inserts one row
- **Batch Duration ≈ Item Submit Latency**: Item latency includes batch wait time

### Performance Indicators

- **High Queue Depth**: Indicates items are waiting longer than expected
- **Low Batch Efficiency**: Fewer rows/items per batch suggests suboptimal batching
- **High p99 Latency**: Indicates tail latency issues
- **Success Rate < 100%**: Indicates failures at batch, item, or row level

## Usage

1. **Import Dashboard**: 
   - Copy `grafana/dashboards/crdb-batch-item-metrics-dashboard.json`
   - Import into Grafana via UI or provisioning

2. **Verify Metrics**:
   ```bash
   # Check Prometheus for metrics
   curl 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep -E "(crdb|vortex)"
   ```

3. **Monitor During Test**:
   - Watch batch throughput increase as load increases
   - Monitor item latency to ensure it stays within acceptable bounds
   - Check queue depth to identify bottlenecks
   - Compare success rates across different levels

## Troubleshooting

### No Data Showing
- Verify metrics are being exported: `curl http://localhost:8889/metrics | grep crdb`
- Check Prometheus is scraping: `curl http://localhost:9090/api/v1/targets`
- Verify dashboard datasource is configured correctly

### Metric Name Mismatches
- Micrometer converts dots to underscores and adds `_total` suffix
- Counters with `.total` in name become `_total_total`
- Timers become histograms with `_seconds` suffix

### Zero Throughput
- Check if test is running: `docker ps | grep crdb`
- Verify database connectivity
- Check application logs for errors

## Related Dashboards

- **CRDB Microbatch Dashboard Complete**: Overall system metrics including VajraPulse adaptive load
- **CRDB Batch Dashboard**: Focused on batch-level operations

