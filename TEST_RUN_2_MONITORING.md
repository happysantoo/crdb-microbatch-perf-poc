# Test Run 2 - Monitoring Guide

## Configuration

- **Connection Pool**: 10 connections (kept from first run)
- **Thread Count**: 1000 threads (increased from 300)
- **Target**: 1,000,000 rows
- **Hypothesis**: Throughput will remain at ~300 TPS (connection pool is the bottleneck)

## Key Metrics to Watch

### Primary Indicators

1. **Throughput** (`vajrapulse_response_throughput_per_second`)
   - **Expected**: ~300 TPS (same as first run)
   - **If higher**: Connection pool may not be the bottleneck
   - **If same**: Confirms connection pool is the limiting factor

2. **Active Connections** (`hikaricp_connections_active`)
   - **Expected**: Should approach 10 (all connections utilized)
   - **Watch for**: Does it stay at 10 or fluctuate lower?

3. **Pending Connections** (`hikaricp_connections_pending`)
   - **Expected**: Should be 0 (virtual threads yield, don't block)
   - **If >0**: Indicates connection contention

### Secondary Indicators

4. **Connection Acquire Time** (`hikaricp_connections_acquire_milliseconds`)
   - **Expected**: Should stay low (<1ms)
   - **If increases**: Indicates contention for connections

5. **Response Times** (`vajrapulse_execution_duration_milliseconds`)
   - **Expected**: Similar to first run (~1-4ms)
   - **If increases**: May indicate connection wait time

6. **Queue Wait Time** (`vajrapulse_execution_queue_wait_time_milliseconds`)
   - **Expected**: Should stay minimal (<0.1ms)
   - **If increases**: Indicates thread contention

7. **Virtual Thread Metrics** (new)
   - `jvm.threads.virtual.pinned` - Watch for pinning events
   - `jvm.threads.virtual.start.failed` - Should be 0

## What to Look For

### Scenario A: Throughput Stays at ~300 TPS
**Conclusion**: Connection pool is confirmed as bottleneck
- All 10 connections should be actively used
- 1000 threads competing for 10 connections
- Next step: Increase connection pool to 100

### Scenario B: Throughput Increases
**Conclusion**: Connection pool was not the only bottleneck
- May indicate other factors (thread scheduling, etc.)
- Analyze what changed

### Scenario C: Throughput Decreases
**Conclusion**: Too many threads causing overhead
- May indicate thread contention
- Consider optimal thread-to-connection ratio

## Grafana Dashboard Panels to Monitor

1. **VajraPulse Response Throughput** - Primary metric
2. **HikariCP Connection Pool Status** - Active/Idle connections
3. **HikariCP Pending Connections** - Contention indicator
4. **VajraPulse Response Times** - Performance indicator
5. **JVM Threads (Including Virtual Threads)** - Thread utilization
6. **Virtual Thread Metrics** (if available) - Pinning events

## Expected Timeline

- **0-5 minutes**: Ramp-up phase (0 → 1000 threads)
- **5+ minutes**: Sustained load at 1000 threads
- **Target**: 1,000,000 rows (should take ~55 minutes at 300 TPS)

## Success Criteria

- **Throughput**: Observe if it stays at ~300 TPS or increases
- **Connections**: All 10 connections should be actively utilized
- **Completion**: Test should complete 1M rows (with fallback mechanism)
- **Stability**: No errors, 100% success rate maintained

## Notes

- This test validates the connection pool bottleneck theory
- If throughput stays at 300 TPS, it confirms we need more connections
- If throughput increases, we'll need to analyze other factors

