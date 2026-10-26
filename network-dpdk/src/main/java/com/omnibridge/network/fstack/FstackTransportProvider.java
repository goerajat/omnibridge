package com.omnibridge.network.fstack;

import com.omnibridge.network.Transport;
import com.omnibridge.network.TransportProvider;
import com.omnibridge.network.config.NetworkConfig;
import com.typesafe.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code network.transport = fstack}, configured by {@code network.fstack { conf, library }}.
 * Found through {@link java.util.ServiceLoader}; nothing here is loaded unless the name is
 * configured.
 */
public final class FstackTransportProvider implements TransportProvider {

    static final String DEFAULT_CONF = "/opt/fstack/fstack.ini";
    static final String DEFAULT_LIBRARY = "/opt/fstack/libomnibridge-fstack.so";

    @Override
    public String name() {
        return FstackTransport.NAME;
    }

    @Override
    public Transport create(NetworkConfig config, Config transportConfig) {
        String conf = transportConfig.hasPath("conf") ? transportConfig.getString("conf") : DEFAULT_CONF;
        String library = transportConfig.hasPath("library") ? transportConfig.getString("library") : DEFAULT_LIBRARY;
        Path libraryPath = Path.of(library);
        if (!Files.isRegularFile(libraryPath)) {
            throw new IllegalArgumentException("network.fstack.library not found: " + library);
        }
        if (!Files.isRegularFile(Path.of(conf))) {
            throw new IllegalArgumentException("network.fstack.conf not found: " + conf);
        }
        return new FstackTransport(new FfmFstackNative(libraryPath), conf);
    }
}
