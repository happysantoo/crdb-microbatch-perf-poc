# AdaptiveLoadPattern Analysis - Logic Gaps and Issues

## Executive Summary

After analyzing the VajraPulse 0.9.6 `AdaptiveLoadPattern` source code from Maven Central, **critical parameter mapping errors** were identified in our usage. The constructor parameters are being passed in the wrong order, causing the pattern to behave incorrectly.

## Source Code Analysis

### Constructor Signature (from Maven Central)

```java
public AdaptiveLoadPattern(
    double initialTps,              // Starting TPS
    double rampIncrement,            // TPS increase per interval
    double rampDecrement,            // TPS decrease per interval  
    Duration rampInterval,           // Time between adjustments
    double maxTps,                   // Maximum TPS limit
    Duration sustainDuration,        // Duration to sustain at stable point
    double errorThreshold,           // Error rate threshold (0.0-1.0)
    MetricsProvider metricsProvider,
    BackpressureProvider backpressureProvider
)
```

### Our Current Usage (INCORRECT)

```java
new AdaptiveLoadPattern(
    INITIAL_TPS,        // ✅ initialTps = 1000.0
    MAX_TPS,            // ❌ WRONG! Should be rampIncrement (step size)
    STEP_SIZE,          // ❌ WRONG! Should be rampDecrement (step size)
    STEP_DURATION,      // ✅ rampInterval = 10 seconds
    COOLDOWN_SECONDS,   // ❌ WRONG! Should be maxTps
    STABILITY_WINDOW,   // ✅ sustainDuration = 30 seconds
    MAX_FAILURE_RATE,   // ✅ errorThreshold = 0.01
    metricsProvider,
    backpressureProvider
);
```

**What we're actually passing:**
- `initialTps = 1000.0` ✅
- `rampIncrement = 10000.0` ❌ (should be 500.0)
- `rampDecrement = 500.0` ❌ (should be 500.0, but in wrong position)
- `rampInterval = 10 seconds` ✅
- `maxTps = 5.0` ❌ (should be 10000.0)
- `sustainDuration = 30 seconds` ✅
- `errorThreshold = 0.01` ✅

## Critical Issues Identified

### Issue 1: **Wrong Parameter Order** ⚠️ CRITICAL

**Problem:**
- `MAX_TPS` (10000.0) is being passed as `rampIncrement`
- `STEP_SIZE` (500.0) is being passed as `rampDecrement`
- `COOLDOWN_SECONDS` (5.0) is being passed as `maxTps`

**Impact:**
- Pattern tries to ramp up by **10,000 TPS per interval** (impossible!)
- Pattern ramps down by **500 TPS per interval** (correct, but wrong position)
- Maximum TPS is capped at **5.0** (way too low!)
- This explains why TPS stays at ~5 TPS!

### Issue 2: **MetricsProvider.getFailureRate() Return Type**

**From source code (line 362):**
```java
cachedFailureRate = metricsProvider.getFailureRate() / PERCENTAGE_TO_RATIO;
```

**Analysis:**
- Code divides by 100.0, expecting percentage (0.0-100.0)
- `MetricsProvider` interface says: "Gets the current failure rate as a percentage" (0.0 to 100.0)
- This is correct - the code converts percentage to ratio

**However:** We need to verify what `pipeline.getMetricsProvider()` actually returns. If it returns a ratio (0.0-1.0) instead of percentage, this would cause issues.

### Issue 3: **Ramp-Up Logic in handleRampUp()**

**From source code (lines 304-313):**
```java
private double handleRampUp(long elapsedMillis, AdaptiveState current) {
    // Check if we've hit max TPS
    if (current.currentTps() >= maxTps) {
        // Treat max TPS as stable point
        transitionPhase(Phase.SUSTAIN, elapsedMillis, maxTps);
        return maxTps;
    }
    
    return current.currentTps();  // Just returns current TPS, no ramp-up here!
}
```

**Analysis:**
- `handleRampUp()` doesn't actually ramp up - it just returns current TPS
- Ramp-up happens in `checkAndAdjust()` when interval elapses
- This is correct design - ramp-up is interval-based, not continuous

### Issue 4: **Backpressure Thresholds**

**From source code (lines 375-376):**
```java
boolean shouldRampDown = errorRate >= errorThreshold || backpressure >= 0.7;
boolean canRampUp = errorRate < errorThreshold && backpressure < 0.3;
```

**Analysis:**
- Ramp down when: error rate ≥ 1% OR backpressure ≥ 0.7
- Ramp up when: error rate < 1% AND backpressure < 0.3
- Moderate backpressure (0.3-0.7): holds current TPS
- This logic is correct

## Root Cause

**The primary issue is parameter mapping error:**

1. We're passing `MAX_TPS` (10000.0) as `rampIncrement`
   - Pattern tries to increase by 10,000 TPS every 10 seconds
   - This is impossible, so it likely hits `maxTps` immediately

2. We're passing `COOLDOWN_SECONDS` (5.0) as `maxTps`
   - Pattern caps at 5.0 TPS maximum
   - This explains why we see ~5 TPS!

3. We're passing `STEP_SIZE` (500.0) as `rampDecrement`
   - This is correct value but wrong position
   - Should be used for both increment and decrement

## Correct Usage

