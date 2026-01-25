package com.omnibridge.optiq.message.session;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq Logout message (Template ID 103).
 * <p>
 * Sent to gracefully terminate a session.
 * Can be sent by either the client or the OEG.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       4       LogicalAccessID
 * 4       2       LogoutReasonCode
 * 6       2       Reserved
 * 8       16      LogoutReasonText (optional)
 * </pre>
 */
public class LogoutMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 103;
    public static final int BLOCK_LENGTH = 24;

    // Field offsets
    private static final int LOGICAL_ACCESS_ID_OFFSET = 0;
    private static final int LOGOUT_REASON_CODE_OFFSET = 4;
    private static final int LOGOUT_REASON_TEXT_OFFSET = 8;
    private static final int LOGOUT_REASON_TEXT_LENGTH = 16;

    @Override
    public int getTemplateId() {
        return TEMPLATE_ID;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public OptiqMessageType getMessageType() {
        return OptiqMessageType.LOGOUT;
    }

    // Logical Access ID
    public int getLogicalAccessId() {
        return getInt(LOGICAL_ACCESS_ID_OFFSET);
    }

    public LogoutMessage setLogicalAccessId(int id) {
        putInt(LOGICAL_ACCESS_ID_OFFSET, id);
        return this;
    }

    // Logout Reason Code
    public int getLogoutReasonCode() {
        return getUnsignedShort(LOGOUT_REASON_CODE_OFFSET);
    }

    public LogoutMessage setLogoutReasonCode(int code) {
        putShort(LOGOUT_REASON_CODE_OFFSET, (short) code);
        return this;
    }

    // Logout Reason Text
    public String getLogoutReasonText() {
        return getString(LOGOUT_REASON_TEXT_OFFSET, LOGOUT_REASON_TEXT_LENGTH);
    }

    public LogoutMessage setLogoutReasonText(String text) {
        putString(LOGOUT_REASON_TEXT_OFFSET, LOGOUT_REASON_TEXT_LENGTH, text);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "LogoutMessage[not wrapped]";
        }
        return String.format("LogoutMessage[logicalAccessId=%d, reasonCode=%d, reason=%s]",
                getLogicalAccessId(), getLogoutReasonCode(), getLogoutReasonText());
    }
}
