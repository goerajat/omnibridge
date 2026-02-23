package com.omnibridge.persistence.aeron.config;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-side HOCON configuration for AeronRemoteStore.
 *
 * <p>Example:
 * <pre>
 * aeron-remote-store {
 *   base-path = "./data/remote-store"
 *   aeron {
 *     media-driver { embedded = true, aeron-dir = "" }
 *     listen { host = "0.0.0.0", data-port = 40456, control-port = 40457 }
 *     engines = [ { name = "engine-1", host = "10.0.0.50", replay-port = 40458 } ]
 *     idle-strategy = "sleeping"
 *     fragment-limit = 256
 *   }
 * }
 * </pre>
 */
public final class AeronRemoteStoreConfig {

    private final String basePath;
    private final boolean embeddedMediaDriver;
    private final String aeronDir;
    private final String listenHost;
    private final int dataPort;
    private final int controlPort;
    private final List<EngineConfig> engines;
    private final String idleStrategy;
    private final int fragmentLimit;

    private AeronRemoteStoreConfig(String basePath, boolean embeddedMediaDriver, String aeronDir,
                                   String listenHost, int dataPort, int controlPort,
                                   List<EngineConfig> engines, String idleStrategy, int fragmentLimit) {
        this.basePath = basePath;
        this.embeddedMediaDriver = embeddedMediaDriver;
        this.aeronDir = aeronDir;
        this.listenHost = listenHost;
        this.dataPort = dataPort;
        this.controlPort = controlPort;
        this.engines = Collections.unmodifiableList(engines);
        this.idleStrategy = idleStrategy;
        this.fragmentLimit = fragmentLimit;
    }

    public static AeronRemoteStoreConfig fromConfig(Config config) {
        Config aeron = config.getConfig("aeron");
        Config mediaDriver = aeron.getConfig("media-driver");
        Config listen = aeron.getConfig("listen");

        List<EngineConfig> engineList = new ArrayList<>();
        for (Config engConf : aeron.getConfigList("engines")) {
            engineList.add(EngineConfig.fromConfig(engConf));
        }

        return new AeronRemoteStoreConfig(
                config.getString("base-path"),
                mediaDriver.getBoolean("embedded"),
                mediaDriver.getString("aeron-dir"),
                listen.getString("host"),
                listen.getInt("data-port"),
                listen.getInt("control-port"),
                engineList,
                aeron.getString("idle-strategy"),
                aeron.getInt("fragment-limit")
        );
    }

    public String getBasePath() {
        return basePath;
    }

    public boolean isEmbeddedMediaDriver() {
        return embeddedMediaDriver;
    }

    public String getAeronDir() {
        return aeronDir;
    }

    public String getListenHost() {
        return listenHost;
    }

    public int getDataPort() {
        return dataPort;
    }

    public int getControlPort() {
        return controlPort;
    }

    public String getDataChannel() {
        return "aeron:udp?endpoint=" + listenHost + ":" + dataPort;
    }

    public String getControlChannel() {
        return "aeron:udp?endpoint=" + listenHost + ":" + controlPort;
    }

    public List<EngineConfig> getEngines() {
        return engines;
    }

    public String getIdleStrategy() {
        return idleStrategy;
    }

    public int getFragmentLimit() {
        return fragmentLimit;
    }

    /**
     * Configuration for a known engine that can request replays.
     */
    public static final class EngineConfig {
        private final String name;
        private final String host;
        private final int replayPort;
        private final long publisherId;

        private EngineConfig(String name, String host, int replayPort, long publisherId) {
            this.name = name;
            this.host = host;
            this.replayPort = replayPort;
            this.publisherId = publisherId;
        }

        public static EngineConfig fromConfig(Config config) {
            long pubId = config.hasPath("publisher-id") ? config.getLong("publisher-id") : 0;
            return new EngineConfig(
                    config.getString("name"),
                    config.getString("host"),
                    config.getInt("replay-port"),
                    pubId
            );
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public int getReplayPort() {
            return replayPort;
        }

        public long getPublisherId() {
            return publisherId;
        }

        public String getReplayChannel() {
            return "aeron:udp?endpoint=" + host + ":" + replayPort;
        }

        @Override
        public String toString() {
            return "EngineConfig{name='" + name + "', host='" + host +
                    "', replayPort=" + replayPort + ", publisherId=" + publisherId + '}';
        }
    }

    @Override
    public String toString() {
        return "AeronRemoteStoreConfig{basePath='" + basePath +
                "', dataPort=" + dataPort + ", controlPort=" + controlPort +
                ", engines=" + engines.size() + ", idleStrategy='" + idleStrategy + "'}";
    }
}
