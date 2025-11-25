# Throughput Optimization Analysis

## Current Situation

- **Observed Throughput**: ~300 TPS (Transactions Per Second)
- **Load Pattern**: 300 threads
- **HikariCP Connection Pool**: 10 connections (max)
- **Bottleneck Identified**: Connection pool size is the limiting factor

## Problem Analysis

With 300 virtual threads trying to execute database inserts but only **10 database connections** available, most threads are waiting for a connection from the pool. This creates a bottleneck:

- **300 threads** competing for **10 connections**
- Each connection can only handle one insert at a time
- Average connection usage time determines max throughput
- If each insert takes ~33ms, max throughput = 10 connections / 0.033s = ~300 TPS

## Optimization Opportunities

### 1. **Increase HikariCP Connection Pool Size** (HIGHEST IMPACT)

**Current**: 10 connections  
**Recommended**: 50-100 connections (or match thread count)

**Why**: With 300 threads, we need more connections to avoid connection pool contention.

**Configuration**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 100
      minimum-idle: 50
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### 2. **Increase Thread Count** (MEDIUM IMPACT)

**Current**: 300 threads  
**Recommended**: 500-1000 threads (with increased connection pool)

**Why**: Virtual threads are lightweight, so we can have many more. The limit should be based on:
- Database connection pool size
- Database server capacity
- Network bandwidth

**Configuration**: Update `LoadTestService.java`:
```java
return new RampUpLoad(1000.0, Duration.ofMinutes(5));
```

### 3. **Optimize Connection Pool Settings** (LOW-MEDIUM IMPACT)

- **Connection Timeout**: Ensure threads don't wait too long
- **Idle Timeout**: Keep connections warm
- **Max Lifetime**: Prevent stale connections

### 4. **Database-Level Optimizations** (MEDIUM IMPACT)

- **CockroachDB Settings**: Check if there are any rate limits
- **Network Latency**: Ensure low latency between app and DB
- **Batch Size**: Currently single-row inserts (by design for microbatching test)

## Recommended Changes

### Step 1: Increase Connection Pool (Immediate Impact)

Add to `application.yml`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 100
      minimum-idle: 50
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### Step 2: Increase Thread Count (After Step 1)

Update `LoadTestService.java`:
```java
return new RampUpLoad(1000.0, Duration.ofMinutes(5));
```

### Step 3: Monitor and Adjust

- Watch HikariCP metrics: active connections, pending connections
- Watch response times: should stay low
- Watch throughput: should increase proportionally
- Watch CockroachDB: ensure it can handle the load

## Expected Results

With 100 connections and 1000 threads:
- **Expected Throughput**: 1000-3000+ TPS (depending on insert latency)
- **Connection Utilization**: Should see ~100 active connections
- **Pending Connections**: Should be near 0 (no waiting)

## Monitoring

Key metrics to watch:
- `hikaricp_connections_active` - Should approach pool size
- `hikaricp_connections_pending` - Should be 0 (no waiting)
- `vajrapulse_response_throughput_per_second` - Should increase
- `vajrapulse_execution_duration_milliseconds` - Should stay low
- `crdb_inserts_duration_milliseconds` - Database insert latency

