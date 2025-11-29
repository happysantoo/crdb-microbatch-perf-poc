# Microbatching Implementation - Phase 2

## Overview

This document describes the implementation of microbatching using the Vortex library for CockroachDB insert operations. The implementation replaces single-row inserts with batched inserts (50 rows or 50ms) to improve throughput.

## Implementation Details

### 1. Vortex Library Integration

**Dependency Added:**
```kotlin
implementation("com.vajrapulse:vortex:0.0.1")
```

**Package Structure:**
- `com.vajrapulse.vortex.Backend<T>` - Backend interface for batch processing
- `com.vajrapulse.vortex.MicroBatcher<T>` - Main batching class
- `com.vajrapulse.vortex.BatcherConfig` - Configuration builder
- `com.vajrapulse.vortex.BatchResult<T>` - Result containing successes and failures
- `com.vajrapulse.vortex.SuccessEvent<T>` - Successful item event
- `com.vajrapulse.vortex.FailureEvent<T>` - Failed item event

### 2. Components Created

#### CrdbBatchBackend
**Location:** `src/main/java/com/crdb/microbatch/backend/CrdbBatchBackend.java`

**Purpose:** Implements the `Backend<TestInsert>` interface to process batches of inserts.

**Key Features:**
- Processes batches of `TestInsert` records
- Uses JDBC batch update for efficient database operations
- Tracks batch-level and row-level metrics
- Handles partial batch failures gracefully

**Metrics Exposed:**
- `crdb.batches.total` - Total batches dispatched
- `crdb.batches.success` - Successful batches
- `crdb.batches.failure` - Failed batches
- `crdb.batch.rows.total` - Total rows in batches
- `crdb.batch.rows.success` - Successfully inserted rows
- `crdb.batch.rows.failure` - Failed rows
- `crdb.batch.duration` - Batch insert operation duration

#### Updated CrdbInsertTask
**Location:** `src/main/java/com/crdb/microbatch/task/CrdbInsertTask.java`

**Changes:**
- Replaced single-row insert with MicroBatcher submission
- Submits individual `TestInsert` records to the batcher
- Waits for `CompletableFuture<BatchResult<TestInsert>>` completion
- Tracks submit-level metrics

**Configuration:**
- **Batch Size**: 50 rows
- **Linger Time**: 50ms
- **Atomic Commit**: false (allows partial batch success)
- **Max Concurrency**: 10 concurrent batch dispatches

**Metrics Exposed:**
- `crdb.submits.total` - Total submits to batcher
- `crdb.submits.success` - Successful submits
- `crdb.submits.failure` - Failed submits
- `crdb.submit.latency` - Time from submit to completion
- `crdb.batch.wait` - Time waiting for batch completion

#### Updated TestInsertRepository
**Location:** `src/main/java/com/crdb/microbatch/repository/TestInsertRepository.java`

**New Method:**
- `insertBatch(List<TestInsert> testInserts)` - JDBC batch insert for multiple rows

**Implementation:**
- Uses `JdbcTemplate.batchUpdate()` for efficient batch operations
- Handles array columns properly for CockroachDB
- Returns array of update counts (one per row)

### 3. Batch Configuration

**BatcherConfig:**
```java
BatcherConfig config = BatcherConfig.builder()
    .batchSize(50)                    // 50 rows per batch
    .lingerTime(Duration.ofMillis(50)) // 50ms max wait
    .atomicCommit(false)               // Allow partial success
    .maxConcurrency(10)                // Max concurrent batches
    .build();
```

**Batching Strategy:**
- **Size-based**: Batch dispatches when 50 rows accumulated
- **Time-based**: Batch dispatches after 50ms even if <50 rows
- **Whichever comes first**: Ensures low latency and high throughput

### 4. Metrics Collection

#### Vortex Built-in Metrics
The Vortex library automatically exposes metrics via Micrometer:
- `vortex.requests.submitted` - Total requests submitted
- `vortex.batches.dispatched` - Total batches dispatched
- `vortex.requests.succeeded` - Total successful requests
- `vortex.requests.failed` - Total failed requests
- `vortex.queue.depth` - Current queue depth (gauge)
- `vortex.batch.dispatch.latency` - Time to dispatch a batch
- `vortex.request.wait.latency` - Time a request waits before batching

#### Custom Metrics
Additional metrics for CRDB-specific tracking:
- Batch-level metrics (counts, success/failure)
- Row-level metrics (counts, success/failure)
- Submit-level metrics (latency, wait time)

### 5. CompletableFuture Handling

**Pattern:**
```java
CompletableFuture<BatchResult<TestInsert>> future = batcher.submit(testInsert);
BatchResult<TestInsert> result = future.get(); // Blocks until batch completes
```

**Metrics Captured:**
- **Submit Latency**: Time from `submit()` to `get()` completion
- **Batch Wait Time**: Time waiting for batch to complete
- **Success/Failure Counts**: From `BatchResult`

**Error Handling:**
- Catches exceptions from `future.get()`
- Handles partial batch failures
- Converts `Throwable` to `Exception` for VajraPulse compatibility

## Expected Improvements

### Throughput
- **Baseline**: 300-500 TPS (single-row inserts)
- **Target**: 1500-5000 TPS (batched inserts)
- **Expected**: 5-10x improvement

### Latency
- **p50**: May increase slightly (batching delay)
- **p99**: Should improve (fewer database round-trips)
- **Batch Wait**: <50ms (linger time)

### Resource Usage
- **Connection Pool**: Better utilization (more active connections)
- **Memory**: Slightly higher (batch buffering)
- **CPU**: Similar or lower (fewer operations)

## Testing Strategy

### Functional Testing
1. **Batch Formation**: Verify batches form correctly (size and time triggers)
2. **Batch Execution**: Verify JDBC batch inserts work correctly
3. **Error Handling**: Test partial batch failures
4. **Metrics**: Verify all metrics are captured correctly

### Performance Testing
1. **Throughput**: Measure TPS improvement
2. **Latency**: Measure p50, p95, p99 latencies
3. **Connection Pool**: Monitor connection utilization
4. **Memory**: Monitor heap usage

### Load Test Configuration
- **Threads**: 500 (same as baseline)
- **Target**: 1,000,000 rows
- **Batch Size**: 50 rows
- **Linger Time**: 50ms

## Next Steps

1. **Run Load Test**: Execute with microbatching enabled
2. **Compare Metrics**: Compare with baseline (single-row inserts)
3. **Analyze Results**: Document throughput and latency improvements
4. **Optimize**: Tune batch size and linger time if needed
5. **Update Dashboard**: Add batch-specific metrics to Grafana

## References

- [Vortex Library](https://github.com/happysantoo/vortex)
- Baseline Performance Analysis: `documents/analysis/BASELINE_PERFORMANCE_ANALYSIS.md`

