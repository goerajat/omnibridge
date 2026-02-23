package com.omnibridge.persistence.aeron.config;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side HOCON configuration for AeronLogStore.
 *
 * <p>Example:
 * <pre>
 * persistence {
 *   store-type = "aeron"
 *   base-path = "./data/local-cache"
 *   aeron {
 *     media-driver { embedded = true, aeron-dir = "" }
 *     publisher-id = 1
 *     subscribers = [ { name = "primary", host = "10.0.1.100", data-port = 40456, control-port = 40457 } ]
 *     local-endpoint { host = "0.0.0.0", replay-port = 40458 }
 *     replay { timeout-ms = 30000, max-batch-size = 10000 }
 *     heartbeat-interval-ms = 1000
 *     idle-strategy = "sleeping"
 *   }
 * }
 * </pre>
 */
public final class AeronLogStoreConfig {

    private final boolean embeddedMediaDriver;
    private final String aeronDir;
    private final long publisherId;
    private final List<SubscriberConfig> subscribers;
    private final String localHost;
    private final int replayPort;
    private final long replayTimeoutMs;
    private final int replayMaxBatchSize;
    private final long heartbeatIntervalMs;
    private final String idleStrategy;

    private AeronLogStoreConfig(boolean embeddedMediaDriver, String aeronDir, long publisherId,
                                List<SubscriberConfig> subscribers, String localHost, int replayPort,
                                long replayTimeoutMs, int replayMaxBatchSize,
                                long heartbeatIntervalMs, String idleStrategy) {
        this.embeddedMediaDriver = embeddedMediaDriver;
        this.aeronDir = aeronDir;
        this.publisherId = publisherId;
        this.subscribers = Collections.unmodifiableList(subscribers);
        this.localHost = localHost;
        this.replayPort = replayPort;
        this.replayTimeoutMs = replayTimeoutMs;
        this.replayMaxBatchSize = replayMaxBatchSize;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.idleStrategy = idleStrategy;
    }

    public static AeronLogStoreConfig fromConfig(Config config) {
        Config aeron = config.getConfig("aeron");
        Config mediaDriver = aeron.getConfig("media-driver");
        Config localEndpoint = aeron.getConfig("local-endpoint");
        Config replay = aeron.getConfig("replay");

        List<SubscriberConfig> subs = new ArrayList<>();
        for (Config subConf : aeron.getConfigList("subscribers")) {
            subs.add(SubscriberConfig.fromConfig(subConf));
        }

        return new AeronLogStoreConfig(
                mediaDriver.getBoolean("embedded"),
                mediaDriver.getString("aeron-dir"),
                aeron.getLong("publisher-id"),
                subs,
                localEndpoint.getString("host"),
                localEndpoint.getInt("replay-port"),
                replay.getLong("timeout-ms"),
                replay.getInt("max-batch-size"),
                aeron.getLong("heartbeat-interval-ms"),
                aeron.getString("idle-strategy")
        );
    }

    public boolean isEmbeddedMediaDriver() {
        return embeddedMediaDriver;
    }

    public String getAeronDir() {
        return aeronDir;
    }

    public long getPublisherId() {
        return publisherId;
    }

    public List<SubscriberConfig> getSubscribers() {
        return subscribers;
    }

    public String getLocalHost() {
        return localHost;
    }

    public int getReplayPort() {
        return replayPort;
    }

    public String getReplayChannel() {
        return "aeron:udp?endpoint=" + localHost + ":" + replayPort;
    }

    public long getReplayTimeoutMs() {
        return replayTimeoutMs;
    }

    public int getReplayMaxBatchSize() {
        return replayMaxBatchSize;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public String getIdleStrategy() {
        return idleStrategy;
    }

    @Override
    public String toString() {
        return "AeronLogStoreConfig{publisherId=" + publisherId +
                ", subscribers=" + subscribers.size() +
                ", replayPort=" + replayPort +
                ", idleStrategy='" + idleStrategy + "'}";
    }
}
