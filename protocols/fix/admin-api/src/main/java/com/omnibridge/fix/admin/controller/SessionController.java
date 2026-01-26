package com.omnibridge.fix.admin.controller;

import com.omnibridge.fix.admin.dto.SessionActionRequest;
import com.omnibridge.fix.admin.dto.SessionActionResponse;
import com.omnibridge.fix.admin.dto.SessionDto;
import com.omnibridge.fix.admin.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for FIX session management.
 */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "Sessions", description = "FIX Session Management API")
@CrossOrigin(origins = "*")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    @Operation(summary = "List all sessions", description = "Get all FIX sessions with optional filtering")
    public ResponseEntity<List<SessionDto>> listSessions(
            @Parameter(description = "Filter by state")
            @RequestParam(required = false) String state,
            @Parameter(description = "Filter by connected status")
            @RequestParam(required = false) Boolean connected,
            @Parameter(description = "Filter by logged on status")
            @RequestParam(required = false) Boolean loggedOn) {

        List<SessionDto> sessions;

        if (state != null) {
            sessions = sessionService.getSessionsByState(state);
        } else if (Boolean.TRUE.equals(connected)) {
            sessions = sessionService.getConnectedSessions();
        } else if (Boolean.TRUE.equals(loggedOn)) {
            sessions = sessionService.getLoggedOnSessions();
        } else {
            sessions = sessionService.getAllSessions();
        }

        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get session details", description = "Get detailed information about a specific session")
    public ResponseEntity<SessionDto> getSession(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId) {

        return sessionService.getSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/connect")
    @Operation(summary = "Connect session", description = "Initiate connection for an initiator session")
    public ResponseEntity<SessionActionResponse> connect(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId) {

        try {
            sessionService.connect(sessionId);
            return ResponseEntity.ok(SessionActionResponse.success(sessionId, "Connection initiated"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/disconnect")
    @Operation(summary = "Disconnect session", description = "Disconnect a session")
    public ResponseEntity<SessionActionResponse> disconnect(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId) {

        try {
            sessionService.disconnect(sessionId);
            return ResponseEntity.ok(SessionActionResponse.success(sessionId, "Disconnection initiated"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/logout")
    @Operation(summary = "Logout session", description = "Send logout message and disconnect")
    public ResponseEntity<SessionActionResponse> logout(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId,
            @RequestBody(required = false) SessionActionRequest request) {

        try {
            String reason = request != null ? request.getReason() : null;
            sessionService.logout(sessionId, reason);
            return ResponseEntity.ok(SessionActionResponse.success(sessionId, "Logout initiated"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/reset-sequence")
    @Operation(summary = "Reset sequence numbers", description = "Reset both incoming and outgoing sequence numbers to 1")
    public ResponseEntity<SessionActionResponse> resetSequence(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId) {

        try {
            sessionService.resetSequence(sessionId);
            return ResponseEntity.ok(SessionActionResponse.success(sessionId, "Sequence numbers reset to 1"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/set-outgoing-seq")
    @Operation(summary = "Set outgoing sequence number", description = "Set the next outgoing sequence number")
    public ResponseEntity<SessionActionResponse> setOutgoingSeq(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId,
            @Valid @RequestBody SessionActionRequest request) {

        try {
            if (request.getSeqNum() == null) {
                return ResponseEntity.badRequest()
                        .body(SessionActionResponse.failure(sessionId, "seqNum is required"));
            }
            sessionService.setOutgoingSeqNum(sessionId, request.getSeqNum());
            return ResponseEntity.ok(SessionActionResponse.success(sessionId,
                    "Outgoing sequence number set to " + request.getSeqNum()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/set-incoming-seq")
    @Operation(summary = "Set incoming sequence number", description = "Set the expected incoming sequence number")
    public ResponseEntity<SessionActionResponse> setIncomingSeq(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId,
            @Valid @RequestBody SessionActionRequest request) {

        try {
            if (request.getSeqNum() == null) {
                return ResponseEntity.badRequest()
                        .body(SessionActionResponse.failure(sessionId, "seqNum is required"));
            }
            sessionService.setIncomingSeqNum(sessionId, request.getSeqNum());
            return ResponseEntity.ok(SessionActionResponse.success(sessionId,
                    "Incoming sequence number set to " + request.getSeqNum()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/send-test-request")
    @Operation(summary = "Send test request", description = "Send a TestRequest message")
    public ResponseEntity<SessionActionResponse> sendTestRequest(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId) {

        try {
            String testReqId = sessionService.sendTestRequest(sessionId);
            return ResponseEntity.ok(SessionActionResponse.success(sessionId,
                    "TestRequest sent", Map.of("testReqId", testReqId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @PostMapping("/{id}/trigger-eod")
    @Operation(summary = "Trigger EOD", description = "Manually trigger End of Day sequence reset")
    public ResponseEntity<SessionActionResponse> triggerEod(
            @Parameter(description = "Session ID")
            @PathVariable("id") String sessionId) {

        try {
            sessionService.triggerEod(sessionId);
            return ResponseEntity.ok(SessionActionResponse.success(sessionId, "EOD triggered"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(SessionActionResponse.failure(sessionId, e.getMessage()));
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if the FIX engine is running")
    public ResponseEntity<Map<String, Object>> health() {
        boolean available = sessionService.isEngineAvailable();
        return ResponseEntity.ok(Map.of(
                "status", available ? "UP" : "DOWN",
                "engineRunning", available
        ));
    }
}
