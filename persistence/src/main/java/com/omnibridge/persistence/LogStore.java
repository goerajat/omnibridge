package com.omnibridge.persistence;

import com.omnibridge.config.Component;

import java.io.Closeable;
import java.util.Collection;

/**
 * Interface for protocol-agnostic message persistence store.
 *
 * <p>Implementations of this interface provide durable storage for messages,
 * supporting both writing (logging) and reading (replay) operations.</p>
 *
 * <p>The store organizes messages by stream name (typically a session identifier)
 * and supports filtering by direction (inbound/outbound) and sequence number.</p>
 */
public interface LogStore extends Closeable, Component {

    /**
     * Write a log entry to the store.
     *
     * @param entry the entry to write
     * @return the position/index of the written entry
     */
    long write(LogEntry entry);

    /**
     * Replay all entries from a stream to a callback.
     *
     * @param streamName the stream to replay from (null for all streams)
     * @param callback the callback to receive entries
     * @return the number of entries replayed
     */
    default long replay(String streamName, LogCallback callback) {
        return replay(streamName, null, 0, 0, callback);
    }

    /**
     * Replay entries from the store to a callback.
     *
     * @param streamName the stream to replay from (null for all streams)
     * @param direction the direction to filter by (null for both)
     * @param fromSeqNum the starting sequence number (inclusive)
     * @param toSeqNum the ending sequence number (inclusive, 0 for all)
     * @param callback the callback to receive entries
     * @return the number of entries replayed
     */
    long replay(String streamName, LogEntry.Direction direction,
                int fromSeqNum, int toSeqNum, LogCallback callback);

    /**
     * Replay entries within a time range.
     *
     * @param streamName the stream to replay from (null for all streams)
     * @param direction the direction to filter by (null for both)
     * @param fromTimestamp start timestamp (epoch millis, inclusive)
     * @param toTimestamp end timestamp (epoch millis, inclusive, 0 for now)
     * @param callback the callback to receive entries
     * @return the number of entries replayed
     */
    long replayByTime(String streamName, LogEntry.Direction direction,
                      long fromTimestamp, long toTimestamp, LogCallback callback);

    /**
     * Get the most recent entry for a stream and direction.
     *
     * @param streamName the stream name
     * @param direction the direction
     * @return the most recent entry, or null if none found
     */
    LogEntry getLatest(String streamName, LogEntry.Direction direction);

    /**
     * Get the entry count for a stream.
     *
     * @param streamName the stream name (null for all streams)
     * @return the number of entries
     */
    long getEntryCount(String streamName);

    /**
     * Get all known stream names.
     *
     * @return collection of stream names
     */
    Collection<String> getStreamNames();

    /**
     * Sync any buffered data to persistent storage.
     */
    void sync();

    /**
     * Get the store's base directory or path.
     *
     * @return the store path
     */
    String getStorePath();

    /**
     * Get the decoder for a specific stream.
     *
     * @param streamName the stream name
     * @return the decoder for the stream, or null if not set
     */
    default Decoder getDecoder(String streamName) {
        return null;
    }

    /**
     * Set the decoder for a specific stream.
     *
     * <p>Each stream can have its own decoder to support different protocols
     * being logged to the same store.</p>
     *
     * @param streamName the stream name
     * @param decoder the decoder to use for this stream
     */
    default void setDecoder(String streamName, Decoder decoder) {
        // Default implementation does nothing
    }

    // ==================== Polling API ====================

    /**
     * Create a reader for polling entries from a stream.
     *
     * <p>The reader starts from the beginning of the stream.</p>
     *
     * @param streamName the stream to read from
     * @return a new LogReader instance
     * @throws IllegalArgumentException if the stream does not exist
     */
    default LogReader createReader(String streamName) {
        return createReader(streamName, LogReader.START);
    }

    /**
     * Create a reader for polling entries from a stream at a specific position.
     *
     * <p>Use {@link LogReader#START} to start from the beginning, or
     * {@link LogReader#END} to start from the current end (tail mode).</p>
     *
     * @param streamName the stream to read from
     * @param startPosition the starting position (byte offset, or START/END constants)
     * @return a new LogReader instance
     * @throws IllegalArgumentException if the stream does not exist
     */
    LogReader createReader(String streamName, long startPosition);

    /**
     * Create a reader for polling entries from all streams.
     *
     * <p>Entries are returned in write order across all streams.</p>
     *
     * @return a new LogReader instance for all streams
     */
    default LogReader createReader() {
        return createReader(null, LogReader.START);
    }
}
