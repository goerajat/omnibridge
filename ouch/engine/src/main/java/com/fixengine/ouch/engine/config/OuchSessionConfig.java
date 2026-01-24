package com.fixengine.ouch.engine.config;

import com.fixengine.ouch.message.OuchVersion;
import com.typesafe.config.Config;

/**
 * Configuration for an OUCH session.
 */
public class OuchSessionConfig {

    private String sessionId;
    private String sessionName;
    private String host;
    private int port = 9200;
    private String username;
    private String password;
    private boolean initiator = true;
    private String scheduleName;
    private int heartbeatInterval = 30;
    private int reconnectDelay = 5;
    private int maxReconnectAttempts = -1; // -1 = unlimited
    private boolean persistMessages = true;
    private OuchVersion protocolVersion = OuchVersion.V42; // Default to V42 for backward compatibility

    public OuchSessionConfig() {
    }

    public OuchSessionConfig(String sessionId) {
        this.sessionId = sessionId;
        this.sessionName = sessionId;
    }

    /**
     * Load configuration from HOCON config.
     */
    public static OuchSessionConfig fromConfig(Config config) {
        OuchSessionConfig sessionConfig = new OuchSessionConfig();

        sessionConfig.setSessionId(config.getString("session-id"));

        if (config.hasPath("session-name")) {
            sessionConfig.setSessionName(config.getString("session-name"));
        } else {
            sessionConfig.setSessionName(sessionConfig.getSessionId());
        }

        if (config.hasPath("host")) {
            sessionConfig.setHost(config.getString("host"));
        }
        if (config.hasPath("port")) {
            sessionConfig.setPort(config.getInt("port"));
        }
        if (config.hasPath("username")) {
            sessionConfig.setUsername(config.getString("username"));
        }
        if (config.hasPath("password")) {
            sessionConfig.setPassword(config.getString("password"));
        }
        if (config.hasPath("initiator")) {
            sessionConfig.setInitiator(config.getBoolean("initiator"));
        }
        if (config.hasPath("schedule")) {
            sessionConfig.setScheduleName(config.getString("schedule"));
        }
        if (config.hasPath("heartbeat-interval")) {
            sessionConfig.setHeartbeatInterval(config.getInt("heartbeat-interval"));
        }
        if (config.hasPath("reconnect-delay")) {
            sessionConfig.setReconnectDelay(config.getInt("reconnect-delay"));
        }
        if (config.hasPath("max-reconnect-attempts")) {
            sessionConfig.setMaxReconnectAttempts(config.getInt("max-reconnect-attempts"));
        }
        if (config.hasPath("persist-messages")) {
            sessionConfig.setPersistMessages(config.getBoolean("persist-messages"));
        }
        if (config.hasPath("protocol-version")) {
            sessionConfig.setProtocolVersion(OuchVersion.fromString(config.getString("protocol-version")));
        }

        return sessionConfig;
    }

    /**
     * Builder for OuchSessionConfig.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String sessionId) {
        return new Builder().sessionId(sessionId);
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public OuchVersion getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(OuchVersion protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /**
     * Builder class for OuchSessionConfig.
     */
    public static class Builder {
        private final OuchSessionConfig config = new OuchSessionConfig();

        public Builder sessionId(String sessionId) {
            config.setSessionId(sessionId);
            config.setSessionName(sessionId);
            return this;
        }

        public Builder sessionName(String name) {
            config.setSessionName(name);
            return this;
        }

        public Builder host(String host) {
            config.setHost(host);
            return this;
        }

        public Builder port(int port) {
            config.setPort(port);
            return this;
        }

        public Builder username(String username) {
            config.setUsername(username);
            return this;
        }

        public Builder password(String password) {
            config.setPassword(password);
            return this;
        }

        public Builder initiator(boolean initiator) {
            config.setInitiator(initiator);
            return this;
        }

        public Builder acceptor() {
            config.setInitiator(false);
            return this;
        }

        public Builder schedule(String scheduleName) {
            config.setScheduleName(scheduleName);
            return this;
        }

        public Builder heartbeatInterval(int seconds) {
            config.setHeartbeatInterval(seconds);
            return this;
        }

        public Builder reconnectDelay(int seconds) {
            config.setReconnectDelay(seconds);
            return this;
        }

        public Builder maxReconnectAttempts(int attempts) {
            config.setMaxReconnectAttempts(attempts);
            return this;
        }

        public Builder persistMessages(boolean persist) {
            config.setPersistMessages(persist);
            return this;
        }

        public Builder protocolVersion(OuchVersion version) {
            config.setProtocolVersion(version);
            return this;
        }

        public Builder protocolVersion(String version) {
            config.setProtocolVersion(OuchVersion.fromString(version));
            return this;
        }

        public OuchSessionConfig build() {
            if (config.getSessionId() == null) {
                throw new IllegalStateException("sessionId is required");
            }
            return config;
        }
    }
}
