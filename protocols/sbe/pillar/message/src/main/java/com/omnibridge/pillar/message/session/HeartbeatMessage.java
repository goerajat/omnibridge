package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Heartbeat message.
 * <p>
 * Sent by both client and gateway to maintain the connection.
 * Must be sent once per second even if other data is being sent.
 * Gateway will close connection if no heartbeat received within 5 seconds.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       8       Timestamp (nanoseconds since epoch)
 * </pre>
 */
public class HeartbeatMessage extends PillarMessage {

    public static final int TIMESTAMP_OFFSET = 0;

    public static final int BLOCK_LENGTH = 8;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.HEARTBEAT;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.HEARTBEAT.getTemplateId();
    }

    // ==================== Getters ====================

    public long getTimestamp() {
        return getUInt64(TIMESTAMP_OFFSET);
    }

    // ==================== Setters ====================

    public HeartbeatMessage setTimestamp(long timestampNanos) {
        putUInt64(TIMESTAMP_OFFSET, timestampNanos);
        return this;
    }

    /**
     * Sets the timestamp to the current time.
     */
    public HeartbeatMessage setCurrentTimestamp() {
        return setTimestamp(System.nanoTime());
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "HeartbeatMessage[not wrapped]";
        }
        return "HeartbeatMessage[timestamp=" + getTimestamp() + "]";
    }
}
