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
 * Integration tests for multi-publisher support in Aeron remote persistence.
 *
 * <p>Verifies that multiple publishers writing to the same remote store have their
 * entries isolated by publisher-prefixed stream names, and that replay requests
 * correctly scope by publisher ID.</p>
 */
class MultiPublisherIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MultiPublisherIntegrationTest.class);

    // Port allocation: start high to avoid conflicts, increment by 4 per test
    // (data, control, replay1, replay2)
    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(42000);

    private Path localCacheDir1;
    private Path localCacheDir2;
    private Path remoteStoreDir;
    private AeronLogStore publisher1;
    private AeronLogStore publisher2;
    private AeronRemoteStore remoteStore;
    private int dataPort;
    private int controlPort;
    private int replayPort1;
    private int replayPort2;

    @BeforeEach
    void setUp() throws Exception {
        localCacheDir1 = Files.createTempDirectory("aeron-pub1-");
        localCacheDir2 = Files.createTempDirectory("aeron-pub2-");
        remoteStoreDir = Files.createTempDirectory("aeron-multi-remote-");

        int basePort = PORT_COUNTER.getAndAdd(4);
        dataPort = basePort;
        controlPort = basePort + 1;
        replayPort1 = basePort + 2;
        replayPort2 = basePort + 3;

        log.info("Test ports: data={}, control={}, replay1={}, replay2={}",
                dataPort, controlPort, replayPort1, replayPort2);
    }

    @AfterEach
    void tearDown() {
        stopQuietly(publisher1);
        stopQuietly(publisher2);
        stopQuietly(remoteStore);
        deleteRecursive(localCacheDir1.toFile());
        deleteRecursive(localCacheDir2.toFile());
        deleteRecursive(remoteStoreDir.toFile());
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("Two publishers write to same remote store, entries stored in separate prefixed streams")
    void testTwoPublishersIsolated() throws Exception {
        startAll();

        // Publisher 1 writes to SESSION-A
        for (int i = 1; i <= 10; i++) {
            writeEntry(publisher1, "SESSION-A", i, LogEntry.Direction.OUTBOUND, "pub1-" + i);
        }
        // Publisher 2 writes to SESSION-A (same stream name)
        for (int i = 1; i <= 10; i++) {
            writeEntry(publisher2, "SESSION-A", i, LogEntry.Direction.OUTBOUND, "pub2-" + i);
        }

        awaitReplication(remoteStore, 20, 15000);

        // Remote store should have entries under pub~1~SESSION-A and pub~2~SESSION-A
        ChronicleLogStore remote = remoteStore.getStore();
        Collection<String> streams = remote.getStreamNames();
        assertTrue(streams.contains("pub~1~SESSION-A"),
                "Should have pub~1~SESSION-A, got: " + streams);
        assertTrue(streams.contains("pub~2~SESSION-A"),
                "Should have pub~2~SESSION-A, got: " + streams);

        assertEquals(10, remote.getEntryCount("pub~1~SESSION-A"));
        assertEquals(10, remote.getEntryCount("pub~2~SESSION-A"));
    }

    @Test
    @DisplayName("Publisher-specific replay returns only that publisher's entries")
    void testPublisherSpecificReplay() throws Exception {
        startAll();

        // Both publishers write entries
        for (int i = 1; i <= 5; i++) {
            writeEntry(publisher1, "SESSION-X", i, LogEntry.Direction.OUTBOUND, "pub1-" + i);
            writeEntry(publisher2, "SESSION-X", i, LogEntry.Direction.OUTBOUND, "pub2-" + i);
        }

        awaitReplication(remoteStore, 10, 15000);

        // Stop publisher 1, clear its local store, restart, and recover
        publisher1.stop();
        deleteRecursive(localCacheDir1.toFile());
        Files.createDirectories(localCacheDir1);

        publisher1 = createPublisher(localCacheDir1, 1, replayPort1);
        publisher1.initialize();
        publisher1.startActive();
        Thread.sleep(2000);

        // Recovery should only pull publisher 1's entries
        long recovered = publisher1.recoverFromRemote(null);
        assertEquals(5, recovered, "Should recover only publisher 1's 5 entries");

        // Verify recovered entries are from publisher 1
        List<LogEntry> entries = replayAll(publisher1.getLocalStore(), "SESSION-X");
        assertEquals(5, entries.size());
        for (LogEntry entry : entries) {
            String body = new String(entry.getRawMessage());
            assertTrue(body.startsWith("pub1-"),
                    "Recovered entry should be from publisher 1: " + body);
        }
    }

    @Test
    @DisplayName("Replay with publisherId=0 returns all publishers' entries")
    void testReplayAllPublishers() throws Exception {
        startAll();

        for (int i = 1; i <= 5; i++) {
            writeEntry(publisher1, "SESSION-Y", i, LogEntry.Direction.OUTBOUND, "pub1-" + i);
            writeEntry(publisher2, "SESSION-Y", i, LogEntry.Direction.OUTBOUND, "pub2-" + i);
        }

        awaitReplication(remoteStore, 10, 15000);

        // Create a fresh store to replay into
        Path replayDir = Files.createTempDirectory("aeron-replay-all-");
        try {
            PersistenceConfig replayConfig = PersistenceConfig.builder()
                    .basePath(replayDir.toString())
                    .storeType(PersistenceConfig.StoreType.CHRONICLE)
                    .build();
            ChronicleLogStore replayStore = new ChronicleLogStore(replayConfig);
            replayStore.initialize();
            replayStore.startActive();

            // Request replay with publisherId=0 (all publishers)
            ReplayClient client = publisher1.getReplayClient();
            long replayed = client.requestReplay(replayStore, null,
                    com.omnibridge.persistence.aeron.codec.MessageTypes.DIRECTION_BOTH,
                    0, 0, 0, 0, 0, 0);
            assertEquals(10, replayed, "Should replay all 10 entries from both publishers");

            replayStore.stop();
        } finally {
            deleteRecursive(replayDir.toFile());
        }
    }

    @Test
    @DisplayName("Recovery only pulls own entries (not another publisher's)")
    void testRecoveryOnlyOwnEntries() throws Exception {
        startAll();

        // Publisher 1 writes 8 entries, Publisher 2 writes 12 entries
        for (int i = 1; i <= 8; i++) {
            writeEntry(publisher1, "RECOVER-SESSION", i, LogEntry.Direction.OUTBOUND, "pub1-" + i);
        }
        for (int i = 1; i <= 12; i++) {
            writeEntry(publisher2, "RECOVER-SESSION", i, LogEntry.Direction.OUTBOUND, "pub2-" + i);
        }

        awaitReplication(remoteStore, 20, 15000);

        // Restart publisher 2 with empty local
        publisher2.stop();
        deleteRecursive(localCacheDir2.toFile());
        Files.createDirectories(localCacheDir2);

        publisher2 = createPublisher(localCacheDir2, 2, replayPort2);
        publisher2.initialize();
        publisher2.startActive();
        Thread.sleep(2000);

        long recovered = publisher2.recoverFromRemote(null);
        assertEquals(12, recovered, "Publisher 2 should recover only its 12 entries");

        List<LogEntry> entries = replayAll(publisher2.getLocalStore(), "RECOVER-SESSION");
        assertEquals(12, entries.size());
        for (LogEntry entry : entries) {
            String body = new String(entry.getRawMessage());
            assertTrue(body.startsWith("pub2-"),
                    "Recovered entry should be from publisher 2: " + body);
        }
    }

    @Test
    @DisplayName("Per-publisher position tracking via getPublisherStates()")
    void testPublisherPositionTracking() throws Exception {
        startAll();

        for (int i = 1; i <= 15; i++) {
            writeEntry(publisher1, "TRACK-SESSION", i, LogEntry.Direction.OUTBOUND, "pub1-" + i);
        }
        for (int i = 1; i <= 25; i++) {
            writeEntry(publisher2, "TRACK-SESSION", i, LogEntry.Direction.OUTBOUND, "pub2-" + i);
        }

        awaitReplication(remoteStore, 40, 15000);

        Map<Long, AeronRemoteStore.PublisherState> states = remoteStore.getPublisherStates();

        assertTrue(states.containsKey(1L), "Should track publisher 1");
        assertTrue(states.containsKey(2L), "Should track publisher 2");
        assertEquals(15, states.get(1L).getEntriesReceived(), "Publisher 1 should have 15 entries");
        assertEquals(25, states.get(2L).getEntriesReceived(), "Publisher 2 should have 25 entries");
        assertEquals(15, states.get(1L).getLastSeqNum(), "Publisher 1 last seqNum should be 15");
        assertEquals(25, states.get(2L).getLastSeqNum(), "Publisher 2 last seqNum should be 25");
    }

    @Test
    @DisplayName("Catch-up sync replays only that publisher's entries after reconnect")
    void testCatchUpSyncMultiPublisher() throws Exception {
        // Start publisher 1 first (before remote)
        publisher1 = createPublisher(localCacheDir1, 1, replayPort1);
        publisher1.initialize();
        publisher1.startActive();

        // Write entries while remote is down
        for (int i = 1; i <= 10; i++) {
            writeEntry(publisher1, "CATCHUP-SESSION", i, LogEntry.Direction.OUTBOUND, "pub1-" + i);
        }

        // Now start remote
        startRemoteStore();
        Thread.sleep(2000); // Wait for catch-up sync

        // Start publisher 2 and write entries
        publisher2 = createPublisher(localCacheDir2, 2, replayPort2);
        publisher2.initialize();
        publisher2.startActive();
        Thread.sleep(1000);

        for (int i = 1; i <= 10; i++) {
            writeEntry(publisher2, "CATCHUP-SESSION", i, LogEntry.Direction.OUTBOUND, "pub2-" + i);
        }

        // Wait for all entries (10 from catch-up + 10 from publisher 2)
        awaitReplication(remoteStore, 20, 20000);

        ChronicleLogStore remote = remoteStore.getStore();
        assertEquals(10, remote.getEntryCount("pub~1~CATCHUP-SESSION"));
        assertEquals(10, remote.getEntryCount("pub~2~CATCHUP-SESSION"));
    }

    @Test
    @DisplayName("Replayed entries have original (unprefixed) stream names")
    void testStreamNameUnprefixedOnReplay() throws Exception {
        startAll();

        String originalStream = "FIX.4.4:SENDER->TARGET";
        for (int i = 1; i <= 3; i++) {
            writeEntry(publisher1, originalStream, i, LogEntry.Direction.OUTBOUND, "msg-" + i);
        }

        awaitReplication(remoteStore, 3, 15000);

        // Verify remote stores with prefix
        assertTrue(remoteStore.getStore().getStreamNames().contains("pub~1~" + originalStream));

        // Now recover — entries should come back with original stream name
        publisher1.stop();
        deleteRecursive(localCacheDir1.toFile());
        Files.createDirectories(localCacheDir1);

        publisher1 = createPublisher(localCacheDir1, 1, replayPort1);
        publisher1.initialize();
        publisher1.startActive();
        Thread.sleep(2000);

        long recovered = publisher1.recoverFromRemote(null);
        assertEquals(3, recovered);

        // Verify entries have original stream name (no prefix)
        List<LogEntry> entries = replayAll(publisher1.getLocalStore(), originalStream);
        assertEquals(3, entries.size());
        for (LogEntry entry : entries) {
            assertEquals(originalStream, entry.getStreamName(),
                    "Replayed entry should have original (unprefixed) stream name");
        }
    }

    // ==================== Helper Methods ====================

    private void startAll() throws Exception {
        startRemoteStore();
        publisher1 = createPublisher(localCacheDir1, 1, replayPort1);
        publisher2 = createPublisher(localCacheDir2, 2, replayPort2);
        publisher1.initialize();
        publisher1.startActive();
        publisher2.initialize();
        publisher2.startActive();
        Thread.sleep(1500);
    }

    private void startRemoteStore() throws Exception {
        String remoteConfig = String.format("""
                aeron-remote-store {
                    base-path = "%s"
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        listen { host = "0.0.0.0", data-port = %d, control-port = %d }
                        engines = [
                            { name = "engine-1", host = "localhost", replay-port = %d, publisher-id = 1 },
                            { name = "engine-2", host = "localhost", replay-port = %d, publisher-id = 2 }
                        ]
                        idle-strategy = "sleeping"
                        fragment-limit = 256
                    }
                }
                """, remoteStoreDir.toString().replace("\\", "/"),
                dataPort, controlPort, replayPort1, replayPort2);

        Config cfg = ConfigFactory.parseString(remoteConfig);
        AeronRemoteStoreConfig config = AeronRemoteStoreConfig.fromConfig(cfg.getConfig("aeron-remote-store"));
        remoteStore = new AeronRemoteStore(config);
        remoteStore.initialize();
        remoteStore.startActive();
    }

    private AeronLogStore createPublisher(Path cacheDir, long publisherId, int replayPort) {
        PersistenceConfig persistenceConfig = PersistenceConfig.builder()
                .basePath(cacheDir.toString())
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();

        String aeronConfig = String.format("""
                persistence {
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        publisher-id = %d
                        subscribers = [{ name = "primary", host = "localhost", data-port = %d, control-port = %d }]
                        local-endpoint { host = "0.0.0.0", replay-port = %d }
                        replay { timeout-ms = 10000, max-batch-size = 10000 }
                        heartbeat-interval-ms = 1000
                        idle-strategy = "sleeping"
                    }
                }
                """, publisherId, dataPort, controlPort, replayPort);

        Config cfg = ConfigFactory.parseString(aeronConfig);
        AeronLogStoreConfig config = AeronLogStoreConfig.fromConfig(cfg.getConfig("persistence"));
        return new AeronLogStore(persistenceConfig, config);
    }

    private void writeEntry(AeronLogStore store, String stream, int seqNum,
                             LogEntry.Direction direction, String body) {
        LogEntry entry = LogEntry.builder()
                .timestamp(System.currentTimeMillis())
                .direction(direction)
                .sequenceNumber(seqNum)
                .streamName(stream)
                .rawMessage(body.getBytes())
                .build();
        store.write(entry);
    }

    private void awaitReplication(AeronRemoteStore remote, long expectedCount, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (remote.getEntriesReceived() >= expectedCount) {
                Thread.sleep(200);
                return;
            }
            Thread.sleep(100);
        }
        fail("Replication timeout: expected " + expectedCount + " entries, got " +
                remote.getEntriesReceived() + " after " + timeoutMs + "ms");
    }

    private List<LogEntry> replayAll(ChronicleLogStore store, String stream) {
        List<LogEntry> entries = new ArrayList<>();
        store.replay(stream, null, 0, 0, entry -> {
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

    private static void stopQuietly(AeronLogStore store) {
        if (store != null) {
            try { store.stop(); } catch (Exception e) {
                log.warn("Error stopping AeronLogStore", e);
            }
        }
    }

    private static void stopQuietly(AeronRemoteStore store) {
        if (store != null) {
            try { store.stop(); } catch (Exception e) {
                log.warn("Error stopping AeronRemoteStore", e);
            }
        }
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
}
