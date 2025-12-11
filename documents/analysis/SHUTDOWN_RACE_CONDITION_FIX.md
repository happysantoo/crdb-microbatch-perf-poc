# Shutdown Race Condition Fix

## Problem

During shutdown, we're seeing this error:

```
java.util.concurrent.RejectedExecutionException: null
    at java.base/java.util.concurrent.ThreadPerTaskExecutor.ensureNotShutdown(...)
    at com.vajrapulse.vortex.MicroBatcher.dispatchBatch(...)
```

## Root Cause

This is a **race condition during shutdown**:

1. **Test completes** → VajraPulse `ShutdownManager` starts shutdown sequence
2. **Task teardown() called** → `batcher.close()` is invoked
3. **MicroBatcher closes executor** → ThreadPerTaskExecutor is shut down
4. **Background batches still processing** → Some batches are still in the queue or being processed
5. **Batch tries to dispatch** → Attempts to submit to already-shut-down executor
6. **RejectedExecutionException** → Executor rejects the submission

## Why This Happens

The MicroBatcher has an internal batch processor that:
- Continuously processes batches from the queue
- Uses a `ThreadPerTaskExecutor` to dispatch batches to the backend
- When `close()` is called, the executor is shut down immediately
- But there may still be batches in-flight or queued

**Timeline:**
```
T0: Test completes, pipeline.run() returns
T1: task.teardown() called → batcher.close()
T2: MicroBatcher closes executor
T3: Background batch processor tries to dispatch a batch
T4: Executor is already shut down → RejectedExecutionException
```

## Solution

We need to **wait for the batcher to finish processing** before closing. However, MicroBatcher doesn't expose a direct "wait for completion" method.

### Option 1: Wait for Queue to Empty (Recommended)

Wait for the queue to drain before closing:

```java
@Override
public void teardown() throws Exception {
    if (batcher != null) {
        // Wait for queue to drain (with timeout)
        waitForQueueToDrain(5, TimeUnit.SECONDS);
        batcher.close();
    }
}

private void waitForQueueToDrain(long timeout, TimeUnit unit) {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
        int queueDepth = batcher.diagnostics().getQueueDepth();
        if (queueDepth == 0) {
            log.debug("Queue drained, closing batcher");
            return;
        }
        try {
            Thread.sleep(100);  // Check every 100ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    log.warn("Queue did not drain within timeout, closing anyway");
}
```

### Option 2: Graceful Exception Handling

Since this is expected during shutdown, we can catch and ignore the exception:

```java
@Override
public void teardown() throws Exception {
    if (batcher != null) {
        try {
            batcher.close();
        } catch (RejectedExecutionException e) {
            // Expected during shutdown - batches may still be processing
            log.debug("RejectedExecutionException during shutdown (expected): {}", e.getMessage());
        }
    }
}
```

**Note:** This doesn't fix the root cause, but prevents the error from appearing in logs.

### Option 3: Add Shutdown Delay

Add a small delay before closing to allow batches to complete:

```java
@Override
public void teardown() throws Exception {
    if (batcher != null) {
        // Wait a bit for in-flight batches to complete
        // LINGER_TIME (50ms) + some buffer for processing
        Thread.sleep(LINGER_TIME.toMillis() * 2);  // 100ms
        batcher.close();
    }
}
```

**Note:** This is a workaround and may not always work if batches take longer.

## Recommended Solution

**Use Option 1** (wait for queue to drain) as it's the most reliable:

1. Checks actual queue depth
2. Waits for completion with timeout
3. Handles edge cases gracefully
4. Provides logging for debugging

## Implementation

See the updated `CrdbInsertTask.teardown()` method.

