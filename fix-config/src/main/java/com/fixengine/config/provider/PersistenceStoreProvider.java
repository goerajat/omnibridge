package com.fixengine.config.provider;

import com.fixengine.config.PersistenceConfig;

/**
 * Provider for persistence store component.
 *
 * @param <T> the persistence store type
 */
public interface PersistenceStoreProvider<T> {

    /**
     * Get the persistence configuration.
     */
    PersistenceConfig getConfig();

    /**
     * Get or create the persistence store instance.
     * Multiple calls return the same instance.
     * Returns null if persistence is disabled.
     */
    T get() throws Exception;

    /**
     * Check if the persistence store has been created.
     */
    boolean isCreated();

    /**
     * Check if persistence is enabled.
     */
    boolean isEnabled();

    /**
     * Close the persistence store and release resources.
     */
    void close();
}
