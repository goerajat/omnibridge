package com.fixengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the lifecycle of multiple {@link Component} instances.
 *
 * <p>LifeCycleComponent itself implements the Component interface, allowing for
 * hierarchical composition of components. It manages child components and
 * coordinates their lifecycle transitions.</p>
 *
 * <p>Lifecycle operations are performed in order:</p>
 * <ul>
 *   <li>{@code initialize()}, {@code startActive()}, {@code startStandby()}, {@code becomeActive()}
 *       - Called in registration order</li>
 *   <li>{@code becomeStandby()}, {@code stop()} - Called in reverse registration order
 *       for graceful shutdown</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * LifeCycleComponent lifecycle = new LifeCycleComponent("engine");
 * lifecycle.addComponent(networkEventLoop);
 * lifecycle.addComponent(persistenceStore);
 * lifecycle.addComponent(fixEngine);
 *
 * lifecycle.initialize();
 * lifecycle.startActive();
 *
 * // Later...
 * lifecycle.stop();  // Stops in reverse order: fixEngine, persistenceStore, networkEventLoop
 * }</pre>
 */
public class LifeCycleComponent implements Component {

    private static final Logger log = LoggerFactory.getLogger(LifeCycleComponent.class);

    private final String name;
    private final List<Component> components;
    private final AtomicReference<ComponentState> state;

    /**
     * Create a new LifeCycleComponent with the given name.
     *
     * @param name the component name
     */
    public LifeCycleComponent(String name) {
        this.name = name;
        this.components = new ArrayList<>();
        this.state = new AtomicReference<>(ComponentState.UNINITIALIZED);
    }

    /**
     * Create a new LifeCycleComponent with default name.
     */
    public LifeCycleComponent() {
        this("lifecycle");
    }

    /**
     * Add a component to be managed.
     * Components are initialized/started in the order they are added,
     * and stopped in reverse order.
     *
     * @param component the component to add
     * @return this instance for chaining
     */
    public LifeCycleComponent addComponent(Component component) {
        if (state.get() != ComponentState.UNINITIALIZED) {
            throw new IllegalStateException("Cannot add components after initialization");
        }
        components.add(component);
        log.debug("[{}] Added component: {}", name, component.getName());
        return this;
    }

    /**
     * Get the list of managed components.
     *
     * @return unmodifiable list of components
     */
    public List<Component> getComponents() {
        return Collections.unmodifiableList(components);
    }

    @Override
    public void initialize() throws Exception {
        if (!state.compareAndSet(ComponentState.UNINITIALIZED, ComponentState.INITIALIZED)) {
            throw new IllegalStateException("Cannot initialize from state: " + state.get());
        }

        log.info("[{}] Initializing {} components", name, components.size());

        for (Component component : components) {
            log.debug("[{}] Initializing component: {}", name, component.getName());
            try {
                component.initialize();
            } catch (Exception e) {
                log.error("[{}] Failed to initialize component: {}", name, component.getName(), e);
                // Rollback: stop already initialized components
                stopInitializedComponents(components.indexOf(component));
                state.set(ComponentState.STOPPED);
                throw e;
            }
        }

        log.info("[{}] All components initialized", name);
    }

    @Override
    public void startActive() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + currentState);
        }

        log.info("[{}] Starting {} components in ACTIVE mode", name, components.size());

        for (Component component : components) {
            log.debug("[{}] Starting component active: {}", name, component.getName());
            try {
                component.startActive();
            } catch (Exception e) {
                log.error("[{}] Failed to start component: {}", name, component.getName(), e);
                // Rollback: stop already started components
                stopStartedComponents(components.indexOf(component));
                state.set(ComponentState.STOPPED);
                throw e;
            }
        }

        state.set(ComponentState.ACTIVE);
        log.info("[{}] All components started in ACTIVE mode", name);
    }

    @Override
    public void startStandby() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + currentState);
        }

        log.info("[{}] Starting {} components in STANDBY mode", name, components.size());

        for (Component component : components) {
            log.debug("[{}] Starting component standby: {}", name, component.getName());
            try {
                component.startStandby();
            } catch (Exception e) {
                log.error("[{}] Failed to start component in standby: {}", name, component.getName(), e);
                stopStartedComponents(components.indexOf(component));
                state.set(ComponentState.STOPPED);
                throw e;
            }
        }

        state.set(ComponentState.STANDBY);
        log.info("[{}] All components started in STANDBY mode", name);
    }

    @Override
    public void becomeActive() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + currentState);
        }

        log.info("[{}] Transitioning {} components to ACTIVE mode", name, components.size());

        for (Component component : components) {
            log.debug("[{}] Activating component: {}", name, component.getName());
            try {
                component.becomeActive();
            } catch (Exception e) {
                log.error("[{}] Failed to activate component: {}", name, component.getName(), e);
                throw e;
            }
        }

        state.set(ComponentState.ACTIVE);
        log.info("[{}] All components now ACTIVE", name);
    }

    @Override
    public void becomeStandby() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + currentState);
        }

        log.info("[{}] Transitioning {} components to STANDBY mode", name, components.size());

        // Transition in reverse order for graceful degradation
        for (int i = components.size() - 1; i >= 0; i--) {
            Component component = components.get(i);
            log.debug("[{}] Deactivating component: {}", name, component.getName());
            try {
                component.becomeStandby();
            } catch (Exception e) {
                log.error("[{}] Failed to deactivate component: {}", name, component.getName(), e);
                throw e;
            }
        }

        state.set(ComponentState.STANDBY);
        log.info("[{}] All components now STANDBY", name);
    }

    @Override
    public void stop() {
        ComponentState currentState = state.get();
        if (currentState == ComponentState.STOPPED) {
            log.debug("[{}] Already stopped", name);
            return;
        }

        log.info("[{}] Stopping {} components", name, components.size());

        // Stop in reverse order for graceful shutdown
        for (int i = components.size() - 1; i >= 0; i--) {
            Component component = components.get(i);
            log.debug("[{}] Stopping component: {}", name, component.getName());
            try {
                component.stop();
            } catch (Exception e) {
                log.error("[{}] Error stopping component: {}", name, component.getName(), e);
                // Continue stopping other components
            }
        }

        state.set(ComponentState.STOPPED);
        log.info("[{}] All components stopped", name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ComponentState getState() {
        return state.get();
    }

    /**
     * Stop components that were already initialized (for rollback on init failure).
     */
    private void stopInitializedComponents(int upToIndex) {
        for (int i = upToIndex - 1; i >= 0; i--) {
            try {
                components.get(i).stop();
            } catch (Exception e) {
                log.error("[{}] Error during rollback stop", name, e);
            }
        }
    }

    /**
     * Stop components that were already started (for rollback on start failure).
     */
    private void stopStartedComponents(int upToIndex) {
        for (int i = upToIndex - 1; i >= 0; i--) {
            try {
                components.get(i).stop();
            } catch (Exception e) {
                log.error("[{}] Error during rollback stop", name, e);
            }
        }
    }
}
