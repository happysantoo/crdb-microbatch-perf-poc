package com.crdb.microbatch.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;

/**
 * Configuration for JVM metrics collection including GC metrics.
 * 
 * <p>Explicitly binds JVM metrics to ensure GC metrics are collected
 * and exported through OpenTelemetry.
 */
@Configuration
public class JvmMetricsConfig {

    /**
     * Binds JVM GC metrics to the meter registry.
     * 
     * @param registry the Micrometer meter registry
     * @return the configured meter registry customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> jvmMetricsCustomizer() {
        return registry -> {
            // Bind JVM GC metrics (includes pause times)
            new JvmGcMetrics().bindTo(registry);
            
            // Bind JVM memory metrics
            new JvmMemoryMetrics().bindTo(registry);
            
            // Bind JVM thread metrics
            new JvmThreadMetrics().bindTo(registry);
        };
    }
}

