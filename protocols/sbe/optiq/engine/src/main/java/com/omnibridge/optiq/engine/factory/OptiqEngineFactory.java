package com.omnibridge.optiq.engine.factory;

import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.optiq.engine.OptiqEngine;
import com.omnibridge.optiq.engine.config.OptiqEngineConfig;
import com.omnibridge.sbe.engine.factory.SbeEngineFactory;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating Optiq engine instances.
 * Extends SbeEngineFactory for component lifecycle integration.
 */
public class OptiqEngineFactory extends SbeEngineFactory<OptiqEngine> {

    private static final Logger log = LoggerFactory.getLogger(OptiqEngineFactory.class);

    @Override
    protected String getConfigPath() {
        return "optiq-engine";
    }

    @Override
    protected OptiqEngine createEngine(Config config, ComponentProvider provider) {
        OptiqEngineConfig engineConfig = OptiqEngineConfig.fromConfig(config);
        return new OptiqEngine(engineConfig, provider);
    }

    /**
     * Creates an Optiq engine from HOCON configuration.
     *
     * @param config the HOCON configuration
     * @return the engine
     */
    public static OptiqEngine create(Config config) {
        OptiqEngineConfig engineConfig = OptiqEngineConfig.fromConfig(config);
        return new OptiqEngine(engineConfig);
    }

    /**
     * Creates an Optiq engine from HOCON configuration with a component provider.
     *
     * @param config the HOCON configuration
     * @param provider the component provider
     * @return the engine
     */
    public static OptiqEngine create(Config config, ComponentProvider provider) {
        OptiqEngineConfig engineConfig = OptiqEngineConfig.fromConfig(config);
        return new OptiqEngine(engineConfig, provider);
    }

    /**
     * Creates an Optiq engine from a pre-built configuration.
     *
     * @param config the engine configuration
     * @return the engine
     */
    public static OptiqEngine create(OptiqEngineConfig config) {
        return new OptiqEngine(config);
    }

    /**
     * Creates an Optiq engine from a pre-built configuration with a component provider.
     *
     * @param config the engine configuration
     * @param provider the component provider
     * @return the engine
     */
    public static OptiqEngine create(OptiqEngineConfig config, ComponentProvider provider) {
        return new OptiqEngine(config, provider);
    }
}
