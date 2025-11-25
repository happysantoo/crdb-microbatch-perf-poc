package com.crdb.microbatch.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for virtual thread metrics collection.
 * 
 * <p>Binds VirtualThreadMetrics to the MeterRegistry to collect
 * Java 21 virtual thread metrics including:
 * - Virtual thread pinning events (duration and count)
 * - Failed virtual thread starts/unparks
 * - Virtual thread counts (if available on Java 24+)
 */
@Configuration
public class VirtualThreadMetricsConfig {

    /**
     * Binds virtual thread metrics to the meter registry.
     * 
     * @param registry the Micrometer meter registry
     * @return the configured meter registry customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> virtualThreadMetricsCustomizer() {
        return registry -> new VirtualThreadMetrics().bindTo(registry);
    }
}

