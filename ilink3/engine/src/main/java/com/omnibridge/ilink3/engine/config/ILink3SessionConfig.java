package com.omnibridge.ilink3.engine.config;

import com.omnibridge.sbe.engine.config.SbeSessionConfig;
import com.typesafe.config.Config;

/**
 * Configuration for an iLink 3 session.
 * <p>
 * Extends SbeSessionConfig with CME-specific options.
 */
public class ILink3SessionConfig extends SbeSessionConfig {

    /** CME-assigned firm identifier */
    private String firmId;

    /** Session identifier (3 characters) */
    private String session;

    /** Access key ID for HMAC authentication */
    private String accessKeyId;

    /** Secret key for HMAC authentication */
    private String secretKey;

    /** Market segment ID (e.g., CME, CBOT, NYMEX) */
    private int marketSegmentId;

    /** Session UUID (assigned by CME) */
    private long uuid;

    /** Keep alive interval in milliseconds */
    private int keepAliveInterval = 30000;

    /** Enable HMAC authentication */
    private boolean useHmac = true;

    /** Default schema version */
    private static final int DEFAULT_SCHEMA_VERSION = 9;

    public ILink3SessionConfig() {
        setSchemaVersion(DEFAULT_SCHEMA_VERSION);
    }

    public ILink3SessionConfig(String sessionId) {
        super(sessionId);
        setSchemaVersion(DEFAULT_SCHEMA_VERSION);
    }

    /**
     * Load configuration from HOCON config.
     *
     * @param config the HOCON configuration
     */
    @Override
    protected void loadFromConfig(Config config) {
        super.loadFromConfig(config);

        if (config.hasPath("firm-id")) {
            this.firmId = config.getString("firm-id");
        }
        if (config.hasPath("session")) {
            this.session = config.getString("session");
        }
        if (config.hasPath("access-key-id")) {
            this.accessKeyId = config.getString("access-key-id");
        }
        if (config.hasPath("secret-key")) {
            this.secretKey = config.getString("secret-key");
        }
        if (config.hasPath("market-segment-id")) {
            this.marketSegmentId = config.getInt("market-segment-id");
        }
        if (config.hasPath("uuid")) {
            this.uuid = config.getLong("uuid");
        }
        if (config.hasPath("keep-alive-interval")) {
            this.keepAliveInterval = config.getInt("keep-alive-interval");
        }
        if (config.hasPath("use-hmac")) {
            this.useHmac = config.getBoolean("use-hmac");
        }
    }

    /**
     * Create an iLink 3 session config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the session configuration
     */
    public static ILink3SessionConfig fromConfig(Config config) {
        ILink3SessionConfig sessionConfig = new ILink3SessionConfig();
        sessionConfig.loadFromConfig(config);
        return sessionConfig;
    }

    // Getters and setters

    public String getFirmId() {
        return firmId;
    }

    public void setFirmId(String firmId) {
        this.firmId = firmId;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getMarketSegmentId() {
        return marketSegmentId;
    }

    public void setMarketSegmentId(int marketSegmentId) {
        this.marketSegmentId = marketSegmentId;
    }

    public long getUuid() {
        return uuid;
    }

    public void setUuid(long uuid) {
        this.uuid = uuid;
    }

    public int getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public void setKeepAliveInterval(int keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }

    public boolean isUseHmac() {
        return useHmac;
    }

    public void setUseHmac(boolean useHmac) {
        this.useHmac = useHmac;
    }

    /**
     * Builder for ILink3SessionConfig.
     */
    public static class Builder extends SbeSessionConfig.Builder<ILink3SessionConfig, Builder> {

        public Builder() {
            super(new ILink3SessionConfig());
        }

        public Builder firmId(String firmId) {
            config.setFirmId(firmId);
            return this;
        }

        public Builder session(String session) {
            config.setSession(session);
            return this;
        }

        public Builder accessKeyId(String accessKeyId) {
            config.setAccessKeyId(accessKeyId);
            return this;
        }

        public Builder secretKey(String secretKey) {
            config.setSecretKey(secretKey);
            return this;
        }

        public Builder marketSegmentId(int marketSegmentId) {
            config.setMarketSegmentId(marketSegmentId);
            return this;
        }

        public Builder uuid(long uuid) {
            config.setUuid(uuid);
            return this;
        }

        public Builder keepAliveInterval(int interval) {
            config.setKeepAliveInterval(interval);
            return this;
        }

        public Builder useHmac(boolean useHmac) {
            config.setUseHmac(useHmac);
            return this;
        }
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
