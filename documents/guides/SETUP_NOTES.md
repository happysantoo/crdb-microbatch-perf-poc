# Setup Notes

## VajraPulse OpenTelemetry Exporter

The `vajrapulse-exporter-otel:0.9.4` package is currently commented out in `build.gradle.kts` because it's not available in Maven Central.

To use the OpenTelemetry exporter (as shown in the example), you need to:

1. **Build from source:**
   ```bash
   git clone https://github.com/happysantoo/vajrapulse.git
   cd vajrapulse
   ./gradlew :vajrapulse-exporter-otel:publishToMavenLocal
   ```

2. **Uncomment the dependency in build.gradle.kts:**
   ```kotlin
   implementation("com.vajrapulse:vajrapulse-exporter-otel:0.9.4")
   ```

3. **Rebuild the project:**
   ```bash
   ./gradlew build
   ```

The code in `LoadTestService.java` is already configured to use the OpenTelemetry exporter following the example pattern from the VajraPulse repository.

