package com.omnibridge.config.provider;

import com.omnibridge.config.*;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of ComponentProvider with ComponentRegistry support.
 *
 * <p>This provider manages component lifecycle and provides dependency injection
 * through the ComponentProvider interface passed to component factories.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * DefaultComponentProvider provider = DefaultComponentProvider.create(configFiles);
 *
 * // Register component factories
 * provider.register(NetworkEventLoop.class, (name, config, p) ->
 *     new NetworkEventLoop(NetworkConfig.fromConfig(config), p));
 * provider.register(FixEngine.class, (name, config, p) ->
 *     new FixEngine(FixEngineConfig.fromConfig(config), p));
 *
 * // Initialize and start
 * provider.initialize();
 * provider.start();
 *
 * // Get components
 * NetworkEventLoop eventLoop = provider.getComponent(NetworkEventLoop.class);
 * }</pre>
 */
public class DefaultComponentProvider implements ComponentProvider, ComponentRegistry, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultComponentProvider.class);

    private final Config config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Component factories keyed by type
    private final Map<Class<? extends Component>, ComponentFactory<?>> factories = new LinkedHashMap<>();

    // Component instances keyed by "type" or "type:name"
    private final Map<String, Component> instances = new ConcurrentHashMap<>();

    // Lifecycle manager for all components
    private final LifeCycleComponent lifeCycle;

    private DefaultComponentProvider(Config config) {
        this.config = config;
        this.lifeCycle = new LifeCycleComponent("default-provider");
    }

    /**
     * Create a provider from config file paths.
     */
    public static DefaultComponentProvider create(List<String> configFiles) {
        Config config = ConfigLoader.load(configFiles);
        return new DefaultComponentProvider(config);
    }

    /**
     * Create a provider from a Config object.
     */
    public static DefaultComponentProvider create(Config config) {
        return new DefaultComponentProvider(config);
    }

    /**
     * Create a provider with default configuration.
     */
    public static DefaultComponentProvider create() {
        return new DefaultComponentProvider(ConfigLoader.load());
    }

    // ==================== ComponentProvider Implementation ====================

    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public <T extends Component> T getComponent(Class<T> type) {
        return getComponent(null, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Component> T getComponent(String name, Class<T> type) {
        String key = makeKey(type, name);

        // Check if already created
        Component instance = instances.get(key);
        if (instance != null) {
            return type.cast(instance);
        }

        // Get factory for this type
        ComponentFactory<?> factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("No factory registered for type: " + type.getName());
        }

        // Create instance
        synchronized (this) {
            // Double-check after acquiring lock
            instance = instances.get(key);
            if (instance != null) {
                return type.cast(instance);
            }

            try {
                instance = ((ComponentFactory<Component>) factory).create(name, config, this);
                instances.put(key, instance);
                lifeCycle.addComponent(instance);
                log.debug("Created component: {} (type={})", key, type.getSimpleName());
                return type.cast(instance);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create component: " + key, e);
            }
        }
    }

    // ==================== ComponentRegistry Implementation ====================

    @Override
    public <T extends Component> void register(Class<T> type, ComponentFactory<T> factory) {
        if (initialized.get()) {
            throw new IllegalStateException("Cannot register components after initialization");
        }
        factories.put(type, factory);
        log.debug("Registered factory for type: {}", type.getSimpleName());
    }

    @Override
    public boolean hasFactory(Class<? extends Component> type) {
        return factories.containsKey(type);
    }

    @Override
    public Set<Class<? extends Component>> getRegisteredTypes() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    // ==================== Lifecycle Management ====================

    /**
     * Get the lifecycle component for coordinated lifecycle management.
     */
    public LifeCycleComponent getLifeCycle() {
        return lifeCycle;
    }

    /**
     * Initialize all registered components.
     */
    public void initialize() throws Exception {
        if (initialized.compareAndSet(false, true)) {
            log.info("Initializing components");

            // Initialize all registered components via lifecycle
            if (!instances.isEmpty()) {
                lifeCycle.initialize();
            }

            log.info("Components initialized");
        }
    }

    /**
     * Start all components.
     */
    public void start() throws Exception {
        if (!initialized.get()) {
            initialize();
        }

        if (running.compareAndSet(false, true)) {
            log.info("Starting components");

            if (lifeCycle.getState() == ComponentState.INITIALIZED) {
                lifeCycle.startActive();
            }

            log.info("Components started");
        }
    }

    /**
     * Stop all components.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping components");

            if (lifeCycle.getState().isOperational()) {
                lifeCycle.stop();
            }

            log.info("Components stopped");
        }
    }

    /**
     * Check if components are initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Check if components are running.
     */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== Helper Methods ====================

    private String makeKey(Class<?> type, String name) {
        if (name == null || name.isEmpty()) {
            return type.getName();
        }
        return type.getName() + ":" + name;
    }
}
