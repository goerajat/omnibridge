package com.omnibridge.persistence.chronicle;

import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import com.omnibridge.persistence.config.PersistenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pretoucher faults pages in ahead of the Chronicle writer without changing what is stored.
 */
class ChronicleLogStorePretouchTest {

    private static final long AHEAD = 256 * 1024;

    private Path tempDir;
    private ChronicleLogStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("chronicle-pretouch");
        store = new ChronicleLogStore(tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    @Test
    void touchesAheadOfTheWritePositionOnce() throws IOException {
        store.startPretoucher(AHEAD, 1_000_000);   // thread sleeps; rounds are driven by hand
        assertEquals(0, store.pretouchRound(), "nothing to touch before the first write");

        store.write(entry(1, "35=D|11=1|"));
        int first = store.pretouchRound();
        assertTrue(first >= AHEAD / 4096, "the window ahead of the writer: " + first + " pages");
        assertEquals(0, store.pretouchRound(), "the same window is not touched twice");

        for (int i = 2; i <= 200; i++) {
            store.write(entry(i, "35=8|11=" + i + "|58=" + "x".repeat(400) + "|"));
        }
        int next = store.pretouchRound();
        assertTrue(next > 0 && next < first, "only the pages the writer advanced into: " + next);
    }

    @Test
    void touchedPagesStoreTheSameEntries() throws IOException {
        store.startPretoucher(AHEAD, 1);   // the thread runs concurrently with the writes
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 2000; i++) {
            String msg = "35=D|11=" + i + "|58=" + "y".repeat(300) + "|";
            expected.add(msg);
            store.write(entry(i, msg));
        }
        List<String> actual = new ArrayList<>();
        try (LogReader reader = store.createReader("S")) {
            LogEntry e;
            while ((e = reader.poll()) != null) {
                actual.add(new String(e.getRawMessage()));
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void configCarriesTheSettings() {
        PersistenceConfig config = PersistenceConfig.builder()
                .basePath(tempDir.resolve("cfg").toString())
                .pretouchAhead(0)
                .build();
        assertEquals(0, config.getPretouchAhead());
        assertEquals(10, config.getPretouchIntervalMs());
        try (ChronicleLogStore off = new ChronicleLogStore(config)) {
            assertNull(off.pretouchThreadForTest(), "ahead = 0 starts no thread");
        } catch (IOException e) {
            fail(e);
        }
    }

    private static LogEntry entry(int seq, String msg) {
        return LogEntry.builder()
                .timestamp(System.currentTimeMillis())
                .direction(LogEntry.Direction.OUTBOUND)
                .sequenceNumber(seq)
                .streamName("S")
                .rawMessage(msg.getBytes())
                .build();
    }
}
