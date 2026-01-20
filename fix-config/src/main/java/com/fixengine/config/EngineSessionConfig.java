package com.fixengine.config;

import com.typesafe.config.Config;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Configuration for a FIX session loaded from HOCON config.
 */
public final class EngineSessionConfig {

    public enum Role {
        INITIATOR,
        ACCEPTOR
    }

    private final String sessionName;
    private final String beginString;
    private final String senderCompId;
    private final String targetCompId;
    private final Role role;
    private final String host;
    private final int port;
    private final int heartbeatInterval;
    private final boolean resetOnLogon;
    private final boolean resetOnLogout;
    private final boolean resetOnDisconnect;
    private final int reconnectInterval;
    private final int maxReconnectAttempts;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final String timeZone;
    private final LocalTime eodTime;
    private final boolean resetOnEod;
    private final boolean logMessages;
    private final int messagePoolSize;
    private final int maxMessageLength;
    private final int maxTagNumber;
    private final String persistencePath;

    private EngineSessionConfig(Builder builder) {
        this.sessionName = builder.sessionName;
        this.beginString = builder.beginString;
        this.senderCompId = builder.senderCompId;
        this.targetCompId = builder.targetCompId;
        this.role = builder.role;
        this.host = builder.host;
        this.port = builder.port;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.resetOnLogon = builder.resetOnLogon;
        this.resetOnLogout = builder.resetOnLogout;
        this.resetOnDisconnect = builder.resetOnDisconnect;
        this.reconnectInterval = builder.reconnectInterval;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.timeZone = builder.timeZone;
        this.eodTime = builder.eodTime;
        this.resetOnEod = builder.resetOnEod;
        this.logMessages = builder.logMessages;
        this.messagePoolSize = builder.messagePoolSize;
        this.maxMessageLength = builder.maxMessageLength;
        this.maxTagNumber = builder.maxTagNumber;
        this.persistencePath = builder.persistencePath;
    }

    /**
     * Create configuration from Typesafe Config.
     */
    public static EngineSessionConfig fromConfig(Config config) {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        Builder builder = builder()
                .sessionName(config.getString("session-name"))
                .beginString(config.getString("begin-string"))
                .senderCompId(config.getString("sender-comp-id"))
                .targetCompId(config.getString("target-comp-id"))
                .role(Role.valueOf(config.getString("role").toUpperCase()))
                .port(config.getInt("port"))
                .heartbeatInterval(config.getInt("heartbeat-interval"))
                .resetOnLogon(config.getBoolean("reset-on-logon"))
                .resetOnLogout(config.getBoolean("reset-on-logout"))
                .resetOnDisconnect(config.getBoolean("reset-on-disconnect"))
                .reconnectInterval(config.getInt("reconnect-interval"))
                .maxReconnectAttempts(config.getInt("max-reconnect-attempts"))
                .timeZone(config.getString("time-zone"))
                .resetOnEod(config.getBoolean("reset-on-eod"))
                .logMessages(config.getBoolean("log-messages"))
                .messagePoolSize(config.getInt("message-pool-size"))
                .maxMessageLength(config.getInt("max-message-length"))
                .maxTagNumber(config.getInt("max-tag-number"));

        if (config.hasPath("host")) {
            builder.host(config.getString("host"));
        }

        if (config.hasPath("start-time") && !config.getString("start-time").isEmpty()) {
            builder.startTime(LocalTime.parse(config.getString("start-time"), timeFormatter));
        }
        if (config.hasPath("end-time") && !config.getString("end-time").isEmpty()) {
            builder.endTime(LocalTime.parse(config.getString("end-time"), timeFormatter));
        }
        if (config.hasPath("eod-time") && !config.getString("eod-time").isEmpty()) {
            builder.eodTime(LocalTime.parse(config.getString("eod-time"), timeFormatter));
        }
        if (config.hasPath("persistence-path")) {
            builder.persistencePath(config.getString("persistence-path"));
        }

        return builder.build();
    }

    public String getSessionName() {
        return sessionName;
    }

    public String getBeginString() {
        return beginString;
    }

    public String getSenderCompId() {
        return senderCompId;
    }

    public String getTargetCompId() {
        return targetCompId;
    }

