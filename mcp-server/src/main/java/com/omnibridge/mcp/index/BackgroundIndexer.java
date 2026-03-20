package com.omnibridge.mcp.index;

import com.omnibridge.mcp.util.FIXMessageParser;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import com.omnibridge.persistence.LogStore;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;

/**
 * Daemon thread that tails Chronicle Queue streams and populates the RocksDB secondary index.
 *
 * <p>On startup, for each stream it resumes from the last checkpointed position.
 * It polls each reader in round-robin, parses FIX messages, and writes indexed tags
 * to the SecondaryIndex.</p>
 */
public class BackgroundIndexer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BackgroundIndexer.class);

    private final LogStore logStore;
    private final SecondaryIndex index;
    private final int checkpointInterval;
    private volatile boolean running;
    private Thread indexerThread;

    public BackgroundIndexer(LogStore logStore, SecondaryIndex index, int checkpointInterval) {
        this.logStore = logStore;
        this.index = index;
        this.checkpointInterval = checkpointInterval;
    }

    /**
     * Start the background indexer thread.
     */
    public void start() {
        if (running) return;
        running = true;
        indexerThread = new Thread(this::indexLoop, "mcp-indexer");
        indexerThread.setDaemon(true);
        indexerThread.start();
        log.info("Background indexer started (checkpoint interval: {})", checkpointInterval);
    }

    /**
     * Stop the background indexer gracefully.
     */
    @Override
    public void close() {
        running = false;
        if (indexerThread != null) {
            indexerThread.interrupt();
            try {
                indexerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("Background indexer stopped");
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void indexLoop() {
        Map<String, LogReader> readers = new HashMap<>();
        Map<String, Integer> entryCounts = new HashMap<>();
        long lastCheckpointTime = System.currentTimeMillis();

        try {
            while (running) {
                // Discover new streams
                for (String stream : logStore.getStreamNames()) {
                    if (!readers.containsKey(stream)) {
                        try {
                            long startPos = index.getLastIndexedPosition(stream);
                            LogReader reader;
                            if (startPos >= 0) {
                                reader = logStore.createReader(stream, startPos);
                                // Skip the already-indexed entry at startPos
                                reader.poll(0);
                            } else {
                                reader = logStore.createReader(stream);
                            }
                            readers.put(stream, reader);
                            entryCounts.put(stream, 0);
                            log.info("Started indexing stream {} from position {}", stream, startPos);
                        } catch (Exception e) {
                            log.warn("Failed to create reader for stream {}: {}", stream, e.getMessage());
                        }
                    }
                }

                if (readers.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }

                // Round-robin poll
                boolean anyWork = false;
                for (Map.Entry<String, LogReader> entry : readers.entrySet()) {
                    String stream = entry.getKey();
                    LogReader reader = entry.getValue();

                    LogEntry logEntry = reader.poll(0);
                    if (logEntry != null) {
                        anyWork = true;
                        indexEntry(stream, logEntry, reader.getPosition());
                        int count = entryCounts.merge(stream, 1, Integer::sum);

                        if (count % checkpointInterval == 0) {
                            saveCheckpoint(stream, reader.getPosition());
                        }
                    }
                }

                // Periodic checkpoint save
                long now = System.currentTimeMillis();
                if (now - lastCheckpointTime > 5000) {
                    saveAllCheckpoints(readers);
                    lastCheckpointTime = now;
                }

                if (!anyWork) {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Background indexer error", e);
        } finally {
            // Final checkpoint
            try {
                saveAllCheckpoints(readers);
            } catch (Exception e) {
                log.warn("Failed to save final checkpoints", e);
            }
            for (LogReader reader : readers.values()) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    void indexEntry(String stream, LogEntry entry, long position) {
        byte[] raw = entry.getRawMessage();
        if (raw == null || raw.length == 0) return;

        long timestamp = entry.getTimestamp();

        try {
            // Tag 35: MsgType
            String msgType = FIXMessageParser.extractMsgType(raw);
            if (msgType != null) {
                index.put(SecondaryIndex.CF_MSGTYPE, msgType, timestamp, stream, position);
            }

            // Tag 11: ClOrdID
            String clOrdId = FIXMessageParser.extractTag(raw, 11);
            if (clOrdId != null) {
                index.put(SecondaryIndex.CF_CLORDID, clOrdId, timestamp, stream, position);
            }

            // Tag 37: OrderID
            String orderId = FIXMessageParser.extractTag(raw, 37);
            if (orderId != null) {
                index.put(SecondaryIndex.CF_ORDERID, orderId, timestamp, stream, position);
            }

            // Tag 55: Symbol
            String symbol = FIXMessageParser.extractTag(raw, 55);
            if (symbol != null) {
                index.put(SecondaryIndex.CF_SYMBOL, symbol, timestamp, stream, position);
            }

            // Tag 150: ExecType
            String execType = FIXMessageParser.extractTag(raw, 150);
            if (execType != null) {
                index.put(SecondaryIndex.CF_EXECTYPE, execType, timestamp, stream, position);
            }

            // Tags 49+56: SenderCompID:TargetCompID
            String sender = FIXMessageParser.extractSenderCompId(raw);
            String target = FIXMessageParser.extractTargetCompId(raw);
            if (sender != null && target != null) {
                index.put(SecondaryIndex.CF_COMP, sender + ":" + target, timestamp, stream, position);
            }
        } catch (RocksDBException e) {
            log.warn("Failed to index entry in stream {} at position {}: {}", stream, position, e.getMessage());
        }
    }

    private void saveCheckpoint(String stream, long position) {
        try {
            index.setLastIndexedPosition(stream, position);
        } catch (RocksDBException e) {
            log.warn("Failed to save checkpoint for stream {}: {}", stream, e.getMessage());
        }
    }

    private void saveAllCheckpoints(Map<String, LogReader> readers) {
        for (Map.Entry<String, LogReader> entry : readers.entrySet()) {
            saveCheckpoint(entry.getKey(), entry.getValue().getPosition());
        }
    }
}
