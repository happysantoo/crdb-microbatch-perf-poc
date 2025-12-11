# TaskLifecycle Cast Fix

## Issue

**Error**: `ClassCastException: class com.crdb.microbatch.task.CrdbInsertTask cannot be cast to class com.vajrapulse.api.Task`

## Root Cause

The code was trying to cast `CrdbInsertTask` (which implements `TaskLifecycle`) to the deprecated `Task` interface:

```java
// ❌ WRONG - Old code
var result = pipeline.run((com.vajrapulse.api.Task) task, loadPattern);
```

However, `MetricsPipeline.run()` in VajraPulse 0.9.5 expects `TaskLifecycle`, not `Task`:

```java
// From javap output:
public AggregatedMetrics run(TaskLifecycle, LoadPattern) throws Exception;
```

## Fix

**File**: `src/main/java/com/crdb/microbatch/service/LoadTestService.java`

Removed the incorrect cast:

```java
// ✅ CORRECT - Fixed code
var result = pipeline.run(task, loadPattern);
```

## Verification

1. **Compilation**: ✅ Builds successfully
2. **Method Signature**: Confirmed `MetricsPipeline.run()` expects `TaskLifecycle`
3. **Version Consistency**: All VajraPulse modules are at 0.9.5

## If You Still See the Error

If you're still getting the `ClassCastException` at runtime, it means you're running an **old JAR** that was built before this fix. To resolve:

### 1. Rebuild the Application
```bash
./gradlew clean build
```

### 2. Clear Gradle Cache (if needed)
```bash
./gradlew clean
rm -rf ~/.gradle/caches/modules-2/files-2.1/com.vajrapulse/
./gradlew build --refresh-dependencies
```

### 3. Rebuild Boot JAR
```bash
./gradlew bootJar
```

### 4. Run the New JAR
```bash
java -jar build/libs/crdb-microbatch-perf-poc.jar
```

### 5. IDE Cache (if using IDE)
- **IntelliJ IDEA**: File → Invalidate Caches / Restart
- **Eclipse**: Project → Clean → Clean all projects
- **VS Code**: Restart the IDE

## Verification Steps

1. Check the code has no cast:
   ```bash
   grep -n "pipeline.run" src/main/java/com/crdb/microbatch/service/LoadTestService.java
   ```
   Should show: `var result = pipeline.run(task, loadPattern);`

2. Verify build timestamp:
   ```bash
   ls -lh build/libs/*.jar
   ```
   Should show a recent timestamp

3. Check JAR contents (optional):
   ```bash
   jar -tf build/libs/crdb-microbatch-perf-poc.jar | grep LoadTestService
   ```

## Summary

- ✅ Code fix applied: Removed incorrect cast to `Task`
- ✅ `MetricsPipeline.run()` correctly accepts `TaskLifecycle`
- ✅ All VajraPulse versions are consistent (0.9.5)
- ⚠️ If error persists: Rebuild and use the new JAR

