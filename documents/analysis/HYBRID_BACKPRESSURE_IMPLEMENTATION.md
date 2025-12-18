# Hybrid Backpressure Implementation

## Overview

Implemented a **hybrid backpressure approach** that combines blocking (for moderate pressure) with immediate rejection (for severe pressure). This provides better throughput while maintaining system stability and feedback to VajraPulse.

## Implementation Details

### Three-Tier Strategy

1. **Severe Pressure** (`threadsAwaiting >= 2x pool size`):
   - **Action**: Reject immediately
   - **Rationale**: System is severely overloaded, reject to prevent queue growth
   - **Feedback**: VajraPulse sees failure → reduces TPS

2. **Moderate Pressure** (`threadsAwaiting >= pool size`):
   - **Action**: Block briefly (100ms timeout)
   - **Rationale**: Capacity may become available shortly, preserve work
   - **Feedback**: If timeout → reject (VajraPulse sees failure)

3. **Low Pressure** (`threadsAwaiting < pool size`):
   - **Action**: Submit normally
   - **Rationale**: Capacity available, no backpressure needed

### Code Implementation

**Location**: `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`

```java
// Get connection pool metrics
HikariPoolMXBean poolBean = hikariBackpressureProvider.getPoolBean();

if (poolBean != null) {
    int threadsAwaiting = poolBean.getThreadsAwaitingConnection();
    int total = poolBean.getTotalConnections();
    
    // Threshold 1: Severe pressure - reject immediately
    int rejectionThreshold = total * 2;
    if (threadsAwaiting >= rejectionThreshold) {
        return TaskResult.failure(new RuntimeException(...));
    }
    
    // Threshold 2: Moderate pressure - block briefly
    int blockingThreshold = total;
    if (threadsAwaiting >= blockingThreshold) {
        long blockingStartTime = System.currentTimeMillis();
        long blockingTimeout = 100;  // 100ms
        
        while (poolBean.getThreadsAwaitingConnection() >= blockingThreshold) {
            long elapsed = System.currentTimeMillis() - blockingStartTime;
            if (elapsed > blockingTimeout) {
                // Timeout - reject
                return TaskResult.failure(new RuntimeException(...));
            }
            Thread.sleep(10);  // Wait 10ms
        }
        
        // Blocking succeeded - record metrics
        submitBlockingTimer.record(blockingDuration, TimeUnit.MILLISECONDS);
    }
}

// Submit item
batcher.submitWithCallback(testInsert, callback);
return TaskResult.success();
```

## New Metrics

### Counters

1. **`crdb.submits.blocked`**
   - Total number of submits that were blocked waiting for capacity
   - Incremented when `threadsAwaiting >= total`

2. **`crdb.submits.blocked.timeout`**
   - Total number of submits that timed out while blocking
   - Incremented when blocking timeout (100ms) is exceeded

### Timers

1. **`crdb.submit.blocking`**
   - Time spent blocking waiting for connection pool capacity
   - Records duration from blocking start to capacity available or timeout

## Expected Behavior

### Scenario 1: Low Pressure (`threadsAwaiting < total`)

```
Item Submission
    ↓
Check threadsAwaiting: 5 < 10 (total)
    ↓
Submit immediately
    ↓
TaskResult.success()
```

**Metrics:**
- `crdb.submits.total` increments
- `crdb.submits.success` increments (after batch completes)
- No blocking metrics

### Scenario 2: Moderate Pressure (`total <= threadsAwaiting < 2x total`)

```
Item Submission
    ↓
Check threadsAwaiting: 12 >= 10 (blocking threshold)
    ↓
Block and check every 10ms
    ├─ Capacity available after 30ms → Submit
    └─ Timeout after 100ms → Reject
    ↓
If capacity available:
    └─ TaskResult.success()
    └─ submitBlockingTimer.record(30ms)
If timeout:
    └─ TaskResult.failure()
    └─ submitBlockedTimeoutCounter.increment()
```

**Metrics:**
- `crdb.submits.blocked` increments
- `crdb.submit.blocking` records blocking duration
- If timeout: `crdb.submits.blocked.timeout` increments
- If timeout: `crdb.submits.failure` increments

### Scenario 3: Severe Pressure (`threadsAwaiting >= 2x total`)

```
Item Submission
    ↓
Check threadsAwaiting: 25 >= 20 (rejection threshold)
    ↓
Reject immediately
    ↓
TaskResult.failure()
```

**Metrics:**
- `crdb.submits.failure` increments
- No blocking metrics (rejected before blocking)

## Benefits

### 1. Better Throughput

- **Before**: 40% rejection rate (8.77M submits, 5.26M accepted)
- **After**: Lower rejection rate (items wait when capacity available)
- **Impact**: Better utilization of connection pool

