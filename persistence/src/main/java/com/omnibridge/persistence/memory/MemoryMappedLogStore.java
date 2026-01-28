package com.omnibridge.persistence.memory;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.persistence.Decoder;
import com.omnibridge.persistence.LogCallback;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory-mapped file implementation of LogStore.
 *
 * <p>This implementation uses memory-mapped files for high-performance persistence
 * with minimal GC overhead. Each stream is stored in a separate file.</p>
 *
 * <p>File format v2 (protocol-agnostic):</p>
 * <pre>
 * Header (once per file):
 * +------------------+------------------+------------------+
 * | Magic (8 bytes)  | Version (4 bytes)| DecoderLen (4)   |
 * +------------------+------------------+------------------+
 * | DecoderClassName (variable, UTF-8, padded to 128)     |
 * +--------------------------------------------------------+
 *
 * Entry (repeated):
 * +----------+----------+----------+----------+----------+
 * | Length   | Timestamp| SeqNum   | Direction| Metadata |
 * | (4 bytes)| (8 bytes)| (4 bytes)| (1 byte) | (len+str)|
 * +----------+----------+----------+----------+----------+
 * | Raw Message                                          |
 * | (4 bytes length + data)                              |
 * +----------+----------+----------+----------+----------+
 * </pre>
 *
 * <p>For backward compatibility, this class can also read v1 files (legacy format).</p>
 */
public class MemoryMappedLogStore implements LogStore, Component {

    private static final Logger log = LoggerFactory.getLogger(MemoryMappedLogStore.class);

    // Magic number for v2 files: "LOGSTORE" in ASCII
    private static final long MAGIC_V2 = 0x4C4F4753544F5245L;
    private static final int VERSION_2 = 2;

    // Header sizes
    private static final int HEADER_SIZE_V2 = 144; // 8 (magic) + 4 (version) + 4 (decoder len) + 128 (decoder name)
    private static final int HEADER_SIZE_V1 = 64;  // Legacy header size

    // Entry header (excluding 4-byte length prefix): timestamp (8) + seqNum (4) + direction (1)
    private static final int ENTRY_HEADER_SIZE = 8 + 4 + 1;

    private static final long DEFAULT_FILE_SIZE = 256 * 1024 * 1024; // 256 MB

    private final File baseDir;
    private final long maxFileSize;
    private final boolean syncOnWrite;
    private final Map<String, StreamStore> streams = new ConcurrentHashMap<>();
    private final AtomicLong totalEntries = new AtomicLong(0);
    private ComponentProvider componentProvider;
    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;

    private final Map<String, Decoder> streamDecoders = new ConcurrentHashMap<>();
    private final LogEntry flyweightEntry = new LogEntry(); // Reusable for reads

    /**
     * Create a new memory-mapped log store.
     *
     * @param basePath the base directory path for log files
     */
    public MemoryMappedLogStore(String basePath) {
        this(new File(basePath), DEFAULT_FILE_SIZE, false);
    }

    /**
     * Create a new memory-mapped log store with specified max file size.
     *
     * @param basePath the base directory path for log files
     * @param maxFileSize the maximum size for each stream file
     */
    public MemoryMappedLogStore(String basePath, long maxFileSize) {
        this(new File(basePath), maxFileSize, false);
    }

    /**
     * Create a new memory-mapped log store.
     *
     * @param baseDir the base directory for log files
     */
    public MemoryMappedLogStore(File baseDir) {
        this(baseDir, DEFAULT_FILE_SIZE, false);
    }

    /**
     * Create a new memory-mapped log store with specified max file size.
     *
     * @param baseDir the base directory for log files
     * @param maxFileSize the maximum size for each stream file
     */
    public MemoryMappedLogStore(File baseDir, long maxFileSize) {
        this(baseDir, maxFileSize, false);
    }

