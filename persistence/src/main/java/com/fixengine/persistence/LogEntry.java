package com.fixengine.persistence;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Represents a single log entry in the persistence store.
 *
 * <p>This class uses the flyweight pattern for high-performance scenarios where
 * entries are read sequentially. A single instance can be reused by calling
 * {@link #reset} with new data.</p>
 *
 * <p>For zero-copy reads, the ByteBuffer views returned by {@link #getMetadata()}
 * and {@link #getRawMessage()} point directly into the underlying storage buffer.
 * These views are only valid until the next entry is read.</p>
 */
public class LogEntry {

    /**
     * Message direction.
     */
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    private long timestamp;          // Epoch milliseconds
    private Direction direction;     // INBOUND or OUTBOUND
    private int sequenceNumber;      // Protocol-level sequence number
    private String streamName;       // Session/stream identifier
    private byte[] metadata;         // Protocol-specific metadata
    private byte[] rawMessage;       // Raw message bytes

    // Optional ByteBuffer views for zero-copy access
    private ByteBuffer metadataView;
    private ByteBuffer rawMessageView;

    /**
     * Create an empty log entry.
     */
    public LogEntry() {
    }

    /**
     * Create a log entry with all fields.
     *
     * @param timestamp the timestamp in epoch milliseconds
     * @param direction the message direction
     * @param sequenceNumber the sequence number
     * @param streamName the stream/session name
     * @param metadata protocol-specific metadata (may be null)
     * @param rawMessage the raw message bytes (may be null)
     */
    public LogEntry(long timestamp, Direction direction, int sequenceNumber,
                    String streamName, byte[] metadata, byte[] rawMessage) {
        this.timestamp = timestamp;
        this.direction = direction;
        this.sequenceNumber = sequenceNumber;
        this.streamName = streamName;
        this.metadata = metadata;
        this.rawMessage = rawMessage;
    }

    /**
     * Reset this entry with new values (flyweight pattern).
     *
     * <p>This method allows reusing a single LogEntry instance for sequential reads,
     * avoiding object allocation overhead.</p>
     *
     * @param timestamp the timestamp in epoch milliseconds
     * @param direction the message direction
     * @param sequenceNumber the sequence number
     * @param streamName the stream/session name
     * @param metadata protocol-specific metadata (may be null)
     * @param rawMessage the raw message bytes (may be null)
     */
    public void reset(long timestamp, Direction direction, int sequenceNumber,
                      String streamName, byte[] metadata, byte[] rawMessage) {
        this.timestamp = timestamp;
        this.direction = direction;
        this.sequenceNumber = sequenceNumber;
        this.streamName = streamName;
        this.metadata = metadata;
        this.rawMessage = rawMessage;
        this.metadataView = null;
        this.rawMessageView = null;
    }

    /**
     * Reset this entry with ByteBuffer views (zero-copy flyweight pattern).
     *
     * <p>This method allows reusing a single LogEntry instance with zero-copy
     * ByteBuffer views into the underlying storage buffer.</p>
     *
     * @param timestamp the timestamp in epoch milliseconds
     * @param direction the message direction
     * @param sequenceNumber the sequence number
     * @param streamName the stream/session name
     * @param metadataView ByteBuffer view of metadata (may be null)
     * @param rawMessageView ByteBuffer view of raw message (may be null)
     */
    public void resetWithViews(long timestamp, Direction direction, int sequenceNumber,
                               String streamName, ByteBuffer metadataView, ByteBuffer rawMessageView) {
        this.timestamp = timestamp;
        this.direction = direction;
        this.sequenceNumber = sequenceNumber;
        this.streamName = streamName;
        this.metadata = null;
        this.rawMessage = null;
        this.metadataView = metadataView;
        this.rawMessageView = rawMessageView;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final LogEntry entry = new LogEntry();

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

        public Builder sequenceNumber(int sequenceNumber) {
            entry.sequenceNumber = sequenceNumber;
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

        public Builder metadata(byte[] metadata) {
            entry.metadata = metadata;
            return this;
        }

        public Builder metadata(String metadata) {
            entry.metadata = metadata != null ? metadata.getBytes() : null;
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

        public LogEntry build() {
            return entry;
        }
    }

    // ==================== Static Factory ====================

    /**
     * Create a log entry with the given values.
     *
     * @param timestamp the timestamp in epoch milliseconds
     * @param direction the message direction
     * @param sequenceNumber the sequence number
     * @param streamName the stream/session name
     * @param metadata protocol-specific metadata (may be null)
     * @param rawMessage the raw message bytes (may be null)
     * @return a new LogEntry
     */
    public static LogEntry create(long timestamp, Direction direction, int sequenceNumber,
                                   String streamName, byte[] metadata, byte[] rawMessage) {
        return new LogEntry(timestamp, direction, sequenceNumber, streamName, metadata, rawMessage);
    }

    // ==================== Getters ====================

    public long getTimestamp() {
        return timestamp;
    }

    public Instant getTimestampAsInstant() {
        return Instant.ofEpochMilli(timestamp);
    }

    public Direction getDirection() {
        return direction;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * @deprecated Use {@link #getSequenceNumber()} instead
     */
    @Deprecated
    public int getSeqNum() {
        return sequenceNumber;
    }

    public String getStreamName() {
        return streamName;
    }

    /**
     * Get the metadata as a byte array.
     *
     * <p>If this entry was created with a ByteBuffer view, this method will
     * copy the data into a new byte array.</p>
     *
     * @return the metadata bytes, or null if not set
     */
    public byte[] getMetadata() {
        if (metadata != null) {
            return metadata;
        }
        if (metadataView != null && metadataView.remaining() > 0) {
            byte[] result = new byte[metadataView.remaining()];
            metadataView.duplicate().get(result);
            return result;
        }
        return null;
    }

    /**
     * Get the metadata as a ByteBuffer view.
     *
     * <p>The returned buffer is a view and should not be modified.</p>
     *
     * @return a ByteBuffer view of the metadata, or null if not set
     */
    public ByteBuffer getMetadataView() {
        if (metadataView != null) {
            return metadataView.duplicate();
        }
        if (metadata != null) {
            return ByteBuffer.wrap(metadata).asReadOnlyBuffer();
        }
        return null;
    }

    /**
     * Get the metadata as a string.
     *
     * @return the metadata as a string, or null if not set
     */
    public String getMetadataString() {
        byte[] data = getMetadata();
        return data != null ? new String(data) : null;
    }

    /**
     * Get the raw message as a byte array.
     *
     * <p>If this entry was created with a ByteBuffer view, this method will
     * copy the data into a new byte array.</p>
     *
     * @return the raw message bytes, or null if not set
     */
    public byte[] getRawMessage() {
        if (rawMessage != null) {
            return rawMessage;
        }
        if (rawMessageView != null && rawMessageView.remaining() > 0) {
            byte[] result = new byte[rawMessageView.remaining()];
            rawMessageView.duplicate().get(result);
            return result;
        }
        return null;
    }

    /**
     * Get the raw message as a ByteBuffer view.
     *
     * <p>The returned buffer is a view and should not be modified.</p>
     *
     * @return a ByteBuffer view of the raw message, or null if not set
     */
    public ByteBuffer getRawMessageView() {
        if (rawMessageView != null) {
            return rawMessageView.duplicate();
        }
        if (rawMessage != null) {
            return ByteBuffer.wrap(rawMessage).asReadOnlyBuffer();
        }
        return null;
    }

    /**
     * Get the raw message as a string with SOH replaced by '|'.
     * This is a convenience method for FIX protocol messages.
     *
     * @return the message string, or empty string if not set
     */
    public String getRawMessageString() {
        byte[] data = getRawMessage();
        if (data == null) return "";
        return new String(data).replace('\u0001', '|');
    }

    // ==================== Setters (for compatibility) ====================

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * @deprecated Use {@link #setSequenceNumber(int)} instead
     */
    @Deprecated
    public void setSeqNum(int seqNum) {
        this.sequenceNumber = seqNum;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public void setMetadata(byte[] metadata) {
        this.metadata = metadata;
        this.metadataView = null;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata != null ? metadata.getBytes() : null;
        this.metadataView = null;
    }

    public void setRawMessage(byte[] rawMessage) {
        this.rawMessage = rawMessage;
        this.rawMessageView = null;
    }

    @Override
    public String toString() {
        return "LogEntry{" +
                "timestamp=" + timestamp +
                ", direction=" + direction +
                ", sequenceNumber=" + sequenceNumber +
                ", streamName='" + streamName + '\'' +
                ", messageLength=" + (rawMessage != null ? rawMessage.length :
                        (rawMessageView != null ? rawMessageView.remaining() : 0)) +
                '}';
    }
}
