# OutOfMemoryError Analysis and Fixes

## Error Analysis

**Error**: `java.lang.OutOfMemoryError` in multiple threads:
- `http-nio-8081-Poller` - Tomcat HTTP connector
- `Thread-4` - Application thread
- `OkHttp TaskRunner` - OpenTelemetry HTTP client
- `main` - Main application thread

## Root Causes Identified

### 1. **Default JVM Heap Size Too Small**
- Spring Boot default: Often 256MB-512MB
- With 1000 threads + metrics + OpenTelemetry: Insufficient
- **Fix**: Increase heap size to 2GB+

### 2. **OpenTelemetry Metrics Buffering**
- Metrics exported every 30 seconds
- With 1000 threads generating metrics, buffer can grow large
- **Fix**: Reduce export interval or increase buffer limits

### 3. **Potential Connection Leaks**
- If connections aren't properly closed, they accumulate
- With 10 connections and high contention, leaks compound
- **Fix**: Ensure proper connection management

### 4. **Metrics Accumulation**
- Micrometer metrics accumulate over time
- Histograms and counters can grow
- **Fix**: Configure metric retention/rotation

### 5. **Virtual Thread Overhead**
- 1000 virtual threads with high concurrency
- Each thread has some memory overhead
- **Fix**: Monitor and potentially reduce thread count

## Fixes Applied

### 1. JVM Memory Configuration
- **Heap Size**: Increased to 4GB (from default ~512MB, doubled from 2GB)
- **Metaspace**: Increased to 512MB
- **GC Settings**: ZGC for optimal performance with large heaps

### 2. OpenTelemetry Configuration
- **Export Interval**: Reduced from 30s to 10s (faster buffer flush)
- **Buffer Limits**: Configured to prevent unbounded growth

### 3. Connection Pool Settings
- **Connection Timeout**: 30s (prevent hanging connections)
- **Idle Timeout**: 10 minutes (reclaim unused connections)
- **Max Lifetime**: 30 minutes (prevent stale connections)

### 4. Metrics Configuration
- **Histogram Buckets**: Limited to prevent unbounded growth
- **Metric Retention**: Configured for cleanup

## Monitoring

Watch for:
- `jvm_memory_used_bytes` - Should stay below 3GB (75% of 4GB heap)
- `jvm_gc_pause_milliseconds` - ZGC should keep pauses <10ms
- `jvm_threads_live` - Monitor thread count
- Connection pool metrics - Watch for leaks