    /**
     * Create a new memory-mapped log store from PersistenceConfig.
     *
     * @param config the persistence configuration
     */
    public MemoryMappedLogStore(PersistenceConfig config) {
        this(config, null);
    }

    /**
     * Create a new memory-mapped log store from PersistenceConfig and ComponentProvider.
     *
     * @param config the persistence configuration
     * @param provider the component provider (may be null)
     */
    public MemoryMappedLogStore(PersistenceConfig config, ComponentProvider provider) {
        this(new File(config.getBasePath()), config.getMaxFileSize(), config.isSyncOnWrite());
        this.componentProvider = provider;
    }

    /**
     * Create a new memory-mapped log store with full configuration.
     *
     * @param baseDir the base directory for log files
     * @param maxFileSize the maximum size for each stream file
     * @param syncOnWrite whether to sync to disk on every write
     */
    public MemoryMappedLogStore(File baseDir, long maxFileSize, boolean syncOnWrite) {
        this.baseDir = baseDir;
        this.maxFileSize = maxFileSize;
        this.syncOnWrite = syncOnWrite;

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Failed to create directory: " + baseDir);
        }

        // Load existing streams
        loadExistingStreams();

        log.info("MemoryMappedLogStore initialized at {} (maxFileSize={}MB, syncOnWrite={})",
                baseDir.getAbsolutePath(), maxFileSize / (1024 * 1024), syncOnWrite);
    }

    /**
     * Get the component provider.
     */
    public ComponentProvider getComponentProvider() {
        return componentProvider;
    }

    @Override
    public Decoder getDecoder(String streamName) {
        return streamDecoders.get(streamName);
    }

    @Override
    public void setDecoder(String streamName, Decoder decoder) {
        if (decoder != null) {
            streamDecoders.put(streamName, decoder);
            // Update the stream's file header if it exists
            StreamStore store = streams.get(streamName);
            if (store != null) {
                store.updateDecoder(decoder);
            }
        } else {
            streamDecoders.remove(streamName);
        }
    }

    private void loadExistingStreams() {
        // Load both v1 (.fixlog) and v2 (.log) files
        File[] files = baseDir.listFiles((dir, name) -> name.endsWith(".fixlog") || name.endsWith(".log"));
        if (files != null) {
            for (File file : files) {
                String streamName = file.getName()
                        .replace(".fixlog", "")
                        .replace(".log", "");
                try {
                    StreamStore store = new StreamStore(streamName, file, maxFileSize, syncOnWrite);
                    streams.put(streamName, store);
                    totalEntries.addAndGet(store.getEntryCount());
                    log.debug("Loaded stream: {} ({} entries, version={})",
                            streamName, store.getEntryCount(), store.getVersion());

                    // Load decoder from file header for this stream
                    if (store.getDecoderClassName() != null && !store.getDecoderClassName().isEmpty()) {
                        Decoder decoder = instantiateDecoder(store.getDecoderClassName());
                        if (decoder != null) {
                            streamDecoders.put(streamName, decoder);
                        }
                    }
                } catch (IOException e) {
                    log.error("Failed to load stream {}: {}", streamName, e.getMessage());
                }
            }
        }
    }

    private Decoder instantiateDecoder(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (Decoder) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.warn("Failed to instantiate decoder {}: {}", className, e.getMessage());
            return null;
        }
    }

    private StreamStore getOrCreateStream(String streamName) {
        return streams.computeIfAbsent(streamName, name -> {
            try {
                String sanitizedName = sanitizeFileName(name);
                File file = new File(baseDir, sanitizedName + ".log");
                Decoder decoder = streamDecoders.get(name);
                return new StreamStore(name, file, maxFileSize, syncOnWrite, decoder);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create stream: " + name, e);
            }
        });
    }

    /**
     * Sanitize a stream name to be safe for use as a filename.
     */
    private String sanitizeFileName(String name) {
        String sanitized = name.replace("->", "_to_");
        sanitized = sanitized.replaceAll("[<>:\"/\\\\|?*]", "_");
        return sanitized;
    }

    @Override
    public long write(LogEntry entry) {
        if (entry.getStreamName() == null || entry.getStreamName().isEmpty()) {
            throw new IllegalArgumentException("Stream name is required");
        }

        StreamStore store = getOrCreateStream(entry.getStreamName());
        long position = store.write(entry);
        totalEntries.incrementAndGet();
        return position;
    }

    @Override
    public long replay(String streamName, LogEntry.Direction direction,
                       int fromSeqNum, int toSeqNum, LogCallback callback) {
        long count = 0;

        Collection<StreamStore> storesToReplay;
        if (streamName != null) {
            StreamStore store = streams.get(streamName);
            storesToReplay = store != null ? List.of(store) : Collections.emptyList();
        } else {
            storesToReplay = streams.values();
        }

        for (StreamStore store : storesToReplay) {
            count += store.replay(direction, fromSeqNum, toSeqNum, 0, 0, callback, flyweightEntry);
        }

        return count;
    }

    @Override
    public long replayByTime(String streamName, LogEntry.Direction direction,
                             long fromTimestamp, long toTimestamp, LogCallback callback) {
        long count = 0;

        Collection<StreamStore> storesToReplay;
        if (streamName != null) {
            StreamStore store = streams.get(streamName);
            storesToReplay = store != null ? List.of(store) : Collections.emptyList();
        } else {
            storesToReplay = streams.values();
        }

        for (StreamStore store : storesToReplay) {
            count += store.replay(direction, 0, 0, fromTimestamp, toTimestamp, callback, flyweightEntry);
        }

        return count;
    }

    @Override
    public LogEntry getLatest(String streamName, LogEntry.Direction direction) {
        StreamStore store = streams.get(streamName);
        if (store == null) return null;
        return store.getLatest(direction);
    }

    @Override
    public long getEntryCount(String streamName) {
        if (streamName == null) {
            return totalEntries.get();
        }
        StreamStore store = streams.get(streamName);
        return store != null ? store.getEntryCount() : 0;
    }

    @Override
    public Collection<String> getStreamNames() {
        return Collections.unmodifiableSet(streams.keySet());
    }

    @Override
    public void sync() {
        for (StreamStore store : streams.values()) {
            store.sync();
        }
    }

    @Override
    public String getStorePath() {
        return baseDir.getAbsolutePath();
    }

    @Override
    public void close() throws IOException {
        for (StreamStore store : streams.values()) {
            store.close();
        }
        streams.clear();
        componentState = ComponentState.STOPPED;
        log.info("MemoryMappedLogStore closed");
    }

    // ==================== Component Interface ====================

    @Override
    public void initialize() throws Exception {
        if (componentState != ComponentState.UNINITIALIZED) {
            throw new IllegalStateException("Cannot initialize from state: " + componentState);
        }
        componentState = ComponentState.INITIALIZED;
        log.debug("MemoryMappedLogStore component initialized");
    }

    @Override
    public void startActive() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + componentState);
        }
        componentState = ComponentState.ACTIVE;
        log.info("MemoryMappedLogStore started in ACTIVE mode");
    }

    @Override
    public void startStandby() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + componentState);
        }
        componentState = ComponentState.STANDBY;
        log.info("MemoryMappedLogStore started in STANDBY mode");
    }

    @Override
    public void becomeActive() throws Exception {
        if (componentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + componentState);
        }
        componentState = ComponentState.ACTIVE;
        log.info("MemoryMappedLogStore transitioned to ACTIVE mode");
    }

    @Override
    public void becomeStandby() throws Exception {
        if (componentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + componentState);
        }
        componentState = ComponentState.STANDBY;
        log.info("MemoryMappedLogStore transitioned to STANDBY mode");
    }

    @Override
    public void stop() {
        try {
            close();
        } catch (IOException e) {
            log.error("Error closing persistence store", e);
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

    // ==================== Polling API ====================

    @Override
    public LogReader createReader(String streamName, long startPosition) {
        if (streamName != null) {
            // Get or create stream if it doesn't exist
            StreamStore store = getOrCreateStream(streamName);
            return new MappedLogReader(streamName, store, startPosition);
        } else {
            // All-streams reader
            return new AllStreamsLogReader(startPosition);
        }
    }

    /**
     * LogReader implementation for a single stream.
     */
    private class MappedLogReader implements LogReader {
        private final String streamName;
        private final StreamStore store;
        private final LogEntry flyweight = new LogEntry();
        private long position;
        private volatile boolean closed;

        MappedLogReader(String streamName, StreamStore store, long startPosition) {
            this.streamName = streamName;
            this.store = store;

            if (startPosition == LogReader.END) {
                this.position = store.getWritePosition();
            } else if (startPosition == LogReader.START) {
                this.position = store.getHeaderSize();
            } else {
                this.position = startPosition;
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
                    return null; // No wait
                }

                if (timeoutMs > 0 && System.currentTimeMillis() >= deadline) {
                    return null; // Timeout
                }

                // Wait for new data
                try {
                    synchronized (store) {
                        long waitTime = timeoutMs < 0 ? 100 : Math.min(100, deadline - System.currentTimeMillis());
                        if (waitTime > 0) {
                            store.wait(waitTime);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
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
            long writePos = store.getWritePosition();
            if (position >= writePos) {
                return null;
            }

            int offset = (int) position;
            int entrySize = store.readInt(offset);
            if (entrySize <= 0) {
                return null;
            }

            store.readEntry(offset + 4, flyweight);
            flyweight.setStreamName(streamName);
            position = offset + 4 + entrySize;

            return flyweight;
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        public void setPosition(long position) {
            if (position == LogReader.END) {
                this.position = store.getWritePosition();
            } else if (position == LogReader.START) {
                this.position = store.getHeaderSize();
            } else {
                this.position = position;
            }
        }

        @Override
        public String getStreamName() {
            return streamName;
        }

        @Override
        public boolean hasNext() {
            return position < store.getWritePosition();
        }

        @Override
        public long available() {
            // Approximate: count entries between position and writePosition
            long count = 0;
            long pos = position;
            long writePos = store.getWritePosition();

            while (pos < writePos) {
                int entrySize = store.readInt((int) pos);
                if (entrySize <= 0) break;
                pos += 4 + entrySize;
                count++;
            }
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * LogReader implementation for all streams.
     */
    private class AllStreamsLogReader implements LogReader {
        private final Map<String, Long> streamPositions = new HashMap<>();
        private final LogEntry flyweight = new LogEntry();
        private volatile boolean closed;

        AllStreamsLogReader(long startPosition) {
            for (Map.Entry<String, StreamStore> entry : streams.entrySet()) {
                StreamStore store = entry.getValue();
                long pos;
                if (startPosition == LogReader.END) {
                    pos = store.getWritePosition();
                } else if (startPosition == LogReader.START) {
                    pos = store.getHeaderSize();
                } else {
                    pos = store.getHeaderSize(); // Default to start for all-streams
                }
                streamPositions.put(entry.getKey(), pos);
            }
        }

        @Override
        public LogEntry poll(long timeoutMs) {
            long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;

            while (!closed) {
                // Find the next entry across all streams (by timestamp)
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

                // Wait
                try {
                    Thread.sleep(Math.min(100, timeoutMs > 0 ? deadline - System.currentTimeMillis() : 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            return null;
        }

        private LogEntry findNextEntry() {
            LogEntry earliest = null;
            String earliestStream = null;
            long earliestPos = 0;

            for (Map.Entry<String, Long> posEntry : streamPositions.entrySet()) {
                String streamName = posEntry.getKey();
                long position = posEntry.getValue();
                StreamStore store = streams.get(streamName);

                if (store == null || position >= store.getWritePosition()) {
                    continue;
                }

                int offset = (int) position;
                int entrySize = store.readInt(offset);
                if (entrySize <= 0) continue;

                LogEntry temp = new LogEntry();
                store.readEntry(offset + 4, temp);
                temp.setStreamName(streamName);

                if (earliest == null || temp.getTimestamp() < earliest.getTimestamp()) {
                    earliest = temp;
                    earliestStream = streamName;
                    earliestPos = position + 4 + entrySize;
                }
            }

            if (earliest != null) {
                streamPositions.put(earliestStream, earliestPos);
                flyweight.reset(earliest.getTimestamp(), earliest.getDirection(),
                        earliest.getSequenceNumber(), earliest.getStreamName(),
                        earliest.getMetadata(), earliest.getRawMessage());
                return flyweight;
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

        @Override
        public long getPosition() {
            // Return minimum position across all streams
            return streamPositions.values().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(0);
        }

        @Override
        public void setPosition(long position) {
            for (Map.Entry<String, StreamStore> entry : streams.entrySet()) {
                StreamStore store = entry.getValue();
                long pos;
                if (position == LogReader.END) {
                    pos = store.getWritePosition();
                } else if (position == LogReader.START) {
                    pos = store.getHeaderSize();
                } else {
                    pos = store.getHeaderSize();
                }
                streamPositions.put(entry.getKey(), pos);
            }
        }

        @Override
        public String getStreamName() {
            return null; // All streams
        }

        @Override
        public boolean hasNext() {
            for (Map.Entry<String, Long> posEntry : streamPositions.entrySet()) {
                StreamStore store = streams.get(posEntry.getKey());
                if (store != null && posEntry.getValue() < store.getWritePosition()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long available() {
            long count = 0;
            for (Map.Entry<String, Long> posEntry : streamPositions.entrySet()) {
                StreamStore store = streams.get(posEntry.getKey());
                if (store == null) continue;

                long pos = posEntry.getValue();
                long writePos = store.getWritePosition();
                while (pos < writePos) {
                    int entrySize = store.readInt((int) pos);
                    if (entrySize <= 0) break;
                    pos += 4 + entrySize;
                    count++;
                }
            }
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Per-stream storage using memory-mapped file.
     */
    private static class StreamStore {
        private final String name;
        private final File file;
        private final RandomAccessFile raf;
        private final FileChannel channel;
        private MappedByteBuffer buffer;
        private final UnsafeBuffer unsafeBuffer;
        private final long maxSize;
        private final boolean syncOnWrite;
        private long writePosition;
        private long entryCount;
        private int version;
        private String decoderClassName;

        StreamStore(String name, File file, long maxSize, boolean syncOnWrite) throws IOException {
            this(name, file, maxSize, syncOnWrite, null);
        }

        StreamStore(String name, File file, long maxSize, boolean syncOnWrite, Decoder decoder) throws IOException {
            this.name = name;
            this.file = file;
            this.maxSize = maxSize;
            this.syncOnWrite = syncOnWrite;

            boolean newFile = !file.exists();
            this.raf = new RandomAccessFile(file, "rw");
            this.channel = raf.getChannel();

            if (newFile) {
                raf.setLength(maxSize);
                this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, maxSize);
                buffer.order(ByteOrder.LITTLE_ENDIAN);

                // Write v2 header
                this.version = VERSION_2;
                writeHeader(decoder);
            } else {
                this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
                buffer.order(ByteOrder.LITTLE_ENDIAN);

                // Read header to determine version
                readHeader();
            }

            this.unsafeBuffer = new UnsafeBuffer(buffer);
        }

        private void writeHeader(Decoder decoder) {
            buffer.putLong(0, MAGIC_V2);
            buffer.putInt(8, VERSION_2);

            String className = decoder != null ? decoder.getClass().getName() : "";
            byte[] nameBytes = className.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(12, nameBytes.length);

            // Write decoder class name (padded to 128 bytes)
            for (int i = 0; i < Math.min(nameBytes.length, 128); i++) {
                buffer.put(16 + i, nameBytes[i]);
            }

            // Initialize counters
            buffer.putLong(HEADER_SIZE_V2 - 16, 0); // Entry count at end of header
            buffer.putLong(HEADER_SIZE_V2 - 8, HEADER_SIZE_V2); // Write position

            writePosition = HEADER_SIZE_V2;
            entryCount = 0;
            decoderClassName = className;
        }

        private void readHeader() {
            long magic = buffer.getLong(0);

            if (magic == MAGIC_V2) {
                // V2 file
                version = buffer.getInt(8);
                int nameLen = buffer.getInt(12);
                if (nameLen > 0 && nameLen <= 128) {
                    byte[] nameBytes = new byte[nameLen];
                    for (int i = 0; i < nameLen; i++) {
                        nameBytes[i] = buffer.get(16 + i);
                    }
                    decoderClassName = new String(nameBytes, StandardCharsets.UTF_8);
                }
                entryCount = buffer.getLong(HEADER_SIZE_V2 - 16);
                writePosition = buffer.getLong(HEADER_SIZE_V2 - 8);
            } else {
                // V1 file (legacy format)
                version = 1;
                decoderClassName = null;
                entryCount = buffer.getLong(0);
                writePosition = buffer.getLong(8);

                // Ensure minimum write position
                if (writePosition < HEADER_SIZE_V1) {
                    writePosition = HEADER_SIZE_V1;
                }
            }
        }

        int getVersion() {
            return version;
        }

        String getDecoderClassName() {
            return decoderClassName;
        }

        synchronized void updateDecoder(Decoder decoder) {
            if (version != VERSION_2) {
                return; // Can only update decoder in v2 files
            }
            String className = decoder != null ? decoder.getClass().getName() : "";
            byte[] nameBytes = className.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(12, nameBytes.length);

            // Clear existing name area and write new name
            for (int i = 0; i < 128; i++) {
                buffer.put(16 + i, (byte) 0);
            }
            for (int i = 0; i < Math.min(nameBytes.length, 128); i++) {
                buffer.put(16 + i, nameBytes[i]);
            }

            this.decoderClassName = className;
        }

        synchronized long write(LogEntry entry) {
            byte[] metadataBytes = entry.getMetadata() != null ? entry.getMetadata() : new byte[0];
            byte[] rawMessage = entry.getRawMessage() != null ? entry.getRawMessage() : new byte[0];

            // Entry format: length (4) + timestamp (8) + seqNum (4) + direction (1) +
            //               metadataLen (2) + metadata + rawLen (4) + rawMessage
            int entrySize = ENTRY_HEADER_SIZE +
                    2 + metadataBytes.length +
                    4 + rawMessage.length;

            if (writePosition + entrySize + 4 > maxSize) {
                throw new IllegalStateException("Log file full: " + file.getAbsolutePath());
            }

            long position = writePosition;
            int offset = (int) writePosition;

            // Write entry size
            buffer.putInt(offset, entrySize);
            offset += 4;

            // Write timestamp
            buffer.putLong(offset, entry.getTimestamp());
            offset += 8;

            // Write sequence number
            buffer.putInt(offset, entry.getSequenceNumber());
            offset += 4;

            // Write direction
            buffer.put(offset, (byte) (entry.getDirection() == LogEntry.Direction.INBOUND ? 0 : 1));
            offset += 1;

            // Write metadata (length + data)
            buffer.putShort(offset, (short) metadataBytes.length);
            offset += 2;
            for (byte b : metadataBytes) {
                buffer.put(offset++, b);
            }

            // Write raw message (length + data)
            buffer.putInt(offset, rawMessage.length);
            offset += 4;
            for (byte b : rawMessage) {
                buffer.put(offset++, b);
            }

            writePosition = offset;
            entryCount++;

            // Update header counters
            if (version == VERSION_2) {
                buffer.putLong(HEADER_SIZE_V2 - 16, entryCount);
                buffer.putLong(HEADER_SIZE_V2 - 8, writePosition);
            } else {
                buffer.putLong(0, entryCount);
                buffer.putLong(8, writePosition);
            }

            if (syncOnWrite) {
                buffer.force();
            }

            // Notify waiting readers
            synchronized (this) {
                notifyAll();
            }

            return position;
        }

        long getWritePosition() {
            return writePosition;
        }

        int getHeaderSize() {
            return version == VERSION_2 ? HEADER_SIZE_V2 : HEADER_SIZE_V1;
        }

        int readInt(int offset) {
            return buffer.getInt(offset);
        }

        void readEntry(int offset, LogEntry entry) {
            if (version == 1) {
                readEntryV1IntoFlyweight(offset, entry);
            } else {
                readEntryV2(offset, entry);
            }
        }

        private void readEntryV1IntoFlyweight(int offset, LogEntry entry) {
            long timestamp = buffer.getLong(offset);
            offset += 8;

            int seqNum = buffer.getInt(offset);
            offset += 4;

            LogEntry.Direction dir = buffer.get(offset) == 0 ?
                    LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND;
            offset += 1;

            // Skip txnId (8 bytes)
            offset += 8;

            // Skip msgType (2+str)
            int msgTypeLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2 + msgTypeLen;

            // Metadata
            int metadataLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2;
            byte[] metadata = null;
            if (metadataLen > 0) {
                metadata = new byte[metadataLen];
                for (int i = 0; i < metadataLen; i++) {
                    metadata[i] = buffer.get(offset++);
                }
            }

            // Raw message
            int rawLen = buffer.getInt(offset);
            offset += 4;
            byte[] rawMessage = null;
            if (rawLen > 0) {
                rawMessage = new byte[rawLen];
                for (int i = 0; i < rawLen; i++) {
                    rawMessage[i] = buffer.get(offset++);
                }
            }

            entry.reset(timestamp, dir, seqNum, null, metadata, rawMessage);
        }

        long replay(LogEntry.Direction direction, int fromSeqNum, int toSeqNum,
                    long fromTimestamp, long toTimestamp, LogCallback callback, LogEntry flyweight) {
            long count = 0;
            int headerSize = version == VERSION_2 ? HEADER_SIZE_V2 : HEADER_SIZE_V1;
            long pos = headerSize;

            while (pos < writePosition) {
                int offset = (int) pos;
                int entrySize = buffer.getInt(offset);
                if (entrySize <= 0) break;

                offset += 4;

                if (version == 1) {
                    // V1 format: read using old format
                    LogEntry entry = readEntryV1(offset);
                    entry.setStreamName(name);
                    pos += 4 + entrySize;

                    if (!matchesFilter(entry, direction, fromSeqNum, toSeqNum, fromTimestamp, toTimestamp)) {
                        continue;
                    }

                    count++;
                    if (!callback.onEntry(entry)) {
                        break;
                    }
                } else {
                    // V2 format: use flyweight
                    readEntryV2(offset, flyweight);
                    flyweight.setStreamName(name);
                    pos += 4 + entrySize;

                    if (!matchesFilter(flyweight, direction, fromSeqNum, toSeqNum, fromTimestamp, toTimestamp)) {
                        continue;
                    }

                    count++;
                    if (!callback.onEntry(flyweight)) {
                        break;
                    }
                }
            }

            return count;
        }

        private boolean matchesFilter(LogEntry entry, LogEntry.Direction direction,
                                       int fromSeqNum, int toSeqNum, long fromTimestamp, long toTimestamp) {
            if (direction != null && entry.getDirection() != direction) return false;
            if (fromSeqNum > 0 && entry.getSequenceNumber() < fromSeqNum) return false;
            if (toSeqNum > 0 && entry.getSequenceNumber() > toSeqNum) return false;
            if (fromTimestamp > 0 && entry.getTimestamp() < fromTimestamp) return false;
            if (toTimestamp > 0 && entry.getTimestamp() > toTimestamp) return false;
            return true;
        }

        private void readEntryV2(int offset, LogEntry entry) {
            long timestamp = buffer.getLong(offset);
            offset += 8;

            int seqNum = buffer.getInt(offset);
            offset += 4;

            LogEntry.Direction dir = buffer.get(offset) == 0 ?
                    LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND;
            offset += 1;

            // Metadata
            int metadataLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2;
            byte[] metadata = null;
            if (metadataLen > 0) {
                metadata = new byte[metadataLen];
                for (int i = 0; i < metadataLen; i++) {
                    metadata[i] = buffer.get(offset++);
                }
            }

            // Raw message
            int rawLen = buffer.getInt(offset);
            offset += 4;
            byte[] rawMessage = null;
            if (rawLen > 0) {
                rawMessage = new byte[rawLen];
                for (int i = 0; i < rawLen; i++) {
                    rawMessage[i] = buffer.get(offset++);
                }
            }

            entry.reset(timestamp, dir, seqNum, null, metadata, rawMessage);
        }

        private LogEntry readEntryV1(int offset) {
            // V1 format: timestamp (8) + seqNum (4) + direction (1) + txnId (8) +
            //            msgType (2+str) + metadata (2+str) + rawMessage (4+bytes)
            LogEntry entry = new LogEntry();

            long timestamp = buffer.getLong(offset);
            offset += 8;

            int seqNum = buffer.getInt(offset);
            offset += 4;

            LogEntry.Direction dir = buffer.get(offset) == 0 ?
                    LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND;
            offset += 1;

            // Skip txnId (8 bytes) - not in v2 format
            offset += 8;

            // Skip msgType (2+str) - not in v2 format
            int msgTypeLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2 + msgTypeLen;

            // Metadata
            int metadataLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2;
            byte[] metadata = null;
            if (metadataLen > 0) {
                metadata = new byte[metadataLen];
                for (int i = 0; i < metadataLen; i++) {
                    metadata[i] = buffer.get(offset++);
                }
            }

            // Raw message
            int rawLen = buffer.getInt(offset);
            offset += 4;
            byte[] rawMessage = null;
            if (rawLen > 0) {
                rawMessage = new byte[rawLen];
                for (int i = 0; i < rawLen; i++) {
                    rawMessage[i] = buffer.get(offset++);
                }
            }

            entry.reset(timestamp, dir, seqNum, null, metadata, rawMessage);
            return entry;
        }

        LogEntry getLatest(LogEntry.Direction direction) {
            LogEntry latest = null;
            int headerSize = version == VERSION_2 ? HEADER_SIZE_V2 : HEADER_SIZE_V1;
            long pos = headerSize;

            while (pos < writePosition) {
                int offset = (int) pos;
                int entrySize = buffer.getInt(offset);
                if (entrySize <= 0) break;

                offset += 4;

                LogEntry entry;
                if (version == 1) {
                    entry = readEntryV1(offset);
                } else {
                    entry = new LogEntry();
                    readEntryV2(offset, entry);
                }
                entry.setStreamName(name);

                pos += 4 + entrySize;

                if (direction == null || entry.getDirection() == direction) {
                    latest = entry;
                }
            }

            return latest;
        }

        long getEntryCount() {
            return entryCount;
        }

        void sync() {
            buffer.force();
        }

        void close() {
            try {
                buffer.force();
                IoUtil.unmap(buffer);
                channel.close();
                raf.close();
            } catch (IOException e) {
                log.warn("Error closing stream {}: {}", name, e.getMessage());
            }
        }
    }
}
