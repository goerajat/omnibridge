package com.omnibridge.fix.engine.config;

import com.omnibridge.fix.message.ApplVerID;
import com.omnibridge.fix.message.FixVersion;
import com.omnibridge.network.SslConfig;
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
    private final int maxMessageLength;
    private final int maxTagNumber;
    private final String persistencePath;
    private final String scheduleName;

    // FIX 5.0+ support
    private final FixVersion fixVersion;
    private final ApplVerID defaultApplVerID;

    // SSL/TLS configuration
    private final SslConfig sslConfig;

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
        this.maxMessageLength = builder.maxMessageLength;
        this.maxTagNumber = builder.maxTagNumber;
        this.persistencePath = builder.persistencePath;
        this.scheduleName = builder.scheduleName;
        this.fixVersion = builder.fixVersion;
        this.defaultApplVerID = builder.defaultApplVerID;
        this.sslConfig = builder.sslConfig;
    }

    /**
     * Create configuration from Typesafe Config.
     * <p>
     * Supports both 'initiator' boolean (consistent with OUCH/SBE) and legacy 'role' string.
     * If 'initiator' is present, it takes precedence over 'role'.
     */
    public static EngineSessionConfig fromConfig(Config config) {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        Builder builder = builder();

        // Required fields
        builder.sessionName(config.getString("session-id"))
               .senderCompId(config.getString("sender-comp-id"))
               .targetCompId(config.getString("target-comp-id"))
               .port(config.getInt("port"));

        // Role: prefer 'initiator' boolean (consistent with OUCH/SBE), fallback to 'role' string
        if (config.hasPath("initiator")) {
            builder.role(config.getBoolean("initiator") ? Role.INITIATOR : Role.ACCEPTOR);
        } else if (config.hasPath("role")) {
            builder.role(Role.valueOf(config.getString("role").toUpperCase()));
        }

        // Optional fields with defaults
        if (config.hasPath("begin-string")) {
            builder.beginString(config.getString("begin-string"));
        }
        if (config.hasPath("heartbeat-interval")) {
            builder.heartbeatInterval(config.getInt("heartbeat-interval"));
        }
        if (config.hasPath("reset-on-logon")) {
            builder.resetOnLogon(config.getBoolean("reset-on-logon"));
        }
        if (config.hasPath("reset-on-logout")) {
            builder.resetOnLogout(config.getBoolean("reset-on-logout"));
        }
        if (config.hasPath("reset-on-disconnect")) {
            builder.resetOnDisconnect(config.getBoolean("reset-on-disconnect"));
        }
        if (config.hasPath("reconnect-interval")) {
            builder.reconnectInterval(config.getInt("reconnect-interval"));
        }
        if (config.hasPath("max-reconnect-attempts")) {
            builder.maxReconnectAttempts(config.getInt("max-reconnect-attempts"));
        }
        if (config.hasPath("time-zone")) {
            builder.timeZone(config.getString("time-zone"));
        }
        if (config.hasPath("reset-on-eod")) {
            builder.resetOnEod(config.getBoolean("reset-on-eod"));
        }
        if (config.hasPath("log-messages")) {
            builder.logMessages(config.getBoolean("log-messages"));
        }
        if (config.hasPath("max-message-length")) {
            builder.maxMessageLength(config.getInt("max-message-length"));
        }
        if (config.hasPath("max-tag-number")) {
            builder.maxTagNumber(config.getInt("max-tag-number"));
        }
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
        if (config.hasPath("schedule")) {
            builder.scheduleName(config.getString("schedule"));
        }

        // FIX 5.0+ version configuration
        if (config.hasPath("fix-version")) {
            builder.fixVersion(FixVersion.fromString(config.getString("fix-version")));
        }
        if (config.hasPath("default-appl-ver-id")) {
            builder.defaultApplVerID(ApplVerID.fromDisplayName(config.getString("default-appl-ver-id")));
        }

        // SSL/TLS configuration
        if (config.hasPath("ssl")) {
            Config sslCfg = config.getConfig("ssl");
            SslConfig.Builder sslBuilder = SslConfig.builder();

            if (sslCfg.hasPath("enabled")) {
                sslBuilder.enabled(sslCfg.getBoolean("enabled"));
            }
            if (sslCfg.hasPath("protocol")) {
                sslBuilder.protocol(sslCfg.getString("protocol"));
            }
            if (sslCfg.hasPath("key-store-path")) {
                sslBuilder.keyStorePath(sslCfg.getString("key-store-path"));
            }
            if (sslCfg.hasPath("key-store-password")) {
                sslBuilder.keyStorePassword(sslCfg.getString("key-store-password"));
            }
            if (sslCfg.hasPath("key-store-type")) {
                sslBuilder.keyStoreType(sslCfg.getString("key-store-type"));
            }
            if (sslCfg.hasPath("key-password")) {
                sslBuilder.keyPassword(sslCfg.getString("key-password"));
            }
            if (sslCfg.hasPath("trust-store-path")) {
                sslBuilder.trustStorePath(sslCfg.getString("trust-store-path"));
            }
            if (sslCfg.hasPath("trust-store-password")) {
                sslBuilder.trustStorePassword(sslCfg.getString("trust-store-password"));
            }
            if (sslCfg.hasPath("trust-store-type")) {
                sslBuilder.trustStoreType(sslCfg.getString("trust-store-type"));
            }
            if (sslCfg.hasPath("client-auth")) {
                sslBuilder.clientAuth(sslCfg.getBoolean("client-auth"));
            }
            if (sslCfg.hasPath("hostname-verification")) {
                sslBuilder.hostnameVerification(sslCfg.getBoolean("hostname-verification"));
            }

            builder.sslConfig(sslBuilder.build());
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

    /**
     * Check if this is an initiator session.
     * Consistent with OUCH and SBE session config API.
     */
    public boolean isInitiator() {
        return role == Role.INITIATOR;
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
     * Get the schedule name for SessionScheduler association.
     */
    public Optional<String> getScheduleName() {
        return Optional.ofNullable(scheduleName);
    }

    /**
     * Get the FIX protocol version.
     *
     * @return the FIX version
     */
    public FixVersion getFixVersion() {
        return fixVersion;
    }

    /**
     * Get the default ApplVerID for FIX 5.0+ sessions.
     *
     * @return the default ApplVerID, or null for FIX 4.x
     */
    public ApplVerID getDefaultApplVerID() {
        return defaultApplVerID;
    }

    /**
     * Check if this session uses FIXT.1.1 transport (FIX 5.0+).
     *
     * @return true if using FIXT.1.1
     */
    public boolean usesFixt() {
        return fixVersion != null && fixVersion.usesFixt();
    }

    /**
     * Get the SSL/TLS configuration.
     *
     * @return the SSL configuration, or null if SSL is not configured
     */
    public SslConfig getSslConfig() {
        return sslConfig;
    }

    /**
     * Check if SSL/TLS is enabled for this session.
     *
     * @return true if SSL is enabled
     */
    public boolean isSslEnabled() {
        return sslConfig != null && sslConfig.isEnabled();
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
        private int maxMessageLength = 4096;
        private int maxTagNumber = 1000;
        private String persistencePath;
        private String scheduleName;
        private FixVersion fixVersion = FixVersion.FIX44;
        private ApplVerID defaultApplVerID = null;
        private SslConfig sslConfig = null;

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

        public Builder scheduleName(String scheduleName) {
            this.scheduleName = scheduleName;
            return this;
        }

        /**
         * Set the FIX protocol version.
         * This automatically sets the appropriate BeginString.
         *
         * @param fixVersion the FIX version
         * @return this builder
         */
        public Builder fixVersion(FixVersion fixVersion) {
            if (fixVersion == null) {
                throw new IllegalArgumentException("FixVersion cannot be null");
            }
            this.fixVersion = fixVersion;
            this.beginString = fixVersion.getBeginString();
            if (fixVersion.getDefaultApplVerID() != null && this.defaultApplVerID == null) {
                this.defaultApplVerID = fixVersion.getDefaultApplVerID();
            }
            return this;
        }

        /**
         * Set the default ApplVerID for FIX 5.0+ sessions.
         *
         * @param applVerID the default application version ID
         * @return this builder
         */
        public Builder defaultApplVerID(ApplVerID applVerID) {
            this.defaultApplVerID = applVerID;
            return this;
        }

        /**
         * Set the SSL/TLS configuration.
         *
         * @param sslConfig the SSL configuration
         * @return this builder
         */
        public Builder sslConfig(SslConfig sslConfig) {
            this.sslConfig = sslConfig;
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
