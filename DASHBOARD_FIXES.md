# Dashboard Fixes Applied

## Issues Fixed

### 1. JVM Thread Metrics
**Problem**: Dashboard was querying `jvm_threads_live_threads` and `jvm_threads_daemon_threads` which don't exist.

**Fix**: Updated queries to use correct metric names:
- `jvm_threads_live` (not `jvm_threads_live_threads`)
- `jvm_threads_daemon` (not `jvm_threads_daemon_threads`)

### 2. Latency Percentiles
**Problem**: 
- Histogram buckets might not be properly configured for percentile calculation
- The query was dividing buckets by 1000 before calculating quantiles (incorrect)
- When test is not running, `rate()` returns empty results

**Fix**: 
- Changed to divide the quantile result by 1000 (convert milliseconds to seconds)
- Added average latency as primary metric: `rate(crdb_inserts_duration_milliseconds_sum[1m]) / rate(crdb_inserts_duration_milliseconds_count[1m]) / 1000`
- Kept percentile queries as fallback (will show data when test is running)

### 3. Metric Names
All queries now use the correct metric names as exported by the OpenTelemetry collector:
- `crdb_inserts_total` ✅
- `crdb_inserts_errors_total` ✅
- `crdb_inserts_duration_milliseconds_*` ✅
- `crdb_rows_inserted` ✅
- `jvm_threads_live` ✅
- `jvm_threads_daemon` ✅
- `jvm_memory_used_bytes{area="heap"}` ✅

## Current Status

✅ **JVM Threads panel** - Should now show data (32 live threads, 28 daemon threads)
✅ **Latency panel** - Will show average latency; percentiles will appear when test is running
✅ **Memory panel** - Should show heap memory usage
✅ **Throughput panel** - Shows inserts/sec (will be 0 when test not running)
✅ **Row count panel** - Shows total rows inserted (89,999 from last run)

## Next Steps

1. **Run a new test** to generate fresh time-series data:
   ```bash
   ./gradlew bootRun
   ```

2. **Refresh Grafana dashboard** after test starts to see:
   - Real-time throughput
   - Latency percentiles (p50, p95, p99)
   - JVM metrics during load

3. **Check time range** in Grafana (top right):
   - Set to "Last 15 minutes" or "Last 1 hour" to see recent data
   - Use "Last 5 minutes" for active test monitoring

## Troubleshooting

### If panels still show "No data":

1. **Check if metrics exist**:
   ```bash
   curl http://localhost:8889/metrics | grep crdb
   ```

2. **Verify Prometheus is scraping**:
   - Go to http://localhost:9090/targets
   - Check that `otel-collector` target is "UP"

3. **Check time range**:
   - Ensure you're looking at the time period when the test was running
   - Use "Last 1 hour" or custom time range

4. **Run a new test**:
   - The dashboard needs active time-series data
   - Start the application and let it run for a few minutes

