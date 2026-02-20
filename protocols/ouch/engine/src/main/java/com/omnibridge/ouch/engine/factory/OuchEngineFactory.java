package com.omnibridge.ouch.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.ouch.engine.OuchEngine;
import com.omnibridge.ouch.engine.config.OuchEngineConfig;
import com.typesafe.config.Config;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating {@link OuchEngine} instances.
 *
 * <p>Creates an OUCH engine from the "ouch-engine" configuration section.
 * The engine will use the component provider to access dependencies like
 * NetworkEventLoop, LogStore, ClockProvider, SessionScheduler, and
 * SessionManagementService.</p>
 */
public class OuchEngineFactory implements ComponentFactory<OuchEngine> {

    private static final Logger log = LoggerFactory.getLogger(OuchEngineFactory.class);

    @Override
    public OuchEngine create(String name, Config config, ComponentProvider provider) {
        Config ouchConfig = config.hasPath("ouch-engine") ? config.getConfig("ouch-engine") : config;
        OuchEngineConfig engineConfig = OuchEngineConfig.fromConfig(ouchConfig);
        OuchEngine engine = new OuchEngine(engineConfig, provider);

        // Inject meter registry if metrics component is available
        try {
            Class<?> metricsClass = Class.forName("com.omnibridge.metrics.MetricsComponent");
            Object metricsComponent = provider.getComponent(metricsClass.asSubclass(com.omnibridge.config.Component.class));
            if (metricsComponent != null) {
                java.lang.reflect.Method getRegistry = metricsComponent.getClass().getMethod("getMeterRegistry");
                MeterRegistry registry = (MeterRegistry) getRegistry.invoke(metricsComponent);
                if (registry != null) {
                    engine.setMeterRegistry(registry);
                    log.info("Injected MeterRegistry into OUCH engine");
                }
            }
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            log.debug("No MetricsComponent available, metrics disabled for OUCH engine");
        } catch (Exception e) {
            log.debug("Could not inject MeterRegistry into OUCH engine: {}", e.getMessage());
        }

        return engine;
    }
}