```java
new AdaptiveLoadPattern(
    1000.0,                          // initialTps
    500.0,                           // rampIncrement (step size)
    500.0,                           // rampDecrement (step size)
    Duration.ofSeconds(10),          // rampInterval
    10000.0,                         // maxTps
    Duration.ofSeconds(30),          // sustainDuration
    0.01,                            // errorThreshold (1% = 0.01)
    metricsProvider,
    backpressureProvider
);
```

## Workaround (FIXED)

**Status: ✅ FIXED**

The parameter order has been corrected in `LoadTestService.java`:

```java
// Corrected constants matching constructor order
private static final double INITIAL_TPS = 1000.0;
private static final double RAMP_INCREMENT = 500.0;  // Step size for ramp up
private static final double RAMP_DECREMENT = 500.0;  // Step size for ramp down
private static final Duration RAMP_INTERVAL = Duration.ofSeconds(10);
private static final double MAX_TPS = 10000.0;
private static final Duration SUSTAIN_DURATION = Duration.ofSeconds(30);
private static final double ERROR_THRESHOLD = 0.01;

LoadPattern loadPattern = new AdaptiveLoadPattern(
    INITIAL_TPS,           // ✅ 1000.0
    RAMP_INCREMENT,        // ✅ 500.0 (was MAX_TPS = 10000.0)
    RAMP_DECREMENT,        // ✅ 500.0 (was STEP_SIZE = 500.0)
    RAMP_INTERVAL,         // ✅ 10 seconds (was STEP_DURATION)
    MAX_TPS,               // ✅ 10000.0 (was COOLDOWN_SECONDS = 5.0)
    SUSTAIN_DURATION,      // ✅ 30 seconds (was STABILITY_WINDOW)
    ERROR_THRESHOLD,       // ✅ 0.01 (was MAX_FAILURE_RATE)
    metricsProvider,
    backpressureProvider
);
```

**Changes Made:**
- ✅ Renamed constants to match constructor parameter names
- ✅ Fixed parameter order in constructor call
- ✅ Updated all references to use new constant names
- ✅ Code compiles successfully

## Additional Issues to Verify

### 1. MetricsProvider Return Type

**Need to verify:**
- What does `pipeline.getMetricsProvider().getFailureRate()` actually return?
- Does it return percentage (0.0-100.0) or ratio (0.0-1.0)?
- If it returns ratio, the division by 100.0 in AdaptiveLoadPattern would be wrong

**Test:**
```java
MetricsProvider provider = pipeline.getMetricsProvider();
double rate = provider.getFailureRate();
log.info("Failure rate: {} (expecting 0.0-100.0 for percentage)", rate);
```

### 2. Initial State

**From source code (lines 181-190):**
```java
this.state = new AtomicReference<>(new AdaptiveState(
    Phase.RAMP_UP,
    initialTps,
    -1L,  // lastAdjustmentTime - set on first call
    -1.0,  // stableTps - not found yet
    -1L,   // phaseStartTime - set on first call
    0,     // stableIntervalsCount
    0,     // rampDownAttempts
    0L     // phaseTransitionCount
));
```

**Analysis:**
- Initial state is correct
- First call to `calculateTps()` initializes timestamps
- This is correct

### 3. Adjustment Timing

**From source code (lines 288-293):**
```java
long timeSinceLastAdjustment = elapsedMillis - current.lastAdjustmentTime();
if (timeSinceLastAdjustment >= rampInterval.toMillis()) {
    checkAndAdjust(elapsedMillis);
    current = state.get(); // Refresh after adjustment
}
```

**Analysis:**
- Adjustments only happen when interval elapses
- This is correct - prevents too-frequent adjustments

## Testing Strategy

### Step 1: Fix Parameter Order
1. Update `LoadTestService.java` to use correct parameter order
2. Rebuild and test
3. Verify TPS starts at 1000 and ramps up correctly

### Step 2: Verify MetricsProvider
1. Add logging to see what `getFailureRate()` returns
2. Verify it's percentage (0.0-100.0) not ratio (0.0-1.0)
3. If ratio, we need to report this as a library issue

### Step 3: Test Adaptive Behavior
1. Start at 1000 TPS
2. Verify it ramps up by 500 TPS every 10 seconds
3. Verify it ramps down when backpressure ≥ 0.7 or errors ≥ 1%
4. Verify it finds stable point and sustains

### Step 4: Monitor Metrics
1. Check `vajrapulse.adaptive.current_tps` gauge
2. Check `vajrapulse.adaptive.phase` gauge
3. Verify phase transitions are correct

## Recommended Fix

**Immediate Action:**
1. Fix parameter order in `LoadTestService.java`
2. Test with corrected parameters
3. Document the correct usage pattern

**Library Improvement Suggestion:**
1. Consider a builder pattern for AdaptiveLoadPattern to prevent parameter order errors
2. Add validation warnings if parameters seem incorrect (e.g., rampIncrement > maxTps)
3. Add Javadoc examples showing correct usage

## Conclusion

**Primary Issue:** Parameter mapping error - we're passing parameters in wrong order, causing:
- Ramp increment = 10,000 TPS (impossible)
- Max TPS = 5.0 (way too low)
- This explains why TPS stays at ~5 TPS

**Solution:** Fix parameter order to match constructor signature.

**Secondary Issues:** None found in library code - the logic appears sound once parameters are correct.

