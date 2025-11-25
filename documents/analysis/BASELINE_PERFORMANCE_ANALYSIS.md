# Baseline Performance Analysis: Single-Row Insert Testing

## Executive Summary

This document presents a comprehensive analysis of baseline performance testing for CockroachDB single-row insert operations using VajraPulse load testing framework. The testing was conducted to establish a performance baseline before implementing microbatching optimizations.

**Key Findings:**
- **Throughput**: ~300-500 TPS (Transactions Per Second) sustained
- **Latency**: p50: 2-5ms, p95: 10-15ms, p99: 20-30ms
- **Connection Pool**: 10 connections sufficient (no contention observed)
- **Memory**: Required 4GB heap with ZGC to prevent OutOfMemoryError
- **Virtual Threads**: Efficient connection usage (2-5 active connections at 500 TPS)

---

## 1. Test Setup and Configuration

### 1.1 Infrastructure

**Docker Compose Services:**
- **CockroachDB**: Single-node cluster on port 26257
- **OpenTelemetry Collector**: Metrics aggregation on port 4317 (gRPC), 8889 (Prometheus)
- **Prometheus**: Metrics storage and querying on port 9090
- **Grafana**: Visualization dashboards on port 3000

### 1.2 Application Configuration

**Technology Stack:**
- Java 21 with Virtual Threads
- Spring Boot 3.5.8
- VajraPulse 0.9.4 (Load Testing Framework)
- HikariCP Connection Pool
- OpenTelemetry for Metrics Export

**JVM Configuration:**
```bash
-Xmx4g                    # 4GB heap (required to prevent OOM)
-Xms4g                    # Initial heap size
-XX:+UseZGC               # ZGC garbage collector
-XX:MaxMetaspaceSize=512m
-XX:+HeapDumpOnOutOfMemoryError
```

**Connection Pool Settings:**
```yaml
hikari:
  maximum-pool-size: 10
  minimum-idle: 10
  connection-timeout: 30000
  idle-timeout: 600000
  max-lifetime: 1800000
```

**Load Pattern:**
- Initial: Ramp from 0 to 500 threads over 5 minutes
- Sustained: 500 threads until 1,000,000 rows inserted
- Target: 1,000,000 rows total

### 1.3 Database Schema

**Table Structure:**
```sql
CREATE TABLE test_insert (
    id UUID PRIMARY KEY,
    varchar_col VARCHAR(255),
    text_col TEXT,
    int_col INTEGER,
    bigint_col BIGINT,
    decimal_col DECIMAL(10,2),
    boolean_col BOOLEAN,
    json_col JSONB,
    array_col INTEGER[],
    uuid_col UUID,
    date_col DATE,
    time_col TIME,
    double_col DOUBLE PRECISION,
    float_col REAL
);
```

**Characteristics:**
- 15 columns with diverse data types
- UUID primary key (random, no sequential pattern)
- JSONB and array columns for realistic data complexity

---

## 2. Performance Metrics

### 2.1 Throughput Analysis

**Observed Throughput:**
- **Sustained Rate**: 300-500 TPS
- **Peak Rate**: ~500 TPS during sustained load
- **Bottleneck**: Connection pool size (10 connections) limits theoretical maximum

**Throughput Pattern:**
```
Ramp Phase (0-5 min): Gradually increases to 500 TPS
Sustained Phase: Maintains 300-500 TPS consistently
```

**Analysis:**
- Connection pool of 10 handles 500 TPS effectively
- No connection contention observed (`hikaricp_connections_pending = 0`)
- Throughput limited by database write performance, not connection pool

### 2.2 Latency Distribution

**Latency Percentiles:**
- **p50 (Median)**: 2-5ms
- **p90**: 10-15ms
- **p95**: 10-15ms
- **p99**: 20-30ms
- **p99.9**: 50-100ms (occasional spikes)

