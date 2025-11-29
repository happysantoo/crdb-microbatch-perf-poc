# Complete Grafana Dashboard Guide

## Dashboard Overview

The complete dashboard (`crdb-microbatch-dashboard-complete.json`) includes all metrics for monitoring the CRDB microbatch performance test.

## Dashboard Panels

### 1. Overall Throughput
- **VajraPulse Task Throughput**: `rate(vajrapulse_task_executions_total[1m])`
- **CRDB Submits/sec**: `rate(crdb_submits_total_total[1m])`
- **Vortex Requests/sec**: `rate(vortex_requests_submitted_total[1m])`

### 2. Total Rows Inserted (Cumulative)
- **Metric**: `crdb_batch_rows_success_total`
- **Type**: Stat panel showing cumulative count
- **Description**: Total number of rows successfully inserted since start

### 3. Rows Inserted/sec (Throughput)
- **Metric**: `rate(crdb_batch_rows_success_total[1m])`
- **Type**: Stat panel showing current rate
- **Description**: Current throughput of rows being inserted per second

### 4. Task Execution Latency
- **VajraPulse p50/p95/p99**: `histogram_quantile()` on `vajrapulse_task_duration_seconds_bucket`
- **CRDB Submit p50/p95/p99**: `histogram_quantile()` on `crdb_submit_latency_seconds_bucket`

### 5. Batch Processing Throughput
- **Batches/sec**: `rate(crdb_batches_total_total[1m])`
- **Rows Inserted/sec**: `rate(crdb_batch_rows_success_total[1m])`
- **Total Rows/sec**: `rate(crdb_batch_rows_total_total[1m])`
- **Vortex Batches/sec**: `rate(vortex_batches_dispatched_total[1m])`

### 6. Batch Processing Latency
- **Batch Duration p50/p95/p99**: `histogram_quantile()` on `crdb_batch_duration_seconds_bucket`
- **Vortex Dispatch p50/p95**: `histogram_quantile()` on `vortex_batch_dispatch_latency_seconds_bucket`

### 7. VajraPulse Load (Current vs Target)
- **Current Load**: `vajrapulse_load_current`
- **Target Load**: `vajrapulse_load_target`

### 8. Vortex Queue Metrics
- **Queue Depth**: `vortex_queue_depth`
- **Request Wait p50/p95**: `histogram_quantile()` on `vortex_request_wait_latency_seconds_bucket`

### 9. Success/Failure Rates
- **Successful Submits/sec**: `rate(crdb_submits_success_total[1m])`
- **Failed Submits/sec**: `rate(crdb_submits_failure_total[1m])`
- **Successful Batches/sec**: `rate(crdb_batches_success_total[1m])`
- **Failed Batches/sec**: `rate(crdb_batches_failure_total[1m])`

### 10. JVM Memory
- **Heap Used**: `jvm_memory_used_bytes{area="heap"}`
- **Heap Max**: `jvm_memory_max_bytes{area="heap"}`
- **Non-Heap Used**: `jvm_memory_used_bytes{area="nonheap"}`

### 11. JVM GC Metrics
- **GC Pause Rate**: `rate(jvm_gc_pause_seconds_sum[1m])`
- **Max GC Pause**: `jvm_gc_pause_seconds_max`

### 12. JVM Threads
- **Platform Threads**: `jvm_threads_live_threads`
- **Virtual Threads**: `jvm_threads_virtual_threads`

### 13. Success Rate (%)
- **Metric**: `rate(vajrapulse_task_success_total[1m]) / rate(vajrapulse_task_executions_total[1m]) * 100`

### 14. Total Executions
- **Metric**: `vajrapulse_task_executions_total`

## Key Metrics for Rows Inserted

### Total Rows Inserted (Cumulative Counter)
```promql
crdb_batch_rows_success_total
```
This shows the total number of rows successfully inserted since the application started.

### Rows Inserted per Second (Throughput)
```promql
rate(crdb_batch_rows_success_total[1m])
```
This shows the current rate of rows being inserted per second.

### Rows in Batch Processing Throughput Panel
The "Batch Processing Throughput" panel also shows:
- **Rows Inserted/sec**: Same as above
- **Total Rows/sec**: `rate(crdb_batch_rows_total_total[1m])` - includes both successful and failed rows

## Metric Name Mapping

### Code → Prometheus
- `crdb.batch.rows.success` → `crdb_batch_rows_success_total`
- `crdb.batch.rows.total` → `crdb_batch_rows_total_total` (double total)
- `crdb.batches.total` → `crdb_batches_total_total` (double total)
- `crdb.submits.total` → `crdb_submits_total_total` (double total)
- `crdb.batch.duration` → `crdb_batch_duration_seconds` (histogram)
- `crdb.submit.latency` → `crdb_submit_latency_seconds` (histogram)

## Troubleshooting

### If "Total Rows Inserted" shows "No data":

1. **Verify metrics are being exported:**
   ```bash
   curl http://localhost:8889/metrics | grep crdb_batch_rows_success_total
   ```

2. **Check Prometheus is scraping:**
   - Go to http://localhost:9090/targets
   - Verify `otel-collector` target is UP

3. **Query Prometheus directly:**
   ```bash
   curl 'http://localhost:9090/api/v1/query?query=crdb_batch_rows_success_total'
   ```

4. **Check time range in Grafana:**
   - Set to "Last 15 minutes" or "Last 1 hour"
   - Use "Last 5 minutes" for active test monitoring

5. **Verify application is running:**
   ```bash
   ./gradlew bootRun
   ```

### If metrics exist but dashboard shows "No data":

1. **Check metric name spelling** - ensure it matches exactly
2. **Check for labels** - some metrics might have labels that need to be specified
3. **Try querying without rate()** for counters:
   ```promql
   crdb_batch_rows_success_total
   ```
   Instead of:
   ```promql
   rate(crdb_batch_rows_success_total[1m])
   ```

## Dashboard Location

The dashboard file is located at:
- `grafana/dashboards/crdb-microbatch-dashboard-complete.json`
- Also copied to `grafana/dashboards/crdb-microbatch-dashboard.json` (default)

## Loading the Dashboard

1. **Automatic (via provisioning):**
   - Dashboard should auto-load if Grafana is configured to read from `/var/lib/grafana/dashboards`
   - Check Grafana provisioning configuration

2. **Manual:**
   - Go to Grafana UI: http://localhost:3000
   - Click "+" → "Import"
   - Upload `grafana/dashboards/crdb-microbatch-dashboard-complete.json`
   - Select Prometheus datasource
   - Click "Import"

## Expected Values

When the test is running:
- **Total Rows Inserted**: Should increase continuously
- **Rows Inserted/sec**: Should show current throughput (e.g., 100-1000 rows/sec depending on load)
- **Batch Processing Throughput**: Should show batches being processed
- **VajraPulse Task Throughput**: Should match the load pattern TPS

