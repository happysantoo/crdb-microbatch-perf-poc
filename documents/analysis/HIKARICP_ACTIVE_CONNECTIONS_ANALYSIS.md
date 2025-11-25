# HikariCP Active Connections Analysis

## Observation

**At 500 TPS, active connections remain low (2-5) despite high throughput.**

## Why Active Connections Are Low

### 1. **Virtual Thread Behavior**
- **Fast Connection Release**: Virtual threads release connections immediately after use
- **No Blocking**: Virtual threads don't block platform threads, so connections are returned to pool quickly
- **High Turnover**: With 500 TPS and fast operations, connections are acquired and released rapidly

### 2. **Connection Pool Efficiency**
- **Pool Size**: 10 connections
- **Operation Speed**: Each insert operation is fast (~1-5ms)
- **Math**: 
  - 500 TPS / 10 connections = 50 ops/sec per connection (theoretical max)
  - With 2-5ms per operation: 200-500 ops/sec per connection (theoretical)
  - **Reality**: 2-5 active connections can handle 500 TPS easily

### 3. **The Real Bottleneck Indicator**

**Active connections being low is NOT the problem!**

The real indicators of connection pool contention are:

1. **`hikaricp_connections_pending`** (Threads Awaiting Connection)
   - **What it means**: Number of threads waiting for an available connection
   - **If > 0**: There's contention - threads are waiting
   - **If = 0**: No contention - connections are available when needed

2. **Connection Acquisition Time**
   - **If high**: Threads are waiting longer to get connections
   - **If low**: Connections are available immediately

3. **Connection Timeouts**
   - **If > 0**: Threads gave up waiting (30s timeout)
   - **This indicates severe contention**

## Why This Happens with Virtual Threads

### Virtual Threads vs Platform Threads

**Platform Threads (Traditional):**
- Thread blocks while waiting for connection
- Connection held for entire operation duration
- Higher active connection count

**Virtual Threads (Java 21):**
- Thread yields when waiting for connection
- Connection held only during actual DB operation
- Lower active connection count (but same throughput!)

### Example Timeline

**Platform Thread:**
```
Thread 1: [Acquire] [Wait 5ms] [Use 2ms] [Release] = 7ms total, connection held 7ms
```

**Virtual Thread:**
```
Thread 1: [Acquire] [Use 2ms] [Release] = 2ms total, connection held 2ms
Thread 2: [Acquire] [Use 2ms] [Release] = 2ms total, connection held 2ms
Thread 3: [Acquire] [Use 2ms] [Release] = 2ms total, connection held 2ms
```

**Result**: Same 3 operations, but virtual threads use the connection more efficiently!

## Monitoring Strategy

### Key Metrics to Watch

1. **`hikaricp_connections_pending`** ⚠️ **MOST IMPORTANT**
   - **0**: No contention (good)
   - **1-5**: Light contention (acceptable)
   - **>5**: High contention (consider increasing pool size)

2. **`hikaricp_connections_active`**
   - **Low (2-5)**: Normal with virtual threads
   - **High (8-10)**: May indicate slower operations or blocking

3. **`hikaricp_connections_acquire_milliseconds`**
   - **< 1ms**: Connections available immediately (good)
   - **1-10ms**: Some waiting (acceptable)
   - **> 10ms**: Significant waiting (contention)

4. **`hikaricp_connections_timeout_total`**
   - **0**: No timeouts (good)
   - **> 0**: Severe contention (increase pool size immediately)

## Conclusion

**Low active connections (2-5) at 500 TPS is NORMAL and EXPECTED with virtual threads.**

The real question is: **Are threads waiting for connections?**

Monitor `hikaricp_connections_pending`:
- **If pending = 0**: Pool size is adequate, no bottleneck
- **If pending > 0**: There's contention, consider increasing pool size

## Recommendations

1. **Monitor `hikaricp_connections_pending`** - This is the true indicator
2. **If pending > 0 consistently**: Increase pool size
3. **If pending = 0**: Current pool size is sufficient
4. **Don't rely on active connections alone** - With virtual threads, it's misleading

## Dashboard Panels Added

1. **HikariCP Connection Pool: Active vs Pending** - Shows all connection states together
2. **HikariCP Connection Acquisition & Usage Rate** - Shows connection turnover rate

These panels help visualize:
- Whether threads are waiting (pending > 0)
- Connection acquisition rate vs usage rate
- Overall pool health

