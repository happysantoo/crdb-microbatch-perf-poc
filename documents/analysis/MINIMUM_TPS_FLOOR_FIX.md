# Minimum TPS Floor Fix

## Problem

The test was stopping when `AdaptiveLoadPattern` ramped down to 0.0 TPS:

```
🔽 RAMP_DOWN: TPS decreased from 500.00 to 0.00 (-500.00)
Load pattern returned 0.0 TPS after 180020 iterations and 60081ms, stopping execution
```

**Root Cause:**
- `RAMP_DECREMENT = 1000.0` is very aggressive
- When TPS is 500 and it ramps down by 1000, it goes to -500, which gets clamped to 0.0
- Execution engine stops when TPS reaches 0.0

## Solution

Implemented `MinimumTpsLoadPattern` wrapper that enforces a minimum TPS floor (100.0 TPS) to prevent the test from stopping.

### Implementation

**New Class**: `MinimumTpsLoadPattern.java`

```java
public class MinimumTpsLoadPattern implements LoadPattern {
    private final LoadPattern delegate;
    private final double minimumTps;  // 100.0 TPS
    
    @Override
    public double calculateTps(long elapsedMillis) {
        double tps = delegate.calculateTps(elapsedMillis);
        
        // Enforce minimum TPS floor
        if (tps < minimumTps) {
            return minimumTps;  // Return floor instead of 0
        }
        
        return tps;
    }
}
```

**Integration**: Wrapped `AdaptiveLoadPattern` with `MinimumTpsLoadPattern`:

```java
// Wrap to enforce minimum TPS floor
LoadPattern minTpsPattern = new MinimumTpsLoadPattern(adaptivePattern, MINIMUM_TPS);

// Wrap to log phase transitions
LoadPattern loadPattern = new PhaseLoggingLoadPattern(minTpsPattern);
```

## Configuration

- **Minimum TPS**: `100.0` (configurable via `MINIMUM_TPS` constant)
- **Rationale**: 
  - Low enough to allow significant ramp-down when needed
  - High enough to keep the test running and allow recovery
  - Prevents test from stopping

## Expected Behavior

### Before Fix

```
RAMP_DOWN: 500.00 → 0.00
Execution Engine: "TPS is 0.0, stopping test"
Test stops ❌
```

### After Fix

```
RAMP_DOWN: 500.00 → 0.00 (from AdaptiveLoadPattern)
MinimumTpsLoadPattern: 0.00 → 100.00 (enforced floor)
Execution Engine: "TPS is 100.0, continuing test"
Test continues ✅
```

### Recovery Scenario

```
1. High backpressure → RAMP_DOWN → TPS goes to 0.0
2. MinimumTpsLoadPattern enforces 100.0 TPS floor
3. Test continues at 100.0 TPS
4. Backpressure decreases → RAMP_UP → TPS increases
5. Cycle continues (ramp up/down continuously)
```

## Benefits

1. **Test Continues**: Never stops due to 0.0 TPS
2. **Recovery Enabled**: System can recover when conditions improve
3. **Continuous Operation**: Ramp-up and ramp-down cycles continue indefinitely
4. **Safety Net**: Prevents accidental test termination

## Trade-offs

- **Slightly Higher Minimum Load**: Test runs at minimum 100 TPS even when system is under severe pressure
- **May Mask Issues**: If system can't handle 100 TPS, test will still try (but will fail with errors)
- **Recovery Time**: May take longer to fully recover (starts from 100 TPS instead of 0)

## Monitoring

Watch for:
- How often minimum TPS floor is enforced (check logs for "Enforcing minimum TPS floor")
- Recovery time from minimum TPS back to higher TPS
- Whether 100 TPS is appropriate minimum (may need adjustment)

## Future Enhancements

1. **Adaptive Minimum**: Adjust minimum TPS based on system capacity
2. **Gradual Recovery**: Instead of jumping to 100 TPS, gradually increase from 0
3. **Configurable Minimum**: Make minimum TPS configurable via properties

