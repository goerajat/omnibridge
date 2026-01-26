package com.omnibridge.ilink3.engine.factory;

import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.ilink3.engine.ILink3Engine;
import com.omnibridge.ilink3.engine.config.ILink3EngineConfig;
import com.omnibridge.sbe.engine.factory.SbeEngineFactory;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating iLink 3 engine instances.
 * Extends SbeEngineFactory for component lifecycle integration.
 */
public class ILink3EngineFactory extends SbeEngineFactory<ILink3Engine> {

    private static final Logger log = LoggerFactory.getLogger(ILink3EngineFactory.class);

    @Override
    protected String getConfigPath() {
        return "ilink3-engine";
    }

    @Override
    protected ILink3Engine createEngine(Config config, ComponentProvider provider) {
        ILink3EngineConfig engineConfig = ILink3EngineConfig.fromConfig(config);
        return new ILink3Engine(engineConfig, provider);
    }

    /**
     * Creates an iLink 3 engine from HOCON configuration.
     *
     * @param config the HOCON configuration
     * @return the engine
     */
    public static ILink3Engine create(Config config) {
        ILink3EngineConfig engineConfig = ILink3EngineConfig.fromConfig(config);
        return new ILink3Engine(engineConfig);
    }

    /**
     * Creates an iLink 3 engine from HOCON configuration with a component provider.
     *
     * @param config the HOCON configuration
     * @param provider the component provider
     * @return the engine
     */
    public static ILink3Engine create(Config config, ComponentProvider provider) {
        ILink3EngineConfig engineConfig = ILink3EngineConfig.fromConfig(config);
        return new ILink3Engine(engineConfig, provider);
    }

    /**
     * Creates an iLink 3 engine from a pre-built configuration.
     *
     * @param config the engine configuration
     * @return the engine
     */
    public static ILink3Engine create(ILink3EngineConfig config) {
        return new ILink3Engine(config);
    }

    /**
     * Creates an iLink 3 engine from a pre-built configuration with a component provider.
     *
     * @param config the engine configuration
     * @param provider the component provider
     * @return the engine
     */
    public static ILink3Engine create(ILink3EngineConfig config, ComponentProvider provider) {
        return new ILink3Engine(config, provider);
    }
}
