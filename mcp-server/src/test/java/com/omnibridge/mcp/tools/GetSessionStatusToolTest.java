package com.omnibridge.mcp.tools;

import com.omnibridge.persistence.LogEntry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetSessionStatusToolTest {

    private InMemoryLogStore logStore;
    private GetSessionStatusTool tool;

    @BeforeEach
    void setUp() {
        logStore = new InMemoryLogStore();
        tool = new GetSessionStatusTool(logStore);
    }

    @Test
    void noActivity() {
        Map<String, Object> args = new HashMap<>();
        args.put("date", "1970-01-01");

        McpSchema.CallToolResult result = tool.execute(args);
        assertNotNull(result);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"sessions\":[]") || text.contains("\"sessions\":[]"));
    }

    @Test
    void activeSession() {
        // Logon at start of day
        long dayStart = 0L; // epoch = 1970-01-01T00:00:00Z
        addFIXMessage("SESSION1", "A", dayStart + 1000, 1, LogEntry.Direction.INBOUND);
        addFIXMessage("SESSION1", "D", dayStart + 2000, 2, LogEntry.Direction.OUTBOUND);
        addFIXMessage("SESSION1", "8", dayStart + 3000, 3, LogEntry.Direction.INBOUND);

        Map<String, Object> args = new HashMap<>();
        args.put("date", "1970-01-01");
        args.put("stream", "SESSION1");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("ACTIVE"));
        assertTrue(text.contains("\"inbound_count\":2"));
        assertTrue(text.contains("\"outbound_count\":1"));
    }

    @Test
    void disconnectedSession() {
        long dayStart = 0L;
        addFIXMessage("SESSION1", "A", dayStart + 1000, 1, LogEntry.Direction.INBOUND);
        addFIXMessage("SESSION1", "5", dayStart + 5000, 2, LogEntry.Direction.OUTBOUND); // Logout

        Map<String, Object> args = new HashMap<>();
        args.put("date", "1970-01-01");
        args.put("stream", "SESSION1");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("DISCONNECTED"));
    }

    @Test
    void sequenceGapDetection() {
        long dayStart = 0L;
        addFIXMessageWithSeq("SESSION1", "D", dayStart + 1000, 1, LogEntry.Direction.INBOUND);
        addFIXMessageWithSeq("SESSION1", "D", dayStart + 2000, 2, LogEntry.Direction.INBOUND);
        // Gap: seq 3 missing
        addFIXMessageWithSeq("SESSION1", "D", dayStart + 4000, 5, LogEntry.Direction.INBOUND);

        Map<String, Object> args = new HashMap<>();
        args.put("date", "1970-01-01");
        args.put("stream", "SESSION1");
        args.put("include_gaps", true);

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"has_gaps\":true"));
        assertTrue(text.contains("\"expected\":3"));
        assertTrue(text.contains("\"received\":5"));
    }

    @Test
    void filterByPublisherId() {
        long dayStart = 0L;
        addFIXMessageToStream("pub~PUB1~s1", "D", dayStart + 1000, 1);
        addFIXMessageToStream("pub~PUB2~s2", "D", dayStart + 1000, 1);

        Map<String, Object> args = new HashMap<>();
        args.put("date", "1970-01-01");
        args.put("publisher_id", "PUB1");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("pub~PUB1~s1"));
        assertFalse(text.contains("PUB2"));
    }

    @Test
    void messageTypeBreakdown() {
        long dayStart = 0L;
        addFIXMessage("S1", "D", dayStart + 1000, 1, LogEntry.Direction.OUTBOUND);
        addFIXMessage("S1", "D", dayStart + 2000, 2, LogEntry.Direction.OUTBOUND);
        addFIXMessage("S1", "8", dayStart + 3000, 3, LogEntry.Direction.INBOUND);

        Map<String, Object> args = new HashMap<>();
        args.put("date", "1970-01-01");
        args.put("stream", "S1");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("NewOrderSingle"));
        assertTrue(text.contains("ExecutionReport"));
    }

    private void addFIXMessage(String stream, String msgType, long ts, int seq,
                                LogEntry.Direction direction) {
        String msg = "8=FIX.4.2\u000135=" + msgType + "\u000149=SENDER\u000156=TARGET\u000110=001\u0001";
        logStore.addEntry(LogEntry.create(ts, direction, seq, stream, null, msg.getBytes()));
    }

    private void addFIXMessageWithSeq(String stream, String msgType, long ts, int seq,
                                       LogEntry.Direction direction) {
        String msg = "8=FIX.4.2\u000135=" + msgType + "\u000149=SENDER\u000156=TARGET\u000134=" + seq + "\u000110=001\u0001";
        logStore.addEntry(LogEntry.create(ts, direction, seq, stream, null, msg.getBytes()));
    }

    private void addFIXMessageToStream(String stream, String msgType, long ts, int seq) {
        String msg = "8=FIX.4.2\u000135=" + msgType + "\u000149=SENDER\u000156=TARGET\u000110=001\u0001";
        logStore.addEntry(LogEntry.create(ts, LogEntry.Direction.OUTBOUND, seq, stream, null, msg.getBytes()));
    }
}
