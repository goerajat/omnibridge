package com.fixengine.admin.dto;

import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.SessionState;

/**
 * DTO representing a FIX session.
 */
public class SessionDto {

    private String sessionId;
    private String sessionName;
    private String senderCompId;
    private String targetCompId;
    private String role;
    private String state;
    private boolean connected;
    private boolean loggedOn;
    private int outgoingSeqNum;
    private int expectedIncomingSeqNum;
    private String host;
    private int port;
    private int heartbeatInterval;

    public SessionDto() {}

    /**
     * Create DTO from a FixSession.
     */
    public static SessionDto fromSession(FixSession session) {
        SessionDto dto = new SessionDto();
        dto.setSessionId(session.getConfig().getSessionId());
        dto.setSessionName(session.getConfig().getSessionName());
        dto.setSenderCompId(session.getConfig().getSenderCompId());
        dto.setTargetCompId(session.getConfig().getTargetCompId());
        dto.setRole(session.getConfig().getRole().name());
        dto.setState(session.getState().name());
        dto.setConnected(session.getState().isConnected());
        dto.setLoggedOn(session.getState().isLoggedOn());
        dto.setOutgoingSeqNum(session.getOutgoingSeqNum());
        dto.setExpectedIncomingSeqNum(session.getExpectedIncomingSeqNum());
        dto.setHost(session.getConfig().getHost());
        dto.setPort(session.getConfig().getPort());
        dto.setHeartbeatInterval(session.getConfig().getHeartbeatInterval());
        return dto;
    }

    // Getters and Setters

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        this.sessionName = sessionName;
    }

    public String getSenderCompId() {
        return senderCompId;
    }

    public void setSenderCompId(String senderCompId) {
        this.senderCompId = senderCompId;
    }

    public String getTargetCompId() {
        return targetCompId;
    }

    public void setTargetCompId(String targetCompId) {
        this.targetCompId = targetCompId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isLoggedOn() {
        return loggedOn;
    }

    public void setLoggedOn(boolean loggedOn) {
        this.loggedOn = loggedOn;
    }

    public int getOutgoingSeqNum() {
        return outgoingSeqNum;
    }

    public void setOutgoingSeqNum(int outgoingSeqNum) {
        this.outgoingSeqNum = outgoingSeqNum;
    }

    public int getExpectedIncomingSeqNum() {
        return expectedIncomingSeqNum;
    }

    public void setExpectedIncomingSeqNum(int expectedIncomingSeqNum) {
        this.expectedIncomingSeqNum = expectedIncomingSeqNum;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }
}