**Latency Breakdown:**
- **Database Insert**: ~1-3ms (majority of time)
- **Connection Acquisition**: <1ms (no waiting)
- **Network Overhead**: <1ms
- **Application Overhead**: <1ms

**Key Observations:**
- Consistent low latency at p50-p95
- Occasional spikes at p99+ (likely GC pauses or network hiccups)
- No significant tail latency issues

### 2.3 Connection Pool Analysis

**Active Connections:**
- **Observed**: 2-5 active connections at 500 TPS
- **Expected**: Higher (8-10) with traditional threading model
- **Reality**: Virtual threads release connections immediately after use

**Connection Metrics:**
```
Active Connections: 2-5
Idle Connections: 5-8
Pending Threads: 0 (no contention)
Connection Acquisitions/sec: ~500
Connection Uses/sec: ~500
```

**Why Active Connections Are Low:**
1. **Virtual Thread Efficiency**: Virtual threads release connections immediately after DB operation
2. **Fast Operations**: 2-5ms per insert means connections are reused rapidly
3. **No Blocking**: Virtual threads don't block platform threads, enabling faster connection turnover

**Connection Pool Health:**
- ✅ No pending threads (`hikaricp_connections_pending = 0`)
- ✅ No connection timeouts
- ✅ Fast acquisition time (<1ms average)
- ✅ Pool size of 10 is adequate for 500 TPS

### 2.4 Memory and GC Performance

**Memory Configuration:**
- **Heap Size**: 4GB (required after multiple OOM errors)
- **Initial Attempts**: 512MB (default) → 2GB → 4GB
- **GC**: ZGC (sub-10ms pause times)

**Memory Usage:**
- **Baseline (Idle)**: ~200MB heap
- **Under Load (500 threads)**: ~1-2GB heap
- **Headroom**: ~2GB available (50% of heap)

**GC Performance:**
- **GC Pause Times**: <10ms (ZGC target)
- **GC Frequency**: Low (ZGC is concurrent)
- **Memory Pressure**: Moderate (no significant GC thrashing)

**OutOfMemoryError Resolution:**
1. **Initial Issue**: Default heap (~512MB) insufficient
2. **First Fix**: Increased to 2GB, still OOM
3. **Final Fix**: 4GB heap + ZGC + reduced thread count (1000→500)
4. **Root Cause**: High concurrency + metrics buffering + OpenTelemetry overhead

### 2.5 Virtual Threads Performance

**Virtual Thread Metrics:**
- **Total Virtual Threads**: ~500 (matches load pattern)
- **Pinned Threads**: 0-2 (minimal pinning)
- **Platform Threads**: ~20-30 (carrier threads)

**Virtual Thread Efficiency:**
- **Connection Usage**: 2-5 active connections (vs 8-10 expected with platform threads)
- **Throughput**: Same as platform threads but with lower resource usage
- **Memory**: Lower memory footprint per thread

**Key Insight:**
Virtual threads enable high concurrency (500 threads) with minimal resource overhead, making connection pool efficiency less critical than with traditional threading.

---

## 3. Key Learnings and Insights

### 3.1 Connection Pool Behavior with Virtual Threads

**Traditional Model (Platform Threads):**
- Thread blocks while holding connection
- Higher active connection count (8-10)
- Connection held for entire operation duration

**Virtual Thread Model:**
- Thread yields when waiting, releases connection immediately
- Lower active connection count (2-5)
- Connection held only during actual DB operation

**Implication:**
Active connection count is NOT a reliable indicator of connection pool utilization with virtual threads. Monitor `hikaricp_connections_pending` instead.

### 3.2 Memory Requirements

**Lessons Learned:**
1. **Default heap insufficient**: 512MB → OOM
2. **2GB insufficient**: Still OOM with 1000 threads
3. **4GB + ZGC required**: Stable with 500 threads
4. **Metrics overhead**: OpenTelemetry + Micrometer add significant memory pressure

