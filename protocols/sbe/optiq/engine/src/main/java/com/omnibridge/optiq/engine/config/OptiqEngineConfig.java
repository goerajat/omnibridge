package com.omnibridge.optiq.engine.config;

import com.omnibridge.sbe.engine.config.SbeEngineConfig;
import com.typesafe.config.Config;

/**
 * Configuration for an Optiq engine.
 */
public class OptiqEngineConfig extends SbeEngineConfig<OptiqSessionConfig> {

    public OptiqEngineConfig() {
    }

    @Override
    protected OptiqSessionConfig createSessionConfig(Config config) {
        return OptiqSessionConfig.fromConfig(config);
    }

    /**
     * Load configuration from HOCON config.
     *
     * @param config the HOCON configuration
     */
    @Override
    protected void loadFromConfig(Config config) {
        super.loadFromConfig(config);
        loadSessionsFromConfig(config);
    }

    /**
     * Create an Optiq engine config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the engine configuration
     */
    public static OptiqEngineConfig fromConfig(Config config) {
        OptiqEngineConfig engineConfig = new OptiqEngineConfig();
        engineConfig.loadFromConfig(config);
        return engineConfig;
    }
}
