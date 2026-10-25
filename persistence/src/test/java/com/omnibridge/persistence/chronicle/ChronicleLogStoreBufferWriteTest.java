package com.omnibridge.persistence.chronicle;

import com.omnibridge.persistence.LogEntry;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The message path writes from the session's buffer (the socket read buffer or the outgoing
 * ring, both off heap; a heap array in tests) at some offset: what comes back on replay must be
 * exactly the message, with its fields, the same as an entry written the long way.
 */
class ChronicleLogStoreBufferWriteTest {

    private Path tempDir;
    private ChronicleLogStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("chronicle-buffer-write");
        store = new ChronicleLogStore(tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void offHeapAndHeapBuffersReplayAsTheSameEntries() {
        byte[] first = "8=FIX.4.4\u00019=12\u000135=D\u000134=7\u000110=000\u0001".getBytes(StandardCharsets.US_ASCII);
        byte[] second = "8=FIX.4.4\u00019=12\u000135=8\u000134=8\u000110=001\u0001".getBytes(StandardCharsets.US_ASCII);

        // Off heap, message at offset 100
        UnsafeBuffer direct = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        direct.setMemory(0, 1024, (byte) 'x');
        direct.putBytes(100, first);
        store.write(1000L, LogEntry.Direction.INBOUND, 7, "S", direct, 100, first.length);

        // Heap array wrapped at an adjustment, message at a further offset
        byte[] backing = new byte[512];
        UnsafeBuffer heap = new UnsafeBuffer(backing, 64, 448);
        heap.setMemory(0, 448, (byte) 'y');
        heap.putBytes(20, second);
        store.write(2000L, LogEntry.Direction.OUTBOUND, 8, "S", heap, 20, second.length);

        // The long way, for comparison
        store.write(LogEntry.builder().timestamp(3000L).direction(LogEntry.Direction.OUTBOUND)
                .sequenceNumber(9).streamName("S").rawMessage(first).build());

        List<LogEntry> replayed = new ArrayList<>();
        assertEquals(3, store.replay("S", e -> replayed.add(copy(e))));

        assertEntry(replayed.get(0), 1000L, LogEntry.Direction.INBOUND, 7, first);
        assertEntry(replayed.get(1), 2000L, LogEntry.Direction.OUTBOUND, 8, second);
        assertEntry(replayed.get(2), 3000L, LogEntry.Direction.OUTBOUND, 9, first);
        assertEquals(3, store.getEntryCount("S"));
        assertEquals(9, store.getLatest("S", LogEntry.Direction.OUTBOUND).getSequenceNumber());
    }

    @Test
    void bufferWriteNeedsAStreamName() {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[16]);
        assertThrows(IllegalArgumentException.class,
                () -> store.write(1L, LogEntry.Direction.INBOUND, 1, "", buffer, 0, 16));
    }

    private static void assertEntry(LogEntry e, long timestamp, LogEntry.Direction direction, int seqNum, byte[] raw) {
        assertEquals(timestamp, e.getTimestamp());
        assertEquals(direction, e.getDirection());
        assertEquals(seqNum, e.getSequenceNumber());
        assertEquals("S", e.getStreamName());
        assertArrayEquals(raw, e.getRawMessage());
        assertEquals(0, e.getMetadata() == null ? 0 : e.getMetadata().length);
    }

    /** Replay may reuse its entry; keep a copy. */
    private static LogEntry copy(LogEntry e) {
        return new LogEntry(e.getTimestamp(), e.getDirection(), e.getSequenceNumber(), e.getStreamName(),
                e.getMetadata() == null ? null : e.getMetadata().clone(), e.getRawMessage().clone());
    }
}
