package com.fixengine.persistence;

import com.fixengine.config.Component;

import java.io.Closeable;
import java.util.Collection;

/**
 * Interface for FIX message persistence store.
 *
 * <p>Implementations of this interface provide durable storage for FIX messages,
 * supporting both writing (logging) and reading (replay) operations.</p>
 *
 * <p>The store organizes messages by stream name (typically a session identifier)
 * and supports filtering by direction (inbound/outbound) and sequence number.</p>
 */
public interface FixLogStore extends Closeable, Component {

    /**
     * Write a log entry to the store.
     *
     * @param entry the entry to write
     * @return the position/index of the written entry
     */
    long write(FixLogEntry entry);

    /**
     * Replay all entries from a stream to a callback.
     *
     * @param streamName the stream to replay from (null for all streams)
     * @param callback the callback to receive entries
     * @return the number of entries replayed
     */
    default long replay(String streamName, FixLogCallback callback) {
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
    long replay(String streamName, FixLogEntry.Direction direction,
                int fromSeqNum, int toSeqNum, FixLogCallback callback);

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
    long replayByTime(String streamName, FixLogEntry.Direction direction,
                      long fromTimestamp, long toTimestamp, FixLogCallback callback);

    /**
     * Get the most recent entry for a stream and direction.
     *
     * @param streamName the stream name
     * @param direction the direction
     * @return the most recent entry, or null if none found
     */
    FixLogEntry getLatest(String streamName, FixLogEntry.Direction direction);

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
}
