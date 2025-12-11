package com.crdb.microbatch.backpressure;

import com.vajrapulse.vortex.backpressure.BackpressureProvider;

/**
 * Composite backpressure provider that uses the maximum of multiple sources.
 * 
 * <p>This provider combines multiple backpressure sources and returns the maximum
 * backpressure level. This ensures we react to the worst pressure source, providing
 * early warning before any single source becomes critical.
 * 
 * <p>Used to combine:
 * <ul>
 *   <li>Queue depth backpressure - measures items waiting in queue</li>
 *   <li>Connection pool backpressure - measures connection availability</li>
 * </ul>
 * 
 * <p>By using the maximum, we ensure that:
 * - If queue is filling up, we see backpressure (even if pool is available)
 * - If connection pool is exhausted, we see backpressure (even if queue is empty)
 * - System responds to the worst pressure source
 */
public class CompositeBackpressureProvider implements BackpressureProvider {
    
    private final BackpressureProvider[] providers;
    
    /**
     * Constructor for CompositeBackpressureProvider.
     * 
     * @param providers the backpressure providers to combine
     */
    public CompositeBackpressureProvider(BackpressureProvider... providers) {
        if (providers == null || providers.length == 0) {
            throw new IllegalArgumentException("At least one backpressure provider is required");
        }
        this.providers = providers;
    }
    
    @Override
    public String getSourceName() {
        return "Composite (" + providers.length + " sources)";
    }
    
    @Override
    public double getBackpressureLevel() {
        double maxBackpressure = 0.0;
        for (BackpressureProvider provider : providers) {
            double level = provider.getBackpressureLevel();
            if (level > maxBackpressure) {
                maxBackpressure = level;
            }
        }
        return maxBackpressure;
    }
}

