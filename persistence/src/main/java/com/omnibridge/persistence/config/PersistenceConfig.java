package com.omnibridge.persistence.config;

import com.typesafe.config.Config;

/**
 * Configuration for the persistence store.
 */
public final class PersistenceConfig {

    /**
     * Type of persistence store to use.
     */
    public enum StoreType {
        MEMORY_MAPPED,
        CHRONICLE,
        AERON,
        NONE
    }

    private final boolean enabled;
    private final StoreType storeType;
    private final String basePath;
    private final long maxFileSize;
    private final boolean syncOnWrite;
    private final int maxStreams;
    private final long pretouchAhead;
    private final long pretouchIntervalMs;

    private PersistenceConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.storeType = builder.storeType;
        this.basePath = builder.basePath;
        this.maxFileSize = builder.maxFileSize;
        this.syncOnWrite = builder.syncOnWrite;
        this.maxStreams = builder.maxStreams;
        this.pretouchAhead = builder.pretouchAhead;
        this.pretouchIntervalMs = builder.pretouchIntervalMs;
    }

    /**
     * Create configuration from Typesafe Config.
     */
    public static PersistenceConfig fromConfig(Config config) {
        Builder builder = builder()
                .enabled(config.getBoolean("enabled"))
                .storeType(StoreType.valueOf(config.getString("store-type").toUpperCase().replace("-", "_")))
                .basePath(config.getString("base-path"))
                .maxFileSize(config.getBytes("max-file-size"))
                .syncOnWrite(config.getBoolean("sync-on-write"))
                .maxStreams(config.getInt("max-streams"));
        if (config.hasPath("pretouch-ahead")) {
            builder.pretouchAhead(config.getBytes("pretouch-ahead"));
        }
        if (config.hasPath("pretouch-interval-ms")) {
            builder.pretouchIntervalMs(config.getLong("pretouch-interval-ms"));
        }
        return builder.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public StoreType getStoreType() {
        return storeType;
    }

    public String getBasePath() {
        return basePath;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public boolean isSyncOnWrite() {
        return syncOnWrite;
    }

    /** Bytes of journal the Chronicle store keeps faulted in ahead of the write position; 0 turns the pretoucher off. */
    public long getPretouchAhead() {
        return pretouchAhead;
    }

    /** How often the pretouch thread runs. */
    public long getPretouchIntervalMs() {
        return pretouchIntervalMs;
    }

    public int getMaxStreams() {
        return maxStreams;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private StoreType storeType = StoreType.MEMORY_MAPPED;
        private String basePath = "./fix-logs";
        private long maxFileSize = 256 * 1024 * 1024; // 256MB
        private boolean syncOnWrite = false;
        private int maxStreams = 100;
        private long pretouchAhead = 1024 * 1024;
        private long pretouchIntervalMs = 10;

        private Builder() {}

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder storeType(StoreType storeType) {
            this.storeType = storeType;
            return this;
        }

        public Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public Builder maxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
            return this;
        }

        public Builder syncOnWrite(boolean syncOnWrite) {
            this.syncOnWrite = syncOnWrite;
            return this;
        }

        public Builder pretouchAhead(long pretouchAhead) {
            this.pretouchAhead = pretouchAhead;
            return this;
        }

        public Builder pretouchIntervalMs(long pretouchIntervalMs) {
            this.pretouchIntervalMs = pretouchIntervalMs;
            return this;
        }

        public Builder maxStreams(int maxStreams) {
            this.maxStreams = maxStreams;
            return this;
        }

        public PersistenceConfig build() {
            return new PersistenceConfig(this);
        }
    }

    @Override
    public String toString() {
        return "PersistenceConfig{" +
                "enabled=" + enabled +
                ", storeType=" + storeType +
                ", basePath='" + basePath + '\'' +
                ", maxFileSize=" + maxFileSize +
                ", syncOnWrite=" + syncOnWrite +
                ", maxStreams=" + maxStreams +
                '}';
    }
}
