# Metrics Monitoring Guide

## Application Status

The load test application is currently running and generating metrics. Here's how to monitor and observe the metrics:

## Access Points

### 1. Application Logs
```bash
tail -f app.log
```

Look for:
- Load test progress messages
- Row count updates
- OpenTelemetry export confirmations
- Target reached notifications

### 2. OpenTelemetry Collector Metrics
```bash
curl http://localhost:8888/metrics
```

This endpoint exposes all metrics collected by the OpenTelemetry collector, including:
- VajraPulse metrics (task executions, latency, throughput)
- CRDB insert metrics (total inserts, errors, duration)
- JVM metrics (memory, threads, GC)
- Custom application metrics (rows inserted)

### 3. Prometheus
- **URL**: http://localhost:9090
- **Query Examples**:
  - `rate(crdb_inserts_total[1m])` - Insert throughput
  - `histogram_quantile(0.95, rate(crdb_inserts_duration_bucket[1m]))` - p95 latency
  - `vajrapulse_task_executions_total` - VajraPulse task count
  - `crdb_rows_inserted` - Current row count

### 4. Grafana Dashboard
- **URL**: http://localhost:3000
- **Username**: admin
- **Password**: admin
- **Dashboard**: "CRDB Microbatch Performance Test"

The dashboard includes:
- VajraPulse throughput and latency
- CRDB insert metrics
- JVM metrics (memory, threads, GC)
- Virtual threads monitoring
- CockroachDB status

## Key Metrics to Observe

### VajraPulse Metrics
- `vajrapulse_task_executions_total` - Total task executions
- `vajrapulse_task_duration_seconds` - Task execution latency (histogram)
- `vajrapulse_load_current` - Current load (threads)
- `vajrapulse_load_target` - Target load (threads)
- `vajrapulse_task_success_total` - Successful tasks
- `vajrapulse_task_failures_total` - Failed tasks

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

## Monitoring Commands

### Check Application Status
```bash
# Check if app is running
ps aux | grep bootRun

# View recent logs
tail -f app.log | grep -E "INFO|ERROR|Target|rows"
```

### Check Metrics Endpoints
```bash
# OpenTelemetry collector metrics
curl http://localhost:8888/metrics | grep crdb

# Prometheus targets
curl http://localhost:9090/api/v1/targets

# Query specific metric
curl 'http://localhost:9090/api/v1/query?query=crdb_rows_inserted'
```

### Check Docker Services
```bash
docker-compose ps
docker-compose logs otel-collector --tail 50
docker-compose logs prometheus --tail 50
```

## Expected Behavior

1. **Initial Phase (0-5 minutes)**:
   - Load ramps from 0 to 300 threads
   - Throughput gradually increases
   - Latency may be higher during ramp-up

2. **Sustained Phase (5+ minutes)**:
   - Load maintains at 300 threads
   - Throughput stabilizes
   - Latency should stabilize
   - Metrics exported every 30 seconds

3. **Completion**:
   - Application logs "Target reached! Inserted 1000000 rows"
   - Final metrics exported
   - Application shuts down gracefully

## Troubleshooting

### If metrics aren't appearing:
1. Check OpenTelemetry collector logs: `docker-compose logs otel-collector`
2. Verify collector is receiving data: `curl http://localhost:8888/metrics`
3. Check Prometheus targets: http://localhost:9090/targets
4. Verify application is exporting: Check app.log for OpenTelemetry messages

### If application stops early:
1. Check for errors in app.log
2. Verify database connection
3. Check CockroachDB logs: `docker-compose logs cockroachdb`

## Next Steps

1. Monitor the Grafana dashboard for real-time visualization
2. Query Prometheus for specific metrics
3. Analyze latency percentiles to understand performance
4. Observe virtual thread behavior under load
5. Monitor CockroachDB's microbatching effectiveness

