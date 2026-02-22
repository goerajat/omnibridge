package com.omnibridge.persistence.chronicle;

import com.omnibridge.persistence.Decoder;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ChronicleLogStore} covering binary format edge cases,
 * replay paths, durability, concurrency, decoder integration, performance, and lifecycle.
 */
class ChronicleLogStoreIntegrationTest {

    private Path tempDir;
    private ChronicleLogStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("chronicle-integration-test");
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

    // ==================== Binary Format Edge Cases ====================

    @Test
    void testEmptyRawMessage() {
        store.write(LogEntry.builder()
                .streamName("stream-1")
                .sequenceNumber(1)
                .timestampNow()
                .inbound()
                .rawMessage(new byte[0])
                .build());

        List<byte[]> messages = new ArrayList<>();
        long count = store.replay("stream-1", entry -> {
            messages.add(entry.getRawMessage());
            return true;
        });

        assertEquals(1, count);
        // Empty raw message is stored as null internally (rawLen == 0 → null on read)
        assertNull(messages.get(0));
    }

    @Test
    void testEmptyMetadata() {
        store.write(LogEntry.builder()
                .streamName("stream-1")
                .sequenceNumber(1)
                .timestampNow()
                .inbound()
                .rawMessage("test-message".getBytes())
                .build());

        List<String> metadatas = new ArrayList<>();
        store.replay("stream-1", entry -> {
            metadatas.add(entry.getMetadataString());
            return true;
        });

        assertEquals(1, metadatas.size());
        assertNull(metadatas.get(0));
    }

    @Test
    void testLargeMessagePersistence() {
        // 64KB message exceeds the default 4096B read buffer
        byte[] largeMessage = new byte[65536];
        for (int i = 0; i < largeMessage.length; i++) {
            largeMessage[i] = (byte) (i % 256);
        }

        store.write(LogEntry.builder()
                .streamName("stream-1")
                .sequenceNumber(1)
                .timestampNow()
                .inbound()
                .rawMessage(largeMessage)
                .build());

        List<byte[]> messages = new ArrayList<>();
        store.replay("stream-1", entry -> {
            messages.add(entry.getRawMessage());
            return true;
        });

        assertEquals(1, messages.size());
        assertArrayEquals(largeMessage, messages.get(0));
    }

