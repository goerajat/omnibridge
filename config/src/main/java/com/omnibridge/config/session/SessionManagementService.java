package com.omnibridge.config.session;

import com.omnibridge.config.Component;
import com.omnibridge.config.Singleton;

import java.util.Collection;
import java.util.Optional;

/**
 * Central service for managing sessions across all protocols.
 *
 * <p>This service provides a unified registry for session management,
 * enabling:</p>
 * <ul>
 *   <li>Session registration and lookup</li>
 *   <li>Protocol-agnostic session monitoring</li>
 *   <li>Bulk session operations</li>
 *   <li>Session state change notifications</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * SessionManagementService service = ...;
 *
 * // Lookup a session
 * Optional<ManagedSession> session = service.getSession("sender-target");
 *
 * // Get all FIX sessions
 * Collection<ManagedSession> fixSessions = service.getSessionsByProtocol("FIX");
 *
 * // Listen for state changes
 * service.addStateChangeListener((s, oldState, newState) -> {
 *     log.info("Session {} changed from {} to {}", s.getSessionId(), oldState, newState);
 * });
 * }</pre>
 */
@Singleton
public interface SessionManagementService extends Component {

    // ========== Registration ==========

    /**
     * Register a session with this service.
     *
     * <p>After registration, the session can be looked up by its session ID
     * and will be included in queries and bulk operations.</p>
     *
     * @param session the session to register
     * @throws IllegalArgumentException if a session with the same ID is already registered
     */
    void registerSession(ManagedSession session);

    /**
     * Unregister a session from this service.
     *
     * @param sessionId the ID of the session to unregister
     * @return the unregistered session, or null if not found
     */
    ManagedSession unregisterSession(String sessionId);

    // ========== Lookup ==========

    /**
     * Get a session by its ID.
     *
     * @param sessionId the session ID
     * @return the session if found
     */
    Optional<ManagedSession> getSession(String sessionId);

    /**
     * Get all registered sessions.
     *
     * @return unmodifiable collection of all sessions
     */
    Collection<ManagedSession> getAllSessions();

    /**
     * Get all sessions of a specific protocol type.
     *
     * @param protocolType the protocol type (e.g., "FIX", "OUCH")
     * @return collection of sessions matching the protocol type
     */
    Collection<ManagedSession> getSessionsByProtocol(String protocolType);

    /**
     * Get all sessions that are currently connected.
     *
     * @return collection of connected sessions
     */
    Collection<ManagedSession> getConnectedSessions();

    /**
     * Get all sessions that are currently logged on.
     *
     * @return collection of logged on sessions
     */
    Collection<ManagedSession> getLoggedOnSessions();

    // ========== Bulk Operations ==========

    /**
     * Enable all registered sessions.
     *
     * <p>This allows all sessions to connect. Sessions that were previously
     * disabled will begin their connection sequence.</p>
     */
    void enableAllSessions();

    /**
     * Disable all registered sessions.
     *
     * <p>This prevents all sessions from connecting. Connected sessions
     * will be disconnected.</p>
     */
    void disableAllSessions();

    // ========== Listeners ==========

    /**
     * Add a listener for session state changes.
     *
     * @param listener the listener to add
     */
    void addStateChangeListener(SessionStateChangeListener listener);

    /**
     * Remove a session state change listener.
     *
     * @param listener the listener to remove
     */
    void removeStateChangeListener(SessionStateChangeListener listener);

    // ========== Statistics ==========

    /**
     * Get the total number of registered sessions.
     *
     * @return the total session count
     */
    int getTotalSessionCount();

    /**
     * Get the number of currently connected sessions.
     *
     * @return the connected session count
     */
    int getConnectedSessionCount();

    /**
     * Get the number of currently logged on sessions.
     *
     * @return the logged on session count
     */
    int getLoggedOnSessionCount();
}
