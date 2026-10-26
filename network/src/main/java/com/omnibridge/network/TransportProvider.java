package com.omnibridge.network;

import com.omnibridge.network.config.NetworkConfig;
import com.typesafe.config.Config;

import java.io.IOException;

/**
 * Creates a {@link Transport} by name. Implementations outside this module are found with
 * {@link java.util.ServiceLoader}; the kernel transport is built in.
 */
public interface TransportProvider {

    /** The value of {@code network.transport} this provider serves. */
    String name();

    /**
     * @param config the loop's configuration
     * @param transportConfig the {@code network.<name>} block, empty when absent
     */
    Transport create(NetworkConfig config, Config transportConfig) throws IOException;
}
