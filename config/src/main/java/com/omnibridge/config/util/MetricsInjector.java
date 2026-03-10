package com.omnibridge.config.util;

import com.omnibridge.config.Component;
import com.omnibridge.config.provider.ComponentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for finding the MeterRegistry via reflection without a compile-time
 * dependency on the metrics module or Micrometer.
 *
 * <p>Looks up {@code com.omnibridge.metrics.MetricsComponent} through the
 * {@link ComponentProvider} and calls {@code getMeterRegistry()} reflectively.
 * The result is returned as {@code Object} so callers can cast to
 * {@code MeterRegistry} (they already have the Micrometer dependency).
 */
public final class MetricsInjector {

    private static final Logger log = LoggerFactory.getLogger(MetricsInjector.class);
    private static final String METRICS_CLASS_NAME = "com.omnibridge.metrics.MetricsComponent";

    private MetricsInjector() {
    }

    /**
     * Finds the MeterRegistry from the MetricsComponent via the provider.
     *
     * @param provider the component provider
     * @return the MeterRegistry instance as Object, or null if not available
     */
    public static Object findMeterRegistry(ComponentProvider provider) {
        try {
            Class<?> metricsClass = Class.forName(METRICS_CLASS_NAME);
            Object metricsComponent = provider.getComponent(
                    metricsClass.asSubclass(Component.class));
            if (metricsComponent != null) {
                java.lang.reflect.Method getRegistry =
                        metricsComponent.getClass().getMethod("getMeterRegistry");
                return getRegistry.invoke(metricsComponent);
            }
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            log.debug("MetricsComponent not available — metrics disabled");
        } catch (Exception e) {
            log.debug("Could not obtain MeterRegistry: {}", e.getMessage());
        }
        return null;
    }
}
