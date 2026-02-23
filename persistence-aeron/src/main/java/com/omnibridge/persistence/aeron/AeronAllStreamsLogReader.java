package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.LogCallback;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;

/**
 * All-streams LogReader that delegates to the local ChronicleLogStore all-streams reader.
 *
 * <p>All reads are served from the local write-through cache.</p>
 */
class AeronAllStreamsLogReader implements LogReader {

    private final LogReader delegate;

    AeronAllStreamsLogReader(LogReader delegate) {
        this.delegate = delegate;
    }

    @Override
    public LogEntry poll(long timeoutMs) {
        return delegate.poll(timeoutMs);
    }

    @Override
    public int poll(int maxEntries, long timeoutMs, LogCallback callback) {
        return delegate.poll(maxEntries, timeoutMs, callback);
    }

    @Override
    public long getPosition() {
        return delegate.getPosition();
    }

    @Override
    public void setPosition(long position) {
        delegate.setPosition(position);
    }

    @Override
    public String getStreamName() {
        return delegate.getStreamName();
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public long available() {
        return delegate.available();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
