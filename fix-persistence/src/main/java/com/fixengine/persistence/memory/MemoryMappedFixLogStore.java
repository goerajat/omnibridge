package com.fixengine.persistence.memory;

import com.fixengine.config.PersistenceConfig;
import com.fixengine.config.provider.ComponentProvider;
import com.fixengine.persistence.FixLogCallback;
import com.fixengine.persistence.FixLogEntry;
import com.fixengine.persistence.FixLogStore;
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
 * Memory-mapped file implementation of FixLogStore.
 *
 * <p>This implementation uses memory-mapped files for high-performance persistence
 * with minimal GC overhead. Each stream is stored in a separate file.</p>
 *
 * <p>Entry format (binary, little-endian):</p>
 * <pre>
 * +----------+----------+----------+----------+----------+----------+----------+
 * | Length   | Timestamp| SeqNum   | Direction| TxnId    | MsgType  | Metadata |
 * | (4 bytes)| (8 bytes)| (4 bytes)| (1 byte) | (8 bytes)| (len+str)| (len+str)|
 * +----------+----------+----------+----------+----------+----------+----------+
 * | Raw Message                                                                |
 * | (4 bytes length + data)                                                    |
 * +----------+----------+----------+----------+----------+----------+----------+
 * </pre>
 */
public class MemoryMappedFixLogStore implements FixLogStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryMappedFixLogStore.class);

    private static final int HEADER_SIZE = 64; // File header
    private static final long DEFAULT_FILE_SIZE = 256 * 1024 * 1024; // 256 MB
    private static final int ENTRY_HEADER_SIZE = 4 + 8 + 4 + 1 + 8; // length + timestamp + seqNum + direction + txnId

    private final File baseDir;
    private final long maxFileSize;
    private final boolean syncOnWrite;
    private final Map<String, StreamStore> streams = new ConcurrentHashMap<>();
    private final AtomicLong totalEntries = new AtomicLong(0);
    private ComponentProvider<?, ?, ?> componentProvider;

    /**
     * Create a new memory-mapped log store.
     *
     * @param basePath the base directory path for log files
     */
    public MemoryMappedFixLogStore(String basePath) {
        this(new File(basePath), DEFAULT_FILE_SIZE, false);
    }

    /**
     * Create a new memory-mapped log store with specified max file size.
     *
     * @param basePath the base directory path for log files
     * @param maxFileSize the maximum size for each stream file
     */
    public MemoryMappedFixLogStore(String basePath, long maxFileSize) {
        this(new File(basePath), maxFileSize, false);
    }

    /**
     * Create a new memory-mapped log store.
     *
     * @param baseDir the base directory for log files
     */
    public MemoryMappedFixLogStore(File baseDir) {
        this(baseDir, DEFAULT_FILE_SIZE, false);
    }

    /**
     * Create a new memory-mapped log store with specified max file size.
     *
     * @param baseDir the base directory for log files
     * @param maxFileSize the maximum size for each stream file
     */
    public MemoryMappedFixLogStore(File baseDir, long maxFileSize) {
        this(baseDir, maxFileSize, false);
    }

    /**
     * Create a new memory-mapped log store from PersistenceConfig.
     *
     * @param config the persistence configuration
     */
    public MemoryMappedFixLogStore(PersistenceConfig config) {
        this(config, null);
    }

    /**
     * Create a new memory-mapped log store from PersistenceConfig and ComponentProvider.
     *
     * @param config the persistence configuration
     * @param provider the component provider (may be null)
     */
    public MemoryMappedFixLogStore(PersistenceConfig config, ComponentProvider<?, ?, ?> provider) {
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
    public MemoryMappedFixLogStore(File baseDir, long maxFileSize, boolean syncOnWrite) {
        this.baseDir = baseDir;
        this.maxFileSize = maxFileSize;
        this.syncOnWrite = syncOnWrite;

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Failed to create directory: " + baseDir);
        }

        // Load existing streams
        loadExistingStreams();

        log.info("MemoryMappedFixLogStore initialized at {} (maxFileSize={}MB, syncOnWrite={})",
                baseDir.getAbsolutePath(), maxFileSize / (1024 * 1024), syncOnWrite);
    }

    /**
     * Get the component provider.
     */
    public ComponentProvider<?, ?, ?> getComponentProvider() {
        return componentProvider;
    }

    private void loadExistingStreams() {
        File[] files = baseDir.listFiles((dir, name) -> name.endsWith(".fixlog"));
        if (files != null) {
            for (File file : files) {
                String streamName = file.getName().replace(".fixlog", "");
                try {
                    StreamStore store = new StreamStore(streamName, file, maxFileSize, syncOnWrite);
                    streams.put(streamName, store);
                    totalEntries.addAndGet(store.getEntryCount());
                    log.debug("Loaded stream: {} ({} entries)", streamName, store.getEntryCount());
                } catch (IOException e) {
                    log.error("Failed to load stream {}: {}", streamName, e.getMessage());
                }
            }
        }
    }

    private StreamStore getOrCreateStream(String streamName) {
        return streams.computeIfAbsent(streamName, name -> {
            try {
                String sanitizedName = sanitizeFileName(name);
                File file = new File(baseDir, sanitizedName + ".fixlog");
                return new StreamStore(name, file, maxFileSize, syncOnWrite);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create stream: " + name, e);
            }
        });
    }

    /**
     * Sanitize a stream name to be safe for use as a filename.
     * Replaces characters that are invalid in Windows/Unix filenames.
     */
    private String sanitizeFileName(String name) {
        // Replace -> with _to_ for readability
        String sanitized = name.replace("->", "_to_");
        // Replace other invalid characters: < > : " / \ | ? *
        sanitized = sanitized.replaceAll("[<>:\"/\\\\|?*]", "_");
        return sanitized;
    }

    @Override
    public long write(FixLogEntry entry) {
        if (entry.getStreamName() == null || entry.getStreamName().isEmpty()) {
            throw new IllegalArgumentException("Stream name is required");
        }

        StreamStore store = getOrCreateStream(entry.getStreamName());
        long position = store.write(entry);
        totalEntries.incrementAndGet();
        return position;
    }

    @Override
    public long replay(String streamName, FixLogEntry.Direction direction,
                       int fromSeqNum, int toSeqNum, FixLogCallback callback) {
        long count = 0;

        Collection<StreamStore> storesToReplay;
        if (streamName != null) {
            StreamStore store = streams.get(streamName);
            storesToReplay = store != null ? List.of(store) : Collections.emptyList();
        } else {
            storesToReplay = streams.values();
        }

        for (StreamStore store : storesToReplay) {
            count += store.replay(direction, fromSeqNum, toSeqNum, 0, 0, callback);
        }

        return count;
    }

    @Override
    public long replayByTime(String streamName, FixLogEntry.Direction direction,
                             long fromTimestamp, long toTimestamp, FixLogCallback callback) {
        long count = 0;

        Collection<StreamStore> storesToReplay;
        if (streamName != null) {
            StreamStore store = streams.get(streamName);
            storesToReplay = store != null ? List.of(store) : Collections.emptyList();
        } else {
            storesToReplay = streams.values();
        }

        for (StreamStore store : storesToReplay) {
            count += store.replay(direction, 0, 0, fromTimestamp, toTimestamp, callback);
        }

        return count;
    }

    @Override
    public FixLogEntry getLatest(String streamName, FixLogEntry.Direction direction) {
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
        log.info("MemoryMappedFixLogStore closed");
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

        StreamStore(String name, File file, long maxSize, boolean syncOnWrite) throws IOException {
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
                // Write header
                buffer.putLong(0, 0); // Entry count
                buffer.putLong(8, HEADER_SIZE); // Write position
                writePosition = HEADER_SIZE;
                entryCount = 0;
            } else {
                this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                entryCount = buffer.getLong(0);
                writePosition = buffer.getLong(8);
            }

            this.unsafeBuffer = new UnsafeBuffer(buffer);
        }

        synchronized long write(FixLogEntry entry) {
            byte[] msgTypeBytes = entry.getMsgType() != null ?
                    entry.getMsgType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] metadataBytes = entry.getMetadata() != null ?
                    entry.getMetadata().getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] rawMessage = entry.getRawMessage() != null ? entry.getRawMessage() : new byte[0];

            int entrySize = ENTRY_HEADER_SIZE +
                    2 + msgTypeBytes.length +
                    2 + metadataBytes.length +
                    4 + rawMessage.length;

            if (writePosition + entrySize + 4 > maxSize) {
                throw new IllegalStateException("Log file full: " + file.getAbsolutePath());
            }

            long position = writePosition;
            int offset = (int) writePosition;

            // Write entry
            buffer.putInt(offset, entrySize);
            offset += 4;

            buffer.putLong(offset, entry.getTimestamp());
            offset += 8;

            buffer.putInt(offset, entry.getSeqNum());
            offset += 4;

            buffer.put(offset, (byte) (entry.getDirection() == FixLogEntry.Direction.INBOUND ? 0 : 1));
            offset += 1;

            buffer.putLong(offset, entry.getTransactionId());
            offset += 8;

            // MsgType (length + data)
            buffer.putShort(offset, (short) msgTypeBytes.length);
            offset += 2;
            for (byte b : msgTypeBytes) {
                buffer.put(offset++, b);
            }

            // Metadata (length + data)
            buffer.putShort(offset, (short) metadataBytes.length);
            offset += 2;
            for (byte b : metadataBytes) {
                buffer.put(offset++, b);
            }

            // Raw message (length + data)
            buffer.putInt(offset, rawMessage.length);
            offset += 4;
            for (byte b : rawMessage) {
                buffer.put(offset++, b);
            }

            writePosition = offset;
            entryCount++;

            // Update header
            buffer.putLong(0, entryCount);
            buffer.putLong(8, writePosition);

            // Sync to disk if configured
            if (syncOnWrite) {
                buffer.force();
            }

            return position;
        }

        long replay(FixLogEntry.Direction direction, int fromSeqNum, int toSeqNum,
                    long fromTimestamp, long toTimestamp, FixLogCallback callback) {
            long count = 0;
            long pos = HEADER_SIZE;

            while (pos < writePosition) {
                int offset = (int) pos;
                int entrySize = buffer.getInt(offset);
                if (entrySize <= 0) break;

                offset += 4;

                FixLogEntry entry = readEntry(offset);
                entry.setStreamName(name);

                pos += 4 + entrySize;

                // Apply filters
                if (direction != null && entry.getDirection() != direction) continue;
                if (fromSeqNum > 0 && entry.getSeqNum() < fromSeqNum) continue;
                if (toSeqNum > 0 && entry.getSeqNum() > toSeqNum) continue;
                if (fromTimestamp > 0 && entry.getTimestamp() < fromTimestamp) continue;
                if (toTimestamp > 0 && entry.getTimestamp() > toTimestamp) continue;

                count++;
                if (!callback.onEntry(entry)) {
                    break;
                }
            }

            return count;
        }

        FixLogEntry getLatest(FixLogEntry.Direction direction) {
            FixLogEntry latest = null;
            long pos = HEADER_SIZE;

            while (pos < writePosition) {
                int offset = (int) pos;
                int entrySize = buffer.getInt(offset);
                if (entrySize <= 0) break;

                offset += 4;
                FixLogEntry entry = readEntry(offset);
                entry.setStreamName(name);

                pos += 4 + entrySize;

                if (direction == null || entry.getDirection() == direction) {
                    latest = entry;
                }
            }

            return latest;
        }

        private FixLogEntry readEntry(int offset) {
            FixLogEntry entry = new FixLogEntry();

            entry.setTimestamp(buffer.getLong(offset));
            offset += 8;

            entry.setSeqNum(buffer.getInt(offset));
            offset += 4;

            entry.setDirection(buffer.get(offset) == 0 ?
                    FixLogEntry.Direction.INBOUND : FixLogEntry.Direction.OUTBOUND);
            offset += 1;

            entry.setTransactionId(buffer.getLong(offset));
            offset += 8;

            // MsgType
            int msgTypeLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2;
            if (msgTypeLen > 0) {
                byte[] msgTypeBytes = new byte[msgTypeLen];
                for (int i = 0; i < msgTypeLen; i++) {
                    msgTypeBytes[i] = buffer.get(offset++);
                }
                entry.setMsgType(new String(msgTypeBytes, StandardCharsets.UTF_8));
            }

            // Metadata
            int metadataLen = buffer.getShort(offset) & 0xFFFF;
            offset += 2;
            if (metadataLen > 0) {
                byte[] metadataBytes = new byte[metadataLen];
                for (int i = 0; i < metadataLen; i++) {
                    metadataBytes[i] = buffer.get(offset++);
                }
                entry.setMetadata(new String(metadataBytes, StandardCharsets.UTF_8));
            }

            // Raw message
            int rawLen = buffer.getInt(offset);
            offset += 4;
            if (rawLen > 0) {
                byte[] rawBytes = new byte[rawLen];
                for (int i = 0; i < rawLen; i++) {
                    rawBytes[i] = buffer.get(offset++);
                }
                entry.setRawMessage(rawBytes);
            }

            return entry;
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
