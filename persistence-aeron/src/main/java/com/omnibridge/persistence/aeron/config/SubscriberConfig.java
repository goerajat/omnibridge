package com.omnibridge.persistence.aeron.config;

import com.typesafe.config.Config;

/**
 * Configuration for a single remote subscriber endpoint.
 */
public final class SubscriberConfig {

    private final String name;
    private final String host;
    private final int dataPort;
    private final int controlPort;

    private SubscriberConfig(String name, String host, int dataPort, int controlPort) {
        this.name = name;
        this.host = host;
        this.dataPort = dataPort;
        this.controlPort = controlPort;
    }

    public static SubscriberConfig fromConfig(Config config) {
        return new SubscriberConfig(
                config.getString("name"),
                config.getString("host"),
                config.getInt("data-port"),
                config.getInt("control-port")
        );
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public int getDataPort() {
        return dataPort;
    }

    public int getControlPort() {
        return controlPort;
    }

    public String getDataChannel() {
        return "aeron:udp?endpoint=" + host + ":" + dataPort;
    }

    public String getControlChannel() {
        return "aeron:udp?endpoint=" + host + ":" + controlPort;
    }

    @Override
    public String toString() {
        return "SubscriberConfig{name='" + name + "', host='" + host +
                "', dataPort=" + dataPort + ", controlPort=" + controlPort + '}';
    }
}
