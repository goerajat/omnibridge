package com.omnibridge.fix.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.config.FixEngineConfig;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link FixEngine} instances.
 *
 * <p>Creates a FIX engine from the "fix-engine" configuration section.
 * The engine will use the component provider to access dependencies like
 * NetworkEventLoop, LogStore, ClockProvider, and SessionScheduler.</p>
 */
public class FixEngineFactory implements ComponentFactory<FixEngine> {

    @Override
    public FixEngine create(String name, Config config, ComponentProvider provider) {
        FixEngineConfig engineConfig = FixEngineConfig.fromConfig(config);
        return new FixEngine(engineConfig, provider);
    }
}