    @Test
    void testSpecialCharactersInStreamName() {
        String streamName = "CLIENT->EXCHANGE";
        writeEntry(streamName, 1, "msg-1");
        writeEntry(streamName, 2, "msg-2");

        assertEquals(2, store.getEntryCount(streamName));

        List<Integer> seqNums = new ArrayList<>();
        store.replay(streamName, entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(List.of(1, 2), seqNums);
        assertTrue(store.getStreamNames().contains(streamName));
    }

    @Test
    void testMaxSequenceNumber() {
        writeEntry("stream-1", Integer.MAX_VALUE, "max-seq");

        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(1, seqNums.size());
        assertEquals(Integer.MAX_VALUE, seqNums.get(0));
    }

    @Test
    void testTimestampOrdering() {
        // Write entries with descending timestamps in same stream
        long now = System.currentTimeMillis();
        store.write(LogEntry.builder()
                .streamName("stream-1").sequenceNumber(1).timestamp(now + 1000)
                .inbound().rawMessage("later".getBytes()).build());
        store.write(LogEntry.builder()
                .streamName("stream-1").sequenceNumber(2).timestamp(now)
                .inbound().rawMessage("earlier".getBytes()).build());
        store.write(LogEntry.builder()
                .streamName("stream-1").sequenceNumber(3).timestamp(now + 500)
                .inbound().rawMessage("middle".getBytes()).build());

        // Replay should preserve write order (not timestamp order)
        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(List.of(1, 2, 3), seqNums);
    }

    // ==================== Replay Integration ====================

    @Test
    void testResendReplayOutboundBySeqRange() {
        // Simulate FIX session outbound messages seqnums 1-10
        for (int i = 1; i <= 10; i++) {
            writeEntry("session-1", i, "msg-" + i, LogEntry.Direction.OUTBOUND);
        }

        // Replay range 3-7 (mirrors FixSession.processResendRequest)
        List<Integer> seqNums = new ArrayList<>();
        long count = store.replay("session-1", LogEntry.Direction.OUTBOUND, 3, 7, entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(5, count);
        assertEquals(List.of(3, 4, 5, 6, 7), seqNums);
    }

    @Test
    void testResendReplayMixedDirections() {
        // Write interleaved INBOUND/OUTBOUND
        for (int i = 1; i <= 10; i++) {
            LogEntry.Direction dir = (i % 2 == 0) ? LogEntry.Direction.OUTBOUND : LogEntry.Direction.INBOUND;
            writeEntry("session-1", i, "msg-" + i, dir);
        }

        // Replay only OUTBOUND in range 1-10
        List<Integer> seqNums = new ArrayList<>();
        store.replay("session-1", LogEntry.Direction.OUTBOUND, 1, 10, entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        // Only even seqnums are OUTBOUND
        assertEquals(List.of(2, 4, 6, 8, 10), seqNums);
    }

    @Test
    void testReplayEmptyRange() {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        // Replay a range that doesn't exist
        AtomicInteger count = new AtomicInteger();
        store.replay("stream-1", null, 100, 200, entry -> {
            count.incrementAndGet();
            return true;
        });

        assertEquals(0, count.get());
    }

    @Test
    void testReplayNonExistentStream() {
        long count = store.replay("no-such-stream", entry -> {
            fail("Should not receive entries from non-existent stream");
            return true;
        });

        assertEquals(0, count);
    }

    // ==================== Durability & Recovery ====================

    @Test
    void testWriteCloseReopenReplay() throws IOException {
        // Write 1000 entries across 3 streams
        for (int i = 1; i <= 1000; i++) {
            String stream = "stream-" + ((i % 3) + 1);
            writeEntry(stream, i, "message-" + i);
        }

        store.close();

        // Reopen
        store = new ChronicleLogStore(tempDir.toFile());

        // Verify counts
        long total = store.getEntryCount(null);
        assertEquals(1000, total);

        // Verify full replay content
        for (int s = 1; s <= 3; s++) {
            String stream = "stream-" + s;
            List<Integer> seqNums = new ArrayList<>();
            store.replay(stream, entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });
            assertFalse(seqNums.isEmpty(), "Stream " + stream + " should have entries");
        }
    }

    @Test
    void testAppendAfterReopen() throws IOException {
        // Write initial entries
        for (int i = 1; i <= 5; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        store.close();

        // Reopen and write more
        store = new ChronicleLogStore(tempDir.toFile());
        for (int i = 6; i <= 10; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        // Verify all entries (old + new) are replayable in correct order
        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });

        assertEquals(10, seqNums.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), seqNums);
    }

    @Test
    void testSyncDurability() throws IOException {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        store.sync();
        store.close();

        // Reopen and verify data present
        store = new ChronicleLogStore(tempDir.toFile());
        assertEquals(10, store.getEntryCount("stream-1"));
    }

    @Test
    void testDoubleClose() throws IOException {
        writeEntry("stream-1", 1, "msg-1");

        store.close();
        // Second close should not throw
        assertDoesNotThrow(() -> store.close());
        // Prevent tearDown from closing again
        store = null;
    }

    // ==================== Concurrent Access ====================

    @Test
    void testMultiWriterDifferentStreams() throws InterruptedException {
        // Each thread writes to its own stream (Chronicle Queue requires single-writer per stream).
        // Verifies concurrent writes to different streams don't interfere.
        int threadCount = 4;
        int entriesPerThread = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    String stream = "stream-" + threadId;
                    for (int i = 0; i < entriesPerThread; i++) {
                        writeEntry(stream, i + 1, "t" + threadId + "-msg-" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All writers should complete");

        // Verify each stream has the correct count
        for (int t = 0; t < threadCount; t++) {
            assertEquals(entriesPerThread, store.getEntryCount("stream-" + t),
                    "Stream stream-" + t + " should have " + entriesPerThread + " entries");
        }

        assertEquals(threadCount * entriesPerThread, store.getEntryCount(null));

        // Verify all entries readable
        AtomicInteger totalReadCount = new AtomicInteger();
        for (int t = 0; t < threadCount; t++) {
            store.replay("stream-" + t, entry -> {
                totalReadCount.incrementAndGet();
                return true;
            });
        }

        assertEquals(threadCount * entriesPerThread, totalReadCount.get());
    }

    @Test
    void testMultipleReadersIndependent() {
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        try (LogReader reader1 = store.createReader("stream-1");
             LogReader reader2 = store.createReader("stream-1")) {

            // Reader 1 reads 3 entries
            for (int i = 0; i < 3; i++) {
                assertNotNull(reader1.poll());
            }

            // Reader 2 should start from beginning independently
            LogEntry entry = reader2.poll();
            assertNotNull(entry);
            assertEquals(1, entry.getSequenceNumber());

            // Reader 1 continues from where it left off
            LogEntry entry4 = reader1.poll();
            assertNotNull(entry4);
            assertEquals(4, entry4.getSequenceNumber());
        }
    }

    @Test
    void testWriteDuringReplay() {
        for (int i = 1; i <= 5; i++) {
            writeEntry("stream-1", i, "msg-" + i);
        }

        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            // Write new entries during replay callback
            if (entry.getSequenceNumber() == 3) {
                writeEntry("stream-1", 100, "new-during-replay");
            }
            return true;
        });

        // Replay should see a consistent snapshot (5 original entries)
        // The new entry may or may not be visible depending on tailer position,
        // but the original 5 should all be present
        assertTrue(seqNums.containsAll(List.of(1, 2, 3, 4, 5)));
    }

    @Test
    void testReaderAfterStoreClose() throws IOException {
        writeEntry("stream-1", 1, "msg-1");

        LogReader reader = store.createReader("stream-1");
        // Read the first entry
        assertNotNull(reader.poll());

        store.close();
        store = null; // Prevent double-close in tearDown

        // After store close, poll should return null gracefully (or throw)
        // The important thing is it doesn't hang or crash the JVM
        try {
            reader.poll();
        } catch (Exception e) {
            // Acceptable — closed queue may throw
        } finally {
            reader.close();
        }
    }

    // ==================== Decoder Integration ====================

    @Test
    void testDecoderRegistration() {
        Decoder testDecoder = createTestDecoder("TEST");

        store.setDecoder("stream-1", testDecoder);
        assertSame(testDecoder, store.getDecoder("stream-1"));

        assertNull(store.getDecoder("unknown-stream"));

        // Remove decoder
        store.setDecoder("stream-1", null);
        assertNull(store.getDecoder("stream-1"));
    }

    @Test
    void testDecoderWithRealFixMessage() {
        // Build a raw FIX message: 8=FIX.4.4|9=70|35=D|34=5|49=SENDER|56=TARGET|52=...|11=ORD001|10=123|
        char SOH = '\u0001';
        String fixMsg = "8=FIX.4.4" + SOH + "9=70" + SOH + "35=D" + SOH + "34=5" + SOH +
                "49=SENDER" + SOH + "56=TARGET" + SOH + "11=ORD001" + SOH + "10=123" + SOH;
        byte[] rawBytes = fixMsg.getBytes(StandardCharsets.US_ASCII);

        store.write(LogEntry.builder()
                .streamName("fix-session")
                .sequenceNumber(5)
                .timestampNow()
                .outbound()
                .rawMessage(rawBytes)
                .build());

        // Create a test decoder that extracts tag 35 and tag 34
        Decoder fixDecoder = createFixLikeDecoder();

        List<String> msgTypes = new ArrayList<>();
        List<Integer> decodedSeqNums = new ArrayList<>();
        store.replay("fix-session", entry -> {
            byte[] msg = entry.getRawMessage();
            msgTypes.add(fixDecoder.decodeMessageType(ByteBuffer.wrap(msg)));
            decodedSeqNums.add(fixDecoder.decodeSequenceNumber(msg));
            return true;
        });

        assertEquals(1, msgTypes.size());
        assertEquals("D", msgTypes.get(0));
        assertEquals(5, decodedSeqNums.get(0));
    }

    @Test
    void testDecoderPersistenceBehavior() throws IOException {
        Decoder testDecoder = createTestDecoder("FIX");
        store.setDecoder("stream-1", testDecoder);
        assertNotNull(store.getDecoder("stream-1"));

        store.close();

        // Reopen — decoders are in-memory only, not persisted
        store = new ChronicleLogStore(tempDir.toFile());
        assertNull(store.getDecoder("stream-1"));
    }

    // ==================== Component Lifecycle Edge Cases ====================

    @Test
    void testWriteInStandbyMode() throws Exception {
        store.initialize();
        store.startStandby();

        // Write should still work in standby mode
        writeEntry("stream-1", 1, "standby-msg");
        assertEquals(1, store.getEntryCount("stream-1"));
    }

    @Test
    void testWriteAfterStop() throws Exception {
        store.initialize();
        store.startActive();

        writeEntry("stream-1", 1, "active-msg");

        store.stop();

        // After stop, write should handle gracefully (may throw or silently fail)
        try {
            writeEntry("stream-1", 2, "after-stop-msg");
        } catch (Exception e) {
            // Acceptable — stopped store may throw
        }
    }

    @Test
    void testInitializeWithExistingData() throws Exception {
        writeEntry("stream-1", 1, "msg-1");
        writeEntry("stream-1", 2, "msg-2");
        store.close();

        // Create new store over existing data, run through full lifecycle
        store = new ChronicleLogStore(tempDir.toFile());
        store.initialize();
        store.startActive();

        assertEquals(2, store.getEntryCount("stream-1"));

        List<Integer> seqNums = new ArrayList<>();
        store.replay("stream-1", entry -> {
            seqNums.add(entry.getSequenceNumber());
            return true;
        });
        assertEquals(List.of(1, 2), seqNums);
    }

    @Test
    void testReadOperationsInStandbyMode() throws Exception {
        writeEntry("stream-1", 1, "msg-1");
        writeEntry("stream-1", 2, "msg-2");

        store.initialize();
        store.startStandby();

        // Replay should work
        AtomicInteger count = new AtomicInteger();
        store.replay("stream-1", entry -> {
            count.incrementAndGet();
            return true;
        });
        assertEquals(2, count.get());

        // getLatest should work
        LogEntry latest = store.getLatest("stream-1", null);
        assertNotNull(latest);
        assertEquals(2, latest.getSequenceNumber());

        // createReader should work
        try (LogReader reader = store.createReader("stream-1")) {
            assertNotNull(reader.poll());
        }
    }

    // ==================== Performance Validation ====================

    @Tag("performance")
    @Test
    void testWriteThroughput() {
        int entryCount = 100_000;
        byte[] message = "PERF-TEST-MESSAGE-PAYLOAD-DATA".getBytes();

        long startNanos = System.nanoTime();
        for (int i = 1; i <= entryCount; i++) {
            store.write(LogEntry.builder()
                    .streamName("perf-stream")
                    .sequenceNumber(i)
                    .timestampNow()
                    .inbound()
                    .rawMessage(message)
                    .build());
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(entryCount, store.getEntryCount("perf-stream"));
        assertTrue(elapsedMs < 10_000,
                "Write throughput too slow: " + entryCount + " entries in " + elapsedMs + "ms (>10K/sec expected)");
    }

    @Tag("performance")
    @Test
    void testReplayThroughput() {
        int entryCount = 100_000;
        byte[] message = "PERF-TEST-MESSAGE-PAYLOAD-DATA".getBytes();

        for (int i = 1; i <= entryCount; i++) {
            store.write(LogEntry.builder()
                    .streamName("perf-stream")
                    .sequenceNumber(i)
                    .timestampNow()
                    .inbound()
                    .rawMessage(message)
                    .build());
        }

        AtomicLong replayCount = new AtomicLong();
        long startNanos = System.nanoTime();
        store.replay("perf-stream", entry -> {
            replayCount.incrementAndGet();
            return true;
        });
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(entryCount, replayCount.get());
        assertTrue(elapsedMs < 5_000,
                "Replay throughput too slow: " + entryCount + " entries in " + elapsedMs + "ms");
    }

    @Tag("performance")
    @Test
    void testPollLatency() throws InterruptedException {
        int entryCount = 1000;
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch writerDone = new CountDownLatch(1);
        CountDownLatch readerDone = new CountDownLatch(1);

        // Writer thread
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= entryCount; i++) {
                store.write(LogEntry.builder()
                        .streamName("latency-stream")
                        .sequenceNumber(i)
                        .timestamp(System.currentTimeMillis())
                        .inbound()
                        .rawMessage(("msg-" + i).getBytes())
                        .build());
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    break;
                }
            }
            writerDone.countDown();
        });

        // Reader thread
        Thread reader = new Thread(() -> {
            try (LogReader logReader = store.createReader("latency-stream", LogReader.END)) {
                int readCount = 0;
                while (readCount < entryCount) {
                    long pollStart = System.nanoTime();
                    LogEntry entry = logReader.poll(100);
                    long pollEnd = System.nanoTime();
                    if (entry != null) {
                        latencies.add((pollEnd - pollStart) / 1_000_000); // ms
                        readCount++;
                    }
                    if (writerDone.getCount() == 0 && entry == null) {
                        break;
                    }
                }
            }
            readerDone.countDown();
        });

        writer.start();
        reader.start();

        assertTrue(writerDone.await(30, TimeUnit.SECONDS));
        assertTrue(readerDone.await(30, TimeUnit.SECONDS));

        // Calculate p99
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        if (!sorted.isEmpty()) {
            long p99 = sorted.get((int) (sorted.size() * 0.99));
            assertTrue(p99 < 50, "p99 poll latency should be < 50ms, was " + p99 + "ms");
        }
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

    private Decoder createTestDecoder(String protocolName) {
        return new Decoder() {
            @Override
            public String getProtocolName() { return protocolName; }

            @Override
            public String decodeMessageType(ByteBuffer message) { return "TEST"; }

            @Override
            public String getMessageTypeName(String messageType) { return messageType; }

            @Override
            public boolean isAdminMessage(String messageType) { return false; }

            @Override
            public String formatMessage(ByteBuffer message, boolean verbose) {
                return "test-formatted";
            }

            @Override
            public int decodeSequenceNumber(ByteBuffer message) { return -1; }
        };
    }

    /**
     * Creates a FIX-like decoder that extracts tag 35 (MsgType) and tag 34 (MsgSeqNum)
     * from raw FIX bytes with SOH delimiters.
     */
    private Decoder createFixLikeDecoder() {
        return new Decoder() {
            @Override
            public String getProtocolName() { return "FIX"; }

            @Override
            public String decodeMessageType(ByteBuffer message) {
                return extractTag(message, "35");
            }

            @Override
            public String getMessageTypeName(String messageType) {
                if ("D".equals(messageType)) return "NewOrderSingle";
                return messageType;
            }

            @Override
            public boolean isAdminMessage(String messageType) {
                return "A".equals(messageType) || "0".equals(messageType) || "5".equals(messageType);
            }

            @Override
            public String formatMessage(ByteBuffer message, boolean verbose) {
                byte[] bytes = new byte[message.remaining()];
                message.duplicate().get(bytes);
                return new String(bytes, StandardCharsets.US_ASCII).replace('\u0001', '|');
            }

            @Override
            public int decodeSequenceNumber(ByteBuffer message) {
                String val = extractTag(message, "34");
                if (val != null) {
                    try {
                        return Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
                return -1;
            }

            private String extractTag(ByteBuffer message, String tagNum) {
                byte[] bytes = new byte[message.remaining()];
                message.duplicate().get(bytes);
                String msg = new String(bytes, StandardCharsets.US_ASCII);
                String prefix = tagNum + "=";
                int start = msg.indexOf(prefix);
                if (start < 0) return null;
                start += prefix.length();
                int end = msg.indexOf('\u0001', start);
                if (end < 0) end = msg.length();
                return msg.substring(start, end);
            }
        };
    }
}
