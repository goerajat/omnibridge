package com.omnibridge.sbe.engine.config;

import com.typesafe.config.Config;

/**
 * Base configuration for an SBE session.
 * <p>
 * Protocol-specific implementations (iLink 3, Optiq) should extend this class
 * to add protocol-specific configuration options.
 */
public class SbeSessionConfig {

    private String sessionId;
    private String sessionName;
    private String host;
    private int port;
    private boolean initiator = true;
    private String scheduleName;
    private int heartbeatInterval = 30;
    private int reconnectDelay = 5;
    private int maxReconnectAttempts = -1; // -1 = unlimited
    private boolean persistMessages = true;
    private int schemaVersion = 1;

    public SbeSessionConfig() {
    }

    public SbeSessionConfig(String sessionId) {
        this.sessionId = sessionId;
        this.sessionName = sessionId;
    }

    /**
     * Load base configuration from HOCON config.
     * Subclasses should call this method and then load additional fields.
     *
     * @param config the HOCON configuration
     */
    protected void loadFromConfig(Config config) {
        if (config.hasPath("session-id")) {
            this.sessionId = config.getString("session-id");
        }
        if (config.hasPath("session-name")) {
            this.sessionName = config.getString("session-name");
        } else if (sessionId != null) {
            this.sessionName = sessionId;
        }
        if (config.hasPath("host")) {
            this.host = config.getString("host");
        }
        if (config.hasPath("port")) {
            this.port = config.getInt("port");
        }
        if (config.hasPath("initiator")) {
            this.initiator = config.getBoolean("initiator");
        }
        if (config.hasPath("schedule")) {
            this.scheduleName = config.getString("schedule");
        }
        if (config.hasPath("heartbeat-interval")) {
            this.heartbeatInterval = config.getInt("heartbeat-interval");
        }
        if (config.hasPath("reconnect-delay")) {
            this.reconnectDelay = config.getInt("reconnect-delay");
        }
        if (config.hasPath("max-reconnect-attempts")) {
            this.maxReconnectAttempts = config.getInt("max-reconnect-attempts");
        }
        if (config.hasPath("persist-messages")) {
            this.persistMessages = config.getBoolean("persist-messages");
        }
        if (config.hasPath("schema-version")) {
            this.schemaVersion = config.getInt("schema-version");
        }
    }

    /**
     * Create a base SBE session config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the session configuration
     */
    public static SbeSessionConfig fromConfig(Config config) {
        SbeSessionConfig sessionConfig = new SbeSessionConfig();
        sessionConfig.loadFromConfig(config);
        return sessionConfig;
    }

    // Getters and setters

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        this.sessionName = sessionName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isInitiator() {
        return initiator;
    }

    public void setInitiator(boolean initiator) {
        this.initiator = initiator;
    }

    public String getScheduleName() {
        return scheduleName;
    }

    public void setScheduleName(String scheduleName) {
        this.scheduleName = scheduleName;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(int reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public void setMaxReconnectAttempts(int maxReconnectAttempts) {
        this.maxReconnectAttempts = maxReconnectAttempts;
    }

    public boolean isPersistMessages() {
        return persistMessages;
    }

    public void setPersistMessages(boolean persistMessages) {
        this.persistMessages = persistMessages;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    /**
     * Abstract builder for session configuration.
     * Protocol-specific implementations should extend this.
     *
     * @param <T> the config type
     * @param <B> the builder type (for method chaining)
     */
    public abstract static class Builder<T extends SbeSessionConfig, B extends Builder<T, B>> {
        protected final T config;

        protected Builder(T config) {
            this.config = config;
        }

        @SuppressWarnings("unchecked")
        protected B self() {
            return (B) this;
        }

        public B sessionId(String sessionId) {
            config.setSessionId(sessionId);
            config.setSessionName(sessionId);
            return self();
        }

        public B sessionName(String name) {
            config.setSessionName(name);
            return self();
        }

        public B host(String host) {
            config.setHost(host);
            return self();
        }

        public B port(int port) {
            config.setPort(port);
            return self();
        }

        public B initiator(boolean initiator) {
            config.setInitiator(initiator);
            return self();
        }

        public B acceptor() {
            config.setInitiator(false);
            return self();
        }

        public B schedule(String scheduleName) {
            config.setScheduleName(scheduleName);
            return self();
        }

        public B heartbeatInterval(int seconds) {
            config.setHeartbeatInterval(seconds);
            return self();
        }

        public B reconnectDelay(int seconds) {
            config.setReconnectDelay(seconds);
            return self();
        }

        public B maxReconnectAttempts(int attempts) {
            config.setMaxReconnectAttempts(attempts);
            return self();
        }

        public B persistMessages(boolean persist) {
            config.setPersistMessages(persist);
            return self();
        }

        public B schemaVersion(int version) {
            config.setSchemaVersion(version);
            return self();
        }

        public T build() {
            if (config.getSessionId() == null) {
                throw new IllegalStateException("sessionId is required");
            }
            return config;
        }
    }
}
