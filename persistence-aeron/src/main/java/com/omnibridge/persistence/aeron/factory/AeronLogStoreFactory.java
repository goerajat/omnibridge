package com.omnibridge.persistence.aeron.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.aeron.AeronLogStore;
import com.omnibridge.persistence.aeron.config.AeronLogStoreConfig;
import com.omnibridge.persistence.config.PersistenceConfig;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link AeronLogStore} instances.
 *
 * <p>Loaded via reflection from {@code LogStoreFactory} when store-type is AERON.</p>
 */
public class AeronLogStoreFactory implements ComponentFactory<LogStore> {

    @Override
    public LogStore create(String name, Config config, ComponentProvider provider) {
        PersistenceConfig persistenceConfig = PersistenceConfig.fromConfig(config.getConfig("persistence"));
        AeronLogStoreConfig aeronConfig = AeronLogStoreConfig.fromConfig(config.getConfig("persistence"));
        return new AeronLogStore(persistenceConfig, aeronConfig);
    }
}
