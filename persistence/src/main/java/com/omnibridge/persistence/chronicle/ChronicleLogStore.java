package com.omnibridge.persistence.chronicle;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.persistence.Decoder;
import com.omnibridge.persistence.LogCallback;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chronicle Queue-backed implementation of {@link LogStore}.
 *
 * <p>Uses the raw {@link Bytes} API (no named Wire fields) for zero-allocation
 * on the write and read hot paths. Each stream is stored in a separate
 * Chronicle Queue directory.</p>
 *
 * <p>Binary entry layout (per entry, written via Bytes API):</p>
 * <pre>
 *   timestamp       (8 bytes, writeLong)
 *   direction       (1 byte,  writeByte: 0=INBOUND, 1=OUTBOUND)
 *   sequenceNumber  (4 bytes, writeInt)
 *   metadataLen     (2 bytes, writeShort)
 *   metadata        (N bytes, write(byte[], off, len))
 *   rawMessageLen   (4 bytes, writeInt)
 *   rawMessage      (N bytes, write(byte[], off, len))
 * </pre>
 */
public class ChronicleLogStore implements LogStore, Component {

    private static final Logger log = LoggerFactory.getLogger(ChronicleLogStore.class);

    private static final byte[] EMPTY = new byte[0];

    private final File baseDir;
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private final Map<String, Decoder> streamDecoders = new ConcurrentHashMap<>();
    private final AtomicLong totalEntries = new AtomicLong(0);
    private final LogEntry flyweightEntry = new LogEntry();
    private ComponentProvider componentProvider;
    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;

    public ChronicleLogStore(String basePath) {
        this(new File(basePath));
    }

