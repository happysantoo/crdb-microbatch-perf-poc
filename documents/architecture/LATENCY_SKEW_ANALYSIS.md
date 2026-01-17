# Latency Skew Analysis: VajraPulse Metrics vs. Reality

## Problem Statement

When using non-blocking batching with `submitSync()` + `submitWithCallback()`, there's a **latency skew** between what VajraPulse records and actual processing time.

## The Issue

### Current Flow

```
T=0ms:   execute() starts
T=1ms:   submitSync() completes (item queued)
T=1ms:   TaskResult.success() returned
         → VajraPulse records: latency = 1ms ❌ (too low!)

T=50ms:  Batch actually processes (database insert completes)
         → Actual latency: 50ms ✅ (but VajraPulse doesn't see this)
```

### Why This Happens

**VajraPulse Latency Measurement:**
- VajraPulse tracks latency from `execute()` start to `TaskResult` return
- If we return `TaskResult.success()` immediately (before batch processes), VajraPulse sees very short latency
- Actual batch processing happens later (in callback)
- **Result:** `vajrapulse_task_duration_seconds` metric is skewed (too low)

**Code Pattern:**
```java
@Override
public TaskResult execute(long iteration) throws Exception {
    long startTime = System.currentTimeMillis();
    
    // Submit item (non-blocking)
    batcher.submitWithCallback(item, (item, result) -> {
        // Batch processes here (50ms later)
        // But VajraPulse already recorded latency = 1ms
    });
    
    // Return immediately (VajraPulse records 1ms latency)
    return TaskResult.success();
}
```

## Impact Assessment

### 1. AdaptiveLoadPattern Behavior

**✅ No Impact**
- AdaptiveLoadPattern **doesn't use latency** for decisions
- Uses only: **error rate** and **backpressure**
- Latency skew doesn't affect TPS adjustments

**Evidence:**
- From AdaptiveLoadPattern source analysis, decisions are based on:
  - `errorRate >= errorThreshold` → RAMP_DOWN
  - `backpressure >= 0.7` → RAMP_DOWN
  - `errorRate < errorThreshold && backpressure < 0.3` → RAMP_UP
- No latency checks in decision logic

### 2. VajraPulse Metrics

**⚠️ Skewed (But Acceptable)**
- `vajrapulse_task_duration_seconds` will be too low (~1ms instead of 50ms+)
- Affects monitoring/observability, not behavior
- Can be misleading if used for latency analysis

**Mitigation:**
- Use custom metrics (`crdb.submit.latency`) for accurate latency
- Document that VajraPulse latency is "queue time" not "processing time"
- Use VajraPulse latency to understand task submission rate, not processing latency

### 3. Custom Metrics

**✅ Accurate**
- `crdb.submit.latency` tracks actual batch processing time
- Measured in callback when batch completes
- Provides accurate latency for monitoring

**Code:**
```java
Timer.Sample sample = Timer.start(meterRegistry);

batcher.submitWithCallback(item, (item, result) -> {
    // Stop timer when batch actually processes
    sample.stop(submitLatencyTimer);  // Accurate latency (50ms+)
});
```

## Solutions

### Option A: Track Latency in Callback (Recommended) ✅

**Approach:**
- Start timer in `execute()`
- Stop timer in callback (when batch actually processes)
- VajraPulse's internal latency will be low, but custom metrics will be accurate

**Pros:**
- Non-blocking (maintains batching benefits)
- Accurate custom metrics
- No performance impact

**Cons:**
- VajraPulse latency metrics are skewed (but not used for decisions)

**Code:**
```java
Timer.Sample sample = Timer.start(meterRegistry);

batcher.submitWithCallback(item, (item, result) -> {
    sample.stop(submitLatencyTimer);  // Accurate latency
});

return TaskResult.success();  // VajraPulse sees low latency, but that's OK
```

### Option B: Block Until Batch Completes (Not Recommended) ❌

**Approach:**
- Wait for batch to complete before returning `TaskResult`
- VajraPulse latency would be accurate

**Pros:**
- VajraPulse latency metrics are accurate

**Cons:**
- Blocks thread (defeats batching purpose)
- Reduces throughput significantly
- Adds latency (waiting for batch)
- Can't queue multiple items

**Code:**
```java
CompletableFuture<ItemResult<T>> future = batcher.submit(item);
ItemResult<T> result = future.get(100, TimeUnit.MILLISECONDS); // Blocks!
return convertToTaskResult(result);
```

### Option C: Report Estimated Latency (Complex) ❌

**Approach:**
- Track average batch processing time
- Add estimated latency to immediate return

**Pros:**
- VajraPulse latency metrics closer to reality

**Cons:**
- Complex (need to track averages)
- Inaccurate (estimates, not actual)
- Adds complexity without clear benefit

## Recommendation

**Use Option A** - Track accurate latency in callback via custom metrics.

**Why:**
1. **AdaptiveLoadPattern doesn't use latency** - Only uses error rate and backpressure
2. **Custom metrics provide accuracy** - `crdb.submit.latency` tracks actual processing time
3. **Non-blocking is more important** - Batching benefits outweigh perfect VajraPulse latency metrics
4. **VajraPulse latency is for observability** - Not used for decision-making

## Monitoring Strategy

### Use Custom Metrics for Latency

**Accurate Latency:**
```promql
# Use custom metric for actual processing latency
histogram_quantile(0.50, rate(crdb_submit_latency_seconds_bucket[1m]))
histogram_quantile(0.95, rate(crdb_submit_latency_seconds_bucket[1m]))
histogram_quantile(0.99, rate(crdb_submit_latency_seconds_bucket[1m]))
```

**VajraPulse Latency (Queue Time):**
```promql
# VajraPulse latency shows queue time, not processing time
histogram_quantile(0.50, rate(vajrapulse_task_duration_seconds_bucket[1m]))
```

**Documentation:**
- Document that `vajrapulse_task_duration_seconds` is "queue time" not "processing time"
- Use `crdb.submit.latency` for actual processing latency
- Use `vajrapulse_task_duration_seconds` to understand task submission rate

## Conclusion

**Latency skew is acceptable** because:
1. AdaptiveLoadPattern doesn't use latency for decisions
2. Custom metrics provide accurate latency for monitoring
3. Non-blocking batching is more important than perfect VajraPulse latency metrics
4. The trade-off (skewed VajraPulse latency) is worth the benefit (non-blocking batching)

**Key Insight:** VajraPulse latency metrics are for observability, not decision-making. As long as we have accurate custom metrics, the skew is acceptable.