    public Role getRole() {
        return role;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public boolean isResetOnLogon() {
        return resetOnLogon;
    }

    public boolean isResetOnLogout() {
        return resetOnLogout;
    }

    public boolean isResetOnDisconnect() {
        return resetOnDisconnect;
    }

    public int getReconnectInterval() {
        return reconnectInterval;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public Optional<LocalTime> getStartTime() {
        return Optional.ofNullable(startTime);
    }

    public Optional<LocalTime> getEndTime() {
        return Optional.ofNullable(endTime);
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Optional<LocalTime> getEodTime() {
        return Optional.ofNullable(eodTime);
    }

    public boolean isResetOnEod() {
        return resetOnEod;
    }

    public boolean isLogMessages() {
        return logMessages;
    }

    public int getMessagePoolSize() {
        return messagePoolSize;
    }

    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    public int getMaxTagNumber() {
        return maxTagNumber;
    }

    public Optional<String> getPersistencePath() {
        return Optional.ofNullable(persistencePath);
    }

    /**
     * Get the session ID (SenderCompID->TargetCompID).
     */
    public String getSessionId() {
        return senderCompId + "->" + targetCompId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sessionName;
        private String beginString = "FIX.4.4";
        private String senderCompId;
        private String targetCompId;
        private Role role = Role.INITIATOR;
        private String host;
        private int port;
        private int heartbeatInterval = 30;
        private boolean resetOnLogon = false;
        private boolean resetOnLogout = false;
        private boolean resetOnDisconnect = false;
        private int reconnectInterval = 5;
        private int maxReconnectAttempts = -1;
        private LocalTime startTime;
        private LocalTime endTime;
        private String timeZone = "UTC";
        private LocalTime eodTime;
        private boolean resetOnEod = false;
        private boolean logMessages = true;
        private int messagePoolSize = 64;
        private int maxMessageLength = 4096;
        private int maxTagNumber = 1000;
        private String persistencePath;

        private Builder() {}

        public Builder sessionName(String sessionName) {
            this.sessionName = sessionName;
            return this;
        }

        public Builder beginString(String beginString) {
            this.beginString = beginString;
            return this;
        }

        public Builder senderCompId(String senderCompId) {
            this.senderCompId = senderCompId;
            return this;
        }

        public Builder targetCompId(String targetCompId) {
            this.targetCompId = targetCompId;
            return this;
        }

        public Builder role(Role role) {
            this.role = role;
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

        public Builder heartbeatInterval(int heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        public Builder resetOnLogon(boolean resetOnLogon) {
            this.resetOnLogon = resetOnLogon;
            return this;
        }

        public Builder resetOnLogout(boolean resetOnLogout) {
            this.resetOnLogout = resetOnLogout;
            return this;
        }

        public Builder resetOnDisconnect(boolean resetOnDisconnect) {
            this.resetOnDisconnect = resetOnDisconnect;
            return this;
        }

        public Builder reconnectInterval(int reconnectInterval) {
            this.reconnectInterval = reconnectInterval;
            return this;
        }

        public Builder maxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        public Builder startTime(LocalTime startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(LocalTime endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder timeZone(String timeZone) {
            this.timeZone = timeZone;
            return this;
        }

        public Builder eodTime(LocalTime eodTime) {
            this.eodTime = eodTime;
            return this;
        }

        public Builder resetOnEod(boolean resetOnEod) {
            this.resetOnEod = resetOnEod;
            return this;
        }

        public Builder logMessages(boolean logMessages) {
            this.logMessages = logMessages;
            return this;
        }

        public Builder messagePoolSize(int messagePoolSize) {
            this.messagePoolSize = messagePoolSize;
            return this;
        }

        public Builder maxMessageLength(int maxMessageLength) {
            this.maxMessageLength = maxMessageLength;
            return this;
        }

        public Builder maxTagNumber(int maxTagNumber) {
            this.maxTagNumber = maxTagNumber;
            return this;
        }

        public Builder persistencePath(String persistencePath) {
            this.persistencePath = persistencePath;
            return this;
        }

        public EngineSessionConfig build() {
            if (sessionName == null || sessionName.isEmpty()) {
                throw new IllegalArgumentException("sessionName is required");
            }
            if (senderCompId == null || senderCompId.isEmpty()) {
                throw new IllegalArgumentException("senderCompId is required");
            }
            if (targetCompId == null || targetCompId.isEmpty()) {
                throw new IllegalArgumentException("targetCompId is required");
            }
            if (role == Role.INITIATOR && (host == null || host.isEmpty())) {
                throw new IllegalArgumentException("host is required for initiator sessions");
            }
            if (port <= 0) {
                throw new IllegalArgumentException("port must be positive");
            }
            return new EngineSessionConfig(this);
        }
    }

    @Override
    public String toString() {
        return "EngineSessionConfig{" +
                "sessionName='" + sessionName + '\'' +
                ", role=" + role +
                ", senderCompId='" + senderCompId + '\'' +
                ", targetCompId='" + targetCompId + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                '}';
    }
}
