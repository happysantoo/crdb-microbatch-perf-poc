# Test Initialization and Logging Improvements

## Overview

Added comprehensive improvements for test initialization, logging, and debugging to address issues with:
1. Table not being cleared before test
2. No visibility into what's happening (Grafana graphs not showing data)
3. Test stopping at 169k records without errors
4. No error/warning logging

## Changes Made

### 1. **Database Initialization**

**Added to `TestInsertRepository`:**
- `truncateTable()` - Truncates table (faster than DELETE)
- `dropAndRecreateTable()` - Drops and recreates table (fastest for large tables)

**Added to `LoadTestService`:**
- `initializeDatabase()` - Called at start of test
- Automatically detects existing rows and clears them
- Uses DROP/CREATE for speed (faster than TRUNCATE for large tables)

**Implementation:**
```java
private void initializeDatabase() {
    try {
        long existingCount = repository.getCount();
        if (existingCount > 0) {
            log.info("Found {} existing rows, dropping and recreating table...", existingCount);
            repository.dropAndRecreateTable();
            log.info("Table recreated successfully");
        } else {
            log.info("Table is empty, no cleanup needed");
        }
    } catch (Exception e) {
        log.error("Failed to initialize database", e);
        throw new RuntimeException("Database initialization failed", e);
    }
}
```

### 2. **Logging Configuration**

**Updated `application.yml`:**
```yaml
logging:
  level:
    root: INFO                    # Changed from WARN
    com.crdb.microbatch: INFO    # Changed from WARN
    com.crdb.microbatch.service: INFO
    com.crdb.microbatch.backend: INFO
    com.crdb.microbatch.repository: INFO
    com.crdb.microbatch.task: INFO
    com.vajrapulse: WARN
    org.springframework: WARN
    org.hikaricp: INFO           # Added for connection pool visibility
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

**Benefits:**
- See INFO level logs from our application
- See HikariCP connection pool logs
- Formatted timestamps and thread names
- Still suppress verbose Spring/VajraPulse logs

### 3. **Comprehensive Logging**

**LoadTestService:**
- Startup/shutdown logging
- Database initialization logging
- Pipeline thread start/stop logging
- Progress logging every 10 seconds or 10k rows
- Shows both in-memory count and database count
- Shows progress percentage

**Example logs:**
```
2024-01-15 10:00:00 [main] INFO  LoadTestService - === Starting CRDB Microbatch Load Test ===
2024-01-15 10:00:00 [main] INFO  LoadTestService - Target: 1000000 rows
2024-01-15 10:00:01 [main] INFO  LoadTestService - Found 169000 existing rows, dropping and recreating table...
2024-01-15 10:00:02 [main] INFO  LoadTestService - Table recreated successfully
2024-01-15 10:00:05 [RampUp-Pipeline] INFO  LoadTestService - Pipeline thread started
2024-01-15 10:00:15 [LoadTest-Monitor] INFO  LoadTestService - Progress: 50000 / 1000000 (5.00%) | In-memory: 50000 | DB count: 50000
```

**CrdbBatchBackend:**
- Error logging with stack traces
- Warning logs for batch failures
- Warning logs for individual item failures
- Batch size and failure count logging

**Example logs:**
```
2024-01-15 10:00:20 [pool-1-thread-5] WARN  CrdbBatchBackend - Item 3 in batch failed: rowsAffected=-3
2024-01-15 10:00:20 [pool-1-thread-5] WARN  CrdbBatchBackend - Batch had 1 failures out of 50 items
2024-01-15 10:00:25 [pool-1-thread-8] ERROR CrdbBatchBackend - Batch insert failed for 50 items: Connection timeout
```

### 4. **Enhanced Monitoring Thread**

**Improvements:**
- Logs progress every 10 seconds OR every 10k rows (whichever comes first)
- Shows both in-memory count and database count
- Shows progress percentage
- Better error handling

**Why this helps:**
- Can see if inserts are actually happening
- Can detect if in-memory count differs from DB count (indicates issues)
- Can see progress in real-time
- Can identify when/why test stops

### 5. **Error Visibility**

**Before:**
- Errors were caught but not logged
- Console showed nothing
- No way to debug issues

**After:**
- All errors logged with stack traces
- Warnings for partial failures
- Progress logging shows what's happening
- Database count verification

## Why Test Might Stop at 169k

**Possible causes (now visible with logging):**

1. **Connection Pool Exhaustion**
   - HikariCP logs will show connection timeouts
   - Check for "Connection is not available" errors

2. **Database Lock/Deadlock**
   - CRDB might be locking
   - Check for transaction timeout errors

3. **Memory Issues**
   - OutOfMemoryError (should be visible now)
   - Check GC logs

4. **Pipeline Thread Failure**
   - Now logged: "Pipeline thread failed"
   - Check stack trace

5. **Monitoring Thread Issue**
   - Should see "Monitoring thread interrupted"
   - Check if thread is still alive

## Verification Steps

1. **Check Logs:**
   ```bash
   ./gradlew bootRun 2>&1 | tee test.log
   ```

2. **Look for:**
   - "Table recreated successfully" - confirms initialization
   - Progress logs every 10 seconds
   - Any ERROR or WARN messages
   - "Pipeline thread failed" - indicates why it stopped

3. **Check Database:**
   ```sql
   SELECT COUNT(*) FROM test_inserts;
   ```

4. **Compare Counts:**
   - In-memory count (from logs)
   - Database count (from SQL)
   - If different, indicates transaction/commit issues

## Expected Behavior

**With these changes:**
- ✅ Table cleared at start
- ✅ Progress visible every 10 seconds
- ✅ Errors logged with stack traces
- ✅ Warnings for partial failures
- ✅ Both in-memory and DB counts shown
- ✅ Can identify why test stops

## Next Steps

1. Run test and watch logs
2. Check for any ERROR/WARN messages
3. Compare in-memory vs DB counts
4. Check HikariCP connection pool logs
5. Investigate any specific errors found

