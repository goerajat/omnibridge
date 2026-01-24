package com.omnibridge.config.factory;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link ClockProvider} instances.
 *
 * <p>By default, creates a system clock provider. Can be configured to use
 * a fixed time for testing purposes.</p>
 */
public class ClockProviderFactory implements ComponentFactory<ClockProvider> {

    @Override
    public ClockProvider create(String name, Config config, ComponentProvider provider) {
        return ClockProvider.system();
    }
}
