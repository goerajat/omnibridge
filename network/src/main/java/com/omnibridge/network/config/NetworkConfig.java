package com.omnibridge.network.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueType;

/**
 * Configuration for the network event loop.
 */
public final class NetworkConfig {

    /** The built-in transport: kernel sockets through NIO. */
    public static final String KERNEL_TRANSPORT = "kernel";

    private final String name;
    private final int cpuAffinity;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final int selectTimeoutMs;
    private final boolean busySpinMode;
    private final boolean writeOnLoopCommit;
    private final String transport;
    private final Config transportConfig;

    private NetworkConfig(Builder builder) {
        this.name = builder.name;
        this.cpuAffinity = builder.cpuAffinity;
        this.readBufferSize = builder.readBufferSize;
        this.writeBufferSize = builder.writeBufferSize;
        this.selectTimeoutMs = builder.selectTimeoutMs;
        this.busySpinMode = builder.busySpinMode;
        this.writeOnLoopCommit = builder.writeOnLoopCommit;
        this.transport = builder.transport;
        this.transportConfig = builder.transportConfig;
    }

    /**
     * Create configuration from Typesafe Config. The transport's own settings are the block
     * named after it ({@code network.fstack { ... }}), passed through untouched.
     */
    public static NetworkConfig fromConfig(Config config) {
        String transport = config.hasPath("transport") ? config.getString("transport") : KERNEL_TRANSPORT;
        Config transportConfig = ConfigFactory.empty();
        if (!KERNEL_TRANSPORT.equals(transport) && config.hasPath(transport)
                && config.getValue(transport).valueType() == ConfigValueType.OBJECT) {
            transportConfig = config.getConfig(transport);
        }
        return builder()
                .name(config.getString("name"))
                .cpuAffinity(config.getInt("cpu-affinity"))
                .readBufferSize(config.getInt("read-buffer-size"))
                .writeBufferSize(config.getInt("write-buffer-size"))
                .selectTimeoutMs(config.getInt("select-timeout-ms"))
                .busySpinMode(config.getBoolean("busy-spin-mode"))
                .writeOnLoopCommit(!config.hasPath("write-on-loop-commit") || config.getBoolean("write-on-loop-commit"))
                .transport(transport)
                .transportConfig(transportConfig)
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

    /**
     * Whether a message committed on the event-loop thread is written before commit() returns.
     */
    public boolean isWriteOnLoopCommit() {
        return writeOnLoopCommit;
    }

    /**
     * The transport name: {@value #KERNEL_TRANSPORT} or the name of a TransportProvider.
     */
    public String getTransport() {
        return transport;
    }

    /**
     * The transport's own configuration block (empty for the kernel transport or when absent).
     */
    public Config getTransportConfig() {
        return transportConfig;
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
        private boolean writeOnLoopCommit = true;
        private String transport = KERNEL_TRANSPORT;
        private Config transportConfig = ConfigFactory.empty();

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

        public Builder writeOnLoopCommit(boolean writeOnLoopCommit) {
            this.writeOnLoopCommit = writeOnLoopCommit;
            return this;
        }

        public Builder transport(String transport) {
            this.transport = transport == null || transport.isEmpty() ? KERNEL_TRANSPORT : transport;
            return this;
        }

        public Builder transportConfig(Config transportConfig) {
            this.transportConfig = transportConfig != null ? transportConfig : ConfigFactory.empty();
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
                ", busySpinMode=" + busySpinMode +
                ", writeOnLoopCommit=" + writeOnLoopCommit +
                ", transport='" + transport + '\'' +
                '}';
    }
}
