# Grafana Dashboard Access Guide

## Dashboard Status

✅ **Dashboard is successfully provisioned and available!**

- **Dashboard Name**: CRDB Microbatch Performance Test
- **UID**: crdb-microbatch
- **Location**: Automatically provisioned from `grafana/dashboards/crdb-microbatch-dashboard.json`

## Access the Dashboard

### Method 1: Direct URL
```
http://localhost:3000/d/crdb-microbatch/crdb-microbatch-performance-test
```

### Method 2: Via Grafana UI
1. Open Grafana: http://localhost:3000
2. Login: `admin` / `admin`
3. Click on **Dashboards** in the left menu
4. Look for **"CRDB Microbatch Performance Test"**
5. Click to open

### Method 3: Search
1. Click the **Search** icon (magnifying glass) in the left menu
2. Type "crdb" or "microbatch"
3. Select the dashboard from results

## Dashboard Panels

The dashboard includes the following panels:

1. **CRDB Insert Throughput** - Shows inserts per second
2. **CRDB Insert Latency Percentiles** - p50, p95, p99 latencies
3. **Total Rows Inserted** - Current row count
4. **Insert Errors** - Error count
5. **JVM Memory Usage** - Heap memory usage
6. **JVM Threads** - Live and daemon threads

## Troubleshooting

### If dashboard doesn't appear:

1. **Refresh the browser** - Sometimes Grafana needs a refresh
2. **Check datasource** - Ensure Prometheus datasource is configured:
   - Go to Configuration → Data Sources
   - Verify "Prometheus" is listed and working
3. **Check dashboard provisioning**:
   ```bash
   docker-compose logs grafana | grep -i dashboard
   ```
4. **Manually verify dashboard exists**:
   ```bash
   curl -u admin:admin 'http://localhost:3000/api/search?type=dash-db'
   ```

### If panels show "No data":

1. **Check time range** - Ensure you're looking at the right time period
2. **Verify metrics exist**:
   ```bash
   curl http://localhost:8889/metrics | grep crdb
   ```
3. **Check Prometheus targets**: http://localhost:9090/targets
4. **Verify application is running** and generating metrics

## Current Metrics Available

Based on the test run, the following metrics are available:
- `crdb_inserts_total` - 89,999 inserts
- `crdb_inserts_errors_total` - 0 errors
- `crdb_inserts_duration_milliseconds` - Latency histogram
- `crdb_rows_inserted` - 89,999 rows

## Next Steps

1. **Run a new test** to generate fresh metrics
2. **Adjust time range** in Grafana to view historical data
3. **Add more panels** for additional metrics
4. **Set up alerts** based on thresholds

