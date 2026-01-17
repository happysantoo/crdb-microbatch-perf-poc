# StaticLoad 5000 TPS - 30 Minute Test Configuration

## Configuration

- **Load Pattern**: StaticLoad
- **TPS**: 5,000 transactions per second
- **Duration**: 30 minutes
- **Expected Total Executions**: ~9,000,000 (5000 TPS × 1800 seconds)

## Purpose

This configuration is used to test batching performance at a sustained high load while waiting for AdaptiveLoadPattern to be fixed in the VajraPulse library.

## Test Goals

1. **Sustained Throughput**: Maintain 5000 TPS for 30 minutes
2. **Batching Efficiency**: Monitor how well batches are utilized
3. **Latency**: Measure per-item and batch latencies at high load
4. **Stability**: Verify system can handle sustained load without degradation

## Expected Behavior

### Throughput
- **Items/sec**: ~5000
- **Batches/sec**: ~100 (assuming ~50 items per batch)
- **Rows/sec**: ~5000

### Batching
- **Target Batch Size**: 50 items
- **Batch Utilization**: Should be close to 100% (50 items/batch)
- **Queue Depth**: Should remain low if batching is efficient

### Latency
- **Item Submit Latency**: < 100ms (p95)
- **Batch Duration**: < 50ms (p95)
- **Queue Wait Time**: < 50ms (p95)

## Monitoring

### Key Metrics to Watch

1. **Overall Throughput**:
   - `rate(crdb_batch_rows_success_total[1m])` - Should stay near 5000 rows/sec
   - `rate(crdb_batches_total[1m])` - Should stay near 100 batches/sec
   - `rate(crdb_submits_total[1m])` - Should stay near 5000 items/sec

2. **Batching Efficiency**:
   - `rate(crdb_batch_rows_success_total[1m]) / rate(crdb_batches_total[1m])` - Should be ~50
   - Batch Utilization % - Should be close to 100%

3. **Latencies**:
   - `histogram_quantile(0.95, rate(crdb_submit_latency_milliseconds_bucket[1m]))` - Item latency
   - `histogram_quantile(0.95, rate(crdb_batch_duration_milliseconds_bucket[1m]))` - Batch latency
   - `histogram_quantile(0.95, rate(vortex_request_wait_latency_milliseconds_bucket[1m]))` - Queue wait

4. **Success Rates**:
   - Should stay > 99% throughout the test

5. **Queue Depth**:
   - `vortex_queue_depth` - Should remain low (< 100) if batching is efficient

## Running the Test

```bash
./gradlew bootRun
```

## Dashboard

Use the **"CRDB Adaptive Batching Efficiency Dashboard"** to monitor:
- Overall throughput
- Batching efficiency
- Per-item latencies
- Queuing wait time
- CRDB batch metrics

## Expected Results

After 30 minutes:
- **Total Rows Inserted**: ~9,000,000
- **Total Batches**: ~180,000
- **Success Rate**: > 99%
- **Average Batch Size**: ~50 items
- **Average Latency**: Stable throughout test

## Troubleshooting

### If TPS doesn't reach 5000:
- Check connection pool utilization
- Monitor queue depth - may be backing up
- Check database CPU/memory
- Review application logs for errors

### If batching efficiency is low:
- Queue may be draining too fast
- May need to adjust batch size or linger time
- Check queue wait time

### If latency increases over time:
- May indicate resource exhaustion
- Check memory usage
- Check database performance
- Monitor GC pauses

## Next Steps

Once AdaptiveLoadPattern is fixed in the library:
1. Switch back to AdaptiveLoadPattern
2. Use it to find maximum sustainable TPS
3. Compare results with this static 5000 TPS baseline

