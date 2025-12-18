# Vortex Backpressure Explained

## Overview

Vortex uses **queue-based backpressure** to prevent the system from being overwhelmed when items are submitted faster than they can be processed in batches.

## Current Configuration

Based on `CrdbInsertTask.java`:

```java
BATCH_SIZE = 50 items
LINGER_TIME = 50ms
maxQueueSize = BATCH_SIZE * 20 = 1000 items
backpressureThreshold = 0.7 (70%)
```

## How It Works

### 1. Queue Structure

```
┌─────────────────────────────────────┐
│   Vortex MicroBatcher Queue         │
│   Max Size: 1000 items              │
│   Current Depth: 0-1000 items       │
└─────────────────────────────────────┘
         ↓
    When batch ready:
    - 50 items OR
    - 50ms elapsed
         ↓
    CrdbBatchBackend.dispatch()
```

### 2. Queue Size Calculation

**Maximum Queue Size**: `1000 items`
- Calculated as: `BATCH_SIZE × 20 = 50 × 20 = 1000`
- This allows **20 batches** to be queued before backpressure triggers
- At 8000 TPS, this provides ~125ms of buffer (1000 items / 8000 TPS = 0.125s)

### 3. Backpressure Calculation

**QueueDepthBackpressureProvider** calculates backpressure using linear scaling:

```java
backpressure = queueDepth / maxQueueSize
```

**Examples:**
- Queue empty (0 items): `backpressure = 0 / 1000 = 0.0` (no pressure)
- Queue half full (500 items): `backpressure = 500 / 1000 = 0.5` (moderate pressure)
- Queue 70% full (700 items): `backpressure = 700 / 1000 = 0.7` (threshold reached)
- Queue full (1000 items): `backpressure = 1000 / 1000 = 1.0` (severe pressure)

### 4. RejectStrategy Behavior

**Threshold**: `0.7` (70% capacity = 700 items)

**When backpressure >= 0.7:**
- `RejectStrategy` **immediately rejects** new items
- `submitSync()` returns `ItemResult.Failure` with `BackpressureException`
- VajraPulse sees this as a failure and can adjust load accordingly

**Behavior:**
```java
if (backpressure >= 0.7) {
    // Reject immediately - don't even try to queue
    return ItemResult.failure(new BackpressureException(...));
} else {
    // Try to queue the item
    if (queue.offer(item)) {
        return ItemResult.success(item);
    } else {
        // Queue is full (race condition)
        return ItemResult.failure(new QueueFullException(...));
    }
}
```

## What Happens When Queue is Full

### Scenario 1: Queue Reaches 70% (700 items)

**Trigger**: Backpressure = 0.7

**Action**:
1. `RejectStrategy` checks backpressure before queuing
2. If `backpressure >= 0.7`, item is **rejected immediately**
3. `submitSync()` returns `ItemResult.Failure`
4. No item is added to queue
5. VajraPulse sees failure and may reduce TPS

**Result**: Items are rejected **before** queue fills up completely, preventing overflow.

### Scenario 2: Queue Reaches 100% (1000 items)

**Trigger**: Queue is completely full

**Action**:
1. Even if backpressure check passes (race condition), `queue.offer()` fails
2. `submitSync()` returns `ItemResult.Failure` with `QueueFullException`
3. Item is not queued

**Result**: System gracefully rejects items when queue is full.

### Scenario 3: Queue Processing Catches Up

**Trigger**: Batches are processed faster than items arrive

**Action**:
1. Queue depth decreases
2. Backpressure drops below 0.7
3. `RejectStrategy` allows new items again
4. `submitSync()` returns `ItemResult.Success`

**Result**: System automatically recovers and accepts new items.

## Flow Diagram

