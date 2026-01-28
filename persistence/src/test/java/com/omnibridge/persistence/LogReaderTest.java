package com.omnibridge.persistence;

import com.omnibridge.persistence.memory.MemoryMappedLogStore;
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
 * Tests for LogReader polling functionality.
 */
class LogReaderTest {

    private Path tempDir;
    private MemoryMappedLogStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("logreader-test");
        store = new MemoryMappedLogStore(tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) {
            store.close();
        }
        // Clean up temp directory
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void testPollFromStart() {
        // Write some entries
        writeEntry("stream-1", 1, "message-1");
        writeEntry("stream-1", 2, "message-2");
        writeEntry("stream-1", 3, "message-3");

        // Create reader from start
        try (LogReader reader = store.createReader("stream-1")) {
            assertEquals("stream-1", reader.getStreamName());
            assertTrue(reader.hasNext());
            assertEquals(3, reader.available());

            LogEntry entry1 = reader.poll();
            assertNotNull(entry1);
            assertEquals(1, entry1.getSequenceNumber());

            LogEntry entry2 = reader.poll();
            assertNotNull(entry2);
            assertEquals(2, entry2.getSequenceNumber());

            LogEntry entry3 = reader.poll();
            assertNotNull(entry3);
            assertEquals(3, entry3.getSequenceNumber());

            // No more entries
            assertFalse(reader.hasNext());
            assertNull(reader.poll());
        }
    }

    @Test
    void testPollFromEnd() {
        // Write initial entries
        writeEntry("stream-1", 1, "message-1");
        writeEntry("stream-1", 2, "message-2");

        // Create reader from end (tail mode)
        try (LogReader reader = store.createReader("stream-1", LogReader.END)) {
            assertFalse(reader.hasNext());
            assertEquals(0, reader.available());

            // Write new entry
            writeEntry("stream-1", 3, "message-3");

            // Should see new entry
            assertTrue(reader.hasNext());
            LogEntry entry = reader.poll();
            assertNotNull(entry);
            assertEquals(3, entry.getSequenceNumber());
        }
    }

    @Test
    void testPollWithTimeout() {
        writeEntry("stream-1", 1, "message-1");

        try (LogReader reader = store.createReader("stream-1")) {
            // First poll should succeed
            LogEntry entry = reader.poll(1000);
            assertNotNull(entry);
            assertEquals(1, entry.getSequenceNumber());

            // Second poll should timeout
            long start = System.currentTimeMillis();
            LogEntry nothing = reader.poll(100);
            long elapsed = System.currentTimeMillis() - start;

            assertNull(nothing);
            assertTrue(elapsed >= 90, "Should have waited at least 90ms");
        }
    }

    @Test
    void testPollBatch() {
        // Write entries
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
                return entry.getSequenceNumber() < 3; // Stop after seq 3
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
            // Read first two
            reader.poll();
            reader.poll();
            long posAfterTwo = reader.getPosition();

            // Read third
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

        // Start reader thread
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

        // Give reader time to start
        Thread.sleep(50);

        // Writer thread
        Thread writerThread = new Thread(() -> {
            for (int i = 1; i <= 100; i++) {
                writeEntry("stream-1", i, "message-" + i);
                try {
                    Thread.sleep(5); // Simulate some delay
                } catch (InterruptedException e) {
                    break;
                }
            }
            writerDone.countDown();
        });
        writerThread.start();

        // Wait for completion
        assertTrue(writerDone.await(10, TimeUnit.SECONDS), "Writer should complete");
        assertTrue(readerDone.await(10, TimeUnit.SECONDS), "Reader should complete");

        assertEquals(100, readCount.get());
    }

    @Test
    void testTryPollNonBlocking() {
        writeEntry("stream-1", 1, "message-1");

        try (LogReader reader = store.createReader("stream-1")) {
            // tryPoll should return entry immediately
            LogEntry entry = reader.tryPoll();
            assertNotNull(entry);
            assertEquals(1, entry.getSequenceNumber());

            // tryPoll should return null immediately when no data
            long start = System.currentTimeMillis();
            LogEntry nothing = reader.tryPoll();
            long elapsed = System.currentTimeMillis() - start;

            assertNull(nothing);
            assertTrue(elapsed < 50, "tryPoll should not block");
        }
    }

    @Test
    void testDrain() {
        // Write entries
        for (int i = 1; i <= 10; i++) {
            writeEntry("stream-1", i, "message-" + i);
        }

        try (LogReader reader = store.createReader("stream-1")) {
            List<Integer> seqNums = new ArrayList<>();

            // Drain all entries
            int count = reader.drain(entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(10, count);
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), seqNums);

            // Drain again should return 0
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

            // Drain only 3 entries
            int count = reader.drain(3, entry -> {
                seqNums.add(entry.getSequenceNumber());
                return true;
            });

            assertEquals(3, count);
            assertEquals(List.of(1, 2, 3), seqNums);

            // Drain remaining
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
        // Create reader on empty stream
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
        // Write to multiple streams
        writeEntry("stream-1", 1, "s1-m1");
        writeEntry("stream-2", 1, "s2-m1");
        writeEntry("stream-1", 2, "s1-m2");
        writeEntry("stream-2", 2, "s2-m2");

        try (LogReader reader = store.createReader()) {
            assertNull(reader.getStreamName()); // All streams
            assertTrue(reader.hasNext());

            List<String> messages = new ArrayList<>();
            LogEntry entry;
            while ((entry = reader.poll()) != null) {
                messages.add(entry.getStreamName() + "-" + entry.getSequenceNumber());
            }

            assertEquals(4, messages.size());
            // Entries should be in timestamp order
        }
    }

    private void writeEntry(String stream, int seqNum, String message) {
        store.write(LogEntry.builder()
                .streamName(stream)
                .sequenceNumber(seqNum)
                .timestampNow()
                .inbound()
                .rawMessage(message.getBytes())
                .build());
    }
}
