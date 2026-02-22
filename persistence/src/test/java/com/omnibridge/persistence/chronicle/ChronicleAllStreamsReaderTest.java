package com.omnibridge.persistence.chronicle;

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
 * Tests for {@link ChronicleAllStreamsLogReader} — the multi-stream merge-sort reader.
 */
class ChronicleAllStreamsReaderTest {

    private Path tempDir;
    private ChronicleLogStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("chronicle-allstreams-test");
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

    @Test
    void testTimestampOrderAcrossStreams() throws InterruptedException {
        // Write entries to 3 streams with interleaved timestamps
        long base = System.currentTimeMillis();

        // Stream A: timestamps at base+10, base+40, base+70
        writeEntryAt("stream-A", 1, "A1", base + 10);
        writeEntryAt("stream-A", 2, "A2", base + 40);
        writeEntryAt("stream-A", 3, "A3", base + 70);

        // Stream B: timestamps at base+20, base+50, base+80
        writeEntryAt("stream-B", 1, "B1", base + 20);
        writeEntryAt("stream-B", 2, "B2", base + 50);
        writeEntryAt("stream-B", 3, "B3", base + 80);

        // Stream C: timestamps at base+30, base+60, base+90
        writeEntryAt("stream-C", 1, "C1", base + 30);
        writeEntryAt("stream-C", 2, "C2", base + 60);
        writeEntryAt("stream-C", 3, "C3", base + 90);

        try (LogReader reader = store.createReader()) {
            List<String> order = new ArrayList<>();
            List<Long> timestamps = new ArrayList<>();
            LogEntry entry;
            while ((entry = reader.poll()) != null) {
                order.add(entry.getStreamName() + "-" + entry.getSequenceNumber());
                timestamps.add(entry.getTimestamp());
            }

            assertEquals(9, order.size());

            // Verify strict timestamp ordering
            for (int i = 1; i < timestamps.size(); i++) {
                assertTrue(timestamps.get(i) >= timestamps.get(i - 1),
                        "Entries should be in timestamp order: " + timestamps.get(i - 1) + " > " + timestamps.get(i) +
                        " at index " + i);
            }
        }
    }

    @Test
    void testEmptyAndNonEmptyStreamMix() {
        // Only write to stream-1 and stream-3, leave stream-2 empty
        writeEntry("stream-1", 1, "s1-m1");
        writeEntry("stream-1", 2, "s1-m2");
        // stream-2 is implicitly created by getOrCreateStream when reader is created
        store.getOrCreateStream("stream-2");
        writeEntry("stream-3", 1, "s3-m1");

        try (LogReader reader = store.createReader()) {
            List<String> entries = new ArrayList<>();
            LogEntry entry;
            while ((entry = reader.poll()) != null) {
                entries.add(entry.getStreamName() + "-" + entry.getSequenceNumber());
            }

            assertEquals(3, entries.size());
            // All entries from stream-1 and stream-3 should be present
            assertTrue(entries.stream().anyMatch(e -> e.startsWith("stream-1")));
            assertTrue(entries.stream().anyMatch(e -> e.startsWith("stream-3")));
        }
    }

    @Test
    void testConcurrentWriteMultipleStreams() throws InterruptedException {
        int entriesPerStream = 100;
        String[] streamNames = {"stream-A", "stream-B", "stream-C"};

        // Write entries to each stream from separate threads, serialized per-stream
        // (Chronicle Queue appenders are single-writer per stream)
        CountDownLatch doneLatch = new CountDownLatch(streamNames.length);

        for (String stream : streamNames) {
            new Thread(() -> {
                try {
                    for (int i = 1; i <= entriesPerStream; i++) {
                        synchronized (store) {
                            writeEntry(stream, i, stream + "-msg-" + i);
                        }
                    }
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All writers should complete");

        int expectedTotal = entriesPerStream * streamNames.length;

        // Verify via entry counts
        assertEquals(expectedTotal, store.getEntryCount(null));

        // Use the all-streams reader to read everything
        try (LogReader reader = store.createReader()) {
            AtomicInteger totalRead = new AtomicInteger();
            LogEntry entry;
            while ((entry = reader.poll()) != null) {
                totalRead.incrementAndGet();
            }

            assertEquals(expectedTotal, totalRead.get());
        }
    }

    @Test
    void testPositionResumeAllStreams() {
        long base = System.currentTimeMillis();

        // Write interleaved entries to 2 streams
        writeEntryAt("stream-A", 1, "A1", base + 10);
        writeEntryAt("stream-B", 1, "B1", base + 20);
        writeEntryAt("stream-A", 2, "A2", base + 30);
        writeEntryAt("stream-B", 2, "B2", base + 40);
        writeEntryAt("stream-A", 3, "A3", base + 50);
        writeEntryAt("stream-B", 3, "B3", base + 60);

        // Read first 3 entries
        try (LogReader reader = store.createReader()) {
            for (int i = 0; i < 3; i++) {
                assertNotNull(reader.poll(), "Should read entry " + (i + 1));
            }

            // Reset to start and re-read
            reader.setPosition(LogReader.START);

            List<String> allEntries = new ArrayList<>();
            LogEntry entry;
            while ((entry = reader.poll()) != null) {
                allEntries.add(entry.getStreamName() + "-" + entry.getSequenceNumber());
            }

            assertEquals(6, allEntries.size(), "Should read all 6 entries after reset to START");
        }

        // Test set position to END
        try (LogReader reader = store.createReader()) {
            reader.setPosition(LogReader.END);
            assertNull(reader.poll(), "Should have no entries at END position");
        }
    }

    // ==================== Helpers ====================

    private void writeEntry(String stream, int seqNum, String message) {
        store.write(LogEntry.builder()
                .streamName(stream)
                .sequenceNumber(seqNum)
                .timestampNow()
                .inbound()
                .rawMessage(message.getBytes())
                .build());
    }

    private void writeEntryAt(String stream, int seqNum, String message, long timestamp) {
        store.write(LogEntry.builder()
                .streamName(stream)
                .sequenceNumber(seqNum)
                .timestamp(timestamp)
                .inbound()
                .rawMessage(message.getBytes())
                .build());
    }
}
