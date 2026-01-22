package com.fixengine.config.provider;

import com.fixengine.config.Component;
import com.typesafe.config.Config;

/**
 * Provider interface for accessing FIX engine components.
 *
 * <p>This interface provides access to components by type. Components are created
 * on first access and cached for subsequent calls.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * // Get component without name
 * NetworkEventLoop eventLoop = provider.getComponent(NetworkEventLoop.class);
 *
 * // Get named component
 * FixSession session = provider.getComponent("primary", FixSession.class);
 * }</pre>
 */
public interface ComponentProvider {

    /**
     * Get a component by type.
     * Creates the component if not already created.
     *
     * @param type the component class
     * @param <T> the component type
     * @return the component instance
     */
    <T extends Component> T getComponent(Class<T> type);

    /**
     * Get a named component by type.
     * Creates the component if not already created.
     *
     * @param name the component name
     * @param type the component class
     * @param <T> the component type
     * @return the component instance
     */
    <T extends Component> T getComponent(String name, Class<T> type);

    /**
     * Get the raw configuration.
     *
     * @return the typesafe config
     */
    Config getConfig();
}
