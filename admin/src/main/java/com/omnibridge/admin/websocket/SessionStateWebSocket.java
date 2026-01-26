package com.omnibridge.admin.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.omnibridge.config.session.ManagedSession;
import com.omnibridge.config.session.SessionConnectionState;
import com.omnibridge.config.session.SessionManagementService;
import com.omnibridge.config.session.SessionStateChangeListener;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WebSocket handler for real-time session state updates.
 *
 * <p>Pushes session state changes to all connected WebSocket clients.
 * Clients can subscribe to receive notifications when:</p>
 * <ul>
 *   <li>Session state changes (DISCONNECTED -> CONNECTING -> CONNECTED -> LOGGED_ON, etc.)</li>
 *   <li>Sessions are registered or unregistered</li>
 * </ul>
 *
 * <p>Message format:</p>
 * <pre>{@code
 * {
 *   "type": "STATE_CHANGE",
 *   "timestamp": "2024-01-20T10:30:00Z",
 *   "sessionId": "SENDER-TARGET",
 *   "sessionName": "SENDER-TARGET",
 *   "protocolType": "FIX",
 *   "oldState": "DISCONNECTED",
 *   "newState": "CONNECTING"
 * }
 * }</pre>
 *
 * <p>WebSocket endpoint: /ws/sessions (configurable)</p>
 */
public class SessionStateWebSocket implements WebSocketHandler, SessionStateChangeListener {

    private static final Logger log = LoggerFactory.getLogger(SessionStateWebSocket.class);

    private final SessionManagementService sessionService;
    private final Set<WsContext> clients;
    private final ObjectMapper objectMapper;
    private volatile boolean active;

    public SessionStateWebSocket(SessionManagementService sessionService) {
        this.sessionService = sessionService;
        this.clients = ConcurrentHashMap.newKeySet();
        this.objectMapper = createObjectMapper();
        this.active = false;
    }

    @Override
    public String getPath() {
        return "/sessions";
    }

    @Override
    public String getDescription() {
        return "Session State WebSocket";
    }

    @Override
    public Consumer<WsConfig> getHandler() {
        return ws -> {
            ws.onConnect(ctx -> {
                clients.add(ctx);
                log.info("WebSocket client connected: {} (total: {})",
                        ctx.session.getRemoteAddress(), clients.size());

                // Send current state of all sessions on connect
                sendInitialState(ctx);
            });

            ws.onClose(ctx -> {
                clients.remove(ctx);
                log.info("WebSocket client disconnected: {} (total: {})",
                        ctx.session.getRemoteAddress(), clients.size());
            });

            ws.onError(ctx -> {
                log.warn("WebSocket error for {}: {}",
                        ctx.session.getRemoteAddress(),
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
                clients.remove(ctx);
            });

            ws.onMessage(ctx -> {
                // Handle incoming messages (e.g., ping/pong, subscriptions)
                String message = ctx.message();
                log.debug("WebSocket message from {}: {}", ctx.session.getRemoteAddress(), message);

                if ("ping".equalsIgnoreCase(message)) {
                    ctx.send("{\"type\":\"PONG\"}");
                }
            });
        };
    }

    @Override
    public void onServerStart() {
        active = true;
        sessionService.addStateChangeListener(this);
        log.info("Session state WebSocket activated");
    }

    @Override
    public void onServerStop() {
        active = false;
        sessionService.removeStateChangeListener(this);

        // Close all client connections
        for (WsContext client : clients) {
            try {
                client.session.close();
            } catch (Exception e) {
                log.debug("Error closing WebSocket client", e);
            }
        }
        clients.clear();

        log.info("Session state WebSocket deactivated");
    }

    // ========== SessionStateChangeListener Implementation ==========

    @Override
    public void onSessionStateChange(ManagedSession session,
                                      SessionConnectionState oldState,
                                      SessionConnectionState newState) {
        if (!active) return;

        // Payload contains state change details
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.getSessionId());
        payload.put("sessionName", session.getSessionName());
        payload.put("protocolType", session.getProtocolType());
        payload.put("oldState", oldState.toString());
        payload.put("state", newState.toString());
        payload.put("connected", session.isConnected());
        payload.put("loggedOn", session.isLoggedOn());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "STATE_CHANGE");
        event.put("timestamp", Instant.now().toString());
        event.put("payload", payload);

        broadcast(event);
    }

    @Override
    public void onSessionRegistered(ManagedSession session) {
        if (!active) return;

        // Payload contains session details
        Map<String, Object> sessionDto = toSessionDto(session);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "SESSION_REGISTERED");
        event.put("timestamp", Instant.now().toString());
        event.put("payload", Map.of("session", sessionDto));

        broadcast(event);
    }

    @Override
    public void onSessionUnregistered(ManagedSession session) {
        if (!active) return;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "SESSION_UNREGISTERED");
        event.put("timestamp", Instant.now().toString());
        event.put("payload", Map.of("sessionId", session.getSessionId()));

        broadcast(event);
    }

    // ========== Helper Methods ==========

    private void sendInitialState(WsContext ctx) {
        // Payload contains sessions list and stats
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessions", sessionService.getAllSessions().stream()
                .map(this::toSessionDto)
                .toList());
        payload.put("stats", Map.of(
                "total", sessionService.getTotalSessionCount(),
                "connected", sessionService.getConnectedSessionCount(),
                "loggedOn", sessionService.getLoggedOnSessionCount()
        ));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "INITIAL_STATE");
        event.put("timestamp", Instant.now().toString());
        event.put("payload", payload);

        sendToClient(ctx, event);
    }

    private Map<String, Object> toSessionDto(ManagedSession session) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sessionId", session.getSessionId());
        dto.put("sessionName", session.getSessionName());
        dto.put("protocolType", session.getProtocolType());
        dto.put("state", session.getConnectionState().toString());
        dto.put("connected", session.isConnected());
        dto.put("loggedOn", session.isLoggedOn());
        dto.put("enabled", session.isEnabled());
        return dto;
    }

    private void broadcast(Map<String, Object> event) {
        String json = toJson(event);
        if (json == null) return;

        int sent = 0;
        for (WsContext client : clients) {
            if (client.session.isOpen()) {
                try {
                    client.send(json);
                    sent++;
                } catch (Exception e) {
                    log.debug("Failed to send to WebSocket client", e);
                    clients.remove(client);
                }
            } else {
                clients.remove(client);
            }
        }

        log.debug("Broadcast {} to {} clients", event.get("type"), sent);
    }

    private void sendToClient(WsContext ctx, Map<String, Object> event) {
        String json = toJson(event);
        if (json == null) return;

        try {
            ctx.send(json);
        } catch (Exception e) {
            log.debug("Failed to send to WebSocket client", e);
            clients.remove(ctx);
        }
    }

    private String toJson(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event to JSON", e);
            return null;
        }
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Get the number of connected WebSocket clients.
     */
    public int getClientCount() {
        return clients.size();
    }
}
