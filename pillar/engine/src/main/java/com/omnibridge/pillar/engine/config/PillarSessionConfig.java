package com.omnibridge.pillar.engine.config;

import com.omnibridge.sbe.engine.config.SbeSessionConfig;
import com.typesafe.config.Config;

/**
 * Configuration for a NYSE Pillar session.
 */
public class PillarSessionConfig extends SbeSessionConfig {

    private String username;
    private String password;
    private String mpid;
    private String account;
    private long tgStreamId;
    private long gtStreamId;
    private long heartbeatIntervalNanos = 1_000_000_000L; // 1 second default
    private int protocolVersionMajor = 1;
    private int protocolVersionMinor = 0;
    private byte throttlePreference = 0; // 0=Reject, 1=Queue

    public PillarSessionConfig() {
        super();
    }

    public PillarSessionConfig(String sessionId) {
        super(sessionId);
    }

    /**
     * Load configuration from HOCON config.
     *
     * @param config the HOCON configuration
     */
    @Override
    protected void loadFromConfig(Config config) {
        super.loadFromConfig(config);

        if (config.hasPath("username")) {
            this.username = config.getString("username");
        }
        if (config.hasPath("password")) {
            this.password = config.getString("password");
        }
        if (config.hasPath("mpid")) {
            this.mpid = config.getString("mpid");
        }
        if (config.hasPath("account")) {
            this.account = config.getString("account");
        }
        if (config.hasPath("tg-stream-id")) {
            this.tgStreamId = config.getLong("tg-stream-id");
        }
        if (config.hasPath("gt-stream-id")) {
            this.gtStreamId = config.getLong("gt-stream-id");
        }
        if (config.hasPath("heartbeat-interval")) {
            this.heartbeatIntervalNanos = config.getLong("heartbeat-interval") * 1_000_000L;
        }
        if (config.hasPath("protocol-version-major")) {
            this.protocolVersionMajor = config.getInt("protocol-version-major");
        }
        if (config.hasPath("protocol-version-minor")) {
            this.protocolVersionMinor = config.getInt("protocol-version-minor");
        }
        if (config.hasPath("throttle-preference")) {
            this.throttlePreference = (byte) config.getInt("throttle-preference");
        }
    }

    /**
     * Create a Pillar session config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the session configuration
     */
    public static PillarSessionConfig fromConfig(Config config) {
        PillarSessionConfig sessionConfig = new PillarSessionConfig();
        sessionConfig.loadFromConfig(config);
        return sessionConfig;
    }

    // ==================== Getters ====================

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getMpid() {
        return mpid;
    }

    public String getAccount() {
        return account;
    }

    public long getTgStreamId() {
        return tgStreamId;
    }

    public long getGtStreamId() {
        return gtStreamId;
    }

    public long getHeartbeatIntervalNanos() {
        return heartbeatIntervalNanos;
    }

    public int getProtocolVersionMajor() {
        return protocolVersionMajor;
    }

    public int getProtocolVersionMinor() {
        return protocolVersionMinor;
    }

    public byte getThrottlePreference() {
        return throttlePreference;
    }

    // ==================== Setters ====================

    public PillarSessionConfig setUsername(String username) {
        this.username = username;
        return this;
    }

    public PillarSessionConfig setPassword(String password) {
        this.password = password;
        return this;
    }

    public PillarSessionConfig setMpid(String mpid) {
        this.mpid = mpid;
        return this;
    }

    public PillarSessionConfig setAccount(String account) {
        this.account = account;
        return this;
    }

    public PillarSessionConfig setTgStreamId(long tgStreamId) {
        this.tgStreamId = tgStreamId;
        return this;
    }

    public PillarSessionConfig setGtStreamId(long gtStreamId) {
        this.gtStreamId = gtStreamId;
        return this;
    }

    public PillarSessionConfig setHeartbeatIntervalNanos(long heartbeatIntervalNanos) {
        this.heartbeatIntervalNanos = heartbeatIntervalNanos;
        return this;
    }

    public PillarSessionConfig setProtocolVersionMajor(int protocolVersionMajor) {
        this.protocolVersionMajor = protocolVersionMajor;
        return this;
    }

    public PillarSessionConfig setProtocolVersionMinor(int protocolVersionMinor) {
        this.protocolVersionMinor = protocolVersionMinor;
        return this;
    }

    public PillarSessionConfig setThrottlePreference(byte throttlePreference) {
        this.throttlePreference = throttlePreference;
        return this;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends SbeSessionConfig.Builder<PillarSessionConfig, Builder> {

        public Builder() {
            super(new PillarSessionConfig());
        }

        public Builder username(String username) {
            config.setUsername(username);
            return this;
        }

        public Builder password(String password) {
            config.setPassword(password);
            return this;
        }

        public Builder mpid(String mpid) {
            config.setMpid(mpid);
            return this;
        }

        public Builder account(String account) {
            config.setAccount(account);
            return this;
        }

        public Builder tgStreamId(long tgStreamId) {
            config.setTgStreamId(tgStreamId);
            return this;
        }

        public Builder gtStreamId(long gtStreamId) {
            config.setGtStreamId(gtStreamId);
            return this;
        }

        public Builder heartbeatIntervalNanos(long intervalNanos) {
            config.setHeartbeatIntervalNanos(intervalNanos);
            return this;
        }

        public Builder heartbeatIntervalMillis(long intervalMillis) {
            config.setHeartbeatIntervalNanos(intervalMillis * 1_000_000L);
            return this;
        }

        public Builder protocolVersion(int major, int minor) {
            config.setProtocolVersionMajor(major);
            config.setProtocolVersionMinor(minor);
            return this;
        }

        public Builder throttlePreference(byte preference) {
            config.setThrottlePreference(preference);
            return this;
        }
    }
}
