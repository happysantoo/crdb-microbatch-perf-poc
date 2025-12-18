# AdaptiveLoadPattern Debug Report

## Issue Summary

The AdaptiveLoadPattern was not working correctly. After investigation, multiple issues were identified and fixed.

## Root Causes Identified

### 1. **Incorrect Metric Names in MetricsProvider** ❌

**Problem:**
The MetricsProvider was looking for metrics that don't exist:
- Looking for: `vajrapulse.task.executions` and `vajrapulse.task.failures`
- Actual metrics: `vajrapulse.execution.count` with `status` label

**Impact:**
- MetricsProvider always returned 0.0 for failure rate and 0L for total executions
- AdaptiveLoadPattern had no feedback to make decisions
- Load pattern couldn't adapt because it thought there were no executions

**Fix Applied:**
Updated MetricsProvider to use correct metric names:
```java
var successCounter = meterRegistry.find("vajrapulse.execution.count")
    .tag("status", "success")
    .counter();
var failureCounter = meterRegistry.find("vajrapulse.execution.count")
    .tag("status", "failure")
    .counter();
```

### 2. **Metric Name Format Mismatch** ❌

**Problem:**
- Micrometer uses dots (`.`) in metric names: `vajrapulse.execution.count`
- Prometheus exports use underscores (`_`): `vajrapulse_execution_count_total`
- MetricsProvider was using wrong format

**Impact:**
- MetricsProvider couldn't find metrics in Micrometer registry
- Always returned default values (0.0, 0L)

**Fix Applied:**
- Use dot notation when querying Micrometer registry
- Added proper tag filtering for `status` label

### 3. **Missing Debug Logging** ⚠️

**Problem:**
- No logging to verify MetricsProvider was being called
- No visibility into what values were being returned
- Hard to diagnose issues

**Fix Applied:**
- Added debug logging in MetricsProvider methods
- Logs failure rate, total executions, and counter availability
- Added warning logs for errors

## Actual VajraPulse Metrics Available

From investigation, the actual metrics exported by VajraPulse are:

### Counters:
- `vajrapulse.execution.count` (Micrometer) / `vajrapulse_execution_count_total` (Prometheus)
  - Labels: `status` = "success" | "failure"
  - Description: Count of task executions partitioned by status

### Gauges:
- `vajrapulse.execution.duration.milliseconds` - Execution duration percentiles
- `vajrapulse.execution.queue.size` - Number of pending executions
- `vajrapulse.execution.queue.wait.time.milliseconds` - Queue wait time
- `vajrapulse.request.throughput.per.second` - Requested target throughput
- `vajrapulse.response.throughput.per.second` - Achieved response throughput
- `vajrapulse.success.rate` - Success rate percentage (0-100)

## Verification Steps

### 1. Check Metrics Are Available
```bash
# Check OpenTelemetry collector
curl http://localhost:8889/metrics | grep vajrapulse_execution_count_total

# Check Prometheus
curl 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[]' | grep vajrapulse
```

### 2. Verify MetricsProvider Is Called
- Check application logs for debug messages:
  - `MetricsProvider: failures=X, successes=Y, total=Z, failureRate=W`
  - `MetricsProvider: totalExecutions=X`

### 3. Verify AdaptiveLoadPattern Behavior
- Check logs for TPS changes
- Monitor `vajrapulse_request_throughput_per_second` metric
- Should see TPS ramping up from 100 to higher values

## Expected Behavior After Fix

1. **MetricsProvider Returns Real Values:**
   - `getFailureRate()` returns actual failure rate (0.0 to 1.0)
   - `getTotalExecutions()` returns actual execution count

2. **AdaptiveLoadPattern Adapts:**
   - Starts at 100 TPS
   - Increases by 500 TPS every 10 seconds if metrics are good
   - Decreases if failure rate > 1% or latency spikes
   - Finds maximum sustainable TPS

3. **Metrics Are Visible:**
   - `vajrapulse_request_throughput_per_second` shows current target TPS
   - `vajrapulse_response_throughput_per_second` shows achieved TPS
   - `vajrapulse_success_rate` shows current success rate

## Testing the Fix

### Run the Application
```bash
./gradlew bootRun
```

### Monitor Logs
Look for:
- `MetricsProvider: failures=...` messages (debug level)
- TPS changes in logs
- Any warnings about metrics

### Monitor Metrics
```bash
# Watch request throughput (should increase)
watch -n 1 'curl -s http://localhost:8889/metrics | grep vajrapulse_request_throughput_per_second'

# Watch success rate (should stay high)
watch -n 1 'curl -s http://localhost:8889/metrics | grep vajrapulse_success_rate'
```

### Check Grafana
- `vajrapulse_request_throughput_per_second` should show increasing TPS
- `vajrapulse_response_throughput_per_second` should match request throughput
- `vajrapulse_success_rate` should stay near 100%

## Additional Issues to Watch For

### 1. Metrics Not Available During Early Execution
- **Issue**: MetricsProvider may be called before metrics are available
- **Mitigation**: Returns 0.0/0L initially, which is safe (no failures = good)

### 2. Metrics Lag
- **Issue**: Metrics may lag behind actual execution
- **Impact**: AdaptiveLoadPattern may make decisions based on slightly stale data
- **Mitigation**: Step duration (10s) and stability window (30s) provide buffer

### 3. Metric Export Interval
- **Current**: 10 seconds
- **Impact**: Metrics may be up to 10 seconds old
- **Consideration**: May need to reduce if adaptive behavior is too slow

## Configuration Summary

### AdaptiveLoadPattern Settings
- **Initial TPS**: 100
- **Max TPS**: 10,000
- **Step Size**: 500 TPS
- **Step Duration**: 10 seconds
- **Cooldown**: 5 seconds
- **Stability Window**: 30 seconds
- **Max Failure Rate**: 1%

### MetricsProvider Implementation
- Reads from Micrometer registry
- Uses `vajrapulse.execution.count` with `status` tag
- Calculates failure rate: `failures / (successes + failures)`
- Returns total executions: `successes + failures`

## Next Steps

1. ✅ Fixed MetricsProvider to use correct metric names
2. ✅ Added debug logging
3. ⏳ Test with actual execution
4. ⏳ Monitor adaptive behavior
5. ⏳ Verify TPS ramps up correctly
6. ⏳ Verify TPS backs off if failures occur

## Files Modified

1. **LoadTestService.java**:
   - Fixed MetricsProvider implementation
   - Updated metric names to match actual VajraPulse exports
   - Added debug logging
   - Added proper tag filtering

## Conclusion

The primary issue was incorrect metric names in the MetricsProvider. The fix ensures:
- MetricsProvider can find and read actual VajraPulse metrics
- AdaptiveLoadPattern receives real-time feedback
- System can adapt TPS based on actual performance

The system should now correctly:
- Start at 100 TPS
- Ramp up to find maximum throughput
- Back off if failures occur
- Find optimal TPS for the batching configuration

