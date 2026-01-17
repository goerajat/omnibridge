package com.fixengine.admin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixengine.admin.dto.SessionDto;
import com.fixengine.admin.service.SessionService;
import com.fixengine.engine.session.EodEvent;
import com.fixengine.engine.session.EodEventListener;
import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time session state updates.
 */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler
        implements SessionStateListener, EodEventListener {

    private static final Logger log = LoggerFactory.getLogger(SessionWebSocketHandler.class);

    private final SessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public SessionWebSocketHandler(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
        sessions.add(session);

        // Send initial state of all sessions
        sendAllSessionsUpdate(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {} - {}", session.getId(), status);
        sessions.remove(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Handle incoming messages (e.g., subscription requests)
        String payload = message.getPayload();
        log.debug("Received WebSocket message: {}", payload);

        // For now, any message triggers a refresh
        if ("refresh".equals(payload)) {
            sendAllSessionsUpdate(session);
        }
    }

    @Override
    public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
        log.debug("Session state change: {} {} -> {}", session.getConfig().getSessionId(), oldState, newState);
        broadcastSessionUpdate(session, "STATE_CHANGE", Map.of(
                "oldState", oldState.name(),
                "newState", newState.name()
        ));
    }

    @Override
    public void onSessionConnected(FixSession session) {
        broadcastSessionUpdate(session, "CONNECTED", null);
    }

    @Override
    public void onSessionLogon(FixSession session) {
        broadcastSessionUpdate(session, "LOGON", null);
    }

    @Override
    public void onSessionLogout(FixSession session, String reason) {
        broadcastSessionUpdate(session, "LOGOUT", Map.of("reason", reason != null ? reason : ""));
    }

    @Override
    public void onSessionDisconnected(FixSession session, Throwable cause) {
        broadcastSessionUpdate(session, "DISCONNECTED", Map.of(
                "cause", cause != null ? cause.getMessage() : ""
        ));
    }

    @Override
    public void onSessionError(FixSession session, Throwable error) {
        broadcastSessionUpdate(session, "ERROR", Map.of(
                "error", error != null ? error.getMessage() : ""
        ));
    }

    @Override
    public void onEodEvent(FixSession session, EodEvent event) {
        broadcastSessionUpdate(session, "EOD", Map.of(
                "type", event.getType().name(),
                "previousOutSeq", event.getPreviousOutgoingSeqNum(),
                "previousInSeq", event.getPreviousIncomingSeqNum()
        ));
    }

    private void broadcastSessionUpdate(FixSession fixSession, String eventType, Map<String, Object> extra) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            SessionDto dto = SessionDto.fromSession(fixSession);
            Map<String, Object> message = Map.of(
                    "type", "SESSION_UPDATE",
                    "event", eventType,
                    "session", dto,
                    "timestamp", System.currentTimeMillis(),
                    "extra", extra != null ? extra : Map.of()
            );

            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.warn("Failed to send WebSocket message to {}: {}", session.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error broadcasting session update", e);
        }
    }

    private void sendAllSessionsUpdate(WebSocketSession wsSession) {
        try {
            var allSessions = sessionService.getAllSessions();
            Map<String, Object> message = Map.of(
                    "type", "ALL_SESSIONS",
                    "sessions", allSessions,
                    "timestamp", System.currentTimeMillis()
            );

            String json = objectMapper.writeValueAsString(message);
            wsSession.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("Error sending all sessions update", e);
        }
    }

    /**
     * Register this handler with the FIX engine for state updates.
     */
    public void registerWithEngine() {
        if (sessionService.isEngineAvailable()) {
            sessionService.getEngine().addStateListener(this);
            sessionService.getEngine().addEodListener(this);
            log.info("WebSocket handler registered with FIX engine");
        }
    }

    /**
     * Unregister this handler from the FIX engine.
     */
    public void unregisterFromEngine() {
        if (sessionService.isEngineAvailable()) {
            sessionService.getEngine().removeStateListener(this);
            sessionService.getEngine().removeEodListener(this);
            log.info("WebSocket handler unregistered from FIX engine");
        }
    }
}
