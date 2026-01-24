package com.omnibridge.admin.routes;

import com.omnibridge.config.session.ManagedSession;
import com.omnibridge.config.session.SessionManagementService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API routes for session management.
 *
 * <p>Provides endpoints for:</p>
 * <ul>
 *   <li>Listing all sessions</li>
 *   <li>Getting session details</li>
 *   <li>Enabling/disabling sessions</li>
 *   <li>Session statistics</li>
 *   <li>Bulk operations</li>
 * </ul>
 *
 * <p>Endpoints:</p>
 * <pre>
 * GET    /sessions              - List all sessions
 * GET    /sessions/stats        - Get session statistics
 * GET    /sessions/{id}         - Get session by ID
 * POST   /sessions/{id}/enable  - Enable a session
 * POST   /sessions/{id}/disable - Disable a session
 * POST   /sessions/enable-all   - Enable all sessions
 * POST   /sessions/disable-all  - Disable all sessions
 * GET    /sessions/protocol/{type} - Get sessions by protocol
 * GET    /sessions/connected    - Get connected sessions
 * GET    /sessions/logged-on    - Get logged on sessions
 * </pre>
 */
public class SessionRoutes implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(SessionRoutes.class);

    private final SessionManagementService sessionService;

    public SessionRoutes(SessionManagementService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String getBasePath() {
        return "/sessions";
    }

    @Override
    public String getDescription() {
        return "Session Management API";
    }

    @Override
    public int getPriority() {
        return 10; // Register early
    }

    @Override
    public void registerRoutes(Javalin app, String contextPath) {
        String base = contextPath + getBasePath();

        // List all sessions
        app.get(base, this::getAllSessions);

        // Session statistics
        app.get(base + "/stats", this::getStats);

        // Bulk operations
        app.post(base + "/enable-all", this::enableAll);
        app.post(base + "/disable-all", this::disableAll);

        // Filter by protocol
        app.get(base + "/protocol/{type}", this::getByProtocol);

        // Filter by state
        app.get(base + "/connected", this::getConnected);
        app.get(base + "/logged-on", this::getLoggedOn);

        // Single session operations
        app.get(base + "/{id}", this::getSession);
        app.post(base + "/{id}/enable", this::enableSession);
        app.post(base + "/{id}/disable", this::disableSession);
        app.put(base + "/{id}/sequence", this::setSequenceNumbers);
    }

    // ========== Handlers ==========

    private void getAllSessions(Context ctx) {
        Collection<ManagedSession> sessions = sessionService.getAllSessions();
        List<Map<String, Object>> result = sessions.stream()
                .map(this::toDto)
                .toList();
        ctx.json(result);
    }

    private void getStats(Context ctx) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", sessionService.getTotalSessionCount());
        stats.put("connected", sessionService.getConnectedSessionCount());
        stats.put("loggedOn", sessionService.getLoggedOnSessionCount());

        // Count by protocol
        Map<String, Long> byProtocol = new LinkedHashMap<>();
        for (ManagedSession session : sessionService.getAllSessions()) {
            byProtocol.merge(session.getProtocolType(), 1L, Long::sum);
        }
        stats.put("byProtocol", byProtocol);

        ctx.json(stats);
    }

    private void getSession(Context ctx) {
        String id = ctx.pathParam("id");
        sessionService.getSession(id)
                .ifPresentOrElse(
                        session -> ctx.json(toDetailedDto(session)),
                        () -> {
                            ctx.status(HttpStatus.NOT_FOUND);
                            ctx.json(Map.of("error", "Session not found: " + id));
                        }
                );
    }

    private void enableSession(Context ctx) {
        String id = ctx.pathParam("id");
        sessionService.getSession(id)
                .ifPresentOrElse(
                        session -> {
                            session.enable();
                            log.info("Enabled session: {}", id);
                            ctx.json(Map.of(
                                    "success", true,
                                    "sessionId", id,
                                    "enabled", session.isEnabled()
                            ));
                        },
                        () -> {
                            ctx.status(HttpStatus.NOT_FOUND);
                            ctx.json(Map.of("error", "Session not found: " + id));
                        }
                );
    }

    private void disableSession(Context ctx) {
        String id = ctx.pathParam("id");
        sessionService.getSession(id)
                .ifPresentOrElse(
                        session -> {
                            session.disable();
                            log.info("Disabled session: {}", id);
                            ctx.json(Map.of(
                                    "success", true,
                                    "sessionId", id,
                                    "enabled", session.isEnabled()
                            ));
                        },
                        () -> {
                            ctx.status(HttpStatus.NOT_FOUND);
                            ctx.json(Map.of("error", "Session not found: " + id));
                        }
                );
    }

    private void setSequenceNumbers(Context ctx) {
        String id = ctx.pathParam("id");
        sessionService.getSession(id)
                .ifPresentOrElse(
                        session -> {
                            Map<String, Object> body = ctx.bodyAsClass(Map.class);

                            if (body.containsKey("incomingSeqNum")) {
                                long seqNum = ((Number) body.get("incomingSeqNum")).longValue();
                                session.setIncomingSeqNum(seqNum);
                                log.info("Set incoming sequence number for {}: {}", id, seqNum);
                            }
                            if (body.containsKey("outgoingSeqNum")) {
                                long seqNum = ((Number) body.get("outgoingSeqNum")).longValue();
                                session.setOutgoingSeqNum(seqNum);
                                log.info("Set outgoing sequence number for {}: {}", id, seqNum);
                            }

                            ctx.json(Map.of(
                                    "success", true,
                                    "sessionId", id,
                                    "incomingSeqNum", session.incomingSeqNum(),
                                    "outgoingSeqNum", session.outgoingSeqNum()
                            ));
                        },
                        () -> {
                            ctx.status(HttpStatus.NOT_FOUND);
                            ctx.json(Map.of("error", "Session not found: " + id));
                        }
                );
    }

    private void enableAll(Context ctx) {
        sessionService.enableAllSessions();
        log.info("Enabled all sessions");
        ctx.json(Map.of(
                "success", true,
                "message", "All sessions enabled",
                "count", sessionService.getTotalSessionCount()
        ));
    }

    private void disableAll(Context ctx) {
        sessionService.disableAllSessions();
        log.info("Disabled all sessions");
        ctx.json(Map.of(
                "success", true,
                "message", "All sessions disabled",
                "count", sessionService.getTotalSessionCount()
        ));
    }

    private void getByProtocol(Context ctx) {
        String protocol = ctx.pathParam("type").toUpperCase();
        Collection<ManagedSession> sessions = sessionService.getSessionsByProtocol(protocol);
        List<Map<String, Object>> result = sessions.stream()
                .map(this::toDto)
                .toList();
        ctx.json(result);
    }

    private void getConnected(Context ctx) {
        Collection<ManagedSession> sessions = sessionService.getConnectedSessions();
        List<Map<String, Object>> result = sessions.stream()
                .map(this::toDto)
                .toList();
        ctx.json(result);
    }

    private void getLoggedOn(Context ctx) {
        Collection<ManagedSession> sessions = sessionService.getLoggedOnSessions();
        List<Map<String, Object>> result = sessions.stream()
                .map(this::toDto)
                .toList();
        ctx.json(result);
    }

    // ========== DTO Conversion ==========

    private Map<String, Object> toDto(ManagedSession session) {
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

    private Map<String, Object> toDetailedDto(ManagedSession session) {
        Map<String, Object> dto = toDto(session);
        dto.put("incomingSeqNum", session.incomingSeqNum());
        dto.put("outgoingSeqNum", session.outgoingSeqNum());

        if (session.connectionAddress() != null) {
            dto.put("connectionAddress", session.connectionAddress().toString());
        }
        if (session.connectedAddress() != null) {
            dto.put("connectedAddress", session.connectedAddress().toString());
        }

        return dto;
    }
}
