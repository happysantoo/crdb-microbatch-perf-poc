# Prometheus Metrics Queries Guide

## Available Metrics

### VajraPulse Metrics
- `vajrapulse_task_executions_total` - Total task executions
- `vajrapulse_task_duration_seconds` - Task execution latency (histogram)
- `vajrapulse_task_success_total` - Successful tasks
- `vajrapulse_task_failures_total` - Failed tasks
- `vajrapulse_load_current` - Current load (threads)
- `vajrapulse_load_target` - Target load (threads)

### CRDB Insert Metrics
- `crdb_inserts_total` - Total insert attempts
- `crdb_inserts_errors_total` - Insert errors
- `crdb_inserts_duration` - Insert operation duration (histogram)
- `crdb_rows_inserted` - Current number of rows inserted (gauge)

### JVM Metrics
- `jvm_memory_used_bytes` - Memory usage
- `jvm_threads_live_threads` - Live threads
- `jvm_threads_virtual_threads` - Virtual threads (Java 21)
- `jvm_gc_pause_seconds` - GC pause times

## Useful Prometheus Queries

### Throughput Queries

**VajraPulse Task Throughput:**
```promql
rate(vajrapulse_task_executions_total[1m])
```

**CRDB Insert Throughput:**
```promql
rate(crdb_inserts_total[1m])
```

### Latency Queries

**VajraPulse Task Latency (p50, p95, p99):**
```promql
histogram_quantile(0.50, rate(vajrapulse_task_duration_seconds_bucket[1m]))
histogram_quantile(0.95, rate(vajrapulse_task_duration_seconds_bucket[1m]))
histogram_quantile(0.99, rate(vajrapulse_task_duration_seconds_bucket[1m]))
```

**CRDB Insert Latency (p50, p95, p99):**
```promql
histogram_quantile(0.50, rate(crdb_inserts_duration_bucket[1m]))
histogram_quantile(0.95, rate(crdb_inserts_duration_bucket[1m]))
histogram_quantile(0.99, rate(crdb_inserts_duration_bucket[1m]))
```

### Success Rate Queries

**VajraPulse Task Success Rate:**
```promql
rate(vajrapulse_task_success_total[1m]) / rate(vajrapulse_task_executions_total[1m]) * 100
```

**CRDB Insert Success Rate:**
```promql
(rate(crdb_inserts_total[1m]) - rate(crdb_inserts_errors_total[1m])) / rate(crdb_inserts_total[1m]) * 100
```

### Load Queries

**Current vs Target Load:**
```promql
vajrapulse_load_current
vajrapulse_load_target
```

**Load Utilization:**
```promql
vajrapulse_load_current / vajrapulse_load_target * 100
```

### Row Count Queries

**Total Rows Inserted:**
```promql
crdb_rows_inserted
```

**Rows Inserted Rate:**
```promql
rate(crdb_rows_inserted[1m])
```

### JVM Queries

**Memory Usage:**
```promql
jvm_memory_used_bytes{area="heap"}
jvm_memory_max_bytes{area="heap"}
```

**Thread Count:**
```promql
jvm_threads_live_threads
jvm_threads_virtual_threads
```

**GC Pause Time:**
```promql
rate(jvm_gc_pause_seconds_sum[1m])
```

## Access Points

- **Prometheus UI**: http://localhost:9090
- **Grafana Dashboard**: http://localhost:3000 (admin/admin)
- **OpenTelemetry Collector Metrics**: http://localhost:8889/metrics
- **Prometheus Targets**: http://localhost:9090/targets

## Example Queries for Analysis

### Overall Performance
```promql
# Average throughput over last 5 minutes
avg_over_time(rate(vajrapulse_task_executions_total[1m])[5m:])
```

### Error Rate
```promql
# Error percentage
rate(vajrapulse_task_failures_total[1m]) / rate(vajrapulse_task_executions_total[1m]) * 100
```

### Latency Distribution
```promql
# All percentiles
histogram_quantile(0.50, rate(vajrapulse_task_duration_seconds_bucket[1m]))
histogram_quantile(0.90, rate(vajrapulse_task_duration_seconds_bucket[1m]))
histogram_quantile(0.95, rate(vajrapulse_task_duration_seconds_bucket[1m]))
histogram_quantile(0.99, rate(vajrapulse_task_duration_seconds_bucket[1m]))
```

