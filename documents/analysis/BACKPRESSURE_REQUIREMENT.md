# Backpressure Requirement Analysis

## Issue

At higher TPS (15k+), the system experiences connection pool exhaustion even with increased pool size:
- 50 connections: All active, 8,724 requests waiting
- Without backpressure, requests queue up indefinitely

## Root Cause

**No Backpressure Mechanism**: The system accepts requests faster than it can process them, leading to:
1. Connection pool exhaustion
2. Growing request queues
3. Timeout errors
4. Resource exhaustion

## Current Limitation

**Connection Pool Capacity**: 10 connections
- Can handle ~200 batches/sec (assuming 50ms per batch)
- At 10k TPS: ~200 batches/sec (10k ÷ 50 items/batch)
- This is approximately the limit without backpressure

## Why Backpressure is Needed

### Without Backpressure:
- System accepts all requests immediately
- Requests queue up if processing can't keep up
- Queue grows unbounded
- Eventually exhausts resources (connections, memory)

### With Backpressure:
- System controls request acceptance rate
- Rejects or delays requests when overloaded
- Maintains stable resource usage
- Prevents cascading failures

## Proposed Backpressure Implementation (Tabled for Later)

### Option 1: Queue-Based Backpressure
- Limit queue size in Vortex MicroBatcher
- Reject requests when queue is full
- Return failure immediately instead of queuing

### Option 2: Rate Limiting
- Limit request acceptance rate
- Use adaptive rate limiting based on connection pool availability
- Integrate with AdaptiveLoadPattern

### Option 3: Connection Pool Aware Backpressure
- Monitor connection pool availability
- Reject requests when pool is exhausted
- Return meaningful error messages

### Option 4: Circuit Breaker Pattern
- Open circuit when failure rate is high
- Reject requests during circuit open
- Close circuit when conditions improve

## Current Configuration

- **TPS**: 10,000 (reduced from 15k)
- **Connection Pool**: 10 connections
- **Batch Size**: 50 items
- **Expected Batches/sec**: ~200 batches/sec
- **Capacity**: ~200 batches/sec (matches requirement)

## Testing at 10k TPS

### Expected Behavior
- **Throughput**: ~10,000 rows/sec
- **Batches/sec**: ~200 batches/sec
- **Connection Pool**: Should handle load without exhaustion
- **Queue Depth**: Should remain manageable

### Monitoring
- Watch `hikaricp_connections_active` - should stay < 10
- Watch `hikaricp_connections_pending` - should be 0 or low
- Watch `vortex_queue_depth` - should not grow unbounded
- Watch for connection timeout errors

## Future Work

1. **Implement Backpressure**: Add queue size limits or rate limiting
2. **Integrate with AdaptiveLoadPattern**: Use backpressure signals for adaptive behavior
3. **Connection Pool Scaling**: Dynamic pool sizing based on load
4. **Graceful Degradation**: Handle overload gracefully

## Files Modified

- `src/main/java/com/crdb/microbatch/service/LoadTestService.java`: Reduced TPS to 10k
- `src/main/resources/application.yml`: Reduced connection pool to 10

## Conclusion

At 10k TPS with 10 connections, the system should operate within capacity limits. For higher TPS, backpressure implementation is required to prevent resource exhaustion and maintain system stability.

