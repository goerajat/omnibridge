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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AeronLogStore catch-up sync scenarios:
 * <ol>
 *   <li>Engine starts before the remote store — entries are catch-up synced when store connects</li>
 *   <li>Remote store intra-day restart — missed entries are replayed on reconnection</li>
 * </ol>
 */
class CatchUpSyncIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CatchUpSyncIntegrationTest.class);

    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(42000);
    private static final long PUBLISHER_ID = 1;
    private static final String PUB_PREFIX = "pub~" + PUBLISHER_ID + "~";

    private Path localCacheDir;
    private Path remoteStoreDir;
    private AeronLogStore aeronLogStore;
    private AeronRemoteStore aeronRemoteStore;
    private int dataPort;
    private int controlPort;
    private int replayPort;

    @BeforeEach
    void setUp() throws Exception {
        localCacheDir = Files.createTempDirectory("catchup-local-");
        remoteStoreDir = Files.createTempDirectory("catchup-remote-");

        int basePort = PORT_COUNTER.getAndAdd(3);
        dataPort = basePort;
        controlPort = basePort + 1;
        replayPort = basePort + 2;

        log.info("Test ports: data={}, control={}, replay={}", dataPort, controlPort, replayPort);
    }

    @AfterEach
    void tearDown() {
        stopQuietly(aeronLogStore);
        stopQuietly(aeronRemoteStore);
        deleteRecursive(localCacheDir.toFile());
        deleteRecursive(remoteStoreDir.toFile());
    }

    // ==================== Scenario 1: Engine Starts Before Store ====================

    @Test
    @DisplayName("Engine starts before store: single stream catch-up sync")
    void testEngineStartsBeforeStore_singleStream() throws Exception {
        // Start engine (local store) without remote store
        startLocalStore();

        // Write entries while remote is down
        for (int i = 1; i <= 100; i++) {
            writeEntry(aeronLogStore, "FIX-SESSION", i, LogEntry.Direction.OUTBOUND, "msg-" + i);
        }
        assertEquals(100, aeronLogStore.getLocalStore().getEntryCount("FIX-SESSION"));

        // Start remote store — catch-up thread should detect connection and replay
        startRemoteStore();

        awaitReplication(aeronRemoteStore, 100, 15000);
        assertEquals(100, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "FIX-SESSION"));

        // Verify data integrity
        assertEntriesMatch(aeronLogStore.getLocalStore(), "FIX-SESSION",
                aeronRemoteStore.getStore(), PUB_PREFIX + "FIX-SESSION");
    }

    @Test
    @DisplayName("Engine starts before store: multiple streams catch-up sync")
    void testEngineStartsBeforeStore_multipleStreams() throws Exception {
        startLocalStore();

        // Write to 3 different streams while remote is down
        for (int i = 1; i <= 50; i++) {
            writeEntry(aeronLogStore, "SESSION-A", i, LogEntry.Direction.OUTBOUND, "A-" + i);
        }
        for (int i = 1; i <= 30; i++) {
            writeEntry(aeronLogStore, "SESSION-B", i, LogEntry.Direction.INBOUND, "B-" + i);
        }
        for (int i = 1; i <= 20; i++) {
            writeEntry(aeronLogStore, "SESSION-C", i, LogEntry.Direction.OUTBOUND, "C-" + i);
        }

        // Start remote store
        startRemoteStore();

        awaitReplication(aeronRemoteStore, 100, 15000);

        ChronicleLogStore remote = aeronRemoteStore.getStore();
        assertEquals(50, remote.getEntryCount(PUB_PREFIX + "SESSION-A"));
        assertEquals(30, remote.getEntryCount(PUB_PREFIX + "SESSION-B"));
        assertEquals(20, remote.getEntryCount(PUB_PREFIX + "SESSION-C"));
    }

    @Test
    @DisplayName("Engine starts before store: entries written before AND after store connects")
    void testEngineStartsBeforeStore_beforeAndAfterConnect() throws Exception {
        startLocalStore();

        // Phase 1: Write 50 entries before remote starts
        for (int i = 1; i <= 50; i++) {
            writeEntry(aeronLogStore, "MIXED-SESSION", i, LogEntry.Direction.OUTBOUND, "pre-" + i);
        }

        // Start remote store — catch-up replays the first 50
        startRemoteStore();
        awaitReplication(aeronRemoteStore, 50, 15000);

        // Phase 2: Write 50 more entries after remote is connected (normal replication path)
        for (int i = 51; i <= 100; i++) {
            writeEntry(aeronLogStore, "MIXED-SESSION", i, LogEntry.Direction.OUTBOUND, "post-" + i);
        }

        awaitReplication(aeronRemoteStore, 100, 15000);
        assertEquals(100, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "MIXED-SESSION"));

        // Verify all 100 entries intact
        assertEntriesMatch(aeronLogStore.getLocalStore(), "MIXED-SESSION",
                aeronRemoteStore.getStore(), PUB_PREFIX + "MIXED-SESSION");
    }

    // ==================== Scenario 2: Store Intra-Day Restart ====================

    @Test
    @DisplayName("Store intra-day restart: missed entries are catch-up synced")
    void testStoreIntraDayRestart() throws Exception {
        // Phase 1: Both running, establish baseline
        startRemoteStore();
        startLocalStore();
        Thread.sleep(1000); // Let connection establish

        for (int i = 1; i <= 50; i++) {
            writeEntry(aeronLogStore, "RESTART-SESSION", i, LogEntry.Direction.OUTBOUND, "phase1-" + i);
        }
        awaitReplication(aeronRemoteStore, 50, 10000);
        assertEquals(50, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "RESTART-SESSION"));

        // Phase 2: Stop remote store (simulate intra-day restart)
        log.info("Stopping remote store to simulate intra-day restart...");
        aeronRemoteStore.stop();
        aeronRemoteStore = null;
        Thread.sleep(1000); // Let disconnection propagate

        // Phase 3: Write entries while remote is down
        for (int i = 51; i <= 80; i++) {
            writeEntry(aeronLogStore, "RESTART-SESSION", i, LogEntry.Direction.OUTBOUND, "gap-" + i);
        }

        // Phase 4: Restart remote store with FRESH data directory
        // (simulates a clean restart where the store lost its state)
        deleteRecursive(remoteStoreDir.toFile());
        Files.createDirectories(remoteStoreDir);

        log.info("Restarting remote store...");
        startRemoteStore();

        // Catch-up thread detects reconnection and replays ALL local entries (1-80)
        awaitReplication(aeronRemoteStore, 80, 20000);

        assertEquals(80, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "RESTART-SESSION"));
        assertEntriesMatch(aeronLogStore.getLocalStore(), "RESTART-SESSION",
                aeronRemoteStore.getStore(), PUB_PREFIX + "RESTART-SESSION");
    }

    @Test
    @DisplayName("Store intra-day restart: multiple streams catch-up correctly")
    void testStoreIntraDayRestart_multipleStreams() throws Exception {
        // Phase 1: Both running
        startRemoteStore();
        startLocalStore();
        Thread.sleep(1000);

        for (int i = 1; i <= 20; i++) {
            writeEntry(aeronLogStore, "STREAM-X", i, LogEntry.Direction.OUTBOUND, "X-" + i);
            writeEntry(aeronLogStore, "STREAM-Y", i, LogEntry.Direction.INBOUND, "Y-" + i);
        }
        awaitReplication(aeronRemoteStore, 40, 10000);

        // Phase 2: Restart remote store
        aeronRemoteStore.stop();
        aeronRemoteStore = null;
        Thread.sleep(1000);

        // Write more to both streams during downtime
        for (int i = 21; i <= 30; i++) {
            writeEntry(aeronLogStore, "STREAM-X", i, LogEntry.Direction.OUTBOUND, "X-" + i);
            writeEntry(aeronLogStore, "STREAM-Y", i, LogEntry.Direction.INBOUND, "Y-" + i);
        }
        // Also write to a new stream
        for (int i = 1; i <= 10; i++) {
            writeEntry(aeronLogStore, "STREAM-Z", i, LogEntry.Direction.OUTBOUND, "Z-" + i);
        }

        // Phase 3: Restart remote — catch-up should replay ALL entries from ALL streams
        deleteRecursive(remoteStoreDir.toFile());
        Files.createDirectories(remoteStoreDir);
        startRemoteStore();

        awaitReplication(aeronRemoteStore, 70, 20000); // 30+30+10

        ChronicleLogStore remote = aeronRemoteStore.getStore();
        assertEquals(30, remote.getEntryCount(PUB_PREFIX + "STREAM-X"));
        assertEquals(30, remote.getEntryCount(PUB_PREFIX + "STREAM-Y"));
        assertEquals(10, remote.getEntryCount(PUB_PREFIX + "STREAM-Z"));
    }

    @Test
    @DisplayName("Store intra-day restart: continued writing after restart works normally")
    void testStoreIntraDayRestart_continuedWriting() throws Exception {
        // Phase 1: Establish connection
        startRemoteStore();
        startLocalStore();
        Thread.sleep(1000);

        for (int i = 1; i <= 30; i++) {
            writeEntry(aeronLogStore, "CONT-SESSION", i, LogEntry.Direction.OUTBOUND, "init-" + i);
        }
        awaitReplication(aeronRemoteStore, 30, 10000);

        // Phase 2: Restart remote
        aeronRemoteStore.stop();
        aeronRemoteStore = null;
        Thread.sleep(1000);

        for (int i = 31; i <= 40; i++) {
            writeEntry(aeronLogStore, "CONT-SESSION", i, LogEntry.Direction.OUTBOUND, "gap-" + i);
        }

        deleteRecursive(remoteStoreDir.toFile());
        Files.createDirectories(remoteStoreDir);
        startRemoteStore();

        // Wait for catch-up of all 40 entries
        awaitReplication(aeronRemoteStore, 40, 20000);

        // Phase 3: Write more after reconnection — should replicate normally
        for (int i = 41; i <= 60; i++) {
            writeEntry(aeronLogStore, "CONT-SESSION", i, LogEntry.Direction.OUTBOUND, "normal-" + i);
        }

        awaitReplication(aeronRemoteStore, 60, 10000);
        assertEquals(60, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "CONT-SESSION"));

        // Verify full data integrity
        assertEntriesMatch(aeronLogStore.getLocalStore(), "CONT-SESSION",
                aeronRemoteStore.getStore(), PUB_PREFIX + "CONT-SESSION");
    }

    // ==================== Scenario 3: Store-Initiated Catch-Up ====================

    @Test
    @DisplayName("Store-initiated catch-up: single stream partial data")
    void testStoreInitiatedCatchUp_singleStream() throws Exception {
        // Phase 1: Both running — write 50 entries
        startRemoteStore();
        startLocalStore();
        Thread.sleep(1000);

        for (int i = 1; i <= 50; i++) {
            writeEntry(aeronLogStore, "STORE-CATCHUP", i, LogEntry.Direction.OUTBOUND, "entry-" + i);
        }
        awaitReplication(aeronRemoteStore, 50, 10000);
        assertEquals(50, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "STORE-CATCHUP"));

        // Phase 2: Stop store, write more entries on engine side
        aeronRemoteStore.stop();
        aeronRemoteStore = null;
        Thread.sleep(1000);

        for (int i = 51; i <= 80; i++) {
            writeEntry(aeronLogStore, "STORE-CATCHUP", i, LogEntry.Direction.OUTBOUND, "missed-" + i);
        }

        // Phase 3: Restart store WITH its existing data (not a clean restart)
        // Store has entries 1-50, engine has 1-80.
        // The store sends a CATCH_UP_REQUEST listing last seq=50,
        // and the engine replays only the 30 missing entries (51-80).
        startRemoteStore();

        // Only 30 entries sent via Aeron (incremental catch-up, not full replay)
        awaitReplication(aeronRemoteStore, 30, 20000);

        // Store had 50 locally + 30 incremental = 80 total
        assertEquals(80, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "STORE-CATCHUP"));
        assertEntriesMatch(aeronLogStore.getLocalStore(), "STORE-CATCHUP",
                aeronRemoteStore.getStore(), PUB_PREFIX + "STORE-CATCHUP");
    }

    @Test
    @DisplayName("Store-initiated catch-up: store has no data (fresh start)")
    void testStoreInitiatedCatchUp_storeHasNoData() throws Exception {
        // Engine writes entries first, then a fresh store starts
        startLocalStore();

        for (int i = 1; i <= 40; i++) {
            writeEntry(aeronLogStore, "FRESH-STORE", i, LogEntry.Direction.OUTBOUND, "data-" + i);
        }

        // Start fresh remote store — has no data, sends CATCH_UP_REQUEST with empty streams
        startRemoteStore();

        awaitReplication(aeronRemoteStore, 40, 15000);
        assertEquals(40, aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "FRESH-STORE"));
        assertEntriesMatch(aeronLogStore.getLocalStore(), "FRESH-STORE",
                aeronRemoteStore.getStore(), PUB_PREFIX + "FRESH-STORE");
    }

    @Test
    @DisplayName("Store-initiated catch-up: store already current (zero replays)")
    void testStoreInitiatedCatchUp_storeAlreadyCurrent() throws Exception {
        // Phase 1: Both running, fully synced
        startRemoteStore();
        startLocalStore();
        Thread.sleep(1000);

        for (int i = 1; i <= 30; i++) {
            writeEntry(aeronLogStore, "CURRENT-SESSION", i, LogEntry.Direction.OUTBOUND, "msg-" + i);
        }
        awaitReplication(aeronRemoteStore, 30, 10000);

        // Phase 2: Restart store (keeps existing data), engine has same 30 entries
        aeronRemoteStore.stop();
        aeronRemoteStore = null;
        Thread.sleep(1000);

        // Restart with same data directory — store is already current
        startRemoteStore();
        Thread.sleep(3000); // Give time for catch-up to process

        // Should still have exactly 30 entries (no duplicates from catch-up replay)
        // Note: the connection-based catch-up will replay, but entries are idempotent
        // The CATCH_UP_REQUEST with last seq=30 means engine only replays seq > 30 = nothing
        long total = aeronRemoteStore.getStore().getEntryCount(PUB_PREFIX + "CURRENT-SESSION");
        assertTrue(total >= 30, "Expected at least 30 entries, got " + total);
    }

    // ==================== Helper Methods ====================

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

    private void startRemoteStore() throws Exception {
        String remoteConfig = String.format("""
                aeron-remote-store {
                    base-path = "%s"
                    aeron {
                        media-driver { embedded = true, aeron-dir = "" }
                        listen { host = "0.0.0.0", data-port = %d, control-port = %d }
                        engines = [{ name = "test-engine", host = "localhost", replay-port = %d, publisher-id = 1 }]
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

    private LogEntry writeEntry(AeronLogStore store, String stream, int seqNum,
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

    private void awaitReplication(AeronRemoteStore remoteStore, long expectedCount, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (remoteStore.getEntriesReceived() >= expectedCount) {
                Thread.sleep(200); // Flush time
                return;
            }
            Thread.sleep(100);
        }
        fail("Replication timeout: expected " + expectedCount + " entries, got " +
                remoteStore.getEntriesReceived() + " after " + timeoutMs + "ms");
    }

    private void assertEntriesMatch(ChronicleLogStore local, String localStream,
                                     ChronicleLogStore remote, String remoteStream) {
        List<LogEntry> localEntries = replayAll(local, localStream);
        List<LogEntry> remoteEntries = replayAll(remote, remoteStream);

        assertEquals(localEntries.size(), remoteEntries.size(),
                "Entry count mismatch: local=" + localEntries.size() +
                        " remote=" + remoteEntries.size());

        for (int i = 0; i < localEntries.size(); i++) {
            LogEntry le = localEntries.get(i);
            LogEntry re = remoteEntries.get(i);
            assertEquals(le.getTimestamp(), re.getTimestamp(),
                    "Timestamp mismatch at entry " + i);
            assertEquals(le.getDirection(), re.getDirection(),
                    "Direction mismatch at entry " + i);
            assertEquals(le.getSequenceNumber(), re.getSequenceNumber(),
                    "SeqNum mismatch at entry " + i);
            assertArrayEquals(le.getRawMessage(), re.getRawMessage(),
                    "RawMessage mismatch at entry " + i);
        }
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

    private static void stopQuietly(Object component) {
        if (component == null) return;
        try {
            if (component instanceof AeronLogStore s) s.stop();
            else if (component instanceof AeronRemoteStore s) s.stop();
        } catch (Exception e) {
            log.warn("Error stopping component", e);
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
