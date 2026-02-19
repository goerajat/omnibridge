package com.omnibridge.persistence.chronicle;

import com.omnibridge.persistence.LogCallback;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Multi-stream {@link LogReader} that merges entries from all Chronicle Queue streams
 * by timestamp order.
 *
 * <p>Maintains per-stream tailers and a peeked-entry buffer per stream. On each
 * read, peeks the next entry from each stream and selects the one with the minimum
 * timestamp.</p>
 *
 * <p>Thread safety: NOT thread-safe. Each thread should use its own reader.</p>
 */
class ChronicleAllStreamsLogReader implements LogReader {

    private static final long PARK_NANOS = 1_000_000L; // 1ms

    private final Map<String, StreamReader> streamReaders = new HashMap<>();
    private final LogEntry flyweight = new LogEntry();
    private volatile boolean closed;

    ChronicleAllStreamsLogReader(Map<String, ChronicleLogStore.StreamState> streams, long startPosition) {
        for (Map.Entry<String, ChronicleLogStore.StreamState> entry : streams.entrySet()) {
            String name = entry.getKey();
            ChronicleLogStore.StreamState state = entry.getValue();
            StreamReader sr = new StreamReader(name, state, startPosition);
            streamReaders.put(name, sr);
        }
    }

    @Override
    public LogEntry poll(long timeoutMs) {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;

        while (!closed) {
            LogEntry next = findNextEntry();
            if (next != null) {
                return next;
            }

            if (timeoutMs == 0) {
                return null;
            }

            if (timeoutMs > 0 && System.currentTimeMillis() >= deadline) {
                return null;
            }

            Thread.onSpinWait();
            LockSupport.parkNanos(PARK_NANOS);
        }

        return null;
    }

    @Override
    public int poll(int maxEntries, long timeoutMs, LogCallback callback) {
        int count = 0;

        LogEntry first = poll(timeoutMs);
        if (first != null) {
            count++;
            if (!callback.onEntry(first)) {
                return count;
            }
        } else {
            return 0;
        }

        while (count < maxEntries) {
            LogEntry entry = findNextEntry();
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

    private LogEntry findNextEntry() {
        // Ensure all streams have peeked their next entry
        for (StreamReader sr : streamReaders.values()) {
            sr.peekIfNeeded();
        }

        // Find the stream with the minimum timestamp
        StreamReader earliest = null;
        for (StreamReader sr : streamReaders.values()) {
            if (!sr.hasPeeked) continue;
            if (earliest == null || sr.peekedTimestamp < earliest.peekedTimestamp) {
                earliest = sr;
            }
        }

        if (earliest == null) {
            return null;
        }

        // Consume the peeked entry
        flyweight.reset(earliest.peekedTimestamp, earliest.peekedDirection,
                earliest.peekedSeqNum, earliest.streamName,
                earliest.peekedMetadata, earliest.peekedRawMessage);
        earliest.consumePeeked();
        return flyweight;
    }

    @Override
    public long getPosition() {
        // Return minimum index across all streams
        long min = Long.MAX_VALUE;
        for (StreamReader sr : streamReaders.values()) {
            long idx = sr.tailer.index();
            if (idx > 0 && idx < min) {
                min = idx;
            }
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    @Override
    public void setPosition(long position) {
        for (StreamReader sr : streamReaders.values()) {
            if (position == LogReader.END) {
                sr.tailer.toEnd();
            } else if (position == LogReader.START) {
                sr.tailer.toStart();
            } else {
                sr.tailer.toStart(); // Default to start for all-streams
            }
            sr.hasPeeked = false;
        }
    }

    @Override
    public String getStreamName() {
        return null; // All streams
    }

    @Override
    public boolean hasNext() {
        for (StreamReader sr : streamReaders.values()) {
            sr.peekIfNeeded();
            if (sr.hasPeeked) {
                return true;
            }
        }
        return false;
    }

    @Override
    public long available() {
        long count = 0;
        for (StreamReader sr : streamReaders.values()) {
            // Count remaining entries in this stream's tailer
            long savedIndex = sr.tailer.index();
            while (true) {
                try (DocumentContext dc = sr.tailer.readingDocument()) {
                    if (!dc.isPresent()) break;
                    count++;
                }
            }
            // Restore position
            if (savedIndex == 0) {
                sr.tailer.toStart();
            } else {
                sr.tailer.moveToIndex(savedIndex);
            }
        }
        // Add any already-peeked entries
        for (StreamReader sr : streamReaders.values()) {
            if (sr.hasPeeked) count++;
        }
        return count;
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * Per-stream reader that maintains a peek buffer for merge sorting.
     */
    private static class StreamReader {
        final String streamName;
        final ExcerptTailer tailer;

        // Pre-allocated peek state (one per stream, no allocation during merge)
        boolean hasPeeked;
        long peekedTimestamp;
        LogEntry.Direction peekedDirection;
        int peekedSeqNum;
        byte[] peekedMetadata;
        byte[] peekedRawMessage;

        private byte[] metadataBuf = new byte[256];
        private byte[] messageBuf = new byte[4096];

        StreamReader(String streamName, ChronicleLogStore.StreamState state, long startPosition) {
            this.streamName = streamName;
            this.tailer = state.queue.createTailer();

            if (startPosition == LogReader.END) {
                tailer.toEnd();
            } else {
                tailer.toStart();
            }
        }

        void peekIfNeeded() {
            if (hasPeeked) return;

            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    return;
                }

                Bytes<?> bytes = dc.wire().bytes();
                peekedTimestamp = bytes.readLong();
                byte dirByte = bytes.readByte();
                peekedDirection = dirByte == 0 ? LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND;
                peekedSeqNum = bytes.readInt();

                int metadataLen = bytes.readShort() & 0xFFFF;
                if (metadataLen > metadataBuf.length) {
                    metadataBuf = new byte[metadataLen];
                }
                if (metadataLen > 0) {
                    bytes.read(metadataBuf, 0, metadataLen);
                    peekedMetadata = new byte[metadataLen];
                    System.arraycopy(metadataBuf, 0, peekedMetadata, 0, metadataLen);
                } else {
                    peekedMetadata = null;
                }

                int rawLen = bytes.readInt();
                if (rawLen > messageBuf.length) {
                    messageBuf = new byte[rawLen];
                }
                if (rawLen > 0) {
                    bytes.read(messageBuf, 0, rawLen);
                    peekedRawMessage = new byte[rawLen];
                    System.arraycopy(messageBuf, 0, peekedRawMessage, 0, rawLen);
                } else {
                    peekedRawMessage = null;
                }

                hasPeeked = true;
            }
        }

        void consumePeeked() {
            hasPeeked = false;
        }
    }
}
