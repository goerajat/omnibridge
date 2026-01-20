package com.fixengine.config.provider;

import com.fixengine.config.*;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of ComponentProvider.
 *
 * <p>This provider uses factory interfaces to create components, allowing
 * the actual component implementations to be defined in other modules.</p>
 */
public class DefaultComponentProvider implements ComponentProvider<Object, Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(DefaultComponentProvider.class);

    private final Config rawConfig;
    private final NetworkConfig networkConfig;
    private final PersistenceConfig persistenceConfig;
    private final FixEngineConfig engineConfig;

    private NetworkEventLoopProvider<Object> networkProvider;
    private PersistenceStoreProvider<Object> persistenceProvider;
    private FixEngineProvider<Object> fixEngineProvider;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Factory interfaces for component creation
    private NetworkEventLoopFactory networkFactory;
    private PersistenceStoreFactory persistenceFactory;
    private FixEngineFactory fixEngineFactory;

    private DefaultComponentProvider(Config rawConfig, NetworkConfig networkConfig,
                                     PersistenceConfig persistenceConfig, FixEngineConfig engineConfig) {
        this.rawConfig = rawConfig;
        this.networkConfig = networkConfig;
        this.persistenceConfig = persistenceConfig;
        this.engineConfig = engineConfig;
    }

    /**
     * Create a provider from config file paths.
     */
    public static DefaultComponentProvider create(List<String> configFiles) {
        Config config = ConfigLoader.load(configFiles);
        NetworkConfig networkConfig = ConfigLoader.loadNetworkConfig(config);
        PersistenceConfig persistenceConfig = ConfigLoader.loadPersistenceConfig(config);
        FixEngineConfig engineConfig = FixEngineConfig.fromConfig(config);
        return new DefaultComponentProvider(config, networkConfig, persistenceConfig, engineConfig);
    }

    /**
     * Create a provider from pre-built configs.
     */
    public static DefaultComponentProvider create(NetworkConfig networkConfig,
                                                  PersistenceConfig persistenceConfig,
                                                  FixEngineConfig engineConfig) {
        return new DefaultComponentProvider(null, networkConfig, persistenceConfig, engineConfig);
    }

    /**
     * Create a provider from a pre-built engine config with default network and persistence.
     */
    public static DefaultComponentProvider create(FixEngineConfig engineConfig) {
        return new DefaultComponentProvider(null,
                NetworkConfig.builder().build(),
                PersistenceConfig.builder().build(),
                engineConfig);
    }

    // ==================== Factory Registration ====================

    /**
     * Register a factory for creating NetworkEventLoop instances.
     */
    public DefaultComponentProvider withNetworkFactory(NetworkEventLoopFactory factory) {
        this.networkFactory = factory;
        return this;
    }

    /**
     * Register a factory for creating PersistenceStore instances.
     */
    public DefaultComponentProvider withPersistenceFactory(PersistenceStoreFactory factory) {
        this.persistenceFactory = factory;
        return this;
    }

    /**
     * Register a factory for creating FixEngine instances.
     */
    public DefaultComponentProvider withEngineFactory(FixEngineFactory factory) {
        this.fixEngineFactory = factory;
        return this;
    }

    // ==================== ComponentProvider Implementation ====================

    @Override
    public Config getRawConfig() {
        return rawConfig;
    }

    @Override
    public FixEngineConfig getEngineConfig() {
        return engineConfig;
    }

    /**
     * Get the network configuration.
     */
    public NetworkConfig getNetworkConfig() {
        return networkConfig;
    }

    /**
     * Get the persistence configuration.
     */
    public PersistenceConfig getPersistenceConfig() {
        return persistenceConfig;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NetworkEventLoopProvider<Object> getNetworkProvider() {
        if (networkProvider == null) {
            if (networkFactory == null) {
                throw new IllegalStateException("NetworkEventLoopFactory not registered. Call withNetworkFactory() first.");
            }
            networkProvider = new DefaultNetworkEventLoopProvider(networkConfig, networkFactory);
        }
        return networkProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PersistenceStoreProvider<Object> getPersistenceProvider() {
        if (persistenceProvider == null) {
            if (persistenceFactory == null) {
                throw new IllegalStateException("PersistenceStoreFactory not registered. Call withPersistenceFactory() first.");
            }
            persistenceProvider = new DefaultPersistenceStoreProvider(persistenceConfig, persistenceFactory);
        }
        return persistenceProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public FixEngineProvider<Object> getEngineProvider() {
        if (fixEngineProvider == null) {
            if (fixEngineFactory == null) {
                throw new IllegalStateException("FixEngineFactory not registered. Call withEngineFactory() first.");
            }
            fixEngineProvider = new DefaultFixEngineProvider(
                    engineConfig,
                    getNetworkProvider(),
                    getPersistenceProvider(),
                    fixEngineFactory
            );
        }
        return fixEngineProvider;
    }

    @Override
    public void initialize() throws Exception {
        if (initialized.compareAndSet(false, true)) {
            log.info("Initializing FIX engine components");

            // Create network event loop
            if (networkProvider != null || networkFactory != null) {
                getNetworkProvider().get();
                log.info("Network event loop created");
            }

            // Create persistence store
            if ((persistenceProvider != null || persistenceFactory != null) && persistenceConfig.isEnabled()) {
                getPersistenceProvider().get();
                log.info("Persistence store created");
            }

            // Create FIX engine
            if (fixEngineProvider != null || fixEngineFactory != null) {
                getEngineProvider().get();
                log.info("FIX engine created");
            }

            log.info("FIX engine components initialized");
        }
    }

    @Override
    public void start() throws Exception {
        if (!initialized.get()) {
            initialize();
        }

        if (running.compareAndSet(false, true)) {
            log.info("Starting FIX engine components");

            // Start network event loop
            if (networkProvider != null) {
                networkProvider.start();
            }

            // Start FIX engine
            if (fixEngineProvider != null) {
                fixEngineProvider.start();
            }

            log.info("FIX engine components started");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping FIX engine components");

            // Stop FIX engine first
            if (fixEngineProvider != null) {
                try {
                    fixEngineProvider.stop();
                } catch (Exception e) {
                    log.error("Error stopping FIX engine", e);
                }
            }

            // Stop network event loop
            if (networkProvider != null) {
                try {
                    networkProvider.stop();
                } catch (Exception e) {
                    log.error("Error stopping network event loop", e);
                }
            }

            // Close persistence store
            if (persistenceProvider != null) {
                try {
                    persistenceProvider.close();
                } catch (Exception e) {
                    log.error("Error closing persistence store", e);
                }
            }

            log.info("FIX engine components stopped");
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized.get();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== Factory Interfaces ====================

    /**
     * Factory for creating NetworkEventLoop instances.
     */
    @FunctionalInterface
    public interface NetworkEventLoopFactory {
        Object create(NetworkConfig config) throws Exception;
    }

    /**
     * Factory for creating PersistenceStore instances.
     */
    @FunctionalInterface
    public interface PersistenceStoreFactory {
        Object create(PersistenceConfig config) throws Exception;
    }

    /**
     * Factory for creating FixEngine instances.
     */
    @FunctionalInterface
    public interface FixEngineFactory {
        Object create(FixEngineConfig config, Object networkEventLoop, Object persistenceStore) throws Exception;
    }

    // ==================== Default Provider Implementations ====================

    private static class DefaultNetworkEventLoopProvider implements NetworkEventLoopProvider<Object> {
        private final NetworkConfig config;
        private final NetworkEventLoopFactory factory;
        private Object instance;
        private final AtomicBoolean running = new AtomicBoolean(false);

        DefaultNetworkEventLoopProvider(NetworkConfig config, NetworkEventLoopFactory factory) {
            this.config = config;
            this.factory = factory;
        }

        @Override
        public NetworkConfig getConfig() {
            return config;
        }

        @Override
        public synchronized Object get() throws Exception {
            if (instance == null) {
                instance = factory.create(config);
            }
            return instance;
        }

        @Override
        public boolean isCreated() {
            return instance != null;
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void start() throws Exception {
            if (instance != null && running.compareAndSet(false, true)) {
                // Use reflection to call start() method
                instance.getClass().getMethod("start").invoke(instance);
            }
        }

        @Override
        public void stop() {
            if (instance != null && running.compareAndSet(true, false)) {
                try {
                    instance.getClass().getMethod("stop").invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to stop network event loop", e);
                }
            }
        }
    }

    private static class DefaultPersistenceStoreProvider implements PersistenceStoreProvider<Object> {
        private final PersistenceConfig config;
        private final PersistenceStoreFactory factory;
        private Object instance;

        DefaultPersistenceStoreProvider(PersistenceConfig config, PersistenceStoreFactory factory) {
            this.config = config;
            this.factory = factory;
        }

        @Override
        public PersistenceConfig getConfig() {
            return config;
        }

        @Override
        public synchronized Object get() throws Exception {
            if (!config.isEnabled()) {
                return null;
            }
            if (instance == null) {
                instance = factory.create(config);
            }
            return instance;
        }

        @Override
        public boolean isCreated() {
            return instance != null;
        }

        @Override
        public boolean isEnabled() {
            return config.isEnabled();
        }

        @Override
        public void close() {
            if (instance != null) {
                try {
                    instance.getClass().getMethod("close").invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to close persistence store", e);
                }
            }
        }
    }

    private static class DefaultFixEngineProvider implements FixEngineProvider<Object> {
        private final FixEngineConfig config;
        private final NetworkEventLoopProvider<Object> networkProvider;
        private final PersistenceStoreProvider<Object> persistenceProvider;
        private final FixEngineFactory factory;
        private Object instance;
        private final AtomicBoolean running = new AtomicBoolean(false);

        DefaultFixEngineProvider(FixEngineConfig config,
                                 NetworkEventLoopProvider<Object> networkProvider,
                                 PersistenceStoreProvider<Object> persistenceProvider,
                                 FixEngineFactory factory) {
            this.config = config;
            this.networkProvider = networkProvider;
            this.persistenceProvider = persistenceProvider;
            this.factory = factory;
        }

        @Override
        public FixEngineConfig getConfig() {
            return config;
        }

        @Override
        public List<EngineSessionConfig> getSessionConfigs() {
            return config.getSessions();
        }

        @Override
        public synchronized Object get() throws Exception {
            if (instance == null) {
                Object networkLoop = networkProvider.get();
                Object persistence = persistenceProvider.isEnabled() ? persistenceProvider.get() : null;
                instance = factory.create(config, networkLoop, persistence);
            }
            return instance;
        }

        @Override
        public boolean isCreated() {
            return instance != null;
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void start() throws Exception {
            if (instance != null && running.compareAndSet(false, true)) {
                instance.getClass().getMethod("start").invoke(instance);
            }
        }

        @Override
        public void stop() {
            if (instance != null && running.compareAndSet(true, false)) {
                try {
                    instance.getClass().getMethod("stop").invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to stop FIX engine", e);
                }
            }
        }

        @Override
        public NetworkEventLoopProvider<?> getNetworkProvider() {
            return networkProvider;
        }

        @Override
        public PersistenceStoreProvider<?> getPersistenceProvider() {
            return persistenceProvider;
        }
    }
}
