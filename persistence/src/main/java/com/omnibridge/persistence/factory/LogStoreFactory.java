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
 * Supports memory-mapped, Chronicle Queue, and Aeron remote log stores.</p>
 */
public class LogStoreFactory implements ComponentFactory<LogStore> {

    @Override
    public LogStore create(String name, Config config, ComponentProvider provider) {
        PersistenceConfig persistenceConfig = PersistenceConfig.fromConfig(config.getConfig("persistence"));
        return switch (persistenceConfig.getStoreType()) {
            case MEMORY_MAPPED -> new MemoryMappedLogStore(persistenceConfig, provider);
            case CHRONICLE -> new ChronicleLogStore(persistenceConfig, provider);
            case AERON -> createAeronLogStore(name, config, provider);
            case NONE -> throw new IllegalArgumentException("Persistence store type NONE is not a valid runtime configuration");
        };
    }

    @SuppressWarnings("unchecked")
    private LogStore createAeronLogStore(String name, Config config, ComponentProvider provider) {
        try {
            Class<?> factoryClass = Class.forName(
                    "com.omnibridge.persistence.aeron.factory.AeronLogStoreFactory");
            ComponentFactory<LogStore> factory =
                    (ComponentFactory<LogStore>) factoryClass.getDeclaredConstructor().newInstance();
            return factory.create(name, config, provider);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Aeron persistence module (persistence-aeron) is not on the classpath. " +
                    "Add com.omnibridge:persistence-aeron dependency.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create AeronLogStore", e);
        }
    }
}
