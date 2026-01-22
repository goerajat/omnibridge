package com.fixengine.config;

/**
 * Represents the lifecycle state of a {@link Component}.
 */
public enum ComponentState {

    /**
     * Initial state before initialization.
     * Component has been created but not yet configured.
     */
    UNINITIALIZED,

    /**
     * Component has been initialized.
     * Resources loaded, configuration validated, ready to start.
     */
    INITIALIZED,

    /**
     * Component is active and processing requests.
     * Fully operational mode.
     */
    ACTIVE,

    /**
     * Component is in standby mode.
     * Ready to take over but not processing requests.
     * Used for HA failover scenarios.
     */
    STANDBY,

    /**
     * Component has been stopped.
     * All resources released, no longer operational.
     */
    STOPPED;

    /**
     * Check if this state is operational (ACTIVE or STANDBY).
     *
     * @return true if operational
     */
    public boolean isOperational() {
        return this == ACTIVE || this == STANDBY;
    }

    /**
     * Check if transition to the target state is valid from this state.
     *
     * @param target the target state
     * @return true if the transition is valid
     */
    public boolean canTransitionTo(ComponentState target) {
        return switch (this) {
            case UNINITIALIZED -> target == INITIALIZED || target == STOPPED;
            case INITIALIZED -> target == ACTIVE || target == STANDBY || target == STOPPED;
            case ACTIVE -> target == STANDBY || target == STOPPED;
            case STANDBY -> target == ACTIVE || target == STOPPED;
            case STOPPED -> false;  // No transitions from STOPPED
        };
    }
}
