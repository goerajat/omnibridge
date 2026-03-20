package com.omnibridge.mcp.index;

import com.omnibridge.persistence.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundIndexerTest {

    @TempDir
    Path tempDir;

    private SecondaryIndex index;

    @BeforeEach
    void setUp() throws Exception {
        index = new SecondaryIndex(tempDir.resolve("indexer-test").toString());
        index.open();
    }

    @AfterEach
    void tearDown() {
        if (index != null) index.close();
    }

    @Test
    void indexEntryExtractsTags() throws Exception {
        // Test the indexEntry method directly (it's package-private)
        BackgroundIndexer indexer = new BackgroundIndexer(null, index, 1000);

        String msg = "8=FIX.4.2\u000135=D\u000149=SENDER\u000156=TARGET\u0001" +
                "11=ORD001\u000155=AAPL\u000137=EX001\u0001150=0\u000110=123\u0001";

        LogEntry entry = LogEntry.create(
                1000L, LogEntry.Direction.OUTBOUND, 1, "session1", null, msg.getBytes());

        indexer.indexEntry("session1", entry, 42L);

        // Verify all indexed fields
        List<SecondaryIndex.IndexEntry> byClOrdId = index.lookup(SecondaryIndex.CF_CLORDID, "ORD001");
        assertEquals(1, byClOrdId.size());
        assertEquals("session1", byClOrdId.get(0).stream());
        assertEquals(42L, byClOrdId.get(0).position());

        assertEquals(1, index.lookup(SecondaryIndex.CF_SYMBOL, "AAPL").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_ORDERID, "EX001").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_MSGTYPE, "D").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_EXECTYPE, "0").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_COMP, "SENDER:TARGET").size());
    }

    @Test
    void indexEntrySkipsNullMessage() throws Exception {
        BackgroundIndexer indexer = new BackgroundIndexer(null, index, 1000);

        LogEntry entry = LogEntry.create(1000L, LogEntry.Direction.OUTBOUND, 1, "s1", null, null);
        indexer.indexEntry("s1", entry, 42L);

        // Nothing should be indexed
        assertTrue(index.lookup(SecondaryIndex.CF_MSGTYPE, "D").isEmpty());
    }

    @Test
    void indexEntryHandlesPartialTags() throws Exception {
        BackgroundIndexer indexer = new BackgroundIndexer(null, index, 1000);

        // Message with only MsgType and CompIDs (no ClOrdID, OrderID, Symbol)
        String msg = "8=FIX.4.2\u000135=A\u000149=SENDER\u000156=TARGET\u000110=001\u0001";
        LogEntry entry = LogEntry.create(1000L, LogEntry.Direction.INBOUND, 1, "s1", null, msg.getBytes());

        indexer.indexEntry("s1", entry, 10L);

        assertEquals(1, index.lookup(SecondaryIndex.CF_MSGTYPE, "A").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_COMP, "SENDER:TARGET").size());
        assertTrue(index.lookup(SecondaryIndex.CF_CLORDID, "ANY").isEmpty());
    }
}
