# Adaptive Load Pattern Setup - 100 to 10,000 TPS

## Overview

The application has been configured to use `AdaptiveLoadPattern` to automatically find the maximum sustainable throughput with batching. The system will start at 100 TPS and adaptively ramp up to 10,000 TPS, adjusting every 10 seconds.

## Configuration

### AdaptiveLoadPattern Parameters

- **Initial TPS**: 100 (starting point)
- **Max TPS**: 10,000 (target maximum)
- **Step Size**: 500 TPS (increment/decrement per step)
- **Step Duration**: 10 seconds (ramp up/down every 10s)
- **Cooldown**: 5 seconds (wait after step change)
- **Stability Window**: 30 seconds (time to assess stability)
- **Max Failure Rate**: 1% (threshold for backing off)

### Goal

Find the maximum sustainable throughput that the batching configuration can support while maintaining:
- Low latency (< 100ms p95 for items)
- High success rate (> 99%)
- Efficient batching (close to 50 items per batch)

## MetricsProvider Implementation

The `MetricsProvider` reads metrics directly from the Micrometer registry:
- **Failure Rate**: Calculated from `vajrapulse.task.executions` and `vajrapulse.task.failures`
- **Total Executions**: Read from `vajrapulse.task.executions` counter

This allows `AdaptiveLoadPattern` to get real-time metrics during execution and adjust TPS accordingly.

## Redesigned Grafana Dashboard

A new dashboard has been created: **"CRDB Adaptive Batching Efficiency Dashboard"**

### Dashboard Panels

1. **Overall Throughput** - Rows/sec, Batches/sec, Items/sec
2. **Total Rows Inserted** - Cumulative count
3. **Current Rows/sec** - Real-time throughput
4. **Batching Efficiency** - Rows per batch, Items per batch, Target batch size
5. **Batch Utilization %** - Percentage of target batch size (50 rows) achieved
6. **Per Item Latencies** - Submit to completion (p50, p95, p99, avg)
7. **Queuing Wait Time** - Time items wait in queue before batching (p50, p95, p99, avg)
8. **Vortex Queue Depth** - Number of items waiting in queue
9. **CRDB Batch Processing Latency** - Database batch operation latency (p50, p95, p99, avg)
10. **CRDB Batch Throughput** - Batches/sec, successful/failed
11. **Success Rates** - CRDB batch, item, and row success rates

### Key Metrics Focus

The dashboard emphasizes:
- **Batching Efficiency**: How well we're utilizing batches (target: 50 items/batch)
- **Per Item Latencies**: End-to-end latency from submit to completion
- **Queuing Wait Time**: Time items spend waiting in the queue
- **CRDB Metrics**: Database-level batch processing metrics

## Running the Test

### Start the Application
```bash
./gradlew bootRun
```

### Monitor Progress

**Grafana Dashboard:**
- URL: http://localhost:3000
- Dashboard: "CRDB Adaptive Batching Efficiency Dashboard"
- Refresh: Every 10 seconds

**Key Metrics to Watch:**
1. **Batching Efficiency**: Should stay close to 50 items/batch
2. **Queue Depth**: Should remain low (< 100) if batching is efficient
3. **Per Item Latency**: Should stay < 100ms (p95) even at high TPS
4. **CRDB Batch Latency**: Should remain stable as TPS increases
5. **Success Rate**: Should stay > 99%

### Expected Behavior

1. **Start**: 100 TPS
2. **Ramp Up**: Increase by 500 TPS every 10 seconds
3. **Adapt**: If failure rate > 1% or latency spikes, back off
4. **Stabilize**: Find maximum sustainable TPS
5. **Continue**: Run for 1 hour, continuously adapting

### Finding Maximum Throughput

The system will automatically:
- Increase TPS when metrics are good (low failure rate, acceptable latency)
- Decrease TPS when metrics degrade (high failure rate, high latency)
- Find the sweet spot where batching is most efficient

## Troubleshooting

### If TPS doesn't increase:
- Check failure rate - may be hitting 1% threshold
- Check queue depth - may be backing up
- Check CRDB batch latency - database may be bottleneck

### If metrics don't appear:
- Verify OpenTelemetry collector: `curl http://localhost:8889/metrics | grep crdb`
- Check Prometheus targets: http://localhost:9090/targets
- Verify Grafana datasource configuration

### If batching efficiency is low:
- Queue may be draining too fast (items not waiting for batch)
- May need to adjust batch size or linger time
- Check queue wait time - should be > 0 but < 50ms

## Files Modified

1. **LoadTestService.java**:
   - Updated `createLoadPattern()` with new AdaptiveLoadPattern parameters
   - Implemented MetricsProvider that reads from Micrometer
   - Removed StaticLoad, now using AdaptiveLoadPattern

2. **Grafana Dashboard**:
   - Created `crdb-adaptive-batching-dashboard.json`
   - Focused on batching efficiency, latencies, and CRDB metrics

## Next Steps

1. Run the test and observe how TPS adapts
2. Monitor batching efficiency as TPS increases
3. Identify the maximum sustainable TPS
4. Analyze where bottlenecks occur (queue, CRDB, batching)

