package com.omnibridge.mcp.tools;

import com.omnibridge.persistence.LogEntry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListStreamsToolTest {

    private InMemoryLogStore logStore;
    private ListStreamsTool tool;

    @BeforeEach
    void setUp() {
        logStore = new InMemoryLogStore();
        tool = new ListStreamsTool(logStore, null);
    }

    @Test
    void listEmptyStore() {
        McpSchema.CallToolResult result = tool.execute(Map.of());
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"total_streams\":0"));
    }

    @Test
    void listStreamsWithEntries() {
        addTestEntry("SESSION1", "D", 1000L, 1);
        addTestEntry("SESSION1", "8", 2000L, 2);
        addTestEntry("SESSION2", "A", 1500L, 1);

        McpSchema.CallToolResult result = tool.execute(Map.of());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"total_streams\":2"));
        assertTrue(text.contains("SESSION1"));
        assertTrue(text.contains("SESSION2"));
    }

    @Test
    void filterByPublisherId() {
        addTestEntry("pub~PUB1~session1", "D", 1000L, 1);
        addTestEntry("pub~PUB2~session2", "D", 1000L, 1);
        addTestEntry("local-session", "D", 1000L, 1);

        McpSchema.CallToolResult result = tool.execute(Map.of("publisher_id", "PUB1"));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"total_streams\":1"));
        assertTrue(text.contains("pub~PUB1~session1"));
        assertFalse(text.contains("PUB2"));
    }

    private void addTestEntry(String stream, String msgType, long timestamp, int seq) {
        String msg = "8=FIX.4.2\u000135=" + msgType + "\u000149=SENDER\u000156=TARGET\u000110=001\u0001";
        logStore.addEntry(LogEntry.create(timestamp, LogEntry.Direction.OUTBOUND, seq, stream, null, msg.getBytes()));
    }
}
