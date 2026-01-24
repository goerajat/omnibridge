package com.omnibridge.config;

/**
 * Lifecycle interface for components supporting High Availability (HA) patterns.
 *
 * <p>Components implementing this interface support the following lifecycle states:</p>
 * <ul>
 *   <li>{@code UNINITIALIZED} - Initial state before any setup</li>
 *   <li>{@code INITIALIZED} - Resources loaded, config validated, ready to start</li>
 *   <li>{@code ACTIVE} - Processing requests, fully operational</li>
 *   <li>{@code STANDBY} - Ready but not processing (for HA failover)</li>
 *   <li>{@code STOPPED} - All resources released</li>
 * </ul>
 *
 * <p>Lifecycle transitions:</p>
 * <pre>
 * UNINITIALIZED ──► INITIALIZED ──► ACTIVE ◄──► STANDBY
 *                        │              │           │
 *                        └──────────────┴───────────┴──► STOPPED
 * </pre>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Component engine = ...;
 * engine.initialize();      // Load resources
 * engine.startActive();     // Begin processing
 * engine.becomeStandby();   // Transition to standby for failover
 * engine.becomeActive();    // Resume processing after failover
 * engine.stop();            // Shutdown
 * }</pre>
 */
public interface Component {

    /**
     * Initialize the component.
     * Load resources, validate configuration, prepare for operation.
     * Transitions from UNINITIALIZED to INITIALIZED.
     *
     * @throws Exception if initialization fails
     */
    void initialize() throws Exception;

    /**
     * Start the component in active mode.
     * Begin processing requests and fully operational.
     * Transitions from INITIALIZED to ACTIVE.
     *
     * @throws Exception if start fails
     */
    void startActive() throws Exception;

    /**
     * Start the component in standby mode.
     * Ready to take over but not processing requests.
     * Transitions from INITIALIZED to STANDBY.
     *
     * @throws Exception if start fails
     */
    void startStandby() throws Exception;

    /**
     * Transition from standby to active mode.
     * Resume processing requests after failover.
     * Transitions from STANDBY to ACTIVE.
     *
     * @throws Exception if transition fails
     */
    void becomeActive() throws Exception;

    /**
     * Transition from active to standby mode.
     * Stop processing but remain ready to resume.
     * Transitions from ACTIVE to STANDBY.
     *
     * @throws Exception if transition fails
     */
    void becomeStandby() throws Exception;

    /**
     * Stop the component and release all resources.
     * Transitions to STOPPED from any state.
     */
    void stop();

    /**
     * Get the component name.
     *
     * @return the component name
     */
    String getName();

    /**
     * Get the current component state.
     *
     * @return the current state
     */
    ComponentState getState();

    /**
     * Check if the component is in active state.
     *
     * @return true if active
     */
    default boolean isActive() {
        return getState() == ComponentState.ACTIVE;
    }

    /**
     * Check if the component is in standby state.
     *
     * @return true if standby
     */
    default boolean isStandby() {
        return getState() == ComponentState.STANDBY;
    }

    /**
     * Check if the component is running (active or standby).
     *
     * @return true if running
     */
    default boolean isRunning() {
        ComponentState state = getState();
        return state == ComponentState.ACTIVE || state == ComponentState.STANDBY;
    }

    /**
     * Check if the component is initialized (INITIALIZED, ACTIVE, or STANDBY).
     *
     * @return true if initialized
     */
    default boolean isInitialized() {
        ComponentState state = getState();
        return state == ComponentState.INITIALIZED ||
               state == ComponentState.ACTIVE ||
               state == ComponentState.STANDBY;
    }
}
