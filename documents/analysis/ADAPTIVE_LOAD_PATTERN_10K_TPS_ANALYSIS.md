# AdaptiveLoadPattern Stuck at 10k TPS - Analysis

## Issue

The AdaptiveLoadPattern is consistently running at 10,000 TPS and not ramping up further.

## Root Cause Analysis

### Why It's Stuck at 10k TPS

**From AdaptiveLoadPattern source code (lines 304-313):**
```java
private double handleRampUp(long elapsedMillis, AdaptiveState current) {
    // Check if we've hit max TPS
    if (current.currentTps() >= maxTps) {
        // Treat max TPS as stable point
        transitionPhase(Phase.SUSTAIN, elapsedMillis, maxTps);
        return maxTps;
    }
    
    return current.currentTps();
}
```

**Analysis:**
1. Pattern starts at **1000 TPS** (INITIAL_TPS)
2. Ramps up by **500 TPS every 10 seconds** (RAMP_INCREMENT, RAMP_INTERVAL)
3. Reaches **10,000 TPS** (MAX_TPS) after ~18 intervals (180 seconds = 3 minutes)
4. Once it hits MAX_TPS, it **immediately transitions to SUSTAIN phase**
5. In SUSTAIN phase, it **stays at MAX_TPS indefinitely**

**This is expected behavior** - the pattern cannot exceed MAX_TPS.

### Current Configuration

```java
INITIAL_TPS = 1000.0
RAMP_INCREMENT = 500.0
RAMP_INTERVAL = 10 seconds
MAX_TPS = 10000.0
```

**Ramp-up timeline:**
- 0s: 1000 TPS
- 10s: 1500 TPS
- 20s: 2000 TPS
- ...
- 180s: 10000 TPS → **Transitions to SUSTAIN**
- 180s+: 10000 TPS (sustained)

## Why It's Not Ramping Up

**Answer:** It already reached MAX_TPS and transitioned to SUSTAIN phase.

The pattern is working correctly - it:
1. ✅ Started at 1000 TPS
2. ✅ Ramped up by 500 TPS every 10 seconds
3. ✅ Reached 10000 TPS (MAX_TPS)
4. ✅ Transitioned to SUSTAIN phase
5. ✅ Sustaining at 10000 TPS

## Solutions

### Option 1: Increase MAX_TPS (If System Can Handle More)

```java
private static final double MAX_TPS = 20000.0;  // Increase from 10000
```

**Considerations:**
- Connection pool capacity (currently 10 connections)
- Database capacity
- Network bandwidth
- Backpressure will prevent exceeding capacity

### Option 2: Start at Lower TPS (To See More Ramp-Up Behavior)

```java
private static final double INITIAL_TPS = 500.0;  // Lower starting point
```

**Benefit:** More visible ramp-up behavior, takes longer to reach MAX_TPS

### Option 3: Reduce RAMP_INCREMENT (More Gradual Ramp-Up)

```java
private static final double RAMP_INCREMENT = 250.0;  // Smaller steps
```

**Benefit:** More gradual ramp-up, easier to observe behavior

### Option 4: Monitor Phase Transitions

The pattern transitions through phases:
- **RAMP_UP**: Increasing TPS
- **RAMP_DOWN**: Decreasing TPS (if errors/backpressure)
- **SUSTAIN**: Holding at stable TPS
- **COMPLETE**: Test ended (if no stable point found)

**Check current phase:**
```java
if (loadPattern instanceof AdaptiveLoadPattern adaptivePattern) {
    log.info("Current Phase: {}, Current TPS: {}", 
        adaptivePattern.getCurrentPhase(), 
        adaptivePattern.getCurrentTps());
}
```

## Expected Behavior at 10k TPS

If the pattern is at 10k TPS in SUSTAIN phase:

1. **It will NOT ramp up further** - MAX_TPS is the limit
2. **It will sustain at 10k TPS** for SUSTAIN_DURATION (30 seconds)
3. **After sustain duration**, it continues at 10k TPS indefinitely
4. **It will ramp down** if:
   - Error rate ≥ 1% (ERROR_THRESHOLD)
   - Backpressure ≥ 0.7

## Verification

To verify the pattern is working correctly:

1. **Check current phase:**
   - Should be `SUSTAIN` if at MAX_TPS
   - Should be `RAMP_UP` if below MAX_TPS

2. **Check phase transitions:**
   - Should have transitioned from `RAMP_UP` → `SUSTAIN` when hitting MAX_TPS
   - Check `getPhaseTransitionCount()` - should be > 0

3. **Check stable TPS:**
   - `getStableTps()` should return 10000.0 (MAX_TPS was treated as stable)

## Recommendations

1. **If you want to see ramping behavior:**
   - Increase MAX_TPS to 20000 or higher
   - Or reduce INITIAL_TPS to see more ramp-up steps

2. **If 10k TPS is the target:**
   - Current behavior is correct
   - Pattern found stable point at MAX_TPS
   - It's sustaining as designed

3. **To monitor phase transitions:**
   - Enable INFO logging for `com.vajrapulse.core.engine.ExecutionEngine`
   - Check metrics: `vajrapulse.adaptive.phase` gauge
   - Check metrics: `vajrapulse.adaptive.current_tps` gauge

## Conclusion

**The pattern is working correctly.** It reached MAX_TPS (10000) and transitioned to SUSTAIN phase, which is expected behavior. To see ramping above 10k, increase MAX_TPS.

## Logging Changes Applied

### 1. Reduced Batch/Record Level Logging
- **CrdbBatchBackend**: Only logs warnings for batching issues (batch size = 1 or < 10)
- **CrdbInsertTask**: Removed per-execution logging
- **HikariCPBackpressureProvider**: Reduced from DEBUG to INFO

### 2. Enabled Phase Transition Logging
- **PhaseLoggingLoadPattern**: New wrapper that logs RAMP_UP and RAMP_DOWN events synchronously
- Logs phase transitions when they occur
- Logs TPS changes during ramp up/down
- No separate monitoring thread - logs happen when `calculateTps()` is called

### 3. Enabled ExecutionEngine Logging
- **application.yml**: Set `com.vajrapulse.core.engine` to INFO level
- This enables ExecutionEngine's built-in logging for load test lifecycle events

## What You'll See in Logs

### Phase Transitions:
```
INFO PhaseLoggingLoadPattern - 🔼 RAMP_UP: Phase transition from RAMP_UP to RAMP_UP, TPS: 1500.00
INFO PhaseLoggingLoadPattern - 🔼 RAMP_UP: TPS increased from 1000.00 to 1500.00 (+500.00)
INFO PhaseLoggingLoadPattern - ⏸️ SUSTAIN: Phase transition from RAMP_UP to SUSTAIN, TPS: 10000.00
```

### ExecutionEngine Events:
```
INFO ExecutionEngine - Starting load test runId=... pattern=AdaptiveLoadPattern duration=...
INFO ExecutionEngine - Task initialization completed for runId=...
```

### Reduced Batch Logging:
- Only warnings for batch size = 1 or < 10
- No per-batch success logs
- No per-item execution logs

