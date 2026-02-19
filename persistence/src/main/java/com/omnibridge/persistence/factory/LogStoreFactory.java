package com.omnibridge.persistence.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import com.omnibridge.persistence.memory.MemoryMappedLogStore;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link LogStore} instances.
 *
 * <p>Creates a log store from the "persistence" configuration section.
 * Supports memory-mapped and Chronicle Queue log stores.</p>
 */
public class LogStoreFactory implements ComponentFactory<LogStore> {

    @Override
    public LogStore create(String name, Config config, ComponentProvider provider) {
        PersistenceConfig persistenceConfig = PersistenceConfig.fromConfig(config.getConfig("persistence"));
        return switch (persistenceConfig.getStoreType()) {
            case MEMORY_MAPPED -> new MemoryMappedLogStore(persistenceConfig, provider);
            case CHRONICLE -> new ChronicleLogStore(persistenceConfig, provider);
            case NONE -> throw new IllegalArgumentException("Persistence store type NONE is not a valid runtime configuration");
        };
    }
}