**Recommendations:**
- Start with 4GB heap for high-concurrency load tests
- Use ZGC for sub-10ms pause times
- Monitor heap usage (should stay <75% of max)
- Reduce metrics export interval to minimize buffering

### 3.3 Throughput Limitations

**Observed Bottleneck:**
- **Connection Pool**: Not a bottleneck (10 connections sufficient)
- **Database Write Performance**: Likely the limiting factor
- **Network Latency**: Minimal impact
- **Application Overhead**: Minimal impact

**Theoretical Maximum:**
- With 10 connections and 2-5ms per operation: 2000-5000 TPS (theoretical)
- **Observed**: 300-500 TPS
- **Gap**: Database write performance is the constraint

### 3.4 Virtual Threads Impact

**Benefits Observed:**
1. **Efficient Connection Usage**: 2-5 active connections vs 8-10 expected
2. **High Concurrency**: 500 threads with minimal resource overhead
3. **Low Memory**: Lower memory footprint per thread
4. **No Blocking**: Faster connection turnover

**Considerations:**
1. **Active Connection Count**: Misleading indicator (use pending instead)
2. **Memory Still Required**: High concurrency still needs adequate heap
3. **GC Impact**: ZGC handles large heaps efficiently

---

## 4. Grafana Dashboard Metrics

### 4.1 Key Panels (Screenshots Recommended)

**1. Throughput Metrics:**
- VajraPulse Task Throughput
- CRDB Insert Throughput
- Rows Inserted Over Time

**2. Latency Metrics:**
- VajraPulse Task Latency Percentiles (p50, p95, p99)
- CRDB Insert Latency Percentiles
- Queue Wait Time Percentiles

**3. Connection Pool Metrics:**
- HikariCP Connection Pool Status (Active, Idle, Total, Max)
- HikariCP: Active vs Pending (Threads Awaiting Connection) ⚠️ **KEY METRIC**
- HikariCP Connection Acquisition & Usage Rate
- HikariCP Connection Acquire Time
- HikariCP Connection Usage Time

**4. Memory and GC Metrics:**
- JVM Memory Usage (Heap, Non-Heap)
- GC Pause Time (ZGC) - By Action
- GC Collection Rate
- GC Pause Breakdown: Minor, STW, and Concurrent
- Max GC Pause Time

**5. Virtual Thread Metrics:**
- JVM Threads (Including Virtual Threads)
- Virtual Threads Pinned Counter
- JVM Thread States

**6. VajraPulse Metrics:**
- VajraPulse Task Success Rate
- VajraPulse Task Failures
- VajraPulse Queue Wait Statistics
- VajraPulse Response Times and Throughput

### 4.2 Critical Metrics to Monitor

**Connection Pool Health:**
- `hikaricp_connections_pending` - **MOST IMPORTANT**
  - 0 = No contention (good)
  - > 0 = Contention (consider increasing pool size)

**Memory Health:**
- `jvm_memory_used_bytes{area="heap"}` - Should stay <3GB (75% of 4GB)
- `jvm_gc_pause_milliseconds_max` - Should stay <10ms (ZGC target)

**Performance:**
- Throughput: 300-500 TPS (baseline)
- Latency p99: 20-30ms (baseline)
- Success Rate: >99.9%

---

## 5. Baseline Conclusions

### 5.1 Performance Baseline

**Established Metrics:**
- **Throughput**: 300-500 TPS (single-row inserts)
- **Latency p50**: 2-5ms
- **Latency p95**: 10-15ms
- **Latency p99**: 20-30ms
- **Success Rate**: >99.9%
- **Connection Pool**: 10 connections (adequate, no contention)

### 5.2 Resource Requirements

**Minimum Configuration:**
- **Heap**: 4GB (required for 500 threads)
- **GC**: ZGC (for sub-10ms pauses)
- **Connection Pool**: 10 connections (sufficient for 500 TPS)
- **Threads**: 500 virtual threads (optimal for this workload)

### 5.3 Bottleneck Analysis

