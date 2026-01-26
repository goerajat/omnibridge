package com.omnibridge.ouch.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.ouch.engine.OuchEngine;
import com.omnibridge.ouch.engine.config.OuchEngineConfig;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link OuchEngine} instances.
 *
 * <p>Creates an OUCH engine from the "ouch-engine" configuration section.
 * The engine will use the component provider to access dependencies like
 * NetworkEventLoop, LogStore, ClockProvider, SessionScheduler, and
 * SessionManagementService.</p>
 */
public class OuchEngineFactory implements ComponentFactory<OuchEngine> {

    @Override
    public OuchEngine create(String name, Config config, ComponentProvider provider) {
        Config ouchConfig = config.hasPath("ouch-engine") ? config.getConfig("ouch-engine") : config;
        OuchEngineConfig engineConfig = OuchEngineConfig.fromConfig(ouchConfig);
        return new OuchEngine(engineConfig, provider);
    }
}
