package com.omnibridge.network.config;

import com.typesafe.config.Config;

/**
 * Configuration for the network event loop.
 */
public final class NetworkConfig {

    private final String name;
    private final int cpuAffinity;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final int selectTimeoutMs;
    private final boolean busySpinMode;

    private NetworkConfig(Builder builder) {
        this.name = builder.name;
        this.cpuAffinity = builder.cpuAffinity;
        this.readBufferSize = builder.readBufferSize;
        this.writeBufferSize = builder.writeBufferSize;
        this.selectTimeoutMs = builder.selectTimeoutMs;
        this.busySpinMode = builder.busySpinMode;
    }

    /**
     * Create configuration from Typesafe Config.
     */
    public static NetworkConfig fromConfig(Config config) {
        return builder()
                .name(config.getString("name"))
                .cpuAffinity(config.getInt("cpu-affinity"))
                .readBufferSize(config.getInt("read-buffer-size"))
                .writeBufferSize(config.getInt("write-buffer-size"))
                .selectTimeoutMs(config.getInt("select-timeout-ms"))
                .busySpinMode(config.getBoolean("busy-spin-mode"))
                .build();
    }

    public String getName() {
        return name;
    }

    public int getCpuAffinity() {
        return cpuAffinity;
    }

    public int getReadBufferSize() {
        return readBufferSize;
    }

    public int getWriteBufferSize() {
        return writeBufferSize;
    }

    public int getSelectTimeoutMs() {
        return selectTimeoutMs;
    }

    public boolean isBusySpinMode() {
        return busySpinMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "fix-event-loop";
        private int cpuAffinity = -1;
        private int readBufferSize = 65536;
        private int writeBufferSize = 65536;
        private int selectTimeoutMs = 100;
        private boolean busySpinMode = false;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder cpuAffinity(int cpuAffinity) {
            this.cpuAffinity = cpuAffinity;
            return this;
        }

        public Builder readBufferSize(int readBufferSize) {
            this.readBufferSize = readBufferSize;
            return this;
        }

        public Builder writeBufferSize(int writeBufferSize) {
            this.writeBufferSize = writeBufferSize;
            return this;
        }

        public Builder selectTimeoutMs(int selectTimeoutMs) {
            this.selectTimeoutMs = selectTimeoutMs;
            return this;
        }

        public Builder busySpinMode(boolean busySpinMode) {
            this.busySpinMode = busySpinMode;
            return this;
        }

        public NetworkConfig build() {
            return new NetworkConfig(this);
        }
    }

    @Override
    public String toString() {
        return "NetworkConfig{" +
                "name='" + name + '\'' +
                ", cpuAffinity=" + cpuAffinity +
                ", readBufferSize=" + readBufferSize +
                ", writeBufferSize=" + writeBufferSize +
                ", selectTimeoutMs=" + selectTimeoutMs +
                '}';
    }
}
