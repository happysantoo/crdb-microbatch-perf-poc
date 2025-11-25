# VajraPulse Metrics Dashboard

## Added VajraPulse Panels

The following VajraPulse metrics panels have been added to the Grafana dashboard:

### 1. VajraPulse Task Throughput
- **Panel Type**: Time series
- **Query**: `rate(vajrapulse_task_executions_total[1m])`
- **Unit**: Operations per second
- **Description**: Shows the rate of task executions per second

### 2. VajraPulse Task Latency Percentiles
- **Panel Type**: Time series
- **Queries**:
  - Average: `rate(vajrapulse_task_duration_seconds_sum[1m]) / rate(vajrapulse_task_duration_seconds_count[1m])`
  - p50: `histogram_quantile(0.50, rate(vajrapulse_task_duration_seconds_bucket[1m]))`
  - p95: `histogram_quantile(0.95, rate(vajrapulse_task_duration_seconds_bucket[1m]))`
  - p99: `histogram_quantile(0.99, rate(vajrapulse_task_duration_seconds_bucket[1m]))`
- **Unit**: Seconds
- **Description**: Shows task execution latency distribution

### 3. VajraPulse Task Success Rate
- **Panel Type**: Time series
- **Query**: `rate(vajrapulse_task_success_total[1m]) / rate(vajrapulse_task_executions_total[1m]) * 100`
- **Unit**: Percentage
- **Description**: Shows the percentage of successful task executions

### 4. VajraPulse Load (Current vs Target)
- **Panel Type**: Time series
- **Queries**:
  - Current Load: `vajrapulse_load_current`
  - Target Load: `vajrapulse_load_target`
- **Unit**: Threads
- **Description**: Shows current load vs target load during ramp-up

### 5. VajraPulse Task Failures
- **Panel Type**: Stat
- **Query**: `vajrapulse_task_failures_total`
- **Description**: Total number of failed task executions

### 6. VajraPulse Total Task Executions
- **Panel Type**: Stat
- **Query**: `vajrapulse_task_executions_total`
- **Description**: Total number of task executions

## Expected VajraPulse Metrics

Based on VajraPulse MetricsPipeline configuration, the following metrics should be exported:

- `vajrapulse_task_executions_total` - Counter: Total task executions
- `vajrapulse_task_duration_seconds` - Histogram: Task execution latency
- `vajrapulse_task_success_total` - Counter: Successful tasks
- `vajrapulse_task_failures_total` - Counter: Failed tasks
- `vajrapulse_load_current` - Gauge: Current load (threads)
- `vajrapulse_load_target` - Gauge: Target load (threads)

## When Metrics Will Appear

VajraPulse metrics are exported by the `MetricsPipeline` during active test execution. They will appear:

1. **During Test Execution**: When the load test is actively running
2. **After Test Start**: Metrics are exported every 30 seconds (as configured in `LoadTestService`)
3. **Via OpenTelemetry**: Metrics flow through: Application → OTel Collector → Prometheus → Grafana

## Troubleshooting

### If VajraPulse panels show "No data":

1. **Verify test is running**:
   ```bash
   ./gradlew bootRun
   ```

2. **Check metrics are being exported**:
   ```bash
   curl http://localhost:8889/metrics | grep vajrapulse
   ```

3. **Verify Prometheus is scraping**:
   - Go to http://localhost:9090/targets
   - Check that `otel-collector` target is "UP"

4. **Check time range in Grafana**:
   - Set to "Last 15 minutes" or "Last 1 hour"
   - Use "Last 5 minutes" for active test monitoring

5. **Verify OpenTelemetry export**:
   - Check application logs for "Exporting metrics" messages
   - Verify `LoadTestService` is using `MetricsPipeline` with `OpenTelemetryExporter`

## Dashboard Layout

The dashboard now includes:
- **Row 1**: CRDB Insert Throughput, CRDB Insert Latency
- **Row 2**: Total Rows Inserted, Insert Errors
- **Row 3**: JVM Memory Usage, JVM Threads
- **Row 4**: VajraPulse Task Throughput, VajraPulse Task Latency
- **Row 5**: VajraPulse Success Rate, VajraPulse Load
- **Row 6**: VajraPulse Task Failures, VajraPulse Total Executions

## Next Steps

1. **Run a new test** to see VajraPulse metrics:
   ```bash
   ./gradlew bootRun
   ```

2. **Refresh Grafana dashboard** after test starts

3. **Monitor in real-time**:
   - Watch load ramp-up from 0 to 300 threads
   - Observe task throughput increase
   - Monitor latency as load increases
   - Track success rate (should be ~100% if no errors)

