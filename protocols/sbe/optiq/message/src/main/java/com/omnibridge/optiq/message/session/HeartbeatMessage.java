package com.omnibridge.optiq.message.session;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq Heartbeat message (Template ID 104).
 * <p>
 * Sent periodically to maintain the session connection.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       4       LogicalAccessID
 * 4       4       Reserved
 * </pre>
 */
public class HeartbeatMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 104;
    public static final int BLOCK_LENGTH = 8;

    // Field offsets
    private static final int LOGICAL_ACCESS_ID_OFFSET = 0;

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
        return OptiqMessageType.HEARTBEAT;
    }

    // Logical Access ID
    public int getLogicalAccessId() {
        return getInt(LOGICAL_ACCESS_ID_OFFSET);
    }

    public HeartbeatMessage setLogicalAccessId(int id) {
        putInt(LOGICAL_ACCESS_ID_OFFSET, id);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "HeartbeatMessage[not wrapped]";
        }
        return String.format("HeartbeatMessage[logicalAccessId=%d]", getLogicalAccessId());
    }
}
