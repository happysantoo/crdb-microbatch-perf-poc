# JVM Dashboard Metric Fixes

## Issue
Most panels in the JVM Metrics Dashboard are showing empty/no data.

## Root Causes

1. **Process Metrics Not Enabled**: Some process metrics (`process_network_*`, `process_files_*`) may not be enabled by default in Spring Boot Actuator
2. **GC Metrics Aggregation**: GC histogram queries need proper `sum by (le, action)` aggregation
3. **Memory Pool Names**: Memory pool names depend on GC algorithm (G1, ZGC, Parallel, etc.)
4. **Metric Name Variations**: Some metrics might have different names or labels

## Fixes Applied

### 1. Memory Pool Queries
**Fixed**: Made memory pool queries more flexible to work with different GC algorithms

**Before:**
```promql
jvm_memory_used_bytes{id="G1 Old Gen"}
```

**After:**
```promql
jvm_memory_used_bytes{id=~"G1 Old Gen|G1 Survivor Space|G1 Eden Space|ZGC.*|PS Old Gen|PS Survivor Space|PS Eden Space"}
```

### 2. GC Count Query
**Fixed**: Changed from counter to increase for better visualization

**Before:**
```promql
jvm_gc_pause_seconds_count
```

**After:**
```promql
increase(jvm_gc_pause_seconds_count[1m])
```

### 3. GC Pause Latency Queries
**Fixed**: Added proper aggregation for histogram quantiles

**Before:**
```promql
histogram_quantile(0.50, rate(jvm_gc_pause_seconds_bucket[1m]))
```

**After:**
```promql
histogram_quantile(0.50, sum by (le, action) (rate(jvm_gc_pause_seconds_bucket[1m])))
```

### 4. CPU Usage
**Fixed**: Removed invalid `jvm_cpu_usage` metric, added heap usage percentage

**Before:**
```promql
jvm_cpu_usage  # This metric doesn't exist
```

**After:**
```promql
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100
```

### 5. File Descriptors
**Fixed**: Removed `rate()` from gauge metric

**Before:**
```promql
rate(process_files_open_fds[1m])  # Wrong - this is a gauge, not a counter
```

**After:**
```promql
process_files_open_fds  # Correct - gauge metric
```

### 6. Class Loading
**Fixed**: Changed unloaded classes to rate instead of total

**Before:**
```promql
jvm_classes_unloaded_classes_total  # Shows cumulative total
```

**After:**
```promql
increase(jvm_classes_unloaded_classes_total[1m])  # Shows per-second rate
```

## Metrics That May Not Be Available

The following metrics require Spring Boot Actuator process metrics to be enabled:

1. **Process Network I/O** (`process_network_receive_bytes_total`, `process_network_send_bytes_total`)
   - These are enabled by default in Spring Boot Actuator
   - If not available, the panel will show "No data"

2. **Process File Descriptors** (`process_files_open_fds`, `process_files_max_fds`)
   - Enabled by default in Spring Boot Actuator
   - If not available, the panel will show "No data"

3. **Process CPU Usage** (`process_cpu_usage`)
   - Enabled by default in Spring Boot Actuator
   - If not available, the panel will show "No data"

## Standard Micrometer JVM Metrics (Always Available)

These metrics are always available when JVM binders are configured:

### Memory Metrics
- `jvm_memory_used_bytes{area="heap"}` ✅
- `jvm_memory_max_bytes{area="heap"}` ✅
- `jvm_memory_committed_bytes{area="heap"}` ✅
- `jvm_memory_used_bytes{area="nonheap"}` ✅
- `jvm_memory_used_bytes{id="Metaspace"}` ✅
- `jvm_memory_used_bytes{id="Code Cache"}` ✅
- `jvm_memory_used_bytes{id="Compressed Class Space"}` ✅

### GC Metrics
- `jvm_gc_pause_seconds_count` ✅
- `jvm_gc_pause_seconds_sum` ✅
- `jvm_gc_pause_seconds_bucket` ✅

### Thread Metrics
- `jvm_threads_live_threads` ✅
- `jvm_threads_daemon_threads` ✅
- `jvm_threads_peak_threads` ✅
- `jvm_threads_states_threads{state="runnable"}` ✅
- `jvm_threads_states_threads{state="blocked"}` ✅
- `jvm_threads_states_threads{state="waiting"}` ✅
- `jvm_threads_states_threads{state="timed-waiting"}` ✅
- `jvm_threads_virtual_threads` ✅ (Java 21)
- `jvm_threads_virtual_peak_threads` ✅ (Java 21)
- `jvm_threads_virtual_daemon_threads` ✅ (Java 21)

### Class Loading Metrics
- `jvm_classes_loaded_classes` ✅
- `jvm_classes_unloaded_classes_total` ✅

## Verification Steps

1. **Check if metrics are being exported:**
   ```bash
   curl http://localhost:8081/actuator/prometheus | grep jvm_memory
   ```

2. **Check Prometheus is scraping:**
   - Go to Prometheus UI
   - Query: `jvm_memory_used_bytes`
   - Should return data

3. **Check Grafana data source:**
   - Verify Prometheus data source is configured correctly
   - Test the connection

4. **Check time range:**
   - Ensure time range includes when metrics were collected
   - Try "Last 15 minutes" or "Last 1 hour"

## If Metrics Still Don't Show

1. **Verify JVM binders are configured:**
   - Check `MetricsConfig.java` - should have `JvmMemoryMetrics`, `JvmGcMetrics`, `JvmThreadMetrics`

2. **Check OTLP export:**
   - Metrics go through OTLP collector → Prometheus
   - Verify OTLP collector is running and forwarding to Prometheus

3. **Check metric names in Prometheus:**
   - Go to Prometheus UI
   - Query: `{__name__=~"jvm_.*"}`
   - See what metrics are actually available

4. **Check for label mismatches:**
   - Some metrics might have different label values
   - Use `jvm_memory_used_bytes` without filters to see all available labels

## Expected Behavior After Fixes

- **JVM Memory Overview**: Should show heap and non-heap memory
- **Memory Pools**: Should show memory pools based on your GC algorithm
- **Non-Heap Memory Pools**: Should show Metaspace, Code Cache, etc.
- **Garbage Collection**: Should show GC counts, time, and pause latencies
- **Thread States**: Should show all thread states
- **CPU Usage**: Should show process and system CPU usage
- **Virtual Threads**: Should show virtual thread metrics (Java 21)
- **Class Loading**: Should show loaded and unloaded classes

If some panels still show "No data", those metrics might not be available in your setup, but the core JVM metrics should work.