### 2. Work Preservation

- **Before**: Items rejected immediately when backpressure >= 0.7
- **After**: Items wait briefly (100ms) when capacity may become available
- **Impact**: Fewer false rejections

### 3. System Stability

- **Before**: Risk of unbounded queue growth (no feedback)
- **After**: Rejects when severely overloaded (provides feedback)
- **Impact**: Prevents system overload

### 4. Feedback Loop

- **Before**: Rejections visible to VajraPulse (good)
- **After**: Rejections still visible (timeouts and severe pressure)
- **Impact**: AdaptiveLoadPattern can still adjust TPS

## Configuration

### Thresholds

- **Blocking Threshold**: `total` (pool size)
  - When `threadsAwaiting >= total`, start blocking
  - Example: With 10 connections, block when 10+ threads waiting

- **Rejection Threshold**: `2x total` (2x pool size)
  - When `threadsAwaiting >= 2x total`, reject immediately
  - Example: With 10 connections, reject when 20+ threads waiting

- **Blocking Timeout**: `100ms`
  - Maximum time to wait for capacity
  - After timeout, reject and provide feedback to VajraPulse

### Tuning Recommendations

**If too many timeouts:**
- Increase blocking timeout (e.g., 150ms)
- Or increase blocking threshold (e.g., `total * 1.5`)

**If too many rejections:**
- Increase rejection threshold (e.g., `3x total`)
- Or increase blocking timeout (e.g., 200ms)

**If queue grows unbounded:**
- Decrease blocking timeout (e.g., 50ms)
- Or decrease rejection threshold (e.g., `1.5x total`)

## Monitoring

### Key Metrics to Watch

1. **Blocking Rate**: `crdb.submits.blocked / crdb.submits.total`
   - Should be < 10% under normal load
   - High rate indicates frequent capacity contention

2. **Timeout Rate**: `crdb.submits.blocked.timeout / crdb.submits.blocked`
   - Should be < 5% of blocked submits
   - High rate indicates blocking timeout too short or severe overload

3. **Average Blocking Time**: `crdb.submit.blocking` (P50, P95, P99)
   - P50 should be < 20ms (most capacity available quickly)
   - P95 should be < 80ms (most capacity available within timeout)
   - P99 may exceed timeout (timeouts included in metric)

4. **Rejection Rate**: `crdb.submits.failure / crdb.submits.total`
   - Should be < 5% under normal load
   - High rate indicates system overload or thresholds too aggressive

### Grafana Queries

```promql
# Blocking rate
rate(crdb_submits_blocked_total[1m]) / rate(crdb_submits_total[1m])

# Timeout rate
rate(crdb_submits_blocked_timeout_total[1m]) / rate(crdb_submits_blocked_total[1m])

# Average blocking time (P50)
histogram_quantile(0.50, rate(crdb_submit_blocking_seconds_bucket[1m]))

# Rejection rate
rate(crdb_submits_failure_total[1m]) / rate(crdb_submits_total[1m])
```

## Comparison with Previous Approach

| Aspect | Previous (Non-Blocking) | Hybrid (Current) |
|--------|------------------------|------------------|
| **Rejection Rate** | 40% (8.77M → 5.26M) | Expected: < 10% |
| **Work Preservation** | ❌ Rejects immediately | ✅ Waits when capacity available |
| **Throughput** | Lower (rejects prevent queuing) | Higher (waits for capacity) |
| **Feedback** | ✅ Immediate (rejections visible) | ✅ Still visible (timeouts + severe) |
| **Latency** | Low (rejects fast) | Variable (waits up to 100ms) |
| **Resource Usage** | Lower (no blocking) | Higher (brief blocking) |

## Testing Checklist

- [ ] Verify blocking occurs when `threadsAwaiting >= total`
- [ ] Verify rejection occurs when `threadsAwaiting >= 2x total`
- [ ] Verify timeout after 100ms if capacity doesn't become available
- [ ] Verify metrics are recorded correctly
- [ ] Verify VajraPulse sees rejections (timeouts + severe pressure)
- [ ] Verify AdaptiveLoadPattern reduces TPS when rejections occur
- [ ] Verify system recovers when pressure decreases
- [ ] Monitor blocking time distribution (P50, P95, P99)
- [ ] Monitor rejection rate (should be lower than 40%)

## Future Enhancements

### Phase 2: Adaptive Timeout

- Adjust blocking timeout based on recent connection availability
- If connections become available quickly → longer timeout
- If connections stay busy → shorter timeout

### Phase 3: Dynamic Thresholds

- Adjust thresholds based on system load
- Lower thresholds during high load
- Higher thresholds during low load

### Phase 4: Predictive Blocking

- Predict when capacity will become available
- Block only if capacity likely to be available soon
- Reject if capacity unlikely to be available

