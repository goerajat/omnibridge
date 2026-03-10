package com.omnibridge.ouch.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.util.MetricsInjector;
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
        MeterRegistry registry = (MeterRegistry) MetricsInjector.findMeterRegistry(provider);
        if (registry != null) {
            engine.setMeterRegistry(registry);
            log.info("Injected MeterRegistry into OUCH engine");
        }

        return engine;
    }
}
