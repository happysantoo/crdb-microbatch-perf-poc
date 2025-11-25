# Target Rows Issue - Analysis and Fix

## Problem

The load test stopped at **89,999 rows** instead of reaching the target of **1,000,000 rows**.

## Root Cause

The issue is **real**, not a metrics problem. Here's what happened:

1. **Load Pattern Behavior**: `RampUpLoad` ramps up to 300 threads over 5 minutes, but it **completes** when the ramp duration ends, rather than sustaining indefinitely.

2. **Pipeline Completion**: When the load pattern completes, the `MetricsPipeline.run()` method returns, causing the pipeline thread to finish.

3. **Race Condition**: The monitoring thread checks every 1 second, but if the pipeline completes naturally (after 5 minutes), it stops before reaching the target.

4. **Result**: The test stopped at 89,999 rows because:
   - The ramp completed after 5 minutes
   - The pipeline stopped executing tasks
   - The monitoring thread detected the pipeline had stopped, but it was too late

## Solution Implemented

The fix adds a **fallback mechanism**:

1. **Initial Load**: Start with `RampUpLoad` (ramp to 300 threads over 5 minutes)
2. **Check on Completion**: When the ramp completes, check if target is reached
3. **Sustained Load**: If target not reached, restart with `StaticLoad` at 300 threads for up to 24 hours
4. **Continue Monitoring**: The monitoring thread continues to check and will stop when target is reached

## Code Changes

- Added `StaticLoad` import
- Modified `executeLoadTest()` to check if target is reached after initial ramp
- Added `restartWithSustainedLoad()` method to continue with sustained load if needed

## Target Configuration

- **Current Target**: `TARGET_ROWS = 1_000_000L` (1 million rows)
- **Requirements**: Initial requirements specified 1 million rows
- **Note**: If you want to change to 100,000 rows, update `TARGET_ROWS` constant

## Verification

To verify the fix works:

1. Run the test again: `./gradlew bootRun`
2. Monitor the metrics in Grafana
3. The test should now:
   - Complete the 5-minute ramp
   - Continue with sustained load at 300 threads
   - Stop when 1,000,000 rows are inserted

## Expected Behavior

- **Ramp Phase**: 0-5 minutes, ramping from 0 to 300 threads
- **Sustain Phase**: 5+ minutes, maintaining 300 threads until 1M rows inserted
- **Total Duration**: Approximately 55-60 minutes at ~300 ops/sec

