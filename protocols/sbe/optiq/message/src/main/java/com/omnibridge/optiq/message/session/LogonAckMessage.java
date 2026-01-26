package com.omnibridge.optiq.message.session;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq LogonAck message (Template ID 101).
 * <p>
 * Sent by the OEG to acknowledge a successful logon.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       4       LogicalAccessID
 * 4       8       LastMsgSeqNum
 * 12      8       LastClMsgSeqNum
 * 20      4       HeartbeatInterval
 * 24      8       ServerTime
 * </pre>
 */
public class LogonAckMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 101;
    public static final int BLOCK_LENGTH = 32;

    // Field offsets
    private static final int LOGICAL_ACCESS_ID_OFFSET = 0;
    private static final int LAST_MSG_SEQ_NUM_OFFSET = 4;
    private static final int LAST_CL_MSG_SEQ_NUM_OFFSET = 12;
    private static final int HEARTBEAT_INTERVAL_OFFSET = 20;
    private static final int SERVER_TIME_OFFSET = 24;

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
        return OptiqMessageType.LOGON_ACK;
    }

    // Logical Access ID
    public int getLogicalAccessId() {
        return getInt(LOGICAL_ACCESS_ID_OFFSET);
    }

    public LogonAckMessage setLogicalAccessId(int id) {
        putInt(LOGICAL_ACCESS_ID_OFFSET, id);
        return this;
    }

    // Last Message Sequence Number
    public long getLastMsgSeqNum() {
        return getLong(LAST_MSG_SEQ_NUM_OFFSET);
    }

    public LogonAckMessage setLastMsgSeqNum(long seqNum) {
        putLong(LAST_MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    // Last Client Message Sequence Number
    public long getLastClMsgSeqNum() {
        return getLong(LAST_CL_MSG_SEQ_NUM_OFFSET);
    }

    public LogonAckMessage setLastClMsgSeqNum(long seqNum) {
        putLong(LAST_CL_MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    // Heartbeat Interval (in seconds)
    public int getHeartbeatInterval() {
        return getInt(HEARTBEAT_INTERVAL_OFFSET);
    }

    public LogonAckMessage setHeartbeatInterval(int interval) {
        putInt(HEARTBEAT_INTERVAL_OFFSET, interval);
        return this;
    }

    // Server Time
    public long getServerTime() {
        return getLong(SERVER_TIME_OFFSET);
    }

    public LogonAckMessage setServerTime(long time) {
        putLong(SERVER_TIME_OFFSET, time);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "LogonAckMessage[not wrapped]";
        }
        return String.format("LogonAckMessage[logicalAccessId=%d, lastSeqNum=%d, heartbeat=%d]",
                getLogicalAccessId(), getLastMsgSeqNum(), getHeartbeatInterval());
    }
}
