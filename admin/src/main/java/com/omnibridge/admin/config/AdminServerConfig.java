package com.omnibridge.admin.config;

import com.typesafe.config.Config;

/**
 * Configuration for the Javalin admin server.
 *
 * <p>Example HOCON configuration:</p>
 * <pre>
 * admin {
 *   enabled = true
 *   host = "0.0.0.0"
 *   port = 8080
 *   context-path = "/api"
 *   cors {
 *     enabled = true
 *     allowed-origins = ["*"]
 *   }
 *   websocket {
 *     enabled = true
 *     path = "/ws"
 *     idle-timeout-ms = 300000
 *   }
 * }
 * </pre>
 */
public class AdminServerConfig {

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String contextPath;
    private final boolean corsEnabled;
    private final String[] corsAllowedOrigins;
    private final boolean websocketEnabled;
    private final String websocketPath;
    private final long websocketIdleTimeoutMs;

    private AdminServerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.host = builder.host;
        this.port = builder.port;
        this.contextPath = builder.contextPath;
        this.corsEnabled = builder.corsEnabled;
        this.corsAllowedOrigins = builder.corsAllowedOrigins;
        this.websocketEnabled = builder.websocketEnabled;
        this.websocketPath = builder.websocketPath;
        this.websocketIdleTimeoutMs = builder.websocketIdleTimeoutMs;
    }

    /**
     * Load configuration from HOCON config.
     */
    public static AdminServerConfig fromConfig(Config config) {
        Builder builder = builder();

        if (config.hasPath("admin.enabled")) {
            builder.enabled(config.getBoolean("admin.enabled"));
        }
        if (config.hasPath("admin.host")) {
            builder.host(config.getString("admin.host"));
        }
        if (config.hasPath("admin.port")) {
            builder.port(config.getInt("admin.port"));
        }
        if (config.hasPath("admin.context-path")) {
            builder.contextPath(config.getString("admin.context-path"));
        }
        if (config.hasPath("admin.cors.enabled")) {
            builder.corsEnabled(config.getBoolean("admin.cors.enabled"));
        }
        if (config.hasPath("admin.cors.allowed-origins")) {
            builder.corsAllowedOrigins(
                config.getStringList("admin.cors.allowed-origins").toArray(new String[0]));
        }
        if (config.hasPath("admin.websocket.enabled")) {
            builder.websocketEnabled(config.getBoolean("admin.websocket.enabled"));
        }
        if (config.hasPath("admin.websocket.path")) {
            builder.websocketPath(config.getString("admin.websocket.path"));
        }
        if (config.hasPath("admin.websocket.idle-timeout-ms")) {
            builder.websocketIdleTimeoutMs(config.getLong("admin.websocket.idle-timeout-ms"));
        }

        return builder.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getContextPath() {
        return contextPath;
    }

    public boolean isCorsEnabled() {
        return corsEnabled;
    }

    public String[] getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public boolean isWebsocketEnabled() {
        return websocketEnabled;
    }

    public String getWebsocketPath() {
        return websocketPath;
    }

    public long getWebsocketIdleTimeoutMs() {
        return websocketIdleTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enabled = true;
        private String host = "0.0.0.0";
        private int port = 8080;
        private String contextPath = "/api";
        private boolean corsEnabled = true;
        private String[] corsAllowedOrigins = new String[]{"*"};
        private boolean websocketEnabled = true;
        private String websocketPath = "/ws";
        private long websocketIdleTimeoutMs = 300000;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder contextPath(String contextPath) {
            this.contextPath = contextPath;
            return this;
        }

        public Builder corsEnabled(boolean corsEnabled) {
            this.corsEnabled = corsEnabled;
            return this;
        }

        public Builder corsAllowedOrigins(String... origins) {
            this.corsAllowedOrigins = origins;
            return this;
        }

        public Builder websocketEnabled(boolean enabled) {
            this.websocketEnabled = enabled;
            return this;
        }

        public Builder websocketPath(String path) {
            this.websocketPath = path;
            return this;
        }

        public Builder websocketIdleTimeoutMs(long timeoutMs) {
            this.websocketIdleTimeoutMs = timeoutMs;
            return this;
        }

        public AdminServerConfig build() {
            return new AdminServerConfig(this);
        }
    }

    @Override
    public String toString() {
        return "AdminServerConfig{" +
                "enabled=" + enabled +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", contextPath='" + contextPath + '\'' +
                ", corsEnabled=" + corsEnabled +
                ", websocketEnabled=" + websocketEnabled +
                ", websocketPath='" + websocketPath + '\'' +
                '}';
    }
}
