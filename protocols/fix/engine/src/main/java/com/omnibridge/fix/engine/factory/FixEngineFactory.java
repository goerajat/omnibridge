package com.omnibridge.fix.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.config.FixEngineConfig;
import com.typesafe.config.Config;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating {@link FixEngine} instances.
 *
 * <p>Creates a FIX engine from the "fix-engine" configuration section.
 * The engine will use the component provider to access dependencies like
 * NetworkEventLoop, LogStore, ClockProvider, and SessionScheduler.</p>
 */
public class FixEngineFactory implements ComponentFactory<FixEngine> {

    private static final Logger log = LoggerFactory.getLogger(FixEngineFactory.class);

    @Override
    public FixEngine create(String name, Config config, ComponentProvider provider) {
        FixEngineConfig engineConfig = FixEngineConfig.fromConfig(config);
        FixEngine engine = new FixEngine(engineConfig, provider);

        // Inject meter registry if metrics component is available
        try {
            // Use reflection-free approach: look for MeterRegistry in provider
            // The MetricsComponent provides getMeterRegistry() but we can't depend on it directly
            // Instead, check if a component provides MeterRegistry
            Object metricsComponent = null;
            try {
                // Try to get MetricsComponent by its class name via the provider
                Class<?> metricsClass = Class.forName("com.omnibridge.metrics.MetricsComponent");
                metricsComponent = provider.getComponent(metricsClass.asSubclass(com.omnibridge.config.Component.class));
            } catch (ClassNotFoundException | IllegalArgumentException e) {
                log.debug("No MetricsComponent available, metrics disabled for FIX engine");
            }

            if (metricsComponent != null) {
                java.lang.reflect.Method getRegistry = metricsComponent.getClass().getMethod("getMeterRegistry");
                MeterRegistry registry = (MeterRegistry) getRegistry.invoke(metricsComponent);
                if (registry != null) {
                    engine.setMeterRegistry(registry);
                    log.info("Injected MeterRegistry into FIX engine");
                }
            }
        } catch (Exception e) {
            log.debug("Could not inject MeterRegistry into FIX engine: {}", e.getMessage());
        }

        return engine;
    }
}
