# Connection Pool Fix for 15k TPS

## Issue

At 15k TPS, the application experienced connection pool exhaustion:
```
HikariPool-1 - Connection is not available, request timed out after 30003ms
(total=10, active=10, idle=0, waiting=8836)
```

## Root Cause Analysis

### Load Calculation
- **TPS**: 15,000 transactions per second
- **Batch Size**: 50 items per batch
- **Batches/sec**: ~300 batches/second (15,000 ÷ 50)
- **Batch Duration**: ~30-50ms per batch (from previous tests)

### Connection Pool Math
- **Current Pool Size**: 10 connections
- **Concurrent Batches**: With 10 connections, can handle ~10 concurrent batches
- **Throughput Capacity**: 
  - If each batch takes 50ms: 10 connections × (1000ms / 50ms) = 200 batches/sec
  - **Required**: 300 batches/sec
  - **Gap**: 100 batches/sec short → connection pool bottleneck

### Why It Failed
At 15k TPS:
- 300 batches/sec need to be processed
- With 10 connections, maximum throughput is ~200 batches/sec
- Result: 8,836 requests waiting for connections
- All 10 connections are constantly busy
- New requests timeout after 30 seconds

## Solution

### Updated Configuration

```yaml
hikari:
  maximum-pool-size: 50  # Increased from 10
  minimum-idle: 20        # Increased from 10
  connection-timeout: 30000
  idle-timeout: 600000
  max-lifetime: 1800000
  leak-detection-threshold: 60000  # Detect connection leaks
```

### Calculation for 50 Connections

- **Pool Size**: 50 connections
- **Concurrent Batches**: 50 concurrent batches
- **Throughput Capacity**: 
  - If each batch takes 50ms: 50 connections × (1000ms / 50ms) = 1,000 batches/sec
  - **Required**: 300 batches/sec
  - **Headroom**: 700 batches/sec (233% capacity)
- **Safety Margin**: 2.3x required capacity

## Expected Behavior After Fix

1. **No Connection Timeouts**: 50 connections should handle 300 batches/sec easily
2. **Lower Queue Depth**: Requests won't wait for connections
3. **Stable Latency**: No connection acquisition delays
4. **Better Throughput**: Can sustain 15k TPS without bottlenecks

## Monitoring

### Key Metrics to Watch

1. **Connection Pool Utilization**:
   - `hikaricp_connections_active` - Should be < 50
   - `hikaricp_connections_idle` - Should have idle connections available
   - `hikaricp_connections_pending` - Should be 0 (no waiting)

2. **Connection Acquisition Time**:
   - `hikaricp_connections_acquire_milliseconds` - Should be low (< 10ms)

3. **Queue Depth**:
   - `vortex_queue_depth` - Should remain low if connections are available

4. **Batch Throughput**:
   - `rate(crdb_batches_total[1m])` - Should stay near 300 batches/sec

## Testing

After applying the fix:
1. Restart the application
2. Monitor connection pool metrics
3. Verify no connection timeouts
4. Check that throughput reaches 15k TPS
5. Monitor queue depth stays low

## Alternative Considerations

If 50 connections still isn't enough:
- **Increase further**: Could go to 100 connections if needed
- **Optimize batch duration**: Reduce batch processing time
- **Increase batch size**: Larger batches = fewer batches/sec needed
- **Database tuning**: Check if CRDB is the bottleneck

## Files Modified

- `src/main/resources/application.yml`: Updated HikariCP pool configuration

