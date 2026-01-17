# Vortex Metrics Troubleshooting

## Issue

Vortex metrics are not appearing in Grafana dashboards.

## Possible Causes

### 1. **Vortex Library Not Exposing Metrics**

The Vortex library might not automatically expose metrics, or might require explicit configuration.

**Check:**
- Verify Vortex library version supports metrics
- Check if MicroBatcher needs to be configured with a MeterRegistry
- Review Vortex library documentation

### 2. **Metric Name Mismatch**

Vortex library might use different metric naming conventions than expected.

**Expected Names:**
- `vortex_requests_submitted_total`
- `vortex_batches_dispatched_total`
- `vortex_queue_depth`
- `vortex_batch_dispatch_latency_seconds`
- `vortex_request_wait_latency_seconds`

**Actual Names May Be:**
- Different prefix (e.g., `vortex_microbatcher_*`)
- Different suffix (e.g., `vortex_requests_submitted` without `_total`)
- Different structure (e.g., `vortex.request.submitted`)

### 3. **Metrics Not Registered**

Vortex metrics might need to be explicitly registered with Micrometer.

**Solution:** Check if MicroBatcher constructor accepts a MeterRegistry parameter.

## Verification Steps

### Step 1: Check if Metrics Exist

```bash
# Check OpenTelemetry collector
curl http://localhost:8889/metrics | grep vortex

# Check Prometheus
curl 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep vortex
```

### Step 2: Check Vortex Library Source

If metrics don't exist, check the Vortex library source code to see:
1. What metrics it exposes
2. What names it uses
3. How to enable metrics

### Step 3: Update Dashboard Queries

If metric names differ, update dashboard queries to match actual names.

## Alternative: Use Custom Metrics

If Vortex doesn't expose metrics, we can add custom metrics in our code:

```java
// In CrdbInsertTask or CrdbBatchBackend
Counter vortexQueueDepth = Counter.builder("crdb.vortex.queue.depth")
    .description("Vortex queue depth")
    .register(meterRegistry);

// Update when submitting
vortexQueueDepth.increment();
```

## Current Dashboard Queries

The dashboard uses these Vortex metric names:
- `vortex_queue_depth` (gauge)
- `vortex_requests_submitted_total` (counter)
- `vortex_batches_dispatched_total` (counter)
- `vortex_batch_dispatch_latency_seconds` (histogram)
- `vortex_request_wait_latency_seconds` (histogram)

**If these don't work**, use the verification script to find actual names:
```bash
./scripts/verify-metrics.sh
```

## Quick Fix

If Vortex metrics aren't available, you can:
1. Comment out Vortex panels in dashboard
2. Focus on CRDB batch metrics (which should work)
3. Add custom metrics to track queue depth manually