    public ChronicleLogStore(File baseDir) {
        this.baseDir = baseDir;
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Failed to create directory: " + baseDir);
        }
        loadExistingStreams();
        log.info("ChronicleLogStore initialized at {}", baseDir.getAbsolutePath());
    }

    public ChronicleLogStore(PersistenceConfig config) {
        this(config, null);
    }

    public ChronicleLogStore(PersistenceConfig config, ComponentProvider provider) {
        this(new File(config.getBasePath()));
        this.componentProvider = provider;
    }

    public ComponentProvider getComponentProvider() {
        return componentProvider;
    }

    // ==================== Decoder Support ====================

    @Override
    public Decoder getDecoder(String streamName) {
        return streamDecoders.get(streamName);
    }

    @Override
    public void setDecoder(String streamName, Decoder decoder) {
        if (decoder != null) {
            streamDecoders.put(streamName, decoder);
        } else {
            streamDecoders.remove(streamName);
        }
    }

    // ==================== Write ====================

    @Override
    public long write(LogEntry entry) {
        if (entry.getStreamName() == null || entry.getStreamName().isEmpty()) {
            throw new IllegalArgumentException("Stream name is required");
        }

        StreamState state = getOrCreateStream(entry.getStreamName());

        byte[] metadata = entry.getMetadata() != null ? entry.getMetadata() : EMPTY;
        byte[] rawMessage = entry.getRawMessage() != null ? entry.getRawMessage() : EMPTY;

        long index;
        try (DocumentContext dc = state.appender.writingDocument()) {
            Bytes<?> bytes = dc.wire().bytes();
            bytes.writeLong(entry.getTimestamp());
            bytes.writeByte((byte) (entry.getDirection() == LogEntry.Direction.INBOUND ? 0 : 1));
            bytes.writeInt(entry.getSequenceNumber());
            bytes.writeShort((short) metadata.length);
            bytes.write(metadata);
            bytes.writeInt(rawMessage.length);
            bytes.write(rawMessage);
            index = state.appender.lastIndexAppended();
        }

        state.entryCount.incrementAndGet();
        totalEntries.incrementAndGet();
        return index;
    }

    // ==================== Replay ====================

    @Override
    public long replay(String streamName, LogEntry.Direction direction,
                       int fromSeqNum, int toSeqNum, LogCallback callback) {
        long count = 0;

        Collection<StreamState> statesToReplay;
        if (streamName != null) {
            StreamState state = streams.get(streamName);
            statesToReplay = state != null ? List.of(state) : Collections.emptyList();
        } else {
            statesToReplay = streams.values();
        }

        for (StreamState state : statesToReplay) {
            count += replayStream(state, direction, fromSeqNum, toSeqNum, 0, 0, callback);
        }

        return count;
    }

    @Override
    public long replayByTime(String streamName, LogEntry.Direction direction,
                             long fromTimestamp, long toTimestamp, LogCallback callback) {
        long count = 0;

        Collection<StreamState> statesToReplay;
        if (streamName != null) {
            StreamState state = streams.get(streamName);
            statesToReplay = state != null ? List.of(state) : Collections.emptyList();
        } else {
            statesToReplay = streams.values();
        }

        for (StreamState state : statesToReplay) {
            count += replayStream(state, direction, 0, 0, fromTimestamp, toTimestamp, callback);
        }

        return count;
    }

    private long replayStream(StreamState state, LogEntry.Direction direction,
                              int fromSeqNum, int toSeqNum,
                              long fromTimestamp, long toTimestamp,
                              LogCallback callback) {
        long count = 0;
        byte[] metadataBuf = new byte[256];
        byte[] messageBuf = new byte[4096];

        ExcerptTailer tailer = state.queue.createTailer();
        tailer.toStart();

        while (true) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
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

                // Apply filters
                if (direction != null && dir != direction) continue;
                if (fromSeqNum > 0 && seqNum < fromSeqNum) continue;
                if (toSeqNum > 0 && seqNum > toSeqNum) continue;
                if (fromTimestamp > 0 && timestamp < fromTimestamp) continue;
                if (toTimestamp > 0 && timestamp > toTimestamp) continue;

                flyweightEntry.reset(timestamp, dir, seqNum, state.name, metadata, rawMessage);
                count++;
                if (!callback.onEntry(flyweightEntry)) {
                    break;
                }
            }
        }

        return count;
    }

    // ==================== Query ====================

    @Override
    public LogEntry getLatest(String streamName, LogEntry.Direction direction) {
        StreamState state = streams.get(streamName);
        if (state == null) return null;

        LogEntry latest = null;
        byte[] metadataBuf = new byte[256];
        byte[] messageBuf = new byte[4096];

        ExcerptTailer tailer = state.queue.createTailer();
        tailer.toStart();

        while (true) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
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

                if (direction == null || dir == direction) {
                    latest = new LogEntry(timestamp, dir, seqNum, streamName, metadata, rawMessage);
                }
            }
        }

        return latest;
    }

    @Override
    public long getEntryCount(String streamName) {
        if (streamName == null) {
            return totalEntries.get();
        }
        StreamState state = streams.get(streamName);
        return state != null ? state.entryCount.get() : 0;
    }

    @Override
    public Collection<String> getStreamNames() {
        return Collections.unmodifiableSet(streams.keySet());
    }

    @Override
    public void sync() {
        // Chronicle Queue handles its own fsync
    }

    @Override
    public String getStorePath() {
        return baseDir.getAbsolutePath();
    }

    // ==================== Polling API ====================

    @Override
    public LogReader createReader(String streamName, long startPosition) {
        if (streamName != null) {
            StreamState state = getOrCreateStream(streamName);
            return new ChronicleLogReader(streamName, state, startPosition);
        } else {
            return new ChronicleAllStreamsLogReader(streams, startPosition);
        }
    }

    // ==================== Lifecycle ====================

    @Override
    public void close() throws IOException {
        for (StreamState state : streams.values()) {
            state.close();
        }
        streams.clear();
        componentState = ComponentState.STOPPED;
        log.info("ChronicleLogStore closed");
    }

    @Override
    public void initialize() throws Exception {
        if (componentState != ComponentState.UNINITIALIZED) {
            throw new IllegalStateException("Cannot initialize from state: " + componentState);
        }
        componentState = ComponentState.INITIALIZED;
        log.debug("ChronicleLogStore component initialized");
    }

    @Override
    public void startActive() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + componentState);
        }
        componentState = ComponentState.ACTIVE;
        log.info("ChronicleLogStore started in ACTIVE mode");
    }

    @Override
    public void startStandby() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + componentState);
        }
        componentState = ComponentState.STANDBY;
        log.info("ChronicleLogStore started in STANDBY mode");
    }

    @Override
    public void becomeActive() throws Exception {
        if (componentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + componentState);
        }
        componentState = ComponentState.ACTIVE;
        log.info("ChronicleLogStore transitioned to ACTIVE mode");
    }

    @Override
    public void becomeStandby() throws Exception {
        if (componentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + componentState);
        }
        componentState = ComponentState.STANDBY;
        log.info("ChronicleLogStore transitioned to STANDBY mode");
    }

    @Override
    public void stop() {
        try {
            close();
        } catch (IOException e) {
            log.error("Error closing chronicle store", e);
        }
    }

    @Override
    public String getName() {
        return "persistence-store";
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }

    // ==================== Internal ====================

    private void loadExistingStreams() {
        File[] dirs = baseDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                // Check if it looks like a Chronicle Queue directory (contains .cq4 files)
                File[] cq4Files = dir.listFiles((d, name) -> name.endsWith(".cq4"));
                if (cq4Files != null && cq4Files.length > 0) {
                    String streamName = dir.getName();
                    try {
                        StreamState state = new StreamState(streamName, dir);
                        streams.put(streamName, state);
                        totalEntries.addAndGet(state.entryCount.get());
                        log.debug("Loaded chronicle stream: {} ({} entries)", streamName, state.entryCount.get());
                    } catch (Exception e) {
                        log.error("Failed to load chronicle stream {}: {}", streamName, e.getMessage());
                    }
                }
            }
        }
    }

    StreamState getOrCreateStream(String streamName) {
        return streams.computeIfAbsent(streamName, name -> {
            String sanitizedName = sanitizeFileName(name);
            File dir = new File(baseDir, sanitizedName);
            return new StreamState(name, dir);
        });
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.replace("->", "_to_");
        sanitized = sanitized.replaceAll("[<>:\"/\\\\|?*]", "_");
        return sanitized;
    }

    // ==================== StreamState ====================

    /**
     * Per-stream state: Chronicle Queue + appender + entry count.
     */
    static class StreamState {
        final String name;
        final SingleChronicleQueue queue;
        final ExcerptAppender appender;
        final AtomicLong entryCount;

        StreamState(String name, File dir) {
            this.name = name;
            this.queue = SingleChronicleQueueBuilder.binary(dir).build();
            this.appender = queue.createAppender();
            this.entryCount = new AtomicLong(countEntries());
        }

        private long countEntries() {
            long count = 0;
            ExcerptTailer tailer = queue.createTailer();
            tailer.toStart();
            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) {
                        break;
                    }
                    count++;
                }
            }
            return count;
        }

        void close() {
            appender.close();
            queue.close();
        }
    }
}
