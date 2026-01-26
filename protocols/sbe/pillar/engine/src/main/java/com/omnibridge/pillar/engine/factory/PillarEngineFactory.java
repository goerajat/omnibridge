package com.omnibridge.pillar.engine.factory;

import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.pillar.engine.PillarEngine;
import com.omnibridge.pillar.engine.config.PillarEngineConfig;
import com.omnibridge.sbe.engine.factory.SbeEngineFactory;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating NYSE Pillar engine instances.
 * Extends SbeEngineFactory for component lifecycle integration.
 */
public class PillarEngineFactory extends SbeEngineFactory<PillarEngine> {

    private static final Logger log = LoggerFactory.getLogger(PillarEngineFactory.class);

    @Override
    protected String getConfigPath() {
        return "pillar-engine";
    }

    @Override
    protected PillarEngine createEngine(Config config, ComponentProvider provider) {
        PillarEngineConfig engineConfig = PillarEngineConfig.fromConfig(config);
        return new PillarEngine(engineConfig, provider);
    }

    /**
     * Creates a Pillar engine from HOCON configuration.
     *
     * @param config the HOCON configuration
     * @return the engine
     */
    public static PillarEngine create(Config config) {
        PillarEngineConfig engineConfig = PillarEngineConfig.fromConfig(config);
        return new PillarEngine(engineConfig);
    }

    /**
     * Creates a Pillar engine from HOCON configuration with a component provider.
     *
     * @param config the HOCON configuration
     * @param provider the component provider
     * @return the engine
     */
    public static PillarEngine create(Config config, ComponentProvider provider) {
        PillarEngineConfig engineConfig = PillarEngineConfig.fromConfig(config);
        return new PillarEngine(engineConfig, provider);
    }

    /**
     * Creates a Pillar engine from a pre-built configuration.
     *
     * @param config the engine configuration
     * @return the engine
     */
    public static PillarEngine create(PillarEngineConfig config) {
        return new PillarEngine(config);
    }

    /**
     * Creates a Pillar engine from a pre-built configuration with a component provider.
     *
     * @param config the engine configuration
     * @param provider the component provider
     * @return the engine
     */
    public static PillarEngine create(PillarEngineConfig config, ComponentProvider provider) {
        return new PillarEngine(config, provider);
    }
}
