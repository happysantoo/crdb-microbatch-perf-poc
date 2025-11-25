plugins {
    java
    id("org.springframework.boot") version "3.5.8"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // VajraPulse might have snapshots or additional repos
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    
    // Database
    implementation("org.postgresql:postgresql")
    
    // VajraPulse
    implementation("com.vajrapulse:vajrapulse-core:0.9.4")
    implementation("com.vajrapulse:vajrapulse-api:0.9.4")
    implementation("com.vajrapulse:vajrapulse-worker:0.9.4") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("com.vajrapulse:vajrapulse-exporter-opentelemetry:0.9.4")
    
    // Micrometer for Prometheus
    implementation("io.micrometer:micrometer-registry-prometheus")
    
    // Micrometer Java 21 for virtual thread metrics
    implementation("io.micrometer:micrometer-java21:1.15.6")
    
    // OpenTelemetry
    implementation("io.opentelemetry:opentelemetry-api:1.44.1")
    implementation("io.opentelemetry:opentelemetry-sdk:1.44.1")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.44.1")
    implementation("io.micrometer:micrometer-registry-otlp:1.15.6")
    
    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    
    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--enable-preview"
    ))
    // Note: -Xlint:doclint may not be available in all Java versions
    // options.compilerArgs.add("-Xlint:doclint")
}

tasks.withType<Test> {
    jvmArgs("--enable-preview")
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "-Xmx4g",              // Doubled to 4GB heap
        "-Xms4g",              // Initial heap: 4GB (prevents resizing overhead)
        "-XX:MaxMetaspaceSize=512m",
        "-XX:+UseZGC",         // ZGC for better performance with large heaps
        "-XX:+UnlockExperimentalVMOptions",  // Required for ZGC in some Java versions
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=./heap-dumps"
    )
}

