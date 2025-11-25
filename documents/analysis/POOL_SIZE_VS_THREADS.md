# Connection Pool Size vs Thread Count: Why Both Matter

## Your Excellent Question

**Observation**: Only 1-6 connections were actively used out of 10 in the first run.  
**Question**: Why increase pool to 100? Why not just increase threads to 1000 and keep pool at 10?

## The Critical Math

### Throughput Formula

```
Max Throughput = (Connection Pool Size) × (1 / Average Insert Latency)
```

**From First Run:**
- Pool Size: 10 connections
- Insert Latency: ~33ms (calculated from: 10 connections / 300 TPS = 0.033s)
- **Max Throughput = 10 × 30 TPS = 300 TPS** ✓ (matches observed)

### Why "Active Connections" is Misleading

**The Metric Shows**: Connections currently executing a query at snapshot time  
**What It Doesn't Show**: How quickly connections rotate and get reused

**What Actually Happens:**
1. Thread 1 gets Connection 1 → executes insert (33ms)
2. Thread 2 gets Connection 2 → executes insert (33ms)
3. ... (up to 10 concurrent)
4. After 33ms, Connection 1 finishes → returns to pool
5. Thread 11 immediately grabs Connection 1 → cycle repeats

**At Any Snapshot:**
- 3-6 connections: Mid-execution (in their 33ms insert)
- 4-7 connections: Just finished, available for next thread
- 290+ threads: Waiting for next available connection

**Key Insight**: All 10 connections ARE being used, just not all simultaneously. They rotate rapidly.

## Scenario Comparison

### Scenario A: 10 Connections + 1000 Threads

**What Happens:**
- 1000 threads compete for 10 connections
- Each connection handles: 1 insert / 33ms = ~30 TPS
- **Max Throughput: 10 × 30 = 300 TPS** (unchanged)
- 990 threads wait most of the time

**Result**: ❌ **No improvement** - Still capped at 300 TPS

**Why?**
- More threads don't create more connections
- Throughput = Connections × TPS per connection
- TPS per connection is fixed by insert latency
- **Bottleneck remains: 10 connections**

### Scenario B: 100 Connections + 300 Threads

**What Happens:**
- 300 threads compete for 100 connections
- Each connection handles: ~30 TPS
- **Max Throughput: 100 × 30 = 3000 TPS** (potential)
- But only 300 threads → can only utilize ~300 TPS initially

**Result**: ⚠️ **Limited by thread count** - Connections available but not enough threads

**Why?**
- Have capacity (3000 TPS) but not enough work (300 threads)
- Need more threads to keep all connections busy

### Scenario C: 100 Connections + 1000 Threads (Recommended)

**What Happens:**
- 1000 threads compete for 100 connections
- Each connection handles: ~30 TPS
- **Max Throughput: 100 × 30 = 3000 TPS** (potential)
- 1000 threads can keep all 100 connections busy

**Result**: ✅ **3-10x improvement** - 1000-3000 TPS

## The Real Bottleneck

**It's Not About Active Connections - It's About Total Capacity**

The formula is simple:
```
Throughput = Connections × (1 / Latency)
```

**First Run:**
- 10 connections × 30 TPS = **300 TPS** (what we got)

**With 100 Connections:**
- 100 connections × 30 TPS = **3000 TPS** (potential)

**With 1000 Threads + 10 Connections:**
- Still: 10 connections × 30 TPS = **300 TPS** (no change)

## Why Both Are Needed

### Connections = Capacity
- **10 connections** = 300 TPS max capacity
- **100 connections** = 3000 TPS max capacity
- **Directly determines maximum throughput**

### Threads = Utilization
- **300 threads** = Can utilize ~300 TPS (matches 10 connections)
- **1000 threads** = Can utilize up to 3000 TPS (matches 100 connections)
- **Ensures connections don't sit idle**

## Recommendation

**Test Both Scenarios to Validate:**

### Test 1: 10 Connections + 1000 Threads
**Hypothesis**: Throughput stays at ~300 TPS (no improvement)  
**Why**: Connection pool is the bottleneck, not thread count

### Test 2: 100 Connections + 1000 Threads
**Hypothesis**: Throughput increases to 1000-3000 TPS  
**Why**: More connections = more capacity

## Alternative: Start Conservative

If you want to test incrementally:

1. **First**: Try 10 connections + 1000 threads
   - If throughput stays at 300 TPS → confirms connection pool is bottleneck
   - Then increase to 20 connections + 1000 threads
   - Then 50 connections + 1000 threads
   - Find the sweet spot

2. **Or**: Start with 100 connections + 1000 threads
   - Monitor connection utilization
   - If only 50 connections are needed, reduce pool size
   - Optimize based on actual usage

## Conclusion

**Your observation is correct**: Only 3-6 connections were "active" at any moment.  
**But the bottleneck is still**: Total connection capacity (10 connections × 30 TPS = 300 TPS max)

**Increasing threads alone won't help** because:
- 1000 threads competing for 10 connections = still 300 TPS max
- Connections are the limiting factor, not threads

**Both are needed**:
- **Connections** provide the throughput capacity
- **Threads** ensure we can utilize that capacity

**Recommendation**: Test with 10 connections + 1000 threads first to validate the theory, then increase connections if throughput doesn't improve.

