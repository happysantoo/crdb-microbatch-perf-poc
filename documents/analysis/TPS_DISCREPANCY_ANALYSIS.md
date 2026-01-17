# TPS Discrepancy Analysis: Logs vs Prometheus

## Problem

**Logs show:**
```
✅ RECOVERY: Conditions improved - triggering ramp-up from 100.00 to 1375.00 TPS
⬇️ RAMP_DOWN: TPS 1375.00 → 100.00 (-1275.00)
```

**Prometheus/Grafana shows:**
- Actual throughput: ~100 TPS

**Question:** Which one is correct?

## Answer: Prometheus is Correct

**The actual TPS being used is 100 TPS, not 1375 TPS.**

## Why the Discrepancy?

### The Problem

1. **`calculateTps()` is called many times per second**
   - VajraPulse execution engine calls `calculateTps()` continuously (every few milliseconds)
   - This is normal behavior - the engine needs to know current TPS frequently

2. **Recovery logic returns 1375 TPS**
   - `MinimumTpsLoadPattern` detects conditions improved
   - Returns 1375 TPS (50% of last known good TPS)
   - **But this is just a suggestion**

3. **AdaptiveLoadPattern is still in RECOVERY phase**
   - `AdaptiveLoadPattern` internally is still in RECOVERY phase
   - It returns 0 TPS (or very low TPS)
   - `MinimumTpsLoadPattern` then floors it to 100 TPS (minimum floor)

4. **PhaseLoggingLoadPattern shows the wrapper's return value**
   - `PhaseLoggingLoadPattern` logs what `MinimumTpsLoadPattern` returns (1375 TPS)
   - But `AdaptiveLoadPattern` is still in RECOVERY, so it ramps down to 100 TPS

5. **Actual TPS used is 100 TPS**
   - The execution engine uses the final TPS after all wrappers
   - Since `AdaptiveLoadPattern` is in RECOVERY, it returns 0 TPS
   - `MinimumTpsLoadPattern` floors it to 100 TPS
   - **This is what gets executed**

## The Flow

```
VajraPulse Execution Engine
    ↓ (calls calculateTps() many times per second)
PhaseLoggingLoadPattern
    ↓ (calls calculateTps())
MinimumTpsLoadPattern
    ↓ (calls calculateTps())
AdaptiveLoadPattern (still in RECOVERY phase)
    ↓ (returns 0 TPS)
MinimumTpsLoadPattern
    ↓ (floors to 100 TPS, but tries to return 1375 TPS if conditions improved)
    ↓ (but AdaptiveLoadPattern is still in RECOVERY, so it ignores the suggestion)
PhaseLoggingLoadPattern
    ↓ (logs 1375 TPS - but this is not what's actually used)
    ↓ (then logs 100 TPS when AdaptiveLoadPattern returns 0)
VajraPulse Execution Engine
    ↓ (uses 100 TPS - the actual floor)
```

## Why Recovery Isn't Working

**The Issue:**
- `MinimumTpsLoadPattern` can't force `AdaptiveLoadPattern` to transition from RECOVERY→RAMP_UP
- We can only return a higher TPS, but `AdaptiveLoadPattern` ignores it if it's still in RECOVERY phase
- `AdaptiveLoadPattern` must transition internally based on its own logic

**The Solution:**
- `AdaptiveLoadPattern` needs to be fixed in VajraPulse to properly transition from RECOVERY→RAMP_UP
- Or we need a different approach to force the transition

## How to Verify Actual TPS

**Prometheus Metrics (Most Accurate):**
```promql
# Actual TPS being executed
rate(vajrapulse_task_executions_total[1m])
```

**This shows the actual throughput, not what the load pattern suggests.**

## Fix Applied

1. **Reduced logging noise:**
   - Recovery log now only prints once per interval (5 seconds)
   - Not every `calculateTps()` call

2. **Clarified log message:**
   - Now says "attempting ramp-up" instead of "triggering ramp-up"
   - Explains that AdaptiveLoadPattern must transition internally

## Conclusion

**Prometheus/Grafana is correct:**
- Actual throughput is ~100 TPS
- This is the minimum floor being enforced
- The 1375 TPS in logs is just a suggestion that AdaptiveLoadPattern is ignoring

**The logs are misleading:**
- They show what the wrapper is trying to do
- But not what's actually being executed
- Prometheus metrics show the reality

**To fix:**
- Need to fix AdaptiveLoadPattern's RECOVERY→RAMP_UP transition
- Or use a different recovery mechanism

