package com.omnibridge.pillar.engine.config;

import com.omnibridge.sbe.engine.config.SbeEngineConfig;
import com.typesafe.config.Config;

/**
 * Configuration for a NYSE Pillar engine.
 */
public class PillarEngineConfig extends SbeEngineConfig<PillarSessionConfig> {

    public PillarEngineConfig() {
    }

    @Override
    protected PillarSessionConfig createSessionConfig(Config config) {
        return PillarSessionConfig.fromConfig(config);
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
     * Create a Pillar engine config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the engine configuration
     */
    public static PillarEngineConfig fromConfig(Config config) {
        PillarEngineConfig engineConfig = new PillarEngineConfig();
        engineConfig.loadFromConfig(config);
        return engineConfig;
    }
}
