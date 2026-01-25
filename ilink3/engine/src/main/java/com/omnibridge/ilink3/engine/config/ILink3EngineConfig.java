package com.omnibridge.ilink3.engine.config;

import com.omnibridge.sbe.engine.config.SbeEngineConfig;
import com.typesafe.config.Config;

/**
 * Configuration for an iLink 3 engine.
 */
public class ILink3EngineConfig extends SbeEngineConfig<ILink3SessionConfig> {

    /** Default sender location for orders */
    private String defaultLocation;

    public ILink3EngineConfig() {
    }

    @Override
    protected ILink3SessionConfig createSessionConfig(Config config) {
        return ILink3SessionConfig.fromConfig(config);
    }

    /**
     * Load configuration from HOCON config.
     *
     * @param config the HOCON configuration
     */
    @Override
    protected void loadFromConfig(Config config) {
        super.loadFromConfig(config);

        if (config.hasPath("default-location")) {
            this.defaultLocation = config.getString("default-location");
        }

        loadSessionsFromConfig(config);
    }

    /**
     * Create an iLink 3 engine config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the engine configuration
     */
    public static ILink3EngineConfig fromConfig(Config config) {
        ILink3EngineConfig engineConfig = new ILink3EngineConfig();
        engineConfig.loadFromConfig(config);
        return engineConfig;
    }

    // Getters and setters

    public String getDefaultLocation() {
        return defaultLocation;
    }

    public void setDefaultLocation(String defaultLocation) {
        this.defaultLocation = defaultLocation;
    }
}
