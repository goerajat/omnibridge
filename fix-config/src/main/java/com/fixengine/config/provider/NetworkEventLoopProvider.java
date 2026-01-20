package com.fixengine.config.provider;

import com.fixengine.config.NetworkConfig;

/**
 * Provider for network event loop component.
 *
 * @param <T> the network event loop type
 */
public interface NetworkEventLoopProvider<T> {

    /**
     * Get the network configuration.
     */
    NetworkConfig getConfig();

    /**
     * Get or create the network event loop instance.
     * Multiple calls return the same instance.
     */
    T get() throws Exception;

    /**
     * Check if the network event loop has been created.
     */
    boolean isCreated();

    /**
     * Check if the network event loop is running.
     */
    boolean isRunning();

    /**
     * Start the network event loop.
     */
    void start() throws Exception;

    /**
     * Stop the network event loop.
     */
    void stop();
}
