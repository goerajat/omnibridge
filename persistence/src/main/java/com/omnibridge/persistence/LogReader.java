package com.omnibridge.persistence;

import java.io.Closeable;

/**
 * A reader for polling log entries from a stream.
 *
 * <p>LogReader maintains a read position and allows polling for new entries
 * as they are written to the store. This enables real-time consumption of
 * log data with blocking waits for new entries.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * try (LogReader reader = logStore.createReader("session-1")) {
 *     // Start from beginning and poll for entries
 *     while (running) {
 *         LogEntry entry = reader.poll(1000); // Wait up to 1 second
 *         if (entry != null) {
 *             processEntry(entry);
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>For tailing (starting from end):</p>
 * <pre>
 * try (LogReader reader = logStore.createReader("session-1", LogReader.END)) {
 *     // Only receive new entries from this point forward
 *     while (running) {
 *         LogEntry entry = reader.poll(1000);
 *         if (entry != null) {
 *             processEntry(entry);
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>Thread safety: LogReader instances are NOT thread-safe. Each thread
 * should use its own reader instance.</p>
 */
public interface LogReader extends Closeable {

    /**
     * Position constant indicating the start of the stream.
     */
    long START = 0;

    /**
     * Position constant indicating the end of the stream (tail mode).
     */
    long END = -1;

    /**
     * Poll for the next log entry, blocking until one is available or timeout expires.
     *
     * @param timeoutMs maximum time to wait in milliseconds (0 for no wait, -1 for infinite)
     * @return the next log entry, or null if timeout expired with no new data
     */
    LogEntry poll(long timeoutMs);

    /**
     * Poll for the next log entry without blocking.
     *
     * <p>This is equivalent to {@code poll(0)}.</p>
     *
     * @return the next log entry, or null if no new data is available
     */
    default LogEntry poll() {
        return poll(0);
    }

    /**
     * Try to read the next log entry without blocking.
     *
     * <p>This is an alias for {@link #poll()} to make non-blocking usage explicit.</p>
     *
     * @return the next log entry, or null if no new data is available
     */
    default LogEntry tryPoll() {
        return poll(0);
    }

    /**
     * Drain all available entries without blocking.
     *
     * <p>This method reads all currently available entries and invokes the
     * callback for each. It returns immediately when no more entries are
     * available, without waiting for new data.</p>
     *
     * @param callback callback to invoke for each entry
     * @return the number of entries read
     */
    default int drain(LogCallback callback) {
        int count = 0;
        LogEntry entry;
        while ((entry = poll(0)) != null) {
            count++;
            if (!callback.onEntry(entry)) {
                break;
            }
        }
        return count;
    }

    /**
     * Drain up to maxEntries without blocking.
     *
     * @param maxEntries maximum number of entries to read
     * @param callback callback to invoke for each entry
     * @return the number of entries read
     */
    default int drain(int maxEntries, LogCallback callback) {
        int count = 0;
        LogEntry entry;
        while (count < maxEntries && (entry = poll(0)) != null) {
            count++;
            if (!callback.onEntry(entry)) {
                break;
            }
        }
        return count;
    }

    /**
     * Poll for multiple entries, invoking the callback for each.
     *
     * <p>This method is more efficient than calling {@link #poll(long)} repeatedly
     * as it can batch-read multiple entries.</p>
     *
     * @param maxEntries maximum number of entries to read
     * @param timeoutMs maximum time to wait for the first entry (0 for no wait)
     * @param callback callback to invoke for each entry
     * @return the number of entries read
     */
    int poll(int maxEntries, long timeoutMs, LogCallback callback);

    /**
     * Get the current read position in the stream.
     *
     * @return the current position (byte offset in the log file)
     */
    long getPosition();

    /**
     * Set the read position.
     *
     * <p>Use {@link #START} to reset to the beginning, or {@link #END} to
     * skip to the current end (tail mode).</p>
     *
     * @param position the new position
     */
    void setPosition(long position);

    /**
     * Get the stream name this reader is attached to.
     *
     * @return the stream name
     */
    String getStreamName();

    /**
     * Check if there are more entries available without blocking.
     *
     * @return true if at least one entry is available
     */
    boolean hasNext();

    /**
     * Get the number of entries available for reading without blocking.
     *
     * @return the number of available entries (may be approximate)
     */
    long available();

    /**
     * Close this reader and release any resources.
     */
    @Override
    void close();
}
