package com.omnibridge.config.session;

import com.omnibridge.config.ComponentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link SessionManagementService}.
 *
 * <p>This implementation provides thread-safe session registration and lookup
 * using a {@link ConcurrentHashMap} for the session registry and a
 * {@link CopyOnWriteArrayList} for state change listeners.</p>
 */
public class DefaultSessionManagementService implements SessionManagementService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionManagementService.class);

    private final String name;
    private final AtomicReference<ComponentState> state;
    private final ConcurrentHashMap<String, ManagedSession> sessions;
    private final CopyOnWriteArrayList<SessionStateChangeListener> listeners;

    /**
     * Create a new DefaultSessionManagementService with the given name.
     *
     * @param name the service name
     */
    public DefaultSessionManagementService(String name) {
        this.name = name;
        this.state = new AtomicReference<>(ComponentState.UNINITIALIZED);
        this.sessions = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Create a new DefaultSessionManagementService with default name.
     */
    public DefaultSessionManagementService() {
        this("session-management-service");
    }

    // ========== Registration ==========

    @Override
    public void registerSession(ManagedSession session) {
        String sessionId = session.getSessionId();
        ManagedSession existing = sessions.putIfAbsent(sessionId, session);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Session already registered with ID: " + sessionId);
        }

        log.info("[{}] Registered session: {} (protocol={})",
                name, sessionId, session.getProtocolType());

        // Notify listeners
        for (SessionStateChangeListener listener : listeners) {
            try {
                listener.onSessionRegistered(session);
            } catch (Exception e) {
                log.error("[{}] Error notifying listener of session registration", name, e);
            }
        }
    }

    @Override
    public ManagedSession unregisterSession(String sessionId) {
        ManagedSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("[{}] Unregistered session: {}", name, sessionId);

            // Notify listeners
            for (SessionStateChangeListener listener : listeners) {
                try {
                    listener.onSessionUnregistered(removed);
                } catch (Exception e) {
                    log.error("[{}] Error notifying listener of session unregistration", name, e);
                }
            }
        }
        return removed;
    }

    // ========== Lookup ==========

    @Override
    public Optional<ManagedSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Collection<ManagedSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    @Override
    public Collection<ManagedSession> getSessionsByProtocol(String protocolType) {
        return sessions.values().stream()
                .filter(s -> protocolType.equalsIgnoreCase(s.getProtocolType()))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<ManagedSession> getConnectedSessions() {
        return sessions.values().stream()
                .filter(ManagedSession::isConnected)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<ManagedSession> getLoggedOnSessions() {
        return sessions.values().stream()
                .filter(ManagedSession::isLoggedOn)
                .collect(Collectors.toUnmodifiableList());
    }

    // ========== Bulk Operations ==========

    @Override
    public void enableAllSessions() {
        log.info("[{}] Enabling all {} sessions", name, sessions.size());
        for (ManagedSession session : sessions.values()) {
            try {
                session.enable();
            } catch (Exception e) {
                log.error("[{}] Error enabling session: {}", name, session.getSessionId(), e);
            }
        }
    }

    @Override
    public void disableAllSessions() {
        log.info("[{}] Disabling all {} sessions", name, sessions.size());
        for (ManagedSession session : sessions.values()) {
            try {
                session.disable();
            } catch (Exception e) {
                log.error("[{}] Error disabling session: {}", name, session.getSessionId(), e);
            }
        }
    }

    // ========== Listeners ==========

    @Override
    public void addStateChangeListener(SessionStateChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeStateChangeListener(SessionStateChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify all listeners of a session state change.
     *
     * <p>This method is called by session adapters when the underlying session
     * state changes.</p>
     *
     * @param session the session that changed
     * @param oldState the previous state
     * @param newState the new state
     */
    public void notifyStateChange(ManagedSession session,
                                  SessionConnectionState oldState,
                                  SessionConnectionState newState) {
        log.debug("[{}] Session {} state change: {} -> {}",
                name, session.getSessionId(), oldState, newState);

        for (SessionStateChangeListener listener : listeners) {
            try {
                listener.onSessionStateChange(session, oldState, newState);
            } catch (Exception e) {
                log.error("[{}] Error notifying listener of state change", name, e);
            }
        }
    }

    // ========== Statistics ==========

    @Override
    public int getTotalSessionCount() {
        return sessions.size();
    }

    @Override
    public int getConnectedSessionCount() {
        return (int) sessions.values().stream()
                .filter(ManagedSession::isConnected)
                .count();
    }

    @Override
    public int getLoggedOnSessionCount() {
        return (int) sessions.values().stream()
                .filter(ManagedSession::isLoggedOn)
                .count();
    }

    // ========== Component Lifecycle ==========

    @Override
    public void initialize() throws Exception {
        if (!state.compareAndSet(ComponentState.UNINITIALIZED, ComponentState.INITIALIZED)) {
            throw new IllegalStateException("Cannot initialize from state: " + state.get());
        }
        log.info("[{}] Initialized", name);
    }

    @Override
    public void startActive() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + currentState);
        }
        state.set(ComponentState.ACTIVE);
        log.info("[{}] Started in ACTIVE mode with {} sessions", name, sessions.size());
    }

    @Override
    public void startStandby() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + currentState);
        }
        state.set(ComponentState.STANDBY);
        log.info("[{}] Started in STANDBY mode", name);
    }

    @Override
    public void becomeActive() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + currentState);
        }
        state.set(ComponentState.ACTIVE);
        log.info("[{}] Transitioned to ACTIVE mode", name);
    }

    @Override
    public void becomeStandby() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + currentState);
        }
        state.set(ComponentState.STANDBY);
        log.info("[{}] Transitioned to STANDBY mode", name);
    }

    @Override
    public void stop() {
        ComponentState currentState = state.get();
        if (currentState == ComponentState.STOPPED) {
            log.debug("[{}] Already stopped", name);
            return;
        }

        log.info("[{}] Stopping with {} sessions", name, sessions.size());

        // Clear sessions
        sessions.clear();
        listeners.clear();

        state.set(ComponentState.STOPPED);
        log.info("[{}] Stopped", name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ComponentState getState() {
        return state.get();
    }
}
