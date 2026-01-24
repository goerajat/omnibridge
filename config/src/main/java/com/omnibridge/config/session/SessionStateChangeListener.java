package com.omnibridge.config.session;

/**
 * Listener for session state changes and registration events.
 *
 * <p>Implementations can register with a {@link SessionManagementService}
 * to receive notifications about session state transitions and
 * registration/unregistration events.</p>
 */
public interface SessionStateChangeListener {

    /**
     * Called when a session's connection state changes.
     *
     * @param session the session that changed state
     * @param oldState the previous state
     * @param newState the new state
     */
    void onSessionStateChange(ManagedSession session,
                              SessionConnectionState oldState,
                              SessionConnectionState newState);

    /**
     * Called when a session is registered with the management service.
     *
     * <p>This is an optional callback with a default no-op implementation.</p>
     *
     * @param session the registered session
     */
    default void onSessionRegistered(ManagedSession session) {
        // No-op by default
    }

    /**
     * Called when a session is unregistered from the management service.
     *
     * <p>This is an optional callback with a default no-op implementation.</p>
     *
     * @param session the unregistered session
     */
    default void onSessionUnregistered(ManagedSession session) {
        // No-op by default
    }
}
