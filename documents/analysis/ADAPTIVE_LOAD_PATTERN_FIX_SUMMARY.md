# AdaptiveLoadPattern Fix Summary

## Issue Identified

**Problem:** AdaptiveLoadPattern was staying at ~5 TPS instead of ramping up to 1000 TPS.

**Root Cause:** Parameter mapping error - parameters were passed in wrong order to constructor.

## What Was Wrong

### Incorrect Usage (Before Fix)
```java
new AdaptiveLoadPattern(
    INITIAL_TPS,        // ✅ 1000.0
    MAX_TPS,            // ❌ 10000.0 passed as rampIncrement
    STEP_SIZE,          // ❌ 500.0 passed as rampDecrement  
    STEP_DURATION,      // ✅ 10 seconds
    COOLDOWN_SECONDS,   // ❌ 5.0 passed as maxTps
    STABILITY_WINDOW,   // ✅ 30 seconds
    MAX_FAILURE_RATE,   // ✅ 0.01
    metricsProvider,
    backpressureProvider
);
```

**Result:**
- Pattern tried to ramp up by **10,000 TPS per interval** (impossible!)
- Maximum TPS was capped at **5.0** (way too low!)
- This explains why TPS stayed at ~5 TPS

### Correct Usage (After Fix)
```java
new AdaptiveLoadPattern(
    INITIAL_TPS,           // ✅ 1000.0
    RAMP_INCREMENT,        // ✅ 500.0 (step size for ramp up)
    RAMP_DECREMENT,        // ✅ 500.0 (step size for ramp down)
    RAMP_INTERVAL,         // ✅ 10 seconds
    MAX_TPS,               // ✅ 10000.0
    SUSTAIN_DURATION,      // ✅ 30 seconds
    ERROR_THRESHOLD,       // ✅ 0.01 (1%)
    metricsProvider,
    backpressureProvider
);
```

## Fix Applied

**File:** `src/main/java/com/crdb/microbatch/service/LoadTestService.java`

**Changes:**
1. Renamed constants to match constructor parameter names:
   - `STEP_SIZE` → `RAMP_INCREMENT` and `RAMP_DECREMENT`
   - `STEP_DURATION` → `RAMP_INTERVAL`
   - `COOLDOWN_SECONDS` → removed (not a constructor parameter)
   - `STABILITY_WINDOW` → `SUSTAIN_DURATION`
   - `MAX_FAILURE_RATE` → `ERROR_THRESHOLD`

2. Fixed parameter order in constructor call

3. Updated all references throughout the file

## Expected Behavior After Fix

1. **Start at 1000 TPS** ✅
2. **Ramp up by 500 TPS every 10 seconds** ✅
3. **Ramp down when:**
   - Error rate ≥ 1%, OR
   - Backpressure ≥ 0.7
4. **Ramp up when:**
   - Error rate < 1% AND
   - Backpressure < 0.3
5. **Find stable point** after 3 consecutive stable intervals
6. **Sustain at stable TPS** for 30 seconds
7. **Continue indefinitely** at stable TPS

## Testing

### Step 1: Rebuild
```bash
./gradlew clean build -x test
```

### Step 2: Run Application
```bash
./gradlew bootRun
```

### Step 3: Verify Behavior

**Look for:**
1. Initial TPS: 1000
2. TPS increases by 500 every 10 seconds
3. Batch sizes should be ~50 items (not 1)
4. Actual TPS should match target TPS

**Monitor logs:**
```
[Metrics] Executions: 10000 (+1000), Actual TPS: 1000.00, Failure Rate: 0.00%, Backpressure: 0.30
```

**Check adaptive metrics:**
- `vajrapulse.adaptive.current_tps` should show increasing values
- `vajrapulse.adaptive.phase` should show phase transitions

## Library Code Analysis

**Conclusion:** The library code is correct. The issue was entirely due to incorrect parameter mapping in our usage.

**No library fixes needed** - the AdaptiveLoadPattern implementation is sound.

## Recommendations for Library

While the library code is correct, these improvements would help prevent similar issues:

1. **Builder Pattern:** Consider adding a builder to prevent parameter order errors
2. **Validation Warnings:** Add checks like "rampIncrement > maxTps" and warn
3. **Javadoc Examples:** Add more detailed examples showing correct usage
4. **Parameter Names:** Consider more descriptive parameter names or a configuration object

## Status

✅ **FIXED** - Parameter order corrected, code compiles, ready for testing.

