package com.omnibridge.config.provider;

import com.omnibridge.config.*;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    // Component definitions from configuration
    private final Map<String, ComponentDefinition> componentDefinitions = new LinkedHashMap<>();

    // Mapping from component name to its type for name-based lookups
    private final Map<String, Class<? extends Component>> nameToType = new LinkedHashMap<>();

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
        String typeOnlyKey = makeKey(type, null);

        // Check if already created
        Component instance = instances.get(key);
        if (instance != null) {
            return type.cast(instance);
        }

        // If looking up by type only, also check if there's a named instance
        if (name == null || name.isEmpty()) {
            // Look for any instance of this type (find first named instance)
            for (var entry : instances.entrySet()) {
                if (entry.getKey().startsWith(type.getName() + ":")) {
                    return type.cast(entry.getValue());
                }
            }
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

            // Also check for named instances if looking up by type only
            if (name == null || name.isEmpty()) {
                for (var entry : instances.entrySet()) {
                    if (entry.getKey().startsWith(type.getName() + ":")) {
                        return type.cast(entry.getValue());
                    }
                }
            }

            try {
                instance = ((ComponentFactory<Component>) factory).create(name, config, this);
                instances.put(key, instance);

                // Also store under type-only key if created with a name, so it can be found by type
                if (name != null && !name.isEmpty()) {
                    instances.putIfAbsent(typeOnlyKey, instance);
                }

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

    // ==================== Config-Driven Component Loading ====================

    /**
     * Load and register components from configuration.
     * Parses component definitions, resolves dependencies, and registers factories.
     *
     * <p>This method reads the "components" section from configuration and:</p>
     * <ol>
     *   <li>Parses all component definitions</li>
     *   <li>Filters to enabled components only</li>
     *   <li>Performs topological sort by dependencies</li>
     *   <li>Registers factories via reflection</li>
     *   <li>Creates all components in dependency order</li>
     * </ol>
     */
    public void loadComponentsFromConfig() {
        if (initialized.get()) {
            throw new IllegalStateException("Cannot load components after initialization");
        }

        // 1. Load component definitions from config
        if (!config.hasPath("components")) {
            log.debug("No 'components' section in config - using manual registration mode");
            return;
        }

        Map<String, ComponentDefinition> definitions = ComponentDefinition.loadAll(config);

        // 2. Filter to enabled components only
        Map<String, ComponentDefinition> enabled = definitions.entrySet().stream()
            .filter(e -> e.getValue().isEnabled())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                     (a, b) -> a, LinkedHashMap::new));

        log.info("Found {} enabled component definitions", enabled.size());

        // 3. Topological sort by dependencies
        List<String> sorted = topologicalSort(enabled);

        // 4. Register factories in dependency order
        for (String name : sorted) {
            ComponentDefinition def = enabled.get(name);
            registerFromDefinition(def);
        }

        // 5. Create all components (triggers factories)
        for (String name : sorted) {
            ComponentDefinition def = enabled.get(name);
            log.info("Creating component: {}", name);
            getComponentByName(name, def.getComponentType());
        }

        log.info("Loaded {} components from configuration", sorted.size());
    }

    /**
     * Perform topological sort on components by their dependencies.
     * Uses Kahn's algorithm.
     *
     * @param components map of component definitions
     * @return list of component names in dependency order
     * @throws IllegalStateException if circular dependency is detected
     */
    private List<String> topologicalSort(Map<String, ComponentDefinition> components) {
        // Build in-degree map and adjacency graph
        Map<String, Set<String>> inDegree = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();

        for (var entry : components.entrySet()) {
            String name = entry.getKey();
            inDegree.putIfAbsent(name, new HashSet<>());
            graph.putIfAbsent(name, new HashSet<>());

            for (String dep : entry.getValue().getDependencies()) {
                if (components.containsKey(dep)) {  // Only consider enabled deps
                    inDegree.get(name).add(dep);
                    graph.computeIfAbsent(dep, k -> new HashSet<>()).add(name);
                }
            }
        }

        // Find nodes with no dependencies
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue().isEmpty()) {
                queue.add(entry.getKey());
            }
        }

        // Process nodes in order
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);

            for (String dependent : graph.getOrDefault(node, Set.of())) {
                inDegree.get(dependent).remove(node);
                if (inDegree.get(dependent).isEmpty()) {
                    queue.add(dependent);
                }
            }
        }

        if (result.size() != components.size()) {
            // Find components involved in cycle
            Set<String> processed = new HashSet<>(result);
            Set<String> inCycle = components.keySet().stream()
                .filter(k -> !processed.contains(k))
                .collect(Collectors.toSet());
            throw new IllegalStateException(
                "Circular dependency detected in components: " + inCycle);
        }

        return result;
    }

    /**
     * Register a factory from a component definition using reflection.
     */
    @SuppressWarnings("unchecked")
    private void registerFromDefinition(ComponentDefinition def) {
        try {
            Class<?> factoryClass = Class.forName(def.getFactoryClassName());
            ComponentFactory<?> factory = (ComponentFactory<?>) factoryClass
                .getDeclaredConstructor().newInstance();

            Class<? extends Component> componentType = def.getComponentType();
            register((Class) componentType, factory);

            componentDefinitions.put(def.getName(), def);
            nameToType.put(def.getName(), componentType);

            log.debug("Registered factory for component '{}' (type={})",
                     def.getName(), componentType.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create factory: " + def.getFactoryClassName(), e);
        }
    }

    /**
     * Get a component by its definition name.
     *
     * @param name the component name from configuration
     * @param type the expected component type
     * @return the component instance
     */
    public <T extends Component> T getComponentByName(String name, Class<T> type) {
        return getComponent(name, type);
    }

    /**
     * Get a component by its definition name, using the registered type.
     *
     * @param name the component name from configuration
     * @return the component instance, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends Component> T getComponentByName(String name) {
        Class<? extends Component> type = nameToType.get(name);
        if (type == null) {
            return null;
        }
        return (T) getComponent(name, type);
    }

    // ==================== Helper Methods ====================

    private String makeKey(Class<?> type, String name) {
        if (name == null || name.isEmpty()) {
            return type.getName();
        }
        return type.getName() + ":" + name;
    }
}
