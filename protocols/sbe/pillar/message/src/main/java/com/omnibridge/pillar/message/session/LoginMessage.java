package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Login message.
 * <p>
 * Sent by the client to authenticate with the gateway.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       32      Username (ASCII, null-padded)
 * 32      32      Password (ASCII, null-padded)
 * 64      2       Protocol Version Major
 * 66      2       Protocol Version Minor
 * 68      4       Reserved
 * </pre>
 */
public class LoginMessage extends PillarMessage {

    public static final int USERNAME_OFFSET = 0;
    public static final int USERNAME_LENGTH = 32;
    public static final int PASSWORD_OFFSET = 32;
    public static final int PASSWORD_LENGTH = 32;
    public static final int PROTOCOL_VERSION_MAJOR_OFFSET = 64;
    public static final int PROTOCOL_VERSION_MINOR_OFFSET = 66;
    public static final int RESERVED_OFFSET = 68;

    public static final int BLOCK_LENGTH = 72;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.LOGIN;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.LOGIN.getTemplateId();
    }

    // ==================== Getters ====================

    public String getUsername() {
        return getString(USERNAME_OFFSET, USERNAME_LENGTH);
    }

    public String getPassword() {
        return getString(PASSWORD_OFFSET, PASSWORD_LENGTH);
    }

    public int getProtocolVersionMajor() {
        return getUInt16(PROTOCOL_VERSION_MAJOR_OFFSET);
    }

    public int getProtocolVersionMinor() {
        return getUInt16(PROTOCOL_VERSION_MINOR_OFFSET);
    }

    // ==================== Setters ====================

    public LoginMessage setUsername(String username) {
        putString(USERNAME_OFFSET, username, USERNAME_LENGTH);
        return this;
    }

    public LoginMessage setPassword(String password) {
        putString(PASSWORD_OFFSET, password, PASSWORD_LENGTH);
        return this;
    }

    public LoginMessage setProtocolVersionMajor(int version) {
        putUInt16(PROTOCOL_VERSION_MAJOR_OFFSET, version);
        return this;
    }

    public LoginMessage setProtocolVersionMinor(int version) {
        putUInt16(PROTOCOL_VERSION_MINOR_OFFSET, version);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "LoginMessage[not wrapped]";
        }
        return "LoginMessage[username=" + getUsername() +
               ", version=" + getProtocolVersionMajor() + "." + getProtocolVersionMinor() + "]";
    }
}
