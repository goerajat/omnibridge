package com.omnibridge.optiq.message.session;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq Logon message (Template ID 100).
 * <p>
 * Sent by the client to establish a session with the OEG.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       4       LogicalAccessID
 * 4       2       OEGINInstance
 * 6       2       Reserved
 * 8       8       LastMsgSeqNum
 * 16      20      SoftwareProvider
 * 36      8       QueueingIndicator
 * 44      4       HeartbeatInterval
 * </pre>
 */
public class LogonMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 100;
    public static final int BLOCK_LENGTH = 48;

    // Field offsets
    private static final int LOGICAL_ACCESS_ID_OFFSET = 0;
    private static final int OEG_IN_INSTANCE_OFFSET = 4;
    private static final int LAST_MSG_SEQ_NUM_OFFSET = 8;
    private static final int SOFTWARE_PROVIDER_OFFSET = 16;
    private static final int SOFTWARE_PROVIDER_LENGTH = 20;
    private static final int QUEUEING_INDICATOR_OFFSET = 36;
    private static final int HEARTBEAT_INTERVAL_OFFSET = 44;

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
        return OptiqMessageType.LOGON;
    }

    // Logical Access ID
    public LogonMessage setLogicalAccessId(int id) {
        putInt(LOGICAL_ACCESS_ID_OFFSET, id);
        return this;
    }

    public int getLogicalAccessId() {
        return getInt(LOGICAL_ACCESS_ID_OFFSET);
    }

    // OEG IN Instance
    public LogonMessage setOegInInstance(int instance) {
        putShort(OEG_IN_INSTANCE_OFFSET, (short) instance);
        return this;
    }

    public int getOegInInstance() {
        return getUnsignedShort(OEG_IN_INSTANCE_OFFSET);
    }

    // Last Message Sequence Number
    public LogonMessage setLastMsgSeqNum(long seqNum) {
        putLong(LAST_MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public long getLastMsgSeqNum() {
        return getLong(LAST_MSG_SEQ_NUM_OFFSET);
    }

    // Software Provider
    public LogonMessage setSoftwareProvider(String provider) {
        putString(SOFTWARE_PROVIDER_OFFSET, SOFTWARE_PROVIDER_LENGTH, provider);
        return this;
    }

    public String getSoftwareProvider() {
        return getString(SOFTWARE_PROVIDER_OFFSET, SOFTWARE_PROVIDER_LENGTH);
    }

    // Queueing Indicator
    public LogonMessage setQueueingIndicator(long indicator) {
        putLong(QUEUEING_INDICATOR_OFFSET, indicator);
        return this;
    }

    public long getQueueingIndicator() {
        return getLong(QUEUEING_INDICATOR_OFFSET);
    }

    // Heartbeat Interval (in seconds)
    public LogonMessage setHeartbeatInterval(int interval) {
        putInt(HEARTBEAT_INTERVAL_OFFSET, interval);
        return this;
    }

    public int getHeartbeatInterval() {
        return getInt(HEARTBEAT_INTERVAL_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "LogonMessage[not wrapped]";
        }
        return String.format("LogonMessage[logicalAccessId=%d, oegInstance=%d, lastSeqNum=%d, heartbeat=%d]",
                getLogicalAccessId(), getOegInInstance(), getLastMsgSeqNum(), getHeartbeatInterval());
    }
}