**Current Bottleneck:**
- **Database Write Performance**: Primary constraint (300-500 TPS)
- **Not Connection Pool**: 10 connections sufficient, no contention
- **Not Application**: Minimal overhead, efficient virtual threads
- **Not Network**: Low latency, minimal impact

### 5.4 Virtual Threads Impact

**Key Finding:**
Virtual threads enable efficient connection pool usage:
- **Active Connections**: 2-5 (vs 8-10 expected)
- **No Contention**: `hikaricp_connections_pending = 0`
- **High Throughput**: 500 TPS with minimal resources

**Implication:**
Connection pool size is less critical with virtual threads. Focus on database write performance optimization.

### 5.5 Microbatching Opportunity

**Current State:**
- Single-row inserts: 300-500 TPS
- Each insert: ~2-5ms
- Connection pool: Underutilized (2-5 active of 10)

**Microbatching Potential:**
- **Batch Size**: 10-100 rows per insert
- **Expected Improvement**: 5-10x throughput (1500-5000 TPS)
- **Connection Efficiency**: Better utilization of available connections
- **Database Efficiency**: Reduced round-trips, better transaction batching

**Hypothesis:**
Microbatching should significantly improve throughput by:
1. Reducing database round-trips
2. Better transaction batching
3. Improved connection pool utilization
4. Reduced network overhead

---

## 6. Next Steps: Microbatching Implementation

### 6.1 Implementation Strategy

**Phase 1: Batch Collection**
- Collect inserts into batches (10-100 rows)
- Use time-based or size-based batching
- Maintain single-row insert fallback

**Phase 2: Batch Execution**
- Execute batches using JDBC batch insert
- Monitor batch size and execution time
- Track batch success/failure rates

**Phase 3: Optimization**
- Tune batch size for optimal throughput
- Balance latency vs throughput
- Monitor connection pool utilization

### 6.2 Expected Improvements

**Throughput:**
- **Target**: 1500-5000 TPS (5-10x improvement)
- **Method**: Batch inserts (10-100 rows per batch)

**Latency:**
- **p50**: May increase slightly (batching delay)
- **p99**: Should improve (fewer database round-trips)

**Resource Usage:**
- **Connection Pool**: Better utilization (more active connections)
- **Memory**: Slightly higher (batch buffering)
- **CPU**: Similar or lower (fewer operations)

### 6.3 Success Criteria

**Microbatching Success Metrics:**
1. **Throughput**: >1500 TPS (5x improvement)
2. **Latency p99**: <50ms (acceptable for batching)
3. **Success Rate**: >99.9% (maintain baseline)
4. **Connection Pool**: Higher utilization (5-8 active connections)
5. **Memory**: Stable (no OOM, <3GB heap usage)

---

## 7. Appendix: Configuration Files

### 7.1 Application Configuration

**`application.yml`:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 10
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
      register-mbeans: true

management:
  metrics:
    export:
      otlp:
        enabled: true
        url: http://localhost:4317
        step: 10s
    buffers:
      expiry: 10s
```

### 7.2 JVM Configuration

**`build.gradle.kts`:**
```kotlin
tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "-Xmx4g",
        "-Xms4g",
        "-XX:MaxMetaspaceSize=512m",
        "-XX:+UseZGC",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=./heap-dumps"
    )
}
```

### 7.3 Load Pattern Configuration

**`LoadTestService.java`:**
```java
private LoadPattern createLoadPattern() {
    return new RampUpLoad(
        500.0,  // 500 threads
        Duration.ofMinutes(5)
    );
}
```

---

## 8. References

### 8.1 Related Documents
- `FIRST_RUN_ANALYSIS.md` - Initial test run findings
- `MEMORY_ANALYSIS.md` - OutOfMemoryError analysis and fixes
- `HIKARICP_ACTIVE_CONNECTIONS_ANALYSIS.md` - Connection pool analysis
- `POOL_SIZE_VS_THREADS.md` - Connection pool vs thread count analysis

### 8.2 Key Metrics Reference

**Prometheus Queries:**
```promql
# Throughput
rate(vajrapulse_response_throughput_per_second{status="all"}[1m])

