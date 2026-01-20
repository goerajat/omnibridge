package com.fixengine.config.provider;

import com.fixengine.config.FixEngineConfig;
import com.typesafe.config.Config;

import java.io.Closeable;
import java.util.List;

/**
 * Main provider interface for FIX engine components.
 *
 * <p>The ComponentProvider is responsible for:
 * <ul>
 *   <li>Loading configuration from files</li>
 *   <li>Creating and managing component instances</li>
 *   <li>Providing access to individual component providers</li>
 *   <li>Managing component lifecycle</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * ComponentProvider provider = ComponentProvider.create("config/base.conf", "config/prod.conf");
 *
 * // Get configuration
 * FixEngineConfig config = provider.getEngineConfig();
 *
 * // Get component providers
 * NetworkEventLoopProvider networkProvider = provider.getNetworkProvider();
 * PersistenceStoreProvider persistenceProvider = provider.getPersistenceProvider();
 *
 * // Start the engine
 * FixEngineProvider engineProvider = provider.getEngineProvider();
 * engineProvider.start();
 * }</pre>
 *
 * @param <N> NetworkEventLoop type
 * @param <P> PersistenceStore type
 * @param <E> FixEngine type
 */
public interface ComponentProvider<N, P, E> extends Closeable {

    /**
     * Get the raw Typesafe Config.
     */
    Config getRawConfig();

    /**
     * Get the parsed FIX engine configuration.
     */
    FixEngineConfig getEngineConfig();

    /**
     * Get the network event loop provider.
     */
    NetworkEventLoopProvider<N> getNetworkProvider();

    /**
     * Get the persistence store provider.
     */
    PersistenceStoreProvider<P> getPersistenceProvider();

    /**
     * Get the FIX engine provider.
     */
    FixEngineProvider<E> getEngineProvider();

    /**
     * Initialize all components.
     * This creates all component instances but does not start them.
     */
    void initialize() throws Exception;

    /**
     * Start all components.
     */
    void start() throws Exception;

    /**
     * Stop all components gracefully.
     */
    void stop();

    /**
     * Check if components are initialized.
     */
    boolean isInitialized();

    /**
     * Check if components are running.
     */
    boolean isRunning();

    /**
     * Create a ComponentProvider from config file paths.
     *
     * @param configFiles paths to config files
     * @param <N> NetworkEventLoop type
     * @param <P> PersistenceStore type
     * @param <E> FixEngine type
     * @return component provider
     */
    static <N, P, E> ComponentProvider<N, P, E> create(String... configFiles) {
        return create(List.of(configFiles));
    }

    /**
     * Create a ComponentProvider from config file paths.
     *
     * @param configFiles list of paths to config files
     * @param <N> NetworkEventLoop type
     * @param <P> PersistenceStore type
     * @param <E> FixEngine type
     * @return component provider
     */
    @SuppressWarnings("unchecked")
    static <N, P, E> ComponentProvider<N, P, E> create(List<String> configFiles) {
        return (ComponentProvider<N, P, E>) DefaultComponentProvider.create(configFiles);
    }

    /**
     * Create a ComponentProvider with a pre-built FixEngineConfig.
     *
     * @param config the engine configuration
     * @param <N> NetworkEventLoop type
     * @param <P> PersistenceStore type
     * @param <E> FixEngine type
     * @return component provider
     */
    @SuppressWarnings("unchecked")
    static <N, P, E> ComponentProvider<N, P, E> create(FixEngineConfig config) {
        return (ComponentProvider<N, P, E>) DefaultComponentProvider.create(config);
    }
}
