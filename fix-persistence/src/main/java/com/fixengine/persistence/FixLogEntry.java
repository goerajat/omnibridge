package com.fixengine.persistence;

import java.time.Instant;

/**
 * Represents a single FIX message log entry.
 */
public class FixLogEntry {

    /**
     * Message direction.
     */
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    private long timestamp;          // Epoch milliseconds
    private int seqNum;              // FIX sequence number
    private Direction direction;     // INBOUND or OUTBOUND
    private String streamName;       // Session/stream identifier
    private String msgType;          // FIX message type
    private long transactionId;      // Application transaction ID
    private byte[] rawMessage;       // Raw FIX message bytes
    private String metadata;         // Additional metadata (JSON)

    /**
     * Create an empty log entry.
     */
    public FixLogEntry() {
    }

    /**
     * Create a log entry with all fields.
     */
    public FixLogEntry(long timestamp, int seqNum, Direction direction, String streamName,
                       String msgType, long transactionId, byte[] rawMessage, String metadata) {
        this.timestamp = timestamp;
        this.seqNum = seqNum;
        this.direction = direction;
        this.streamName = streamName;
        this.msgType = msgType;
        this.transactionId = transactionId;
        this.rawMessage = rawMessage;
        this.metadata = metadata;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final FixLogEntry entry = new FixLogEntry();

        public Builder timestamp(long timestamp) {
            entry.timestamp = timestamp;
            return this;
        }

        public Builder timestamp(Instant instant) {
            entry.timestamp = instant.toEpochMilli();
            return this;
        }

        public Builder timestampNow() {
            entry.timestamp = System.currentTimeMillis();
            return this;
        }

        public Builder seqNum(int seqNum) {
            entry.seqNum = seqNum;
            return this;
        }

        public Builder direction(Direction direction) {
            entry.direction = direction;
            return this;
        }

        public Builder inbound() {
            entry.direction = Direction.INBOUND;
            return this;
        }

        public Builder outbound() {
            entry.direction = Direction.OUTBOUND;
            return this;
        }

        public Builder streamName(String streamName) {
            entry.streamName = streamName;
            return this;
        }

        public Builder msgType(String msgType) {
            entry.msgType = msgType;
            return this;
        }

        public Builder transactionId(long transactionId) {
            entry.transactionId = transactionId;
            return this;
        }

        public Builder rawMessage(byte[] rawMessage) {
            entry.rawMessage = rawMessage;
            return this;
        }

        public Builder rawMessage(byte[] data, int offset, int length) {
            entry.rawMessage = new byte[length];
            System.arraycopy(data, offset, entry.rawMessage, 0, length);
            return this;
        }

        public Builder metadata(String metadata) {
            entry.metadata = metadata;
            return this;
        }

        public FixLogEntry build() {
            return entry;
        }
    }

    // ==================== Getters and Setters ====================

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Instant getTimestampAsInstant() {
        return Instant.ofEpochMilli(timestamp);
    }

    public int getSeqNum() {
        return seqNum;
    }

    public void setSeqNum(int seqNum) {
        this.seqNum = seqNum;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(long transactionId) {
        this.transactionId = transactionId;
    }

    public byte[] getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(byte[] rawMessage) {
        this.rawMessage = rawMessage;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    /**
     * Get the raw message as a string with SOH replaced by '|'.
     */
    public String getRawMessageString() {
        if (rawMessage == null) return "";
        return new String(rawMessage).replace('\u0001', '|');
    }

    @Override
    public String toString() {
        return "FixLogEntry{" +
                "timestamp=" + timestamp +
                ", seqNum=" + seqNum +
                ", direction=" + direction +
                ", streamName='" + streamName + '\'' +
                ", msgType='" + msgType + '\'' +
                ", transactionId=" + transactionId +
                ", messageLength=" + (rawMessage != null ? rawMessage.length : 0) +
                '}';
    }
}