```
VajraPulse (8000 TPS)
    ↓
CrdbInsertTask.execute()
    ↓
batcher.submitSync(item)
    ↓
┌─────────────────────────────────┐
│ RejectStrategy.check()          │
│ backpressure = queueDepth/1000  │
│ if (backpressure >= 0.7) {      │
│     return FAILURE              │ ← Reject immediately
│ }                                │
└─────────────────────────────────┘
    ↓ (if backpressure < 0.7)
┌─────────────────────────────────┐
│ queue.offer(item)               │
│ if (success) {                  │
│     return SUCCESS              │ ← Item queued
│ } else {                        │
│     return FAILURE              │ ← Queue full (race condition)
│ }                                │
└─────────────────────────────────┘
    ↓ (when batch ready: 50 items OR 50ms)
CrdbBatchBackend.dispatch(batch)
    ↓
HikariCP Connection Pool
    ↓
CRDB Database
```

## Key Metrics

### Queue Metrics (from Vortex)

- `vortex.queue.depth` - Current number of items in queue (0-1000)
- `vortex.backpressure.rejected` - Count of items rejected due to backpressure
- `vortex.queue.wait.time` - Time items spend waiting in queue

### Application Metrics

- `crdb.submits.total` - Total submission attempts
- `crdb.submits.success` - Items successfully queued
- `crdb.submits.failure` - Items rejected (backpressure or queue full)

## Why This Design?

### 1. **Prevents Queue Overflow**
- Rejects items at 70% capacity, not 100%
- Provides safety margin for burst traffic
- Prevents memory issues from unbounded queue growth

### 2. **Immediate Feedback**
- `submitSync()` returns immediately (no blocking)
- VajraPulse sees failures right away
- Can adjust load pattern based on rejection rate

### 3. **Simple and Effective**
- Queue depth is a direct indicator of system capacity
- If queue fills up, batches aren't processing fast enough
- Root cause (DB slow, batching slow) doesn't matter - queue depth captures it all

### 4. **Automatic Recovery**
- When batches catch up, queue depth decreases
- Backpressure drops below threshold
- System automatically starts accepting items again

## Configuration Tuning

### Current Settings (Optimized for 8000 TPS)

```java
BATCH_SIZE = 50              // Items per batch
LINGER_TIME = 50ms           // Max wait time for batch
maxQueueSize = 1000          // 20 batches worth
backpressureThreshold = 0.7  // Reject at 70% capacity
```

### Adjusting for Different Loads

**Higher TPS (10K+):**
- Increase `maxQueueSize` to `BATCH_SIZE * 30` (1500 items)
- Provides more buffer for burst traffic

**Lower Latency Requirements:**
- Decrease `LINGER_TIME` to `20ms`
- Batches form faster, reducing queue wait time

**More Aggressive Backpressure:**
- Decrease `backpressureThreshold` to `0.5` (50%)
- Rejects items earlier, preventing queue buildup

**Less Aggressive Backpressure:**
- Increase `backpressureThreshold` to `0.8` (80%)
- Allows queue to fill more before rejecting

## Monitoring

### Healthy System
- Queue depth: 0-500 items (0-50% capacity)
- Backpressure: 0.0-0.5
- Rejection rate: < 1%

### Under Pressure
- Queue depth: 500-700 items (50-70% capacity)
- Backpressure: 0.5-0.7
- Rejection rate: 0-5%

### Overloaded
- Queue depth: 700-1000 items (70-100% capacity)
- Backpressure: 0.7-1.0
- Rejection rate: > 5%
- **Action**: System is rejecting items - reduce load or optimize batch processing

## Summary

**Queue Size**: 1000 items (20 batches)

**Backpressure Threshold**: 0.7 (700 items = 70% capacity)

**When Queue is Full**:
1. At 70%: Items are **rejected immediately** before queue fills
2. At 100%: Items are **rejected** if they somehow get past the 70% check
3. System **automatically recovers** when batches catch up

**Key Benefit**: Prevents queue overflow while providing immediate feedback to load testing framework for automatic load adjustment.

