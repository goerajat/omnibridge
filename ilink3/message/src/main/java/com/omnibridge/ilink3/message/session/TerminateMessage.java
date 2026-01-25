package com.omnibridge.ilink3.message.session;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 Terminate message (Template ID 507).
 * <p>
 * Sent to gracefully terminate a session.
 * Can be sent by either the client or the CME Globex system.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       8       UUID
 * 8       8       RequestTimestamp
 * 16      2       ErrorCodes
 * 18      48      Reason (variable text)
 * 66      1       SplitMsg
 * </pre>
 */
public class TerminateMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 507;
    public static final int BLOCK_LENGTH = 70;

    // Field offsets
    private static final int UUID_OFFSET = 0;
    private static final int REQUEST_TIMESTAMP_OFFSET = 8;
    private static final int ERROR_CODES_OFFSET = 16;
    private static final int REASON_OFFSET = 18;
    private static final int REASON_LENGTH = 48;
    private static final int SPLIT_MSG_OFFSET = 66;

    @Override
    public int getTemplateId() {
        return TEMPLATE_ID;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public ILink3MessageType getMessageType() {
        return ILink3MessageType.TERMINATE;
    }

    // UUID
    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    public TerminateMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    // Request Timestamp
    public long getRequestTimestamp() {
        return getLong(REQUEST_TIMESTAMP_OFFSET);
    }

    public TerminateMessage setRequestTimestamp(long timestamp) {
        putLong(REQUEST_TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    // Error Codes
    public int getErrorCodes() {
        return getUnsignedShort(ERROR_CODES_OFFSET);
    }

    public TerminateMessage setErrorCodes(int errorCodes) {
        putShort(ERROR_CODES_OFFSET, (short) errorCodes);
        return this;
    }

    // Reason
    public String getReason() {
        return getString(REASON_OFFSET, REASON_LENGTH);
    }

    public TerminateMessage setReason(String reason) {
        putString(REASON_OFFSET, REASON_LENGTH, reason);
        return this;
    }

    // Split Message
    public byte getSplitMsg() {
        return getByte(SPLIT_MSG_OFFSET);
    }

    public TerminateMessage setSplitMsg(byte splitMsg) {
        putByte(SPLIT_MSG_OFFSET, splitMsg);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "TerminateMessage[not wrapped]";
        }
        return String.format("TerminateMessage[uuid=%d, errorCodes=%d, reason=%s]",
                getUuid(), getErrorCodes(), getReason());
    }
}
