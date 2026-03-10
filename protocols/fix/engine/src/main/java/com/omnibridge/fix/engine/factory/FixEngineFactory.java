package com.omnibridge.fix.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.util.MetricsInjector;
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
        MeterRegistry registry = (MeterRegistry) MetricsInjector.findMeterRegistry(provider);
        if (registry != null) {
            engine.setMeterRegistry(registry);
            log.info("Injected MeterRegistry into FIX engine");
        }

        return engine;
    }
}
