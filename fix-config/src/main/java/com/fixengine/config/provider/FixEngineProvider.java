package com.fixengine.config.provider;

import com.fixengine.config.EngineSessionConfig;
import com.fixengine.config.FixEngineConfig;

import java.util.List;

/**
 * Provider for FIX engine component.
 *
 * @param <T> the FIX engine type
 */
public interface FixEngineProvider<T> {

    /**
     * Get the FIX engine configuration.
     */
    FixEngineConfig getConfig();

    /**
     * Get the list of session configurations.
     */
    List<EngineSessionConfig> getSessionConfigs();

    /**
     * Get or create the FIX engine instance.
     * Multiple calls return the same instance.
     */
    T get() throws Exception;

    /**
     * Check if the FIX engine has been created.
     */
    boolean isCreated();

    /**
     * Check if the FIX engine is running.
     */
    boolean isRunning();

    /**
     * Start the FIX engine.
     */
    void start() throws Exception;

    /**
     * Stop the FIX engine gracefully.
     */
    void stop();

    /**
     * Get the network event loop provider.
     */
    NetworkEventLoopProvider<?> getNetworkProvider();

    /**
     * Get the persistence store provider.
     */
    PersistenceStoreProvider<?> getPersistenceProvider();
}
