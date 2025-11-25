# Connection Pool Size Analysis: 10 vs 100 Connections

## Question

**Why increase pool size to 100 when only 1-6 connections were actively used in the first run?**

## Key Insight: Throughput vs. Active Connections

### The Math Behind the Bottleneck

**Observed Metrics from First Run:**
- Active connections: 1-6 (average ~3)
- Throughput: 300 TPS (capped)
- Total connections available: 10
- Threads: 300

**Critical Calculation:**
```
Throughput = (Number of Connections) / (Average Insert Latency)

300 TPS = 10 connections / 0.033s
Average Insert Latency = 10 / 300 = 0.033s = 33ms per insert
```

**Why Only 3-6 Active Connections?**

With virtual threads, the connection pool utilization metric can be misleading:

1. **Virtual Thread Behavior**: When a virtual thread needs a connection but all are busy, it yields (doesn't block a platform thread)
2. **Connection Pool Model**: HikariCP shows "active" connections as those currently executing a query
3. **The Reality**: 
   - Connection 1: Executing insert (3ms)
   - Connection 2: Executing insert (3ms)
   - Connection 3: Executing insert (3ms)
   - Connections 4-10: Just returned to pool, available immediately
   - 297 virtual threads: Waiting/yielding for next available connection

**The Bottleneck:**
- Each connection can handle: 1 insert / 33ms = ~30 TPS
- 10 connections × 30 TPS = **300 TPS maximum**
- This matches our observed throughput exactly!

## Scenario Analysis

### Option 1: Keep 10 Connections, Increase to 1000 Threads

**What Happens:**
- 1000 virtual threads competing for 10 connections
- Each connection still handles ~30 TPS (limited by insert latency)
- **Maximum throughput: Still ~300 TPS**
- 990 threads will be waiting/yielding most of the time

**Result**: ❌ **No throughput improvement** - Still capped at 300 TPS

**Why?**
- More threads don't create more connections
- Throughput = Connections × TPS per connection
- TPS per connection is fixed by insert latency (~33ms)
- 10 connections × 30 TPS = 300 TPS (unchanged)

### Option 2: Increase to 100 Connections, Keep 300 Threads

**What Happens:**
- 300 threads competing for 100 connections
- Each connection handles ~30 TPS
- **Maximum throughput: 100 × 30 = 3000 TPS**
- But we only have 300 threads, so we'll get ~300 TPS initially

**Result**: ⚠️ **Limited by thread count** - Need more threads to utilize connections

### Option 3: Increase to 100 Connections + 1000 Threads (Recommended)

**What Happens:**
- 1000 threads competing for 100 connections
- Each connection handles ~30 TPS
- **Maximum throughput: 100 × 30 = 3000 TPS**
- Threads can fully utilize all connections

**Result**: ✅ **3-10x throughput improvement** - 1000-3000 TPS

## Why Active Connections Were Low

**The Misleading Metric:**

The "active connections" metric shows connections currently executing a query. With:
- Insert latency: ~33ms
- 10 connections available
- 300 threads trying to insert

**What Actually Happens:**
1. Thread 1 gets connection 1 → executes insert (33ms)
2. Thread 2 gets connection 2 → executes insert (33ms)
3. ... (up to 10 concurrent)
4. Thread 11+ wait for next available connection
5. After 33ms, connection 1 returns to pool
6. Thread 11 gets connection 1 → cycle repeats

**At Any Given Moment:**
- 3-6 connections actively executing (in the middle of their 33ms insert)
- 4-7 connections just returned/available (between inserts)
- 290+ threads waiting for next available connection

**The Key Point**: Even though only 3-6 are "active" at any moment, all 10 connections are being utilized in rotation. The throughput is limited by: **10 connections × 30 TPS each = 300 TPS max**.

## The Real Bottleneck Formula

```
Max Throughput = (Connection Pool Size) × (1 / Average Insert Latency)

First Run:
Max Throughput = 10 connections × (1 / 0.033s) = 300 TPS ✓ (matches observed)

With 100 Connections:
Max Throughput = 100 connections × (1 / 0.033s) = 3000 TPS (potential)
```

## Recommendation: Why Both Matter

### Increase Connections (10 → 100)
**Reason**: Directly increases throughput capacity
- 10 connections = 300 TPS max
- 100 connections = 3000 TPS max
- **10x potential improvement**

### Increase Threads (300 → 1000)
**Reason**: Ensures we can utilize all connections
- With 100 connections, we need enough threads to keep them busy
- 100 connections × 10 threads per connection = 1000 threads ideal
- **Prevents underutilization**

## Optimal Configuration

**Recommended**: 100 connections + 1000 threads

**Rationale**:
- **Connection Pool**: Provides the throughput capacity (100 × 30 TPS = 3000 TPS)
- **Thread Count**: Ensures we can keep all connections busy (1000 threads / 100 connections = 10:1 ratio)
- **Ratio**: 10:1 thread-to-connection ratio is optimal for I/O-bound operations

**Alternative if Latency Improves**:
- If insert latency drops to ~10ms (through optimizations):
- 100 connections × 100 TPS = 10,000 TPS potential
- Would need 2000+ threads to fully utilize

## Conclusion

**Why increase pool size?**
- Not because we're using all 10 connections simultaneously
- But because **10 connections × 30 TPS = 300 TPS maximum**
- To get more throughput, we need more connections: **100 connections × 30 TPS = 3000 TPS**

**Why increase threads?**
- To ensure we can keep all 100 connections busy
- 1000 threads ensures we have enough concurrent work to utilize the pool
- Without enough threads, connections sit idle

**The Answer**: Both are needed. Connections provide capacity, threads provide utilization.