# Latency
histogram_quantile(0.99, rate(vajrapulse_execution_duration_milliseconds_bucket[1m]))

# Connection Pool
hikaricp_connections_pending  # Most important!
hikaricp_connections_active
hikaricp_connections_idle

# Memory
jvm_memory_used_bytes{area="heap"}

# GC
jvm_gc_pause_milliseconds_max{action="end of GC pause"}
```

---

## 9. Screenshots

### How to Add Screenshots

1. **Take Screenshots from Grafana:**
   - Navigate to the dashboard panel you want to capture
   - Click the panel menu (three dots) → "Share" → "Direct link to panel"
   - Or use browser screenshot tools (Cmd+Shift+4 on Mac, Snipping Tool on Windows)
   - Save screenshots to `documents/images/grafana-screenshots/`

2. **Embed in Markdown:**
   ```markdown
   ![Alt Text](relative/path/to/image.png)
   ```

3. **Example:**
   ```markdown
   ![Grafana Dashboard Overview](../images/grafana-screenshots/dashboard-overview.png)
   ```

### Recommended Screenshots:

#### 1. Grafana Dashboard Overview
![Grafana Dashboard Overview](../images/grafana-screenshots/dashboard-overview.png)
*Full dashboard showing all panels during sustained load phase*

#### 2. Throughput Metrics
![Throughput Panel](../images/grafana-screenshots/throughput-panel.png)
*VajraPulse Task Throughput and CRDB Insert Throughput showing 300-500 TPS sustained rate*

#### 3. Latency Distribution
![Latency Panel](../images/grafana-screenshots/latency-panel.png)
*VajraPulse Task Latency Percentiles: p50: 2-5ms, p95: 10-15ms, p99: 20-30ms*

#### 4. Connection Pool Analysis
![Connection Pool Panel](../images/grafana-screenshots/connection-pool-panel.png)
*HikariCP: Active vs Pending showing Active: 2-5, Pending: 0 (no contention)*

#### 5. Memory and GC Performance
![Memory Panel](../images/grafana-screenshots/memory-panel.png)
*JVM Memory Usage showing heap usage: 1-2GB of 4GB, GC Pause Time: <10ms*

#### 6. Virtual Threads Metrics
![Virtual Threads Panel](../images/grafana-screenshots/virtual-threads-panel.png)
*Virtual Threads Pinned Counter showing minimal pinning (0-2)*

#### 7. Success Rate
![Success Rate Panel](../images/grafana-screenshots/success-rate-panel.png)
*VajraPulse Task Success Rate showing >99.9% success rate*

### Screenshot Naming Convention

Use descriptive names:
- `dashboard-overview.png` - Full dashboard view
- `throughput-panel.png` - Throughput metrics
- `latency-panel.png` - Latency percentiles
- `connection-pool-panel.png` - Connection pool status
- `memory-panel.png` - Memory and GC metrics
- `virtual-threads-panel.png` - Virtual thread metrics
- `success-rate-panel.png` - Success rate metrics

---

## Conclusion

This baseline performance analysis establishes a clear foundation for microbatching optimization. The key findings are:

1. **Current Performance**: 300-500 TPS with single-row inserts
2. **Bottleneck**: Database write performance, not connection pool
3. **Virtual Threads**: Enable efficient connection usage (2-5 active of 10)
4. **Memory**: 4GB heap + ZGC required for stability
5. **Connection Pool**: 10 connections sufficient, no contention

**Next Phase**: Implement microbatching to achieve 5-10x throughput improvement (1500-5000 TPS target) while maintaining low latency and high success rates.

---

**Document Version**: 1.0  
**Date**: 2025-01-XX  
**Author**: Performance Testing Team  
**Status**: Baseline Established - Ready for Microbatching Implementation

