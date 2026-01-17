package com.fixengine.admin.service;

import com.fixengine.admin.dto.SessionDto;
import com.fixengine.engine.FixEngine;
import com.fixengine.engine.session.FixSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing FIX sessions.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private FixEngine engine;

    /**
     * Set the FIX engine instance.
     * This should be called during application initialization.
     */
    public void setEngine(FixEngine engine) {
        this.engine = engine;
    }

    /**
     * Get the FIX engine instance.
     */
    public FixEngine getEngine() {
        return engine;
    }

    /**
     * Check if the engine is available.
     */
    public boolean isEngineAvailable() {
        return engine != null && engine.isRunning();
    }

    /**
     * Get all sessions.
     */
    public List<SessionDto> getAllSessions() {
        checkEngine();
        return engine.getAllSessions().stream()
                .map(SessionDto::fromSession)
                .collect(Collectors.toList());
    }

    /**
     * Get sessions filtered by state.
     */
    public List<SessionDto> getSessionsByState(String state) {
        checkEngine();
        return engine.getAllSessions().stream()
                .filter(s -> s.getState().name().equalsIgnoreCase(state))
                .map(SessionDto::fromSession)
                .collect(Collectors.toList());
    }

    /**
     * Get connected sessions (TCP level).
     */
    public List<SessionDto> getConnectedSessions() {
        checkEngine();
        return engine.getConnectedSessions().stream()
                .map(SessionDto::fromSession)
                .collect(Collectors.toList());
    }

    /**
     * Get logged on sessions (FIX level).
     */
    public List<SessionDto> getLoggedOnSessions() {
        checkEngine();
        return engine.getLoggedOnSessions().stream()
                .map(SessionDto::fromSession)
                .collect(Collectors.toList());
    }

    /**
     * Get a session by ID.
     */
    public Optional<SessionDto> getSession(String sessionId) {
        checkEngine();
        FixSession session = engine.getSession(sessionId);
        return Optional.ofNullable(session).map(SessionDto::fromSession);
    }

    /**
     * Get a session by name.
     */
    public Optional<SessionDto> getSessionByName(String name) {
        checkEngine();
        FixSession session = engine.getSessionByName(name);
        return Optional.ofNullable(session).map(SessionDto::fromSession);
    }

    /**
     * Connect a session.
     */
    public void connect(String sessionId) {
        checkEngine();
        log.info("Connecting session: {}", sessionId);
        engine.connect(sessionId);
    }

    /**
     * Disconnect a session.
     */
    public void disconnect(String sessionId) {
        checkEngine();
        log.info("Disconnecting session: {}", sessionId);
        engine.disconnect(sessionId);
    }

    /**
     * Logout a session.
     */
    public void logout(String sessionId, String reason) {
        checkEngine();
        FixSession session = getSessionOrThrow(sessionId);
        log.info("Logging out session: {} - {}", sessionId, reason);
        session.logout(reason != null ? reason : "Admin logout");
    }

    /**
     * Reset sequence numbers to 1.
     */
    public void resetSequence(String sessionId) {
        checkEngine();
        FixSession session = getSessionOrThrow(sessionId);
        log.info("Resetting sequence numbers for session: {}", sessionId);
        session.resetSequenceNumbers();
    }

    /**
     * Set outgoing sequence number.
     */
    public void setOutgoingSeqNum(String sessionId, int seqNum) {
        checkEngine();
        FixSession session = getSessionOrThrow(sessionId);
        log.info("Setting outgoing seq num for session {}: {}", sessionId, seqNum);
        session.setOutgoingSeqNum(seqNum);
    }

    /**
     * Set expected incoming sequence number.
     */
    public void setIncomingSeqNum(String sessionId, int seqNum) {
        checkEngine();
        FixSession session = getSessionOrThrow(sessionId);
        log.info("Setting incoming seq num for session {}: {}", sessionId, seqNum);
        session.setExpectedIncomingSeqNum(seqNum);
    }

    /**
     * Send a test request.
     */
    public String sendTestRequest(String sessionId) {
        checkEngine();
        FixSession session = getSessionOrThrow(sessionId);
        log.info("Sending test request for session: {}", sessionId);
        return session.sendTestRequest();
    }

    /**
     * Trigger EOD for a session.
     */
    public void triggerEod(String sessionId) {
        checkEngine();
        log.info("Triggering EOD for session: {}", sessionId);
        engine.triggerEod(sessionId);
    }

    private void checkEngine() {
        if (engine == null) {
            throw new IllegalStateException("FIX Engine not initialized");
        }
    }

    private FixSession getSessionOrThrow(String sessionId) {
        FixSession session = engine.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }
}
