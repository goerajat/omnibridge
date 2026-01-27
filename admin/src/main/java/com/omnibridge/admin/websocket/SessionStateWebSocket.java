package com.omnibridge.admin.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.omnibridge.admin.AdminServer.ConfigurableWebSocketHandler;
import com.omnibridge.config.session.ManagedSession;
import com.omnibridge.config.session.SessionConnectionState;
import com.omnibridge.config.session.SessionManagementService;
import com.omnibridge.config.session.SessionStateChangeListener;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
public class SessionStateWebSocket implements ConfigurableWebSocketHandler, SessionStateChangeListener {

    private static final Logger log = LoggerFactory.getLogger(SessionStateWebSocket.class);
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 300000; // 5 minutes

    private final SessionManagementService sessionService;
    private final Set<WsContext> clients;
    private final ObjectMapper objectMapper;
    private volatile boolean active;
    private volatile long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;

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
    public void setIdleTimeoutMs(long timeoutMs) {
        this.idleTimeoutMs = timeoutMs;
        log.info("WebSocket idle timeout set to {}ms", timeoutMs);
    }

    @Override
    public Consumer<WsConfig> getHandler() {
        return ws -> {
            ws.onConnect(ctx -> {
                // Set idle timeout to prevent premature disconnections
                ctx.session.setIdleTimeout(Duration.ofMillis(idleTimeoutMs));

                clients.add(ctx);
                log.info("WebSocket client connected: {} (total: {}, idle timeout: {}ms)",
                        ctx.session.getRemoteAddress(), clients.size(), idleTimeoutMs);

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
        var allSessions = sessionService.getAllSessions();

        payload.put("sessions", allSessions.stream()
                .map(this::toSessionDto)
                .toList());
        payload.put("stats", computeStats(allSessions));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "INITIAL_STATE");
        event.put("timestamp", Instant.now().toString());
        event.put("payload", payload);

        sendToClient(ctx, event);
    }

    /**
     * Compute stats in the format expected by OmniView frontend.
     */
    private Map<String, Object> computeStats(java.util.Collection<ManagedSession> sessions) {
        int total = 0, connected = 0, loggedOn = 0, enabled = 0;
        int fixTotal = 0, fixConnected = 0, fixLoggedOn = 0;
        int ouchTotal = 0, ouchConnected = 0, ouchLoggedOn = 0;

        for (ManagedSession session : sessions) {
            total++;
            if (session.isConnected()) connected++;
            if (session.isLoggedOn()) loggedOn++;
            if (session.isEnabled()) enabled++;

            String protocol = session.getProtocolType();
            if ("FIX".equals(protocol)) {
                fixTotal++;
                if (session.isConnected()) fixConnected++;
                if (session.isLoggedOn()) fixLoggedOn++;
            } else if ("OUCH".equals(protocol)) {
                ouchTotal++;
                if (session.isConnected()) ouchConnected++;
                if (session.isLoggedOn()) ouchLoggedOn++;
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("connected", connected);
        stats.put("loggedOn", loggedOn);
        stats.put("enabled", enabled);
        stats.put("byProtocol", Map.of(
                "FIX", Map.of("total", fixTotal, "connected", fixConnected, "loggedOn", fixLoggedOn),
                "OUCH", Map.of("total", ouchTotal, "connected", ouchConnected, "loggedOn", ouchLoggedOn)
        ));

        return stats;
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
