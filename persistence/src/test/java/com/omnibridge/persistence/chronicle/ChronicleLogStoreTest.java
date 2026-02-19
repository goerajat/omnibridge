package com.omnibridge.persistence.chronicle;

import com.omnibridge.config.ComponentState;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChronicleLogStore} and its LogReader implementations.
 */
class ChronicleLogStoreTest {

    private Path tempDir;
    private ChronicleLogStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("chronicle-test");
        store = new ChronicleLogStore(tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) {
            store.close();
        }
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    // ==================== Write & Replay ====================

    @Test
    void testWriteAndReplay() {
        writeEntry("stream-1", 1, "message-1");
        writeEntry("stream-1", 2, "message-2");
        writeEntry("stream-1", 3, "message-3");

        List<Integer> seqNums = new ArrayList<>();
        long count = store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(3, count);
        assertEquals(List.of(1, 2, 3), seqNums);
    }

    @Test
    void testReplayByDirection() {
        writeEntry("stream-1", 1, "msg-1", LogEntry.Direction.INBOUND);
        writeEntry("stream-1", 2, "msg-2", LogEntry.Direction.OUTBOUND);
        writeEntry("stream-1", 3, "msg-3", LogEntry.Direction.INBOUND);

        List<Integer> inbound = new ArrayList<>();
        store.replay("stream-1", LogEntry.Direction.INBOUND, 0, 0, entry -> {
            inbound.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(List.of(1, 3), inbound);
    }

    @Test
    void testReplayBySeqNum() {
        for (int i = 1; i <= 5; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", null, 2, 4, entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(List.of(2, 3, 4), seqNums);
    }

    @Test
    void testReplayByTime() throws InterruptedException {
        writeEntry("stream-1", 1, "msg-1");
        Thread.sleep(50);
        long startTime = System.currentTimeMillis();
        writeEntry("stream-1", 2, "msg-2");
        writeEntry("stream-1", 3, "msg-3");

        List<Integer> seqNums = new ArrayList<>();
        store.replayByTime("stream-1", null, startTime, 0, entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(List.of(2, 3), seqNums);
    }

    // ==================== Query ====================

    @Test
    void testGetLatest() {
        writeEntry("stream-1", 1, "msg-1", LogEntry.Direction.INBOUND);
        writeEntry("stream-1", 2, "msg-2", LogEntry.Direction.OUTBOUND);
        writeEntry("stream-1", 3, "msg-3", LogEntry.Direction.INBOUND);

        LogEntry latest = store.getLatest("stream-1", LogEntry.Direction.INBOUND);
        assertNotNull(latest);
        assertEquals(3, latest.getSequenceNumber());

        LogEntry latestOut = store.getLatest("stream-1", LogEntry.Direction.OUTBOUND);
        assertNotNull(latestOut);
        assertEquals(2, latestOut.getSequenceNumber());

        LogEntry latestAny = store.getLatest("stream-1", null);
        assertNotNull(latestAny);
        assertEquals(3, latestAny.getSequenceNumber());
    }

    @Test
    void testGetLatestNonExistentStream() {
        assertNull(store.getLatest("no-such-stream", null));
    }

    @Test
    void testEntryCount() {
        assertEquals(0, store.getEntryCount("stream-1"));
        assertEquals(0, store.getEntryCount(null));

        writeEntry("stream-1", 1, "msg-1");
        writeEntry("stream-1", 2, "msg-2");
        writeEntry("stream-2", 1, "msg-1");

        assertEquals(2, store.getEntryCount("stream-1"));
        assertEquals(1, store.getEntryCount("stream-2"));
        assertEquals(3, store.getEntryCount(null));
    }

    @Test
    void testStreamNames() {
        assertTrue(store.getStreamNames().isEmpty());

        writeEntry("stream-1", 1, "msg-1");
        writeEntry("stream-2", 1, "msg-1");

        assertEquals(2, store.getStreamNames().size());
        assertTrue(store.getStreamNames().contains("stream-1"));
        assertTrue(store.getStreamNames().contains("stream-2"));
    }

    @Test
    void testStorePath() {
        assertEquals(tempDir.toFile().getAbsolutePath(), store.getStorePath());
    }

    // ==================== Metadata ====================

    @Test
    void testMetadata() {
        store.write(LogEntry.builder()
                .streamName("stream-1")
                .sequenceNumber(1)
                .timestampNow()
                .inbound()
                .metadata("test-meta")
                .rawMessage("message".getBytes())
                .build());

        List<String> metas = new ArrayList<>();
        store.replay("stream-1", entry -> {
            metas.add(entry.getMetadataString());
            return true;
        });

        assertEquals(1, metas.size());
        assertEquals("test-meta", metas.get(0));
    }

    // ==================== Polling API ====================

    @Test
    void testPollFromStart() {
        writeEntry("stream-1", 1, "message-1");
        writeEntry("stream-1", 2, "message-2");
        writeEntry("stream-1", 3, "message-3");

        try (LogReader reader = store.createReader("stream-1")) {
            assertEquals("stream-1", reader.getStreamName());

            LogEntry entry1 = reader.poll();
            assertNotNull(entry1);
            assertEquals(1, entry1.getSequenceNumber());

            LogEntry entry2 = reader.poll();
            assertNotNull(entry2);
            assertEquals(2, entry2.getSequenceNumber());

            LogEntry entry3 = reader.poll();
            assertNotNull(entry3);
            assertEquals(3, entry3.getSequenceNumber());

            assertNull(reader.poll());
        }
    }

    @Test
    void testPollFromEnd() {
        writeEntry("stream-1", 1, "message-1");
        writeEntry("stream-1", 2, "message-2");

        try (LogReader reader = store.createReader("stream-1", LogReader.END)) {
            // Existing entries should be skipped
            assertNull(reader.poll());

            // Write new entry
            writeEntry("stream-1", 3, "message-3");

            LogEntry entry = reader.poll();
            assertNotNull(entry);
            assertEquals(3, entry.getSequenceNumber());
        }
    }

    @Test
    void testPollWithTimeout() {
        writeEntry("stream-1", 1, "message-1");

        try (LogReader reader = store.createReader("stream-1")) {
            LogEntry entry = reader.poll(1000);
            assertNotNull(entry);
            assertEquals(1, entry.getSequenceNumber());

            // Second poll should timeout
            long start = System.currentTimeMillis();
            LogEntry nothing = reader.poll(100);
            long elapsed = System.currentTimeMillis() - start;

            assertNull(nothing);
            assertTrue(elapsed >= 80, "Should have waited at least 80ms, actual: " + elapsed);
        }
    }

    @Test
    void testPollBatch() {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "message-" + i);
        }

        try (LogReader reader = store.createReader("stream-1")) {
            List<Integer> seqNums = new ArrayList<>();

            int count = reader.poll(5, 0, entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(5, count);
            assertEquals(List.of(1, 2, 3, 4, 5), seqNums);

            // Read remaining
            seqNums.clear();
            count = reader.poll(10, 0, entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(5, count);
            assertEquals(List.of(6, 7, 8, 9, 10), seqNums);
        }
    }

    @Test
    void testPollWithCallbackStop() {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "message-" + i);
        }

        try (LogReader reader = store.createReader("stream-1")) {
            AtomicInteger count = new AtomicInteger();

            reader.poll(10, 0, entry -> {
                count.incrementAndGet();
                return entry.getSequenceNumber() < 3;
            });

            assertEquals(3, count.get());
        }
    }

    @Test
    void testSetPosition() {
        for (int i = 1; i <= 5; i++) {
            writeEntry("stream-1", i, "message-" + i);
        }

        try (LogReader reader = store.createReader("stream-1")) {
            reader.poll();
            reader.poll();
            long posAfterTwo = reader.getPosition();

            LogEntry third = reader.poll();
            assertEquals(3, third.getSequenceNumber());

            // Reset to position after two
            reader.setPosition(posAfterTwo);
            LogEntry thirdAgain = reader.poll();
            assertEquals(3, thirdAgain.getSequenceNumber());

            // Reset to start
            reader.setPosition(LogReader.START);
            LogEntry first = reader.poll();
            assertEquals(1, first.getSequenceNumber());
        }
    }

    @Test
    void testConcurrentWriteAndPoll() throws InterruptedException {
        CountDownLatch writerDone = new CountDownLatch(1);
        CountDownLatch readerDone = new CountDownLatch(1);
        AtomicInteger readCount = new AtomicInteger();

        Thread readerThread = new Thread(() -> {
            try (LogReader reader = store.createReader("stream-1", LogReader.END)) {
                while (readCount.get() < 100) {
                    LogEntry entry = reader.poll(500);
                    if (entry != null) {
                        readCount.incrementAndGet();
                    }
                    if (readCount.get() >= 100) break;
                }
            }
            readerDone.countDown();
        });
        readerThread.start();

        Thread.sleep(50);

        Thread writerThread = new Thread(() -> {
            for (int i = 1; i <= 100; i++) {
                writeEntry("stream-1", i, "message-" + i);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    break;
                }
            }
            writerDone.countDown();
        });
        writerThread.start();

        assertTrue(writerDone.await(15, TimeUnit.SECONDS), "Writer should complete");
        assertTrue(readerDone.await(15, TimeUnit.SECONDS), "Reader should complete");

        assertEquals(100, readCount.get());
    }

    @Test
    void testTryPollNonBlocking() {
        writeEntry("stream-1", 1, "message-1");

        try (LogReader reader = store.createReader("stream-1")) {
            LogEntry entry = reader.tryPoll();
            assertNotNull(entry);
            assertEquals(1, entry.getSequenceNumber());

            long start = System.currentTimeMillis();
            LogEntry nothing = reader.tryPoll();
            long elapsed = System.currentTimeMillis() - start;

            assertNull(nothing);
            assertTrue(elapsed < 50, "tryPoll should not block");
        }
    }

    @Test
    void testDrain() {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "message-" + i);
        }

