# Vortex Metrics Fix

## Issue

Vortex metrics were not appearing in Grafana dashboards.

## Root Cause

The `MicroBatcher` constructor was called without passing the `MeterRegistry`, which is required for Vortex to expose metrics.

**Before (Wrong):**
```java
batcher = new MicroBatcher<>(backend, config);
```

**After (Correct):**
```java
batcher = new MicroBatcher<>(backend, config, meterRegistry);
```

## MicroBatcher Constructor

The Vortex library provides two constructors:
1. `MicroBatcher(Backend<T>, BatcherConfig)` - Does NOT expose metrics
2. `MicroBatcher(Backend<T>, BatcherConfig, MeterRegistry)` - Exposes metrics ✅

## Fix Applied

Updated `CrdbInsertTask.initializeBatcher()` to pass the `MeterRegistry`:

```java
private void initializeBatcher() {
    BatcherConfig config = BatcherConfig.builder()
        .batchSize(BATCH_SIZE)
        .lingerTime(LINGER_TIME)
        .atomicCommit(false)
        .maxConcurrency(10)
        .build();
    
    // Pass MeterRegistry to enable Vortex metrics
    batcher = new MicroBatcher<>(backend, config, meterRegistry);
}
```

## Expected Vortex Metrics

After this fix, Vortex should expose:
- `vortex_requests_submitted_total` - Total requests submitted
- `vortex_batches_dispatched_total` - Total batches dispatched
- `vortex_requests_succeeded_total` - Total successful requests
- `vortex_requests_failed_total` - Total failed requests
- `vortex_queue_depth` - Current queue depth (gauge)
- `vortex_batch_dispatch_latency_seconds` - Batch dispatch latency (histogram)
- `vortex_request_wait_latency_seconds` - Request wait latency (histogram)

## Verification

After running the test, verify metrics exist:

```bash
# Check OpenTelemetry collector
curl http://localhost:8889/metrics | grep vortex

# Check Prometheus
curl 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep vortex
```

## Dashboard Queries

The dashboard queries should now work:
- `vortex_queue_depth` - Queue depth gauge
- `rate(vortex_requests_submitted_total[1m])` - Requests/sec
- `rate(vortex_batches_dispatched_total[1m])` - Batches/sec
- `histogram_quantile()` on `vortex_batch_dispatch_latency_seconds_bucket` - Latency percentiles
- `histogram_quantile()` on `vortex_request_wait_latency_seconds_bucket` - Wait latency

## Next Steps

1. Rebuild and run the test
2. Verify Vortex metrics appear in Prometheus
3. Check Grafana dashboard - Vortex panels should now show data

