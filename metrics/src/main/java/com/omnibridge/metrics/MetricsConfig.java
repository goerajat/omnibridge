package com.omnibridge.metrics;

import com.typesafe.config.Config;

/**
 * Configuration for the metrics component.
 * Parsed from HOCON config block {@code metrics { ... }}.
 */
public class MetricsConfig {

    private final boolean enabled;
    private final boolean includeJvm;
    private final double[] histogramPercentiles;

    private MetricsConfig(boolean enabled, boolean includeJvm, double[] histogramPercentiles) {
        this.enabled = enabled;
        this.includeJvm = includeJvm;
        this.histogramPercentiles = histogramPercentiles;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isIncludeJvm() {
        return includeJvm;
    }

    public double[] getHistogramPercentiles() {
        return histogramPercentiles;
    }

    /**
     * Parse config from HOCON.
     * Expected format:
     * <pre>
     * metrics {
     *   enabled = true
     *   include-jvm = true
     *   histogram-percentiles = [0.5, 0.9, 0.95, 0.99, 0.999]
     * }
     * </pre>
     */
    public static MetricsConfig fromConfig(Config config) {
        boolean enabled = true;
        boolean includeJvm = true;
        double[] percentiles = {0.5, 0.9, 0.95, 0.99, 0.999};

        if (config.hasPath("metrics")) {
            Config metricsConfig = config.getConfig("metrics");

            if (metricsConfig.hasPath("enabled")) {
                enabled = metricsConfig.getBoolean("enabled");
            }
            if (metricsConfig.hasPath("include-jvm")) {
                includeJvm = metricsConfig.getBoolean("include-jvm");
            }
            if (metricsConfig.hasPath("histogram-percentiles")) {
                percentiles = metricsConfig.getDoubleList("histogram-percentiles")
                        .stream().mapToDouble(Double::doubleValue).toArray();
            }
        }

        return new MetricsConfig(enabled, includeJvm, percentiles);
    }

    /**
     * Create a default config with metrics enabled.
     */
    public static MetricsConfig defaults() {
        return new MetricsConfig(true, true, new double[]{0.5, 0.9, 0.95, 0.99, 0.999});
    }
}
