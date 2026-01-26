package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Login Response message.
 * <p>
 * Sent by the gateway in response to a Login message.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       1       Status (0=Success, 1=InvalidUser, 2=InvalidPassword, etc.)
 * 1       1       Reserved
 * 2       2       Protocol Version Major
 * 4       2       Protocol Version Minor
 * 6       2       Reserved
 * 8       8       Session ID
 * 16      8       Heartbeat Interval (nanoseconds)
 * </pre>
 */
public class LoginResponseMessage extends PillarMessage {

    public static final int STATUS_OFFSET = 0;
    public static final int PROTOCOL_VERSION_MAJOR_OFFSET = 2;
    public static final int PROTOCOL_VERSION_MINOR_OFFSET = 4;
    public static final int SESSION_ID_OFFSET = 8;
    public static final int HEARTBEAT_INTERVAL_OFFSET = 16;

    public static final int BLOCK_LENGTH = 24;

    // Status codes
    public static final byte STATUS_SUCCESS = 0;
    public static final byte STATUS_INVALID_USER = 1;
    public static final byte STATUS_INVALID_PASSWORD = 2;
    public static final byte STATUS_ALREADY_LOGGED_IN = 3;
    public static final byte STATUS_PROTOCOL_VERSION_MISMATCH = 4;
    public static final byte STATUS_SESSION_LIMIT = 5;
    public static final byte STATUS_UNAUTHORIZED = 6;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.LOGIN_RESPONSE;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.LOGIN_RESPONSE.getTemplateId();
    }

    // ==================== Getters ====================

    public byte getStatus() {
        return getByte(STATUS_OFFSET);
    }

    public int getProtocolVersionMajor() {
        return getUInt16(PROTOCOL_VERSION_MAJOR_OFFSET);
    }

    public int getProtocolVersionMinor() {
        return getUInt16(PROTOCOL_VERSION_MINOR_OFFSET);
    }

    public long getSessionId() {
        return getUInt64(SESSION_ID_OFFSET);
    }

    public long getHeartbeatInterval() {
        return getUInt64(HEARTBEAT_INTERVAL_OFFSET);
    }

    // ==================== Setters ====================

    public LoginResponseMessage setStatus(byte status) {
        putByte(STATUS_OFFSET, status);
        return this;
    }

    public LoginResponseMessage setProtocolVersionMajor(int version) {
        putUInt16(PROTOCOL_VERSION_MAJOR_OFFSET, version);
        return this;
    }

    public LoginResponseMessage setProtocolVersionMinor(int version) {
        putUInt16(PROTOCOL_VERSION_MINOR_OFFSET, version);
        return this;
    }

    public LoginResponseMessage setSessionId(long sessionId) {
        putUInt64(SESSION_ID_OFFSET, sessionId);
        return this;
    }

    public LoginResponseMessage setHeartbeatInterval(long intervalNanos) {
        putUInt64(HEARTBEAT_INTERVAL_OFFSET, intervalNanos);
        return this;
    }

    // ==================== Status Helpers ====================

    public boolean isSuccess() {
        return getStatus() == STATUS_SUCCESS;
    }

    public String getStatusDescription() {
        return switch (getStatus()) {
            case STATUS_SUCCESS -> "Success";
            case STATUS_INVALID_USER -> "Invalid Username";
            case STATUS_INVALID_PASSWORD -> "Invalid Password";
            case STATUS_ALREADY_LOGGED_IN -> "Already Logged In";
            case STATUS_PROTOCOL_VERSION_MISMATCH -> "Protocol Version Mismatch";
            case STATUS_SESSION_LIMIT -> "Session Limit Exceeded";
            case STATUS_UNAUTHORIZED -> "Unauthorized";
            default -> "Unknown (" + getStatus() + ")";
        };
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "LoginResponseMessage[not wrapped]";
        }
        return "LoginResponseMessage[status=" + getStatusDescription() +
               ", sessionId=" + getSessionId() +
               ", version=" + getProtocolVersionMajor() + "." + getProtocolVersionMinor() + "]";
    }
}
