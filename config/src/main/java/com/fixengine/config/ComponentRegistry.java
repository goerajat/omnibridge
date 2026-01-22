package com.fixengine.config;

import java.util.Set;

/**
 * Registry for managing {@link Component} instances and their factories.
 *
 * <p>The registry provides a central point for:</p>
 * <ul>
 *   <li>Registering component factories by type</li>
 *   <li>Creating and retrieving component instances</li>
 *   <li>Managing component lifecycle</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * ComponentRegistry registry = ...;
 *
 * // Register factories by type
 * registry.register(NetworkEventLoop.class, (name, config, provider) -> new NetworkEventLoop(config));
 * registry.register(LogStore.class, (name, config, provider) -> new MemoryMappedLogStore(config));
 * registry.register(FixEngine.class, (name, config, provider) -> new FixEngine(config, provider));
 *
 * // Get components by type (created on first access)
 * NetworkEventLoop network = registry.getComponent(NetworkEventLoop.class);
 *
 * // Get named components
 * FixSession session = registry.getComponent("primary", FixSession.class);
 * }</pre>
 */
public interface ComponentRegistry {

    /**
     * Register a component factory for a type.
     *
     * @param type the component class
     * @param factory the factory to create instances
     * @param <T> the component type
     */
    <T extends Component> void register(Class<T> type, ComponentFactory<T> factory);

    /**
     * Get or create a component by type.
     * If the component doesn't exist, it will be created using the registered factory.
     *
     * @param type the component type
     * @param <T> the component type
     * @return the component instance
     * @throws IllegalArgumentException if no factory is registered for the type
     */
    <T extends Component> T getComponent(Class<T> type);

    /**
     * Get or create a named component by type.
     * If the component doesn't exist, it will be created using the registered factory.
     *
     * @param name the component name
     * @param type the component type
     * @param <T> the component type
     * @return the component instance
     * @throws IllegalArgumentException if no factory is registered for the type
     */
    <T extends Component> T getComponent(String name, Class<T> type);

    /**
     * Check if a factory is registered for the given type.
     *
     * @param type the component type
     * @return true if a factory is registered
     */
    boolean hasFactory(Class<? extends Component> type);

    /**
     * Get all registered component types.
     *
     * @return set of registered component types
     */
    Set<Class<? extends Component>> getRegisteredTypes();
}
