package com.omnibridge.mcp.tools;

import com.omnibridge.mcp.util.FIXMessageParser;
import com.omnibridge.mcp.util.JsonResultBuilder;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.cli.MsgTypeDecoder;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;

/**
 * MCP tool: get_session_status
 *
 * <p>Session health check with gap analysis. Tracks logon/logout events,
 * message counts, and sequence number gaps.</p>
 */
public class GetSessionStatusTool {

    private static final Logger log = LoggerFactory.getLogger(GetSessionStatusTool.class);

    public static final String NAME = "get_session_status";
    public static final String DESCRIPTION =
            "Get session health status including logon/logout events, message counts by type, " +
            "sequence number gaps, and session duration. Analyzes a single day's activity.";

    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "stream": {
                  "type": "string",
                  "description": "Stream name to analyze. If omitted, analyzes all streams."
                },
                "publisher_id": {
                  "type": "string",
                  "description": "Filter streams by Aeron publisher ID"
                },
                "date": {
                  "type": "string",
                  "description": "Date to analyze in YYYY-MM-DD format. Default: today (UTC)"
                },
                "include_gaps": {
                  "type": "boolean",
                  "description": "Include sequence number gap analysis. Default: true"
                }
              }
            }
            """;

    private final LogStore logStore;

    public GetSessionStatusTool(LogStore logStore) {
        this.logStore = logStore;
    }

    public McpSchema.CallToolResult execute(Map<String, Object> args) {
        try {
            String stream = args != null ? (String) args.get("stream") : null;
            String publisherId = args != null ? (String) args.get("publisher_id") : null;
            String dateStr = args != null ? (String) args.get("date") : null;
            boolean includeGaps = args == null || !Boolean.FALSE.equals(args.get("include_gaps"));

            // Parse date range
            LocalDate date = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now(ZoneOffset.UTC);
            long fromTs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long toTs = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

            Collection<String> streams;
            if (stream != null) {
                streams = List.of(stream);
            } else {
                streams = logStore.getStreamNames();
            }

            List<Map<String, Object>> sessionResults = new ArrayList<>();
            for (String s : streams) {
                if (publisherId != null && !s.startsWith("pub~" + publisherId + "~")) continue;
                sessionResults.add(analyzeSession(s, fromTs, toTs, includeGaps));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("date", date.toString());
            response.put("sessions", sessionResults);

            String json = JsonResultBuilder.toJson(response);
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false);

        } catch (Exception e) {
            log.error("get_session_status failed", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
        }
    }

    private Map<String, Object> analyzeSession(String stream, long fromTs, long toTs,
                                                 boolean includeGaps) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stream", stream);

        // Counters
        Map<String, Long> msgTypeCounts = new TreeMap<>();
        long[] inboundCount = {0};
        long[] outboundCount = {0};
        long[] firstTs = {Long.MAX_VALUE};
        long[] lastTs = {0};

        // Logon/logout tracking
        List<Map<String, Object>> logonEvents = new ArrayList<>();

        // Sequence number tracking for gap analysis
        int[] lastInboundSeq = {0};
        int[] lastOutboundSeq = {0};
        List<Map<String, Object>> gaps = new ArrayList<>();

        logStore.replayByTime(stream, null, fromTs, toTs, entry -> {
            long ts = entry.getTimestamp();
            if (ts < firstTs[0]) firstTs[0] = ts;
            if (ts > lastTs[0]) lastTs[0] = ts;

            if (entry.getDirection() == LogEntry.Direction.INBOUND) {
                inboundCount[0]++;
            } else {
                outboundCount[0]++;
            }

            byte[] raw = entry.getRawMessage();
            String msgType = raw != null ? FIXMessageParser.extractMsgType(raw) : null;
            if (msgType != null) {
                msgTypeCounts.merge(msgType, 1L, Long::sum);

                // Track logon/logout
                if ("A".equals(msgType) || "5".equals(msgType)) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "A".equals(msgType) ? "LOGON" : "LOGOUT");
                    event.put("direction", entry.getDirection().name());
                    event.put("timestamp", Instant.ofEpochMilli(ts).toString());
                    event.put("sequence", entry.getSequenceNumber());
                    logonEvents.add(event);
                }
            }

            // Gap detection
            if (includeGaps) {
                int seq = entry.getSequenceNumber();
                if (entry.getDirection() == LogEntry.Direction.INBOUND) {
                    if (lastInboundSeq[0] > 0 && seq > lastInboundSeq[0] + 1) {
                        Map<String, Object> gap = new LinkedHashMap<>();
                        gap.put("direction", "INBOUND");
                        gap.put("expected", lastInboundSeq[0] + 1);
                        gap.put("received", seq);
                        gap.put("missing_count", seq - lastInboundSeq[0] - 1);
                        gap.put("timestamp", Instant.ofEpochMilli(ts).toString());
                        gaps.add(gap);
                    }
                    lastInboundSeq[0] = seq;
                } else {
                    if (lastOutboundSeq[0] > 0 && seq > lastOutboundSeq[0] + 1) {
                        Map<String, Object> gap = new LinkedHashMap<>();
                        gap.put("direction", "OUTBOUND");
                        gap.put("expected", lastOutboundSeq[0] + 1);
                        gap.put("received", seq);
                        gap.put("missing_count", seq - lastOutboundSeq[0] - 1);
                        gap.put("timestamp", Instant.ofEpochMilli(ts).toString());
                        gaps.add(gap);
                    }
                    lastOutboundSeq[0] = seq;
                }
            }

            return true;
        });

        // Determine session status
        String status;
        if (logonEvents.isEmpty()) {
            status = inboundCount[0] + outboundCount[0] > 0 ? "UNKNOWN" : "NO_ACTIVITY";
        } else {
            Map<String, Object> lastEvent = logonEvents.get(logonEvents.size() - 1);
            status = "LOGON".equals(lastEvent.get("type")) ? "ACTIVE" : "DISCONNECTED";
        }

        result.put("status", status);
        result.put("inbound_count", inboundCount[0]);
        result.put("outbound_count", outboundCount[0]);
        result.put("total_messages", inboundCount[0] + outboundCount[0]);

        if (firstTs[0] != Long.MAX_VALUE) {
            result.put("first_message", Instant.ofEpochMilli(firstTs[0]).toString());
            result.put("last_message", Instant.ofEpochMilli(lastTs[0]).toString());
            long durationSec = (lastTs[0] - firstTs[0]) / 1000;
            result.put("duration_seconds", durationSec);
        }

        result.put("last_inbound_seq", lastInboundSeq[0]);
        result.put("last_outbound_seq", lastOutboundSeq[0]);

        // Message type breakdown with names
        Map<String, Object> typeBreakdown = new LinkedHashMap<>();
        for (var entry : msgTypeCounts.entrySet()) {
            String name = MsgTypeDecoder.decode(entry.getKey());
            typeBreakdown.put(entry.getKey() + " (" + name + ")", entry.getValue());
        }
        result.put("message_types", typeBreakdown);

        result.put("logon_events", logonEvents);

        if (includeGaps) {
            result.put("sequence_gaps", gaps);
            result.put("has_gaps", !gaps.isEmpty());
        }

        return result;
    }
}
