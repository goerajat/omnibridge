package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.aeron.config.AeronLogStoreConfig;
import com.omnibridge.persistence.aeron.config.AeronRemoteStoreConfig;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that wire up {@link AeronLogStore} and {@link AeronRemoteStore}
 * on localhost UDP and verify end-to-end replication.
 *
 * <p>Each test uses unique high-numbered ports to avoid conflicts with other tests
 * or processes running concurrently.</p>
 */
class AeronReplicationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AeronReplicationIntegrationTest.class);

    // Port allocation: start high to avoid conflicts, increment by 3 per test (data, control, replay)
    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(41000);

    private Path localCacheDir;
    private Path remoteStoreDir;
    private AeronLogStore aeronLogStore;
    private AeronRemoteStore aeronRemoteStore;
    private int dataPort;
    private int controlPort;
    private int replayPort;

    @BeforeEach
    void setUp() throws Exception {
        localCacheDir = Files.createTempDirectory("aeron-local-");
        remoteStoreDir = Files.createTempDirectory("aeron-remote-");

        int basePort = PORT_COUNTER.getAndAdd(3);
        dataPort = basePort;
        controlPort = basePort + 1;
        replayPort = basePort + 2;

        log.info("Test ports: data={}, control={}, replay={}", dataPort, controlPort, replayPort);
    }

    @AfterEach
    void tearDown() {
        if (aeronLogStore != null) {
            try {
                aeronLogStore.stop();
            } catch (Exception e) {
                log.warn("Error stopping AeronLogStore", e);
            }
        }
        if (aeronRemoteStore != null) {
            try {
                aeronRemoteStore.stop();
            } catch (Exception e) {
                log.warn("Error stopping AeronRemoteStore", e);
            }
        }
        deleteRecursive(localCacheDir.toFile());
        deleteRecursive(remoteStoreDir.toFile());
    }

    // ==================== Helper Methods ====================

    private void startBothStores() throws Exception {
        startRemoteStore();
        startLocalStore();
        // Give Aeron time to establish UDP connection
        Thread.sleep(1000);
    }

    private void startRemoteStore() throws Exception {
        String remoteConfig = String.format("""
                aeron-remote-store {
                    base-path = "%s"
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        listen { host = "0.0.0.0", data-port = %d, control-port = %d }
                        engines = [{ name = "test-engine", host = "localhost", replay-port = %d }]
                        idle-strategy = "sleeping"
                        fragment-limit = 256
                    }
                }
                """, remoteStoreDir.toString().replace("\\", "/"), dataPort, controlPort, replayPort);

        Config cfg = ConfigFactory.parseString(remoteConfig);
        AeronRemoteStoreConfig config = AeronRemoteStoreConfig.fromConfig(cfg.getConfig("aeron-remote-store"));
        aeronRemoteStore = new AeronRemoteStore(config);
        aeronRemoteStore.initialize();
        aeronRemoteStore.startActive();
    }

    private void startLocalStore() throws Exception {
        PersistenceConfig persistenceConfig = PersistenceConfig.builder()
                .basePath(localCacheDir.toString())
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();

        String aeronConfig = String.format("""
                persistence {
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        publisher-id = 1
                        subscribers = [{ name = "primary", host = "localhost", data-port = %d, control-port = %d }]
                        local-endpoint { host = "0.0.0.0", replay-port = %d }
                        replay { timeout-ms = 10000, max-batch-size = 10000 }
                        heartbeat-interval-ms = 1000
                        idle-strategy = "sleeping"
                    }
                }
                """, dataPort, controlPort, replayPort);

        Config cfg = ConfigFactory.parseString(aeronConfig);
        AeronLogStoreConfig config = AeronLogStoreConfig.fromConfig(cfg.getConfig("persistence"));
        aeronLogStore = new AeronLogStore(persistenceConfig, config);
        aeronLogStore.initialize();
        aeronLogStore.startActive();
    }

    private LogEntry writeFixEntry(AeronLogStore store, String stream, int seqNum,
                                    LogEntry.Direction direction, String body) {
        LogEntry entry = LogEntry.builder()
                .timestamp(System.currentTimeMillis())
                .direction(direction)
                .sequenceNumber(seqNum)
                .streamName(stream)
                .rawMessage(body.getBytes())
                .build();
        store.write(entry);
        return entry;
    }

    private LogEntry writeFullEntry(AeronLogStore store, String stream, int seqNum,
                                     LogEntry.Direction direction, long timestamp,
                                     byte[] metadata, byte[] rawMessage) {
        LogEntry entry = LogEntry.builder()
                .timestamp(timestamp)
                .direction(direction)
                .sequenceNumber(seqNum)
                .streamName(stream)
                .metadata(metadata)
                .rawMessage(rawMessage)
                .build();
        store.write(entry);
        return entry;
    }

    private void awaitReplication(AeronRemoteStore remoteStore, long expectedCount, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (remoteStore.getEntriesReceived() >= expectedCount) {
                // Give a small extra delay for writes to flush to Chronicle
                Thread.sleep(200);
                return;
            }
            Thread.sleep(100);
        }
        fail("Replication timeout: expected " + expectedCount + " entries, got " +
                remoteStore.getEntriesReceived() + " after " + timeoutMs + "ms");
    }

    private void assertStoresMatch(ChronicleLogStore local, ChronicleLogStore remote) {
        Collection<String> localStreams = local.getStreamNames();
        Collection<String> remoteStreams = remote.getStreamNames();
        assertEquals(new TreeSet<>(localStreams), new TreeSet<>(remoteStreams),
                "Stream names should match");

        for (String stream : localStreams) {
            long localCount = local.getEntryCount(stream);
            long remoteCount = remote.getEntryCount(stream);
            assertEquals(localCount, remoteCount,
                    "Entry count should match for stream: " + stream);

            // Compare entry-by-entry
            List<LogEntry> localEntries = replayAll(local, stream);
            List<LogEntry> remoteEntries = replayAll(remote, stream);

            assertEquals(localEntries.size(), remoteEntries.size(),
                    "Replayed entry count should match for stream: " + stream);

            for (int i = 0; i < localEntries.size(); i++) {
                LogEntry le = localEntries.get(i);
                LogEntry re = remoteEntries.get(i);
                assertEquals(le.getTimestamp(), re.getTimestamp(),
                        "Timestamp mismatch at entry " + i + " in stream " + stream);
                assertEquals(le.getDirection(), re.getDirection(),
                        "Direction mismatch at entry " + i + " in stream " + stream);
                assertEquals(le.getSequenceNumber(), re.getSequenceNumber(),
                        "SeqNum mismatch at entry " + i + " in stream " + stream);
                assertArrayEquals(le.getRawMessage(), re.getRawMessage(),
                        "RawMessage mismatch at entry " + i + " in stream " + stream);
                assertArrayEquals(le.getMetadata(), re.getMetadata(),
                        "Metadata mismatch at entry " + i + " in stream " + stream);
            }
        }
    }

    private List<LogEntry> replayAll(ChronicleLogStore store, String stream) {
        List<LogEntry> entries = new ArrayList<>();
        store.replay(stream, null, 0, 0, entry -> {
            // Copy entry since flyweight may be reused
            entries.add(LogEntry.create(
                    entry.getTimestamp(),
                    entry.getDirection(),
                    entry.getSequenceNumber(),
                    entry.getStreamName(),
                    entry.getMetadata(),
                    entry.getRawMessage()
            ));
            return true;
        });
        return entries;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("Basic replication: 100 entries arrive at remote")
    void testBasicReplication() throws Exception {
        startBothStores();

        for (int i = 1; i <= 100; i++) {
            writeFixEntry(aeronLogStore, "SESSION-1", i, LogEntry.Direction.OUTBOUND,
                    "8=FIX.4.4\u000135=D\u000149=CLIENT\u000156=EXCHANGE\u000134=" + i + "\u000110=000\u0001");
        }

        awaitReplication(aeronRemoteStore, 100, 10000);
        assertStoresMatch(aeronLogStore.getLocalStore(), aeronRemoteStore.getStore());
    }

    @Test
    @DisplayName("Multi-stream replication: 3 streams with correct counts")
    void testMultiStreamReplication() throws Exception {
        startBothStores();

        for (int i = 1; i <= 30; i++) {
            writeFixEntry(aeronLogStore, "SESSION-A", i, LogEntry.Direction.OUTBOUND, "msg-A-" + i);
        }
        for (int i = 1; i <= 20; i++) {
            writeFixEntry(aeronLogStore, "SESSION-B", i, LogEntry.Direction.INBOUND, "msg-B-" + i);
        }
        for (int i = 1; i <= 10; i++) {
            writeFixEntry(aeronLogStore, "SESSION-C", i, LogEntry.Direction.OUTBOUND, "msg-C-" + i);
        }

        awaitReplication(aeronRemoteStore, 60, 10000);

        ChronicleLogStore remote = aeronRemoteStore.getStore();
        assertEquals(3, remote.getStreamNames().size(), "Should have 3 streams");
        assertEquals(30, remote.getEntryCount("SESSION-A"));
        assertEquals(20, remote.getEntryCount("SESSION-B"));
        assertEquals(10, remote.getEntryCount("SESSION-C"));

        assertStoresMatch(aeronLogStore.getLocalStore(), remote);
    }

    @Test
    @DisplayName("Both directions preserved: INBOUND + OUTBOUND replicated")
    void testBothDirectionsPreserved() throws Exception {
        startBothStores();

        for (int i = 1; i <= 25; i++) {
            writeFixEntry(aeronLogStore, "BIDIR-SESSION", i, LogEntry.Direction.INBOUND, "in-" + i);
            writeFixEntry(aeronLogStore, "BIDIR-SESSION", i, LogEntry.Direction.OUTBOUND, "out-" + i);
        }

        awaitReplication(aeronRemoteStore, 50, 10000);

        // Replay by direction on remote
        ChronicleLogStore remote = aeronRemoteStore.getStore();
        long[] inboundCount = {0};
        long[] outboundCount = {0};
        remote.replay("BIDIR-SESSION", LogEntry.Direction.INBOUND, 0, 0, entry -> {
            inboundCount[0]++;
            return true;
        });
        remote.replay("BIDIR-SESSION", LogEntry.Direction.OUTBOUND, 0, 0, entry -> {
            outboundCount[0]++;
            return true;
        });

        assertEquals(25, inboundCount[0], "Should have 25 INBOUND entries");
        assertEquals(25, outboundCount[0], "Should have 25 OUTBOUND entries");
    }

    @Test
    @DisplayName("Raw message bytes preserved including SOH characters")
    void testRawMessageBytesPreserved() throws Exception {
        startBothStores();

        // FIX-like message with SOH delimiters
        byte[] fixMessage = "8=FIX.4.4\u000135=D\u000149=CLIENT\u000156=EXCHANGE\u000134=1\u000152=20240101-12:00:00\u000155=AAPL\u000154=1\u000144=150.50\u000138=100\u000140=2\u000110=123\u0001"
                .getBytes();

        writeFullEntry(aeronLogStore, "FIX-SESSION", 1, LogEntry.Direction.OUTBOUND,
                System.currentTimeMillis(), null, fixMessage);

        awaitReplication(aeronRemoteStore, 1, 10000);

        List<LogEntry> remoteEntries = replayAll(aeronRemoteStore.getStore(), "FIX-SESSION");
        assertEquals(1, remoteEntries.size());
        assertArrayEquals(fixMessage, remoteEntries.get(0).getRawMessage(),
                "Raw message bytes (including SOH) should be exactly preserved");
    }

    @Test
    @DisplayName("Metadata bytes preserved through replication")
    void testMetadataPreserved() throws Exception {
        startBothStores();

        byte[] metadata1 = "FIX.4.4|D|CLIENT->EXCHANGE".getBytes();
        byte[] metadata2 = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
        byte[] metadata3 = "session-info:heartbeat=30,reset=true".getBytes();

        writeFullEntry(aeronLogStore, "META-SESSION", 1, LogEntry.Direction.OUTBOUND,
                System.currentTimeMillis(), metadata1, "msg1".getBytes());
        writeFullEntry(aeronLogStore, "META-SESSION", 2, LogEntry.Direction.INBOUND,
                System.currentTimeMillis(), metadata2, "msg2".getBytes());
        writeFullEntry(aeronLogStore, "META-SESSION", 3, LogEntry.Direction.OUTBOUND,
                System.currentTimeMillis(), metadata3, "msg3".getBytes());

        awaitReplication(aeronRemoteStore, 3, 10000);

        List<LogEntry> remoteEntries = replayAll(aeronRemoteStore.getStore(), "META-SESSION");
        assertEquals(3, remoteEntries.size());
        assertArrayEquals(metadata1, remoteEntries.get(0).getMetadata());
        assertArrayEquals(metadata2, remoteEntries.get(1).getMetadata());
        assertArrayEquals(metadata3, remoteEntries.get(2).getMetadata());
    }

    @Test
    @DisplayName("Timestamps and sequence numbers preserved exactly")
    void testTimestampsAndSequenceNumbersPreserved() throws Exception {
        startBothStores();

        long ts1 = 1700000000000L;
        long ts2 = 1700000001000L;
        long ts3 = 1700000002000L;

        writeFullEntry(aeronLogStore, "TS-SESSION", 42, LogEntry.Direction.INBOUND,
                ts1, null, "entry-42".getBytes());
        writeFullEntry(aeronLogStore, "TS-SESSION", 100, LogEntry.Direction.OUTBOUND,
                ts2, null, "entry-100".getBytes());
        writeFullEntry(aeronLogStore, "TS-SESSION", Integer.MAX_VALUE, LogEntry.Direction.INBOUND,
                ts3, null, "entry-max".getBytes());

        awaitReplication(aeronRemoteStore, 3, 10000);

        List<LogEntry> remoteEntries = replayAll(aeronRemoteStore.getStore(), "TS-SESSION");
        assertEquals(3, remoteEntries.size());

        assertEquals(ts1, remoteEntries.get(0).getTimestamp());
        assertEquals(42, remoteEntries.get(0).getSequenceNumber());
        assertEquals(LogEntry.Direction.INBOUND, remoteEntries.get(0).getDirection());

        assertEquals(ts2, remoteEntries.get(1).getTimestamp());
        assertEquals(100, remoteEntries.get(1).getSequenceNumber());
        assertEquals(LogEntry.Direction.OUTBOUND, remoteEntries.get(1).getDirection());

        assertEquals(ts3, remoteEntries.get(2).getTimestamp());
        assertEquals(Integer.MAX_VALUE, remoteEntries.get(2).getSequenceNumber());
        assertEquals(LogEntry.Direction.INBOUND, remoteEntries.get(2).getDirection());
    }

    @Test
    @DisplayName("Catch-up sync: entries written before remote starts arrive via catch-up")
    void testCatchUpSync() throws Exception {
        // Start local store FIRST (remote is NOT running yet)
        PersistenceConfig persistenceConfig = PersistenceConfig.builder()
                .basePath(localCacheDir.toString())
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();

        String aeronConfig = String.format("""
                persistence {
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        publisher-id = 1
                        subscribers = [{ name = "primary", host = "localhost", data-port = %d, control-port = %d }]
                        local-endpoint { host = "0.0.0.0", replay-port = %d }
                        replay { timeout-ms = 10000, max-batch-size = 10000 }
                        heartbeat-interval-ms = 1000
                        idle-strategy = "sleeping"
                    }
                }
                """, dataPort, controlPort, replayPort);

        Config cfg = ConfigFactory.parseString(aeronConfig);
        AeronLogStoreConfig config = AeronLogStoreConfig.fromConfig(cfg.getConfig("persistence"));
        aeronLogStore = new AeronLogStore(persistenceConfig, config);
        aeronLogStore.initialize();
        aeronLogStore.startActive();

        // Write 50 entries while remote is down
        for (int i = 1; i <= 50; i++) {
            writeFixEntry(aeronLogStore, "CATCHUP-SESSION", i, LogEntry.Direction.OUTBOUND, "pre-" + i);
        }

        // Verify local has 50 entries
        assertEquals(50, aeronLogStore.getLocalStore().getEntryCount("CATCHUP-SESSION"));

        // Now start the remote store — catch-up should replay all 50
        startRemoteStore();

        // Wait for catch-up (the catch-up thread checks every 1 second, then replays)
        awaitReplication(aeronRemoteStore, 50, 15000);

        assertEquals(50, aeronRemoteStore.getStore().getEntryCount("CATCHUP-SESSION"));
        assertStoresMatch(aeronLogStore.getLocalStore(), aeronRemoteStore.getStore());
    }

    @Test
    @DisplayName("Large message (64KB) replicated intact")
    void testLargeMessageReplication() throws Exception {
        startBothStores();

        byte[] largeMessage = new byte[64 * 1024];
        Random random = new Random(12345);
        random.nextBytes(largeMessage);

        writeFullEntry(aeronLogStore, "LARGE-SESSION", 1, LogEntry.Direction.OUTBOUND,
                System.currentTimeMillis(), "large-meta".getBytes(), largeMessage);

        awaitReplication(aeronRemoteStore, 1, 10000);

        List<LogEntry> remoteEntries = replayAll(aeronRemoteStore.getStore(), "LARGE-SESSION");
        assertEquals(1, remoteEntries.size());
        assertArrayEquals(largeMessage, remoteEntries.get(0).getRawMessage(),
                "64KB message should be exactly preserved");
        assertArrayEquals("large-meta".getBytes(), remoteEntries.get(0).getMetadata());
    }

    @Test
    @DisplayName("High volume: 10,000 entries replicated correctly")
    void testHighVolumeReplication() throws Exception {
        startBothStores();

        int count = 10_000;
        for (int i = 1; i <= count; i++) {
            writeFixEntry(aeronLogStore, "VOLUME-SESSION", i,
                    i % 2 == 0 ? LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND,
                    "vol-" + i);
        }

        awaitReplication(aeronRemoteStore, count, 30000);

        assertEquals(count, aeronLogStore.getLocalStore().getEntryCount("VOLUME-SESSION"));
        assertEquals(count, aeronRemoteStore.getStore().getEntryCount("VOLUME-SESSION"));
    }

    @Test
    @DisplayName("Recovery from remote: rebuild local store via replay")
    void testRecoveryFromRemote() throws Exception {
        startBothStores();

        // Write 50 entries to both stores
        for (int i = 1; i <= 50; i++) {
            writeFixEntry(aeronLogStore, "RECOVER-SESSION", i, LogEntry.Direction.OUTBOUND, "recover-" + i);
        }

        awaitReplication(aeronRemoteStore, 50, 10000);

        // Verify both stores have 50
        assertEquals(50, aeronLogStore.getLocalStore().getEntryCount("RECOVER-SESSION"));
        assertEquals(50, aeronRemoteStore.getStore().getEntryCount("RECOVER-SESSION"));

        // Stop the local store
        aeronLogStore.stop();

        // Delete local cache
        deleteRecursive(localCacheDir.toFile());
        Files.createDirectories(localCacheDir);

        // Create new AeronLogStore with same config, fresh local cache
        PersistenceConfig persistenceConfig = PersistenceConfig.builder()
                .basePath(localCacheDir.toString())
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();

        String aeronConfigStr = String.format("""
                persistence {
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        publisher-id = 1
                        subscribers = [{ name = "primary", host = "localhost", data-port = %d, control-port = %d }]
                        local-endpoint { host = "0.0.0.0", replay-port = %d }
                        replay { timeout-ms = 10000, max-batch-size = 10000 }
                        heartbeat-interval-ms = 1000
                        idle-strategy = "sleeping"
                    }
                }
                """, dataPort, controlPort, replayPort);

        Config cfg = ConfigFactory.parseString(aeronConfigStr);
        AeronLogStoreConfig aeronCfg = AeronLogStoreConfig.fromConfig(cfg.getConfig("persistence"));
        aeronLogStore = new AeronLogStore(persistenceConfig, aeronCfg);
        aeronLogStore.initialize();
        aeronLogStore.startActive();

        // Give Aeron time to connect
        Thread.sleep(2000);

        // Verify local is empty before recovery
        assertEquals(0, aeronLogStore.getLocalStore().getEntryCount("RECOVER-SESSION"));

        // Recover from remote
        long recovered = aeronLogStore.recoverFromRemote(null);
        assertEquals(50, recovered, "Should recover 50 entries from remote");

        // Verify local now has all entries
        assertEquals(50, aeronLogStore.getLocalStore().getEntryCount("RECOVER-SESSION"));
    }

    @Test
    @DisplayName("Empty store: no entries, empty streams")
    void testEmptyStoreReplication() throws Exception {
        startBothStores();

        // Wait a bit to ensure no phantom entries
        Thread.sleep(2000);

        assertEquals(0, aeronRemoteStore.getEntriesReceived());
        assertTrue(aeronLogStore.getLocalStore().getStreamNames().isEmpty());
        assertTrue(aeronRemoteStore.getStore().getStreamNames().isEmpty());
    }

    @Test
    @DisplayName("Entry order preserved: sequence numbers arrive in order")
    void testEntryOrderPreserved() throws Exception {
        startBothStores();

        for (int i = 1; i <= 200; i++) {
            writeFixEntry(aeronLogStore, "ORDER-SESSION", i, LogEntry.Direction.OUTBOUND, "order-" + i);
        }

        awaitReplication(aeronRemoteStore, 200, 10000);

        List<LogEntry> remoteEntries = replayAll(aeronRemoteStore.getStore(), "ORDER-SESSION");
        assertEquals(200, remoteEntries.size());

        for (int i = 0; i < remoteEntries.size(); i++) {
            assertEquals(i + 1, remoteEntries.get(i).getSequenceNumber(),
                    "Entry " + i + " should have seqNum " + (i + 1));
            assertEquals("order-" + (i + 1), new String(remoteEntries.get(i).getRawMessage()),
                    "Entry " + i + " should have correct message body");
        }
    }
}
