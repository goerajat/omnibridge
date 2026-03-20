package com.omnibridge.mcp.tools;

import com.omnibridge.persistence.LogEntry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryFixMessagesToolTest {

    private InMemoryLogStore logStore;
    private QueryFixMessagesTool tool;

    @BeforeEach
    void setUp() {
        logStore = new InMemoryLogStore();
        tool = new QueryFixMessagesTool(logStore, null, 100, 1000);
    }

    @Test
    void queryByTimeRange() {
        addOrder("SESSION1", "ORD001", "AAPL", 1000L, 1);
        addOrder("SESSION1", "ORD002", "MSFT", 2000L, 2);
        addOrder("SESSION1", "ORD003", "GOOG", 3000L, 3);

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:01Z");
        args.put("end_time", "1970-01-01T00:00:02.5Z");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("ORD001"));
        assertTrue(text.contains("ORD002"));
        assertFalse(text.contains("ORD003"));
    }

    @Test
    void filterByMsgType() {
        addFIXMessage("S1", "D", "ORD001", "AAPL", 1000L, 1);
        addFIXMessage("S1", "8", "ORD001", "AAPL", 2000L, 2);
        addFIXMessage("S1", "A", null, null, 500L, 0);

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:00Z");
        args.put("end_time", "1970-01-01T00:00:03Z");
        args.put("msg_type", List.of("D"));

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"total_matched\":1"));
        assertTrue(text.contains("NewOrderSingle"));
    }

    @Test
    void filterByClOrdId() {
        addOrder("S1", "ORD001", "AAPL", 1000L, 1);
        addOrder("S1", "ORD002", "MSFT", 2000L, 2);

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:00Z");
        args.put("end_time", "1970-01-01T00:00:03Z");
        args.put("clordid", "ORD002");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"total_matched\":1"));
        assertTrue(text.contains("MSFT"));
    }

    @Test
    void skipAdminMessages() {
        addFIXMessage("S1", "0", null, null, 1000L, 1); // Heartbeat
        addFIXMessage("S1", "D", "ORD001", "AAPL", 2000L, 2);

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:00Z");
        args.put("end_time", "1970-01-01T00:00:03Z");
        args.put("skip_admin", true);

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"total_matched\":1"));
    }

    @Test
    void limitAndOffset() {
        for (int i = 1; i <= 5; i++) {
            addOrder("S1", "ORD" + i, "AAPL", i * 1000L, i);
        }

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:00Z");
        args.put("end_time", "1970-01-01T00:00:06Z");
        args.put("limit", 2);
        args.put("offset", 1);

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"returned\":2"));
        assertTrue(text.contains("\"total_matched\":5"));
    }

    @Test
    void fullFormat() {
        addOrder("S1", "ORD001", "AAPL", 1000L, 1);

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:00Z");
        args.put("end_time", "1970-01-01T00:00:02Z");
        args.put("format", "full");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("tags"));
    }

    @Test
    void rawFormat() {
        addOrder("S1", "ORD001", "AAPL", 1000L, 1);

        Map<String, Object> args = new HashMap<>();
        args.put("start_time", "1970-01-01T00:00:00Z");
        args.put("end_time", "1970-01-01T00:00:02Z");
        args.put("format", "raw");

        McpSchema.CallToolResult result = tool.execute(args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("raw"));
    }

    @Test
    void missingRequiredParams() {
        McpSchema.CallToolResult result = tool.execute(Map.of());
        assertTrue(result.isError() != null && result.isError());
    }

    private void addOrder(String stream, String clOrdId, String symbol, long ts, int seq) {
        addFIXMessage(stream, "D", clOrdId, symbol, ts, seq);
    }

    private void addFIXMessage(String stream, String msgType, String clOrdId, String symbol,
                                long ts, int seq) {
        StringBuilder msg = new StringBuilder();
        msg.append("8=FIX.4.2\u0001");
        msg.append("35=").append(msgType).append('\u0001');
        msg.append("49=SENDER\u000156=TARGET\u0001");
        msg.append("34=").append(seq).append('\u0001');
        if (clOrdId != null) msg.append("11=").append(clOrdId).append('\u0001');
        if (symbol != null) msg.append("55=").append(symbol).append('\u0001');
        msg.append("10=001\u0001");

        logStore.addEntry(LogEntry.create(ts, LogEntry.Direction.OUTBOUND, seq,
                stream, null, msg.toString().getBytes()));
    }
}