        try (LogReader reader = store.createReader("stream-1")) {
            List<Integer> seqNums = new ArrayList<>();

            int count = reader.drain(entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(10, count);
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), seqNums);

            count = reader.drain(entry -> true);
            assertEquals(0, count);
        }
    }

    @Test
    void testDrainWithLimit() {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "message-" + i);
        }

        try (LogReader reader = store.createReader("stream-1")) {
            List<Integer> seqNums = new ArrayList<>();

            int count = reader.drain(3, entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(3, count);
            assertEquals(List.of(1, 2, 3), seqNums);

            seqNums.clear();
            count = reader.drain(entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(7, count);
            assertEquals(List.of(4, 5, 6, 7, 8, 9, 10), seqNums);
        }
    }

    @Test
    void testDrainNonBlocking() {
        try (LogReader reader = store.createReader("stream-1")) {
            long start = System.currentTimeMillis();
            int count = reader.drain(entry -> true);
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(0, count);
            assertTrue(elapsed < 50, "drain should not block on empty stream");
        }
    }

    @Test
    void testAllStreamsReader() {
        writeEntry("stream-1", 1, "s1-m1");
        writeEntry("stream-2", 1, "s2-m1");
        writeEntry("stream-1", 2, "s1-m2");
        writeEntry("stream-2", 2, "s2-m2");

        try (LogReader reader = store.createReader()) {
            assertNull(reader.getStreamName());

            List<String> messages = new ArrayList<>();
            LogEntry entry;
            while ((entry = reader.poll()) != null) {
                messages.add(entry.getStreamName() + "-" + entry.getSequenceNumber());
            }

            assertEquals(4, messages.size());
        }
    }

    // ==================== Component Lifecycle ====================

    @Test
    void testComponentLifecycle() throws Exception {
        assertEquals(ComponentState.UNINITIALIZED, store.getState());
        assertEquals("persistence-store", store.getName());

        store.initialize();
        assertEquals(ComponentState.INITIALIZED, store.getState());

        store.startActive();
        assertEquals(ComponentState.ACTIVE, store.getState());
        assertTrue(store.isActive());

        store.becomeStandby();
        assertEquals(ComponentState.STANDBY, store.getState());
        assertTrue(store.isStandby());

        store.becomeActive();
        assertEquals(ComponentState.ACTIVE, store.getState());

        store.stop();
        assertEquals(ComponentState.STOPPED, store.getState());
    }

    @Test
    void testComponentLifecycleStandbyStart() throws Exception {
        store.initialize();
        store.startStandby();
        assertEquals(ComponentState.STANDBY, store.getState());

        store.becomeActive();
        assertEquals(ComponentState.ACTIVE, store.getState());
    }

    @Test
    void testInvalidStateTransition() {
        assertThrows(IllegalStateException.class, () -> store.startActive());
        assertThrows(IllegalStateException.class, () -> store.becomeActive());
    }

    // ==================== Persistence / Reload ====================

    @Test
    void testReloadAfterClose() throws IOException {
        writeEntry("stream-1", 1, "msg-1");
        writeEntry("stream-1", 2, "msg-2");
        writeEntry("stream-2", 1, "msg-1");

        store.close();

        // Re-open
        store = new ChronicleLogStore(tempDir.toFile());

        assertEquals(2, store.getEntryCount("stream-1"));
        assertEquals(1, store.getEntryCount("stream-2"));
        assertEquals(3, store.getEntryCount(null));
        assertEquals(2, store.getStreamNames().size());

        // Replay should work
        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });
        assertEquals(List.of(1, 2), seqNums);
    }

    // ==================== Helpers ====================

    private void writeEntry(String stream, int seqNum, String message) {
        writeEntry(stream, seqNum, message, LogEntry.Direction.INBOUND);
    }

    private void writeEntry(String stream, int seqNum, String message, LogEntry.Direction direction) {
        store.write(LogEntry.builder()
                .streamName(stream)
                .sequenceNumber(seqNum)
                .timestampNow()
                .direction(direction)
                .rawMessage(message.getBytes())
                .build());
    }
}
