package com.omnibridge.optiq.engine.config;

import com.omnibridge.sbe.engine.config.SbeSessionConfig;
import com.typesafe.config.Config;

/**
 * Configuration for an Optiq session.
 * <p>
 * Extends SbeSessionConfig with Euronext-specific options.
 */
public class OptiqSessionConfig extends SbeSessionConfig {

    /** Logical access identifier */
    private int logicalAccessId;

    /** OEG instance ID */
    private int oegInstance;

    /** Partition ID */
    private int partitionId;

    /** Firm ID */
    private int firmId;

    /** Software provider identifier */
    private String softwareProvider;

    /** Default schema version */
    private static final int DEFAULT_SCHEMA_VERSION = 1;

    public OptiqSessionConfig() {
        setSchemaVersion(DEFAULT_SCHEMA_VERSION);
    }

    public OptiqSessionConfig(String sessionId) {
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

        if (config.hasPath("logical-access-id")) {
            this.logicalAccessId = config.getInt("logical-access-id");
        }
        if (config.hasPath("oeg-instance")) {
            this.oegInstance = config.getInt("oeg-instance");
        }
        if (config.hasPath("partition-id")) {
            this.partitionId = config.getInt("partition-id");
        }
        if (config.hasPath("firm-id")) {
            this.firmId = config.getInt("firm-id");
        }
        if (config.hasPath("software-provider")) {
            this.softwareProvider = config.getString("software-provider");
        }
    }

    /**
     * Create an Optiq session config from HOCON config.
     *
     * @param config the HOCON configuration
     * @return the session configuration
     */
    public static OptiqSessionConfig fromConfig(Config config) {
        OptiqSessionConfig sessionConfig = new OptiqSessionConfig();
        sessionConfig.loadFromConfig(config);
        return sessionConfig;
    }

    // Getters and setters

    public int getLogicalAccessId() {
        return logicalAccessId;
    }

    public void setLogicalAccessId(int logicalAccessId) {
        this.logicalAccessId = logicalAccessId;
    }

    public int getOegInstance() {
        return oegInstance;
    }

    public void setOegInstance(int oegInstance) {
        this.oegInstance = oegInstance;
    }

    public int getPartitionId() {
        return partitionId;
    }

    public void setPartitionId(int partitionId) {
        this.partitionId = partitionId;
    }

    public int getFirmId() {
        return firmId;
    }

    public void setFirmId(int firmId) {
        this.firmId = firmId;
    }

    public String getSoftwareProvider() {
        return softwareProvider;
    }

    public void setSoftwareProvider(String softwareProvider) {
        this.softwareProvider = softwareProvider;
    }

    /**
     * Builder for OptiqSessionConfig.
     */
    public static class Builder extends SbeSessionConfig.Builder<OptiqSessionConfig, Builder> {

        public Builder() {
            super(new OptiqSessionConfig());
        }

        public Builder logicalAccessId(int id) {
            config.setLogicalAccessId(id);
            return this;
        }

        public Builder oegInstance(int instance) {
            config.setOegInstance(instance);
            return this;
        }

        public Builder partitionId(int id) {
            config.setPartitionId(id);
            return this;
        }

        public Builder firmId(int id) {
            config.setFirmId(id);
            return this;
        }

        public Builder softwareProvider(String provider) {
            config.setSoftwareProvider(provider);
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
