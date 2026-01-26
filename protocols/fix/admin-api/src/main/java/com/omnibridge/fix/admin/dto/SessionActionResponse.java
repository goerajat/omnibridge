package com.omnibridge.fix.admin.dto;

import java.time.Instant;

/**
 * Response DTO for session actions.
 */
public class SessionActionResponse {

    private boolean success;
    private String message;
    private String sessionId;
    private Instant timestamp;
    private Object data;

    public SessionActionResponse() {
        this.timestamp = Instant.now();
    }

    /**
     * Create a success response.
     */
    public static SessionActionResponse success(String sessionId, String message) {
        SessionActionResponse response = new SessionActionResponse();
        response.setSuccess(true);
        response.setSessionId(sessionId);
        response.setMessage(message);
        return response;
    }

    /**
     * Create a success response with data.
     */
    public static SessionActionResponse success(String sessionId, String message, Object data) {
        SessionActionResponse response = success(sessionId, message);
        response.setData(data);
        return response;
    }

    /**
     * Create a failure response.
     */
    public static SessionActionResponse failure(String sessionId, String message) {
        SessionActionResponse response = new SessionActionResponse();
        response.setSuccess(false);
        response.setSessionId(sessionId);
        response.setMessage(message);
        return response;
    }

    // Getters and Setters

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
