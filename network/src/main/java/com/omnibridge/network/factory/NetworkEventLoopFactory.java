package com.omnibridge.network.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.network.NetworkEventLoop;
import com.omnibridge.network.config.NetworkConfig;
import com.typesafe.config.Config;

import java.io.IOException;

/**
 * Factory for creating {@link NetworkEventLoop} instances.
 *
 * <p>Creates a network event loop from the "network" configuration section.</p>
 */
public class NetworkEventLoopFactory implements ComponentFactory<NetworkEventLoop> {

    @Override
    public NetworkEventLoop create(String name, Config config, ComponentProvider provider) throws IOException {
        // Use the 'network' config section directly
        NetworkConfig networkConfig = NetworkConfig.fromConfig(config.getConfig("network"));
        return new NetworkEventLoop(networkConfig, provider);
    }
}
