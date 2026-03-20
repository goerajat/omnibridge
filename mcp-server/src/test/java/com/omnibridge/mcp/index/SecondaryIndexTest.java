package com.omnibridge.mcp.index;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecondaryIndexTest {

    @TempDir
    Path tempDir;

    private SecondaryIndex index;

    @BeforeEach
    void setUp() throws Exception {
        index = new SecondaryIndex(tempDir.resolve("test-index").toString());
        index.open();
    }

    @AfterEach
    void tearDown() {
        if (index != null) index.close();
    }

    @Test
    void putAndLookup() throws Exception {
        index.put(SecondaryIndex.CF_CLORDID, "ORD001", 1000L, "session1", 100L);
        index.put(SecondaryIndex.CF_CLORDID, "ORD001", 2000L, "session1", 200L);
        index.put(SecondaryIndex.CF_CLORDID, "ORD002", 1500L, "session1", 150L);

        List<SecondaryIndex.IndexEntry> results = index.lookup(SecondaryIndex.CF_CLORDID, "ORD001");
        assertEquals(2, results.size());
        assertEquals("session1", results.get(0).stream());
        assertEquals(100L, results.get(0).position());
        assertEquals(1000L, results.get(0).timestamp());
        assertEquals(200L, results.get(1).position());
    }

    @Test
    void lookupRange() throws Exception {
        index.put(SecondaryIndex.CF_SYMBOL, "AAPL", 1000L, "s1", 10L);
        index.put(SecondaryIndex.CF_SYMBOL, "AAPL", 2000L, "s1", 20L);
        index.put(SecondaryIndex.CF_SYMBOL, "AAPL", 3000L, "s1", 30L);

        List<SecondaryIndex.IndexEntry> results =
                index.lookupRange(SecondaryIndex.CF_SYMBOL, "AAPL", 1500L, 2500L);
        assertEquals(1, results.size());
        assertEquals(20L, results.get(0).position());
    }

    @Test
    void lookupNoResults() throws Exception {
        List<SecondaryIndex.IndexEntry> results = index.lookup(SecondaryIndex.CF_ORDERID, "NONE");
        assertTrue(results.isEmpty());
    }

    @Test
    void checkpointPositions() throws Exception {
        assertEquals(-1, index.getLastIndexedPosition("stream1"));

        index.setLastIndexedPosition("stream1", 42L);
        assertEquals(42L, index.getLastIndexedPosition("stream1"));

        index.setLastIndexedPosition("stream1", 100L);
        assertEquals(100L, index.getLastIndexedPosition("stream1"));
    }

    @Test
    void multipleColumnFamilies() throws Exception {
        index.put(SecondaryIndex.CF_CLORDID, "ORD001", 1000L, "s1", 10L);
        index.put(SecondaryIndex.CF_SYMBOL, "AAPL", 1000L, "s1", 10L);
        index.put(SecondaryIndex.CF_ORDERID, "EX001", 1000L, "s1", 10L);
        index.put(SecondaryIndex.CF_COMP, "SENDER:TARGET", 1000L, "s1", 10L);

        assertEquals(1, index.lookup(SecondaryIndex.CF_CLORDID, "ORD001").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_SYMBOL, "AAPL").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_ORDERID, "EX001").size());
        assertEquals(1, index.lookup(SecondaryIndex.CF_COMP, "SENDER:TARGET").size());

        // Cross-CF isolation
        assertTrue(index.lookup(SecondaryIndex.CF_CLORDID, "AAPL").isEmpty());
    }

    @Test
    void reopenPreservesData() throws Exception {
        index.put(SecondaryIndex.CF_CLORDID, "ORD001", 1000L, "s1", 10L);
        index.setLastIndexedPosition("s1", 50L);
        index.close();

        index = new SecondaryIndex(tempDir.resolve("test-index").toString());
        index.open();

        assertEquals(1, index.lookup(SecondaryIndex.CF_CLORDID, "ORD001").size());
        assertEquals(50L, index.getLastIndexedPosition("s1"));
    }

    @Test
    void keyOrdering() throws Exception {
        // Entries should be ordered by timestamp within a field value
        index.put(SecondaryIndex.CF_SYMBOL, "MSFT", 3000L, "s1", 30L);
        index.put(SecondaryIndex.CF_SYMBOL, "MSFT", 1000L, "s1", 10L);
        index.put(SecondaryIndex.CF_SYMBOL, "MSFT", 2000L, "s1", 20L);

        List<SecondaryIndex.IndexEntry> results = index.lookup(SecondaryIndex.CF_SYMBOL, "MSFT");
        assertEquals(3, results.size());
        assertEquals(1000L, results.get(0).timestamp());
        assertEquals(2000L, results.get(1).timestamp());
        assertEquals(3000L, results.get(2).timestamp());
    }
}
