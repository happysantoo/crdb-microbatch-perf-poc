# Debug Logging Enabled for Performance Diagnosis

## Overview

Debug logging has been enabled across all components to diagnose why the application is inserting rows slowly and not ramping up as expected.

## Changes Made

### 1. **Application Logging Configuration** (`application.yml`)

**Updated logging levels:**
```yaml
logging:
  level:
    com.crdb.microbatch: DEBUG          # All application components
    com.crdb.microbatch.backpressure: DEBUG  # Backpressure provider
    com.vajrapulse: DEBUG               # VajraPulse framework
    com.vajrapulse.api: DEBUG           # VajraPulse API
    com.vajrapulse.worker: DEBUG        # VajraPulse worker
    org.hikaricp: DEBUG                 # HikariCP connection pool
```

### 2. **HikariCPBackpressureProvider** - Enhanced Logging

**Added:**
- Periodic logging (every 5 seconds) of backpressure metrics:
  - Active connections / Total connections
  - Idle connections
  - Threads awaiting connection
  - Pool utilization
  - Queue pressure
  - Final backpressure level

**Example log output:**
```
DEBUG HikariCPBackpressureProvider - Backpressure: active=5/10, idle=5, waiting=0, poolUtil=0.50, queuePressure=0.00, backpressure=0.50
```

### 3. **LoadTestService** - Enhanced Logging

**Added:**
- Initial metrics logging (failure rate, total executions)
- Initial backpressure level logging
- Background metrics logger thread (logs every 5 seconds):
  - Total executions
  - Failure rate
  - Current backpressure level
- Final backpressure level logging

**Example log output:**
```
DEBUG LoadTestService - MetricsProvider obtained from pipeline: ...
DEBUG LoadTestService - Initial metrics - Failure rate: 0.00, Total executions: 0
DEBUG LoadTestService - Initial backpressure level: 0.00
INFO LoadTestService - [Metrics] Executions: 500, Failure Rate: 0.00%, Backpressure: 0.30
```

### 4. **CrdbInsertTask** - Enhanced Logging

**Added:**
- Timing measurements for:
  - Submit duration (time to call `submitWithCallback`)
  - Wait duration (time waiting for `resultFuture.get()`)
  - Total execution duration
- Logging when operations take longer than expected:
  - Submit > 10ms
  - Wait > 100ms
- More frequent iteration logging (every 100 iterations instead of 1000)

**Example log output:**
```
DEBUG CrdbInsertTask - Executed iteration: 100, totalDuration: 45ms, submitDuration: 1ms, waitDuration: 44ms
DEBUG CrdbInsertTask - Submit took 15ms at iteration 250
DEBUG CrdbInsertTask - Waiting for result took 150ms at iteration 500
```

### 5. **CrdbBatchBackend** - Enhanced Logging

**Added:**
- Batch dispatch logging (batch size)
- Insert operation timing
- Per-item timing calculation
- Warning when batch dispatch takes > 100ms

**Example log output:**
```
DEBUG CrdbBatchBackend - Dispatching batch of 50 items
DEBUG CrdbBatchBackend - Batch insert completed: 50 items in 25ms (0.50ms/item)
WARN CrdbBatchBackend - Batch dispatch took 150ms (insert: 25ms) for 50 items
```

## What to Look For in Logs

### 1. **Backpressure Issues**

**Symptoms:**
- High backpressure values (> 0.7) even at low TPS
- Many threads awaiting connection
- High pool utilization

**What to check:**
```
Look for: "Backpressure: active=X/10, waiting=Y"
- If waiting > 0 consistently: Connection pool bottleneck
- If active = 10 consistently: All connections in use
- If backpressure > 0.7: AdaptiveLoadPattern should ramp down
```

### 2. **Task Execution Delays**

**Symptoms:**
- High `waitDuration` in CrdbInsertTask logs
- `resultFuture.get()` taking a long time

**What to check:**
```
Look for: "Waiting for result took Xms"
- If > 100ms consistently: Items are queuing in MicroBatcher
- If > 1000ms: Severe queuing or batch processing delay
```

### 3. **Batch Processing Delays**

**Symptoms:**
- High batch dispatch times
- Slow database inserts

**What to check:**
```
Look for: "Batch dispatch took Xms"
- If > 100ms: Database insert is slow
- Check "insert: Xms" to see actual DB time vs overhead
```

### 4. **AdaptiveLoadPattern Behavior**

**Symptoms:**
- TPS not ramping up
- TPS ramping down unexpectedly

**What to check:**
```
Look for: "[Metrics] Executions: X, Failure Rate: Y%, Backpressure: Z"
- If Failure Rate > 1%: Pattern should ramp down
- If Backpressure > 0.7: Pattern should ramp down
- If both are low but TPS not increasing: Check VajraPulse logs
```

### 5. **Connection Pool Issues**

**Symptoms:**
- Connection timeouts
- High connection wait times

**What to check:**
```
Look for HikariCP DEBUG logs:
- Connection acquisition time
- Connection pool stats
- Connection leaks
```

## Expected Behavior

### Normal Operation

1. **Startup:**
   - Initial backpressure: 0.0
   - Initial TPS: 100
   - No waiting threads

2. **Ramp Up:**
   - TPS increases by 500 every 10 seconds
   - Backpressure gradually increases
   - Executions increase steadily

3. **Stable Operation:**
   - Backpressure < 0.7
   - Failure rate < 1%
   - Consistent execution rate

### Problem Indicators

1. **Slow Ramping:**
   - Backpressure > 0.7 at low TPS → Connection pool too small
   - Failure rate > 1% → Database issues
   - High wait times → MicroBatcher queuing

2. **No Ramping:**
   - TPS stuck at 100 → Check AdaptiveLoadPattern logs
   - No executions → Check task execution logs
   - High backpressure from start → Connection pool issue

## Next Steps

1. **Run the application:**
   ```bash
   ./gradlew bootRun
   ```

2. **Monitor logs for:**
   - Backpressure values (should start low, increase gradually)
   - Task execution times (should be < 100ms per item)
   - Batch dispatch times (should be < 100ms per batch)
   - AdaptiveLoadPattern TPS changes

3. **Look for patterns:**
   - If backpressure is high from start → Connection pool bottleneck
   - If wait times are high → MicroBatcher queuing issue
   - If batch times are high → Database performance issue
   - If TPS not changing → AdaptiveLoadPattern configuration issue

4. **Common Issues to Check:**
   - **Connection pool too small**: Increase `maximum-pool-size` in `application.yml`
   - **Database slow**: Check CockroachDB performance
   - **MicroBatcher queuing**: Check batch size and linger time
   - **AdaptiveLoadPattern not working**: Check VajraPulse logs for errors

## Log File Location

Logs are output to console. To save to file, add to `application.yml`:

```yaml
logging:
  file:
    name: logs/crdb-microbatch.log
```

## Summary

All debug logging is now enabled. The logs will show:
- ✅ Backpressure levels every 5 seconds
- ✅ Task execution timing and delays
- ✅ Batch processing timing
- ✅ Connection pool metrics
- ✅ AdaptiveLoadPattern behavior
- ✅ Overall metrics every 5 seconds

This should help identify where the bottleneck is and why ramping isn't working as expected.

