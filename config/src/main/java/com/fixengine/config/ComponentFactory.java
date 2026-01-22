package com.fixengine.config;

import com.fixengine.config.provider.ComponentProvider;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link Component} instances.
 *
 * <p>ComponentFactory implementations are registered with a {@link ComponentRegistry}
 * and used to create component instances on demand.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * ComponentFactory<NetworkEventLoop> factory = (name, config, provider) ->
 *     new NetworkEventLoop(NetworkConfig.fromConfig(config), provider);
 * registry.register(NetworkEventLoop.class, factory);
 * }</pre>
 *
 * @param <T> the component type to create
 */
@FunctionalInterface
public interface ComponentFactory<T extends Component> {

    /**
     * Create a component instance with the given name and configuration.
     *
     * @param name the component name (may be null for unnamed components)
     * @param config the typesafe config
     * @param provider the component provider for accessing other components
     * @return the created component
     * @throws Exception if creation fails
     */
    T create(String name, Config config, ComponentProvider provider) throws Exception;
}
