package com.crdb.microbatch.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Configuration for metrics collection and filtering.
 * 
 * <p>This configuration ensures MeterFilters are configured before any Meters
 * are registered to avoid warnings. It also binds JVM and virtual thread metrics.
 */
@Configuration
@AutoConfigureBefore(name = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class MetricsConfig {

    /**
     * Configures common tags for all meters.
     * 
     * <p>This MeterFilter is configured with highest precedence to ensure
     * it runs before any Meters are registered.
     * 
     * @return the meter filter for common tags
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public MeterFilter commonTagsMeterFilter() {
        return MeterFilter.commonTags(List.of(
            Tag.of("application", "crdb-microbatch-load-test")
        ));
    }

    /**
     * Binds JVM metrics to the meter registry.
     * 
     * <p>This customizer binds JVM metrics after the registry is fully configured.
     * 
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

    /**
     * Binds virtual thread metrics to the meter registry.
     * 
     * <p>This customizer binds virtual thread metrics after the registry is fully configured.
     * 
     * @return the configured meter registry customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> virtualThreadMetricsCustomizer() {
        return registry -> new VirtualThreadMetrics().bindTo(registry);
    }
}

