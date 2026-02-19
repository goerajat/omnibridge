package com.omnibridge.persistence.chronicle;

import com.omnibridge.persistence.LogCallback;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;

import java.util.concurrent.locks.LockSupport;

/**
 * Single-stream {@link LogReader} backed by a Chronicle Queue tailer.
 *
 * <p>Uses spin-poll with {@link LockSupport#parkNanos(long)} between attempts
 * since Chronicle Queue tailers are non-blocking.</p>
 *
 * <p>Thread safety: NOT thread-safe. Each thread should use its own reader.</p>
 */
class ChronicleLogReader implements LogReader {

    private static final long PARK_NANOS = 1_000_000L; // 1ms

    private final String streamName;
    private final ChronicleLogStore.StreamState state;
    private final ExcerptTailer tailer;
    private final LogEntry flyweight = new LogEntry();
    private byte[] metadataBuf = new byte[256];
    private byte[] messageBuf = new byte[4096];
    private volatile boolean closed;

    ChronicleLogReader(String streamName, ChronicleLogStore.StreamState state, long startPosition) {
        this.streamName = streamName;
        this.state = state;
        this.tailer = state.queue.createTailer();

        if (startPosition == LogReader.END) {
            tailer.toEnd();
        } else if (startPosition == LogReader.START) {
            tailer.toStart();
        } else {
            tailer.moveToIndex(startPosition);
        }
    }

    @Override
    public LogEntry poll(long timeoutMs) {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;

        while (!closed) {
            LogEntry entry = tryReadNext();
            if (entry != null) {
                return entry;
            }

            if (timeoutMs == 0) {
                return null;
            }

            if (timeoutMs > 0 && System.currentTimeMillis() >= deadline) {
                return null;
            }

            // Spin-wait with park
            Thread.onSpinWait();
            LockSupport.parkNanos(PARK_NANOS);
        }

        return null;
    }

    @Override
    public int poll(int maxEntries, long timeoutMs, LogCallback callback) {
        int count = 0;

        // First entry may need to wait
        LogEntry first = poll(timeoutMs);
        if (first != null) {
            count++;
            if (!callback.onEntry(first)) {
                return count;
            }
        } else {
            return 0;
        }

        // Subsequent entries - no wait
        while (count < maxEntries) {
            LogEntry entry = tryReadNext();
            if (entry == null) {
                break;
            }
            count++;
            if (!callback.onEntry(entry)) {
                break;
            }
        }

        return count;
    }

    private LogEntry tryReadNext() {
        try (DocumentContext dc = tailer.readingDocument()) {
            if (!dc.isPresent()) {
                return null;
            }

            Bytes<?> bytes = dc.wire().bytes();
            long timestamp = bytes.readLong();
            byte dirByte = bytes.readByte();
            LogEntry.Direction dir = dirByte == 0 ? LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND;
            int seqNum = bytes.readInt();

            int metadataLen = bytes.readShort() & 0xFFFF;
            if (metadataLen > metadataBuf.length) {
                metadataBuf = new byte[metadataLen];
            }
            byte[] metadata = null;
            if (metadataLen > 0) {
                bytes.read(metadataBuf, 0, metadataLen);
                metadata = new byte[metadataLen];
                System.arraycopy(metadataBuf, 0, metadata, 0, metadataLen);
            }

            int rawLen = bytes.readInt();
            if (rawLen > messageBuf.length) {
                messageBuf = new byte[rawLen];
            }
            byte[] rawMessage = null;
            if (rawLen > 0) {
                bytes.read(messageBuf, 0, rawLen);
                rawMessage = new byte[rawLen];
                System.arraycopy(messageBuf, 0, rawMessage, 0, rawLen);
            }

            flyweight.reset(timestamp, dir, seqNum, streamName, metadata, rawMessage);
            return flyweight;
        }
    }

    @Override
    public long getPosition() {
        return tailer.index();
    }

    @Override
    public void setPosition(long position) {
        if (position == LogReader.END) {
            tailer.toEnd();
        } else if (position == LogReader.START) {
            tailer.toStart();
        } else {
            tailer.moveToIndex(position);
        }
    }

    @Override
    public String getStreamName() {
        return streamName;
    }

    @Override
    public boolean hasNext() {
        try (DocumentContext dc = tailer.readingDocument()) {
            if (!dc.isPresent()) {
                return false;
            }
            // Roll back: we peeked but don't want to consume
            dc.rollbackOnClose();
            return true;
        }
    }

    @Override
    public long available() {
        long count = 0;
        long savedIndex = tailer.index();

        while (true) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
                }
                count++;
            }
        }

        // Restore position
        if (savedIndex == 0) {
            tailer.toStart();
        } else {
            tailer.moveToIndex(savedIndex);
        }
        return count;
    }

    @Override
    public void close() {
        closed = true;
    }
}
