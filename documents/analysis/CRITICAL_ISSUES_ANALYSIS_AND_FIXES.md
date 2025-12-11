# Critical Issues Analysis and Fixes

## Issue 1: Connection Pool Exhaustion (Still Happening)

**Problem:** Queue-only backpressure doesn't catch connection pool exhaustion early enough.

**Root Cause:**
- Queue can be empty/low while connection pool is exhausted
- Batches are processing (holding connections) but queue depth doesn't reflect this
- Queue depth measures "items waiting", not "connections in use"

**Solution:**
Even though design document recommends queue-only, we need to add connection pool monitoring for AdaptiveLoadPattern (not for Vortex rejection). This is a pragmatic fix while keeping Vortex simple.

**Fix:**
- Add connection pool backpressure to AdaptiveLoadPattern (composite with queue)
- Keep Vortex rejection simple (queue-only)
- This gives AdaptiveLoadPattern early warning before queue fills up

## Issue 2: Batches Contain 2k Rows Instead of 50

**Problem:** Batch size is configured as 50, but batches have 2000 rows.

**Root Cause Analysis:**
- BatcherConfig.batchSize(50) should limit batch size
- If batches have 2000 rows, either:
  1. Configuration not being applied
  2. Multiple batches being combined
  3. Backend receiving accumulated items

**Investigation Needed:**
- Check if Vortex is respecting batchSize
- Check if backend is receiving multiple batches
- Add logging to see actual batch sizes

**Fix:**
- Add batch size validation in backend
- Log actual batch sizes
- Verify Vortex configuration is applied

## Issue 3: 0.5M Gap Between VajraPulse Submits vs Vortex Submits

**Problem:** VajraPulse shows more submits than Vortex processes.

**Root Cause Analysis:**
- If submitSync() returns SUCCESS, item should be queued
- Gap suggests items are being accepted but not processed
- Possible causes:
  1. Items queued but batches never dispatched
  2. Items rejected after queuing (shouldn't happen with submitSync)
  3. Metrics mismatch

**Investigation Needed:**
- Compare VajraPulse execution count vs Vortex submit count
- Check if items are stuck in queue
- Verify submitSync() behavior

**Fix:**
- Ensure all rejections are visible to VajraPulse
- Track queue depth vs processed items
- Add metrics to identify gap

## Issue 4: Error Rate Increases with TPS

**Problem:** As TPS increases, error rate increases (expected) but AdaptiveLoadPattern doesn't react fast enough.

**Root Cause:**
- Error rate calculation may be all-time average (includes historical failures)
- AdaptiveLoadPattern checks every 5 seconds (may be too slow)
- Error threshold (2%) may be too high

**Solution:**
- Use recent window failure rate (last 10 seconds)
- Lower error threshold (1% instead of 2%)
- More aggressive ramp-down when errors detected

## Issue 5: True Adaptation Without Data Loss

**Problem:** Need AdaptiveLoadPattern that truly adapts without losing data.

**Requirements:**
1. All rejections visible to VajraPulse (no silent failures)
2. Fast error detection (recent window)
3. Early backpressure detection (before connection pool exhaustion)
4. Elastic recovery (ramp up when conditions improve)
5. No data loss (all items tracked)

**Solution:**
- Composite backpressure (queue + connection pool) for AdaptiveLoadPattern
- Recent window failure rate (10 seconds)
- Lower error threshold (1%)
- Better recovery logic (already implemented)
- Ensure submitSync() rejections are all visible

