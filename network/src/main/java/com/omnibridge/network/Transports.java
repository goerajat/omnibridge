package com.omnibridge.network;

import com.omnibridge.network.config.NetworkConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.IOException;
import java.util.ServiceLoader;

/**
 * Transport lookup: {@value #KERNEL} is the built-in {@link NioTransport}; any other name is
 * served by a {@link TransportProvider} on the class path. The provider classes of other
 * transports are only loaded when their name is configured.
 */
public final class Transports {

    public static final String KERNEL = "kernel";

    private Transports() {
    }

    public static Transport create(NetworkConfig config) throws IOException {
        String name = config.getTransport();
        if (name == null || name.isEmpty() || KERNEL.equals(name)) {
            return new NioTransport();
        }
        Config transportConfig = config.getTransportConfig();
        if (transportConfig == null) {
            transportConfig = ConfigFactory.empty();
        }
        for (TransportProvider provider : ServiceLoader.load(TransportProvider.class)) {
            if (name.equals(provider.name())) {
                Transport transport = provider.create(config, transportConfig);
                validate(config, transport);
                return transport;
            }
        }
        throw new IllegalArgumentException("Unknown network.transport '" + name
                + "': no TransportProvider of that name on the class path");
    }

    /** The loop settings a transport needs, checked before the loop starts. */
    static void validate(NetworkConfig config, Transport transport) {
        if (!transport.supportsBlockingPoll() && !config.isBusySpinMode()) {
            throw new IllegalArgumentException("network.transport '" + transport.name()
                    + "' requires network.busy-spin-mode = true");
        }
        if (transport.opensOnLoopThread() && config.getCpuAffinity() < 0) {
            throw new IllegalArgumentException("network.transport '" + transport.name()
                    + "' requires network.cpu-affinity >= 0 (its poller is pinned to that core)");
        }
    }
}
