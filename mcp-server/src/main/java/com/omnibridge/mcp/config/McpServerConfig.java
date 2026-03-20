package com.omnibridge.mcp.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Configuration for the MCP server, parsed from HOCON.
 */
public final class McpServerConfig {

    private final String transport;
    private final int port;
    private final String storeType;
    private final String basePath;
    private final int defaultLimit;
    private final int maxLimit;
    private final boolean indexEnabled;
    private final String indexDataDir;
    private final int checkpointInterval;

    private McpServerConfig(String transport, int port, String storeType, String basePath,
                            int defaultLimit, int maxLimit,
                            boolean indexEnabled, String indexDataDir, int checkpointInterval) {
        this.transport = transport;
        this.port = port;
        this.storeType = storeType;
        this.basePath = basePath;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.indexEnabled = indexEnabled;
        this.indexDataDir = indexDataDir;
        this.checkpointInterval = checkpointInterval;
    }

    /**
     * Load config from HOCON with defaults from reference.conf.
     */
    public static McpServerConfig fromConfig(Config config) {
        Config c = config.getConfig("mcp-server");
        return new McpServerConfig(
                c.getString("transport"),
                c.getInt("port"),
                c.getString("persistence.store-type"),
                c.getString("persistence.base-path"),
                c.getInt("query.default-limit"),
                c.getInt("query.max-limit"),
                c.getBoolean("index.enabled"),
                c.getString("index.data-dir"),
                c.getInt("index.checkpoint-interval")
        );
    }

    /**
     * Load from reference.conf defaults.
     */
    public static McpServerConfig loadDefault() {
        return fromConfig(ConfigFactory.load());
    }

    /**
     * Create config from CLI arguments, falling back to reference.conf for unset values.
     */
    public static McpServerConfig fromArgs(String basePath, String transport, int port,
                                           boolean indexEnabled, String indexDataDir) {
        McpServerConfig defaults = loadDefault();
        return new McpServerConfig(
                transport != null ? transport : defaults.transport,
                port > 0 ? port : defaults.port,
                defaults.storeType,
                basePath != null ? basePath : defaults.basePath,
                defaults.defaultLimit,
                defaults.maxLimit,
                indexEnabled || defaults.indexEnabled,
                indexDataDir != null ? indexDataDir : defaults.indexDataDir,
                defaults.checkpointInterval
        );
    }

    public String getTransport() { return transport; }
    public int getPort() { return port; }
    public String getStoreType() { return storeType; }
    public String getBasePath() { return basePath; }
    public int getDefaultLimit() { return defaultLimit; }
    public int getMaxLimit() { return maxLimit; }
    public boolean isIndexEnabled() { return indexEnabled; }
    public String getIndexDataDir() { return indexDataDir; }
    public int getCheckpointInterval() { return checkpointInterval; }
}
