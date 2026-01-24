package com.omnibridge.persistence.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import com.omnibridge.persistence.memory.MemoryMappedLogStore;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link LogStore} instances.
 *
 * <p>Creates a log store from the "persistence" configuration section.
 * Currently supports memory-mapped log stores.</p>
 */
public class LogStoreFactory implements ComponentFactory<LogStore> {

    @Override
    public LogStore create(String name, Config config, ComponentProvider provider) {
        // Use the 'persistence' config section directly
        PersistenceConfig persistenceConfig = PersistenceConfig.fromConfig(config.getConfig("persistence"));
        return new MemoryMappedLogStore(persistenceConfig, provider);
    }
}
