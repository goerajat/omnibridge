package com.omnibridge.mcp.tools;

import com.omnibridge.mcp.index.SecondaryIndex;
import com.omnibridge.mcp.util.FIXMessageParser;
import com.omnibridge.mcp.util.JsonResultBuilder;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.cli.MsgTypeDecoder;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * MCP tool: query_fix_messages
 *
 * <p>Primary search tool with full filter set. Supports both sequential scan
 * and index-accelerated lookup paths.</p>
 */
public class QueryFixMessagesTool {

    private static final Logger log = LoggerFactory.getLogger(QueryFixMessagesTool.class);

    public static final String NAME = "query_fix_messages";
    public static final String DESCRIPTION =
            "Query FIX messages from the persistence store with time range, field filters, and format options. " +
            "Returns matching messages with metadata. Use start_time and end_time (ISO-8601) to bound the search.";

    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "start_time": {
                  "type": "string",
                  "description": "Start time in ISO-8601 format (e.g., 2024-01-15T09:30:00Z). Required."
                },
                "end_time": {
                  "type": "string",
                  "description": "End time in ISO-8601 format (e.g., 2024-01-15T16:00:00Z). Required."
                },
                "stream": {
                  "type": "string",
                  "description": "Filter by stream name (full raw name including pub~ prefix if applicable)"
                },
                "publisher_id": {
                  "type": "string",
                  "description": "Filter streams by Aeron publisher ID (matches pub~{id}~ prefix)"
                },
                "direction": {
                  "type": "string",
                  "enum": ["INBOUND", "OUTBOUND"],
                  "description": "Filter by message direction"
                },
                "msg_type": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Filter by FIX MsgType values (e.g., [\"D\", \"8\", \"F\"])"
                },
                "symbol": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Filter by Symbol (tag 55)"
                },
                "clordid": {
                  "type": "string",
                  "description": "Filter by ClOrdID (tag 11)"
                },
                "orig_clordid": {
                  "type": "string",
                  "description": "Filter by OrigClOrdID (tag 41)"
                },
                "orderid": {
                  "type": "string",
                  "description": "Filter by OrderID (tag 37)"
                },
                "sender_comp_id": {
                  "type": "string",
                  "description": "Filter by SenderCompID (tag 49)"
                },
                "target_comp_id": {
                  "type": "string",
                  "description": "Filter by TargetCompID (tag 56)"
                },
                "exec_type": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Filter by ExecType (tag 150) values"
                },
                "text_contains": {
                  "type": "string",
                  "description": "Filter messages containing this text in raw message"
                },
                "from_seq": {
                  "type": "integer",
                  "description": "Minimum sequence number (inclusive)"
                },
                "to_seq": {
                  "type": "integer",
                  "description": "Maximum sequence number (inclusive)"
                },
                "skip_admin": {
                  "type": "boolean",
                  "description": "Skip admin/session messages (Heartbeat, TestRequest, etc.). Default: false"
                },
                "limit": {
                  "type": "integer",
                  "description": "Maximum messages to return (default: 100, max: 1000)"
                },
                "offset": {
                  "type": "integer",
                  "description": "Number of matching messages to skip before returning results"
                },
                "format": {
                  "type": "string",
                  "enum": ["summary", "full", "raw"],
                  "description": "Output format. summary: key fields only. full: all parsed tags. raw: raw message string. Default: summary"
                }
              },
              "required": ["start_time", "end_time"]
            }
            """;

    private final LogStore logStore;
    private final SecondaryIndex index;
    private final int defaultLimit;
    private final int maxLimit;

    public QueryFixMessagesTool(LogStore logStore, SecondaryIndex index,
                                int defaultLimit, int maxLimit) {
        this.logStore = logStore;
        this.index = index;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    @SuppressWarnings("unchecked")
    public McpSchema.CallToolResult execute(Map<String, Object> args) {
        try {
            // Parse time range
            long fromTs = parseIsoTimestamp((String) args.get("start_time"));
            long toTs = parseIsoTimestamp((String) args.get("end_time"));

            // Parse filters
            String stream = (String) args.get("stream");
            String publisherId = (String) args.get("publisher_id");
            String directionStr = (String) args.get("direction");
            LogEntry.Direction direction = directionStr != null ?
                    LogEntry.Direction.valueOf(directionStr) : null;
            List<String> msgTypes = (List<String>) args.get("msg_type");
            List<String> symbols = (List<String>) args.get("symbol");
            String clordid = (String) args.get("clordid");
            String origClordid = (String) args.get("orig_clordid");
            String orderid = (String) args.get("orderid");
            String senderCompId = (String) args.get("sender_comp_id");
            String targetCompId = (String) args.get("target_comp_id");
            List<String> execTypes = (List<String>) args.get("exec_type");
            String textContains = (String) args.get("text_contains");
            int fromSeq = getInt(args, "from_seq", 0);
            int toSeq = getInt(args, "to_seq", 0);
            boolean skipAdmin = Boolean.TRUE.equals(args.get("skip_admin"));
            int limit = Math.min(getInt(args, "limit", defaultLimit), maxLimit);
            int offset = getInt(args, "offset", 0);
            String format = (String) args.getOrDefault("format", "summary");

            // Try index-accelerated path
            List<LogEntry> results;
            String queryPath;

            if (canUseIndex(clordid, orderid, symbols, execTypes, senderCompId, targetCompId)) {
                results = queryViaIndex(clordid, orderid, symbols, execTypes,
                        senderCompId, targetCompId, fromTs, toTs, stream, publisherId);
                queryPath = "index";
                log.debug("Index lookup returned {} candidates", results.size());
            } else {
                results = queryViaScan(stream, publisherId, direction, fromTs, toTs);
                queryPath = "scan";
                log.debug("Sequential scan returned {} candidates", results.size());
            }

            // Apply remaining filters
            List<Map<String, Object>> output = new ArrayList<>();
            int skipped = 0;
            int matched = 0;

            for (LogEntry entry : results) {
                if (!matchesFilters(entry, direction, msgTypes, symbols, clordid, origClordid,
                        orderid, senderCompId, targetCompId, execTypes, textContains,
                        fromSeq, toSeq, skipAdmin, publisherId)) {
                    continue;
                }
                matched++;

                if (skipped < offset) {
                    skipped++;
                    continue;
                }

                if (output.size() < limit) {
                    output.add(formatEntry(entry, format));
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query_path", queryPath);
            response.put("total_matched", matched);
            response.put("returned", output.size());
            response.put("offset", offset);
            response.put("limit", limit);
            response.put("messages", output);

            String json = JsonResultBuilder.toJson(response);
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false);

        } catch (Exception e) {
            log.error("query_fix_messages failed", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
        }
    }

    // ==================== Query Strategies ====================

    private boolean canUseIndex(String clordid, String orderid, List<String> symbols,
                                List<String> execTypes, String senderCompId, String targetCompId) {
        if (index == null || !index.isOpen()) return false;
        return clordid != null || orderid != null ||
                (symbols != null && symbols.size() == 1) ||
                (execTypes != null && execTypes.size() == 1) ||
                (senderCompId != null && targetCompId != null);
    }

    private List<LogEntry> queryViaIndex(String clordid, String orderid, List<String> symbols,
                                          List<String> execTypes, String senderCompId,
                                          String targetCompId, long fromTs, long toTs,
                                          String stream, String publisherId) throws Exception {
        List<SecondaryIndex.IndexEntry> indexEntries;

        // Priority: clordid > orderid > symbol > exectype > comp
        if (clordid != null) {
            indexEntries = index.lookupRange(SecondaryIndex.CF_CLORDID, clordid, fromTs, toTs);
        } else if (orderid != null) {
            indexEntries = index.lookupRange(SecondaryIndex.CF_ORDERID, orderid, fromTs, toTs);
        } else if (symbols != null && symbols.size() == 1) {
            indexEntries = index.lookupRange(SecondaryIndex.CF_SYMBOL, symbols.get(0), fromTs, toTs);
        } else if (execTypes != null && execTypes.size() == 1) {
            indexEntries = index.lookupRange(SecondaryIndex.CF_EXECTYPE, execTypes.get(0), fromTs, toTs);
        } else {
            String compKey = senderCompId + ":" + targetCompId;
            indexEntries = index.lookupRange(SecondaryIndex.CF_COMP, compKey, fromTs, toTs);
        }

        // Read entries at indexed positions
        List<LogEntry> results = new ArrayList<>();
        for (SecondaryIndex.IndexEntry ie : indexEntries) {
            // Filter by stream/publisher
            if (stream != null && !stream.equals(ie.stream())) continue;
            if (publisherId != null && !ie.stream().startsWith("pub~" + publisherId + "~")) continue;

            try (var reader = logStore.createReader(ie.stream(), ie.position())) {
                LogEntry entry = reader.poll(0);
                if (entry != null) {
                    results.add(copyEntry(entry));
                }
            }
        }
        return results;
    }

    private List<LogEntry> queryViaScan(String stream, String publisherId,
                                         LogEntry.Direction direction,
                                         long fromTs, long toTs) {
        List<LogEntry> results = new ArrayList<>();

        Collection<String> streams;
        if (stream != null) {
            streams = List.of(stream);
        } else {
            streams = logStore.getStreamNames();
        }

        for (String s : streams) {
            if (publisherId != null && !s.startsWith("pub~" + publisherId + "~")) continue;

            logStore.replayByTime(s, direction, fromTs, toTs, entry -> {
                results.add(copyEntry(entry));
                // Safety cap during scan to prevent OOM
                return results.size() < maxLimit * 10;
            });
        }
        return results;
    }

    // ==================== Filtering ====================

    private boolean matchesFilters(LogEntry entry, LogEntry.Direction direction,
                                    List<String> msgTypes, List<String> symbols,
                                    String clordid, String origClordid, String orderid,
                                    String senderCompId, String targetCompId,
                                    List<String> execTypes, String textContains,
                                    int fromSeq, int toSeq, boolean skipAdmin,
                                    String publisherId) {
        // Publisher prefix
        if (publisherId != null && !entry.getStreamName().startsWith("pub~" + publisherId + "~")) {
            return false;
        }

        // Direction
        if (direction != null && entry.getDirection() != direction) return false;

        // Sequence range
        if (fromSeq > 0 && entry.getSequenceNumber() < fromSeq) return false;
        if (toSeq > 0 && entry.getSequenceNumber() > toSeq) return false;

        byte[] raw = entry.getRawMessage();
        if (raw == null) return false;

        String msgType = FIXMessageParser.extractMsgType(raw);

        // Skip admin
        if (skipAdmin && MsgTypeDecoder.isAdminMessage(msgType)) return false;

        // MsgType filter
        if (msgTypes != null && !msgTypes.isEmpty()) {
            if (msgType == null || !msgTypes.contains(msgType)) return false;
        }

        // Symbol filter
        if (symbols != null && !symbols.isEmpty()) {
            String sym = FIXMessageParser.extractTag(raw, 55);
            if (sym == null || !symbols.contains(sym)) return false;
        }

        // ClOrdID filter
        if (clordid != null) {
            String val = FIXMessageParser.extractTag(raw, 11);
            if (!clordid.equals(val)) return false;
        }

        // OrigClOrdID filter
        if (origClordid != null) {
            String val = FIXMessageParser.extractTag(raw, 41);
            if (!origClordid.equals(val)) return false;
        }

        // OrderID filter
        if (orderid != null) {
            String val = FIXMessageParser.extractTag(raw, 37);
            if (!orderid.equals(val)) return false;
        }

        // SenderCompID filter
        if (senderCompId != null) {
            String val = FIXMessageParser.extractSenderCompId(raw);
            if (!senderCompId.equals(val)) return false;
        }

        // TargetCompID filter
        if (targetCompId != null) {
            String val = FIXMessageParser.extractTargetCompId(raw);
            if (!targetCompId.equals(val)) return false;
        }

        // ExecType filter
        if (execTypes != null && !execTypes.isEmpty()) {
            String val = FIXMessageParser.extractTag(raw, 150);
            if (val == null || !execTypes.contains(val)) return false;
        }

        // Text contains
        if (textContains != null) {
            String msgStr = entry.getRawMessageString();
            if (!msgStr.contains(textContains)) return false;
        }

        return true;
    }

    // ==================== Formatting ====================

    private Map<String, Object> formatEntry(LogEntry entry, String format) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.ofEpochMilli(entry.getTimestamp()).toString());
        result.put("stream", entry.getStreamName());
        result.put("direction", entry.getDirection().name());
        result.put("sequence", entry.getSequenceNumber());

        byte[] raw = entry.getRawMessage();

        switch (format) {
            case "full" -> {
                if (raw != null) {
                    Map<Integer, String> tags = FIXMessageParser.parse(raw);
                    Map<String, String> namedTags = new LinkedHashMap<>();
                    for (var e : tags.entrySet()) {
                        namedTags.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    result.put("tags", namedTags);
                    String msgType = tags.get(35);
                    if (msgType != null) {
                        result.put("msg_type", msgType);
                        result.put("msg_type_name", MsgTypeDecoder.decode(msgType));
                    }
                }
            }
            case "raw" -> {
                result.put("raw", entry.getRawMessageString());
            }
            default -> {
                // summary format: key fields only
                if (raw != null) {
                    String msgType = FIXMessageParser.extractMsgType(raw);
                    if (msgType != null) {
                        result.put("msg_type", msgType);
                        result.put("msg_type_name", MsgTypeDecoder.decode(msgType));
                    }
                    addIfPresent(result, "clordid", FIXMessageParser.extractTag(raw, 11));
                    addIfPresent(result, "orderid", FIXMessageParser.extractTag(raw, 37));
                    addIfPresent(result, "symbol", FIXMessageParser.extractTag(raw, 55));
                    addIfPresent(result, "side", FIXMessageParser.extractTag(raw, 54));
                    addIfPresent(result, "price", FIXMessageParser.extractTag(raw, 44));
                    addIfPresent(result, "quantity", FIXMessageParser.extractTag(raw, 38));
                    addIfPresent(result, "exec_type", FIXMessageParser.extractTag(raw, 150));
                    addIfPresent(result, "ord_status", FIXMessageParser.extractTag(raw, 39));
                    addIfPresent(result, "sender", FIXMessageParser.extractSenderCompId(raw));
                    addIfPresent(result, "target", FIXMessageParser.extractTargetCompId(raw));
                }
            }
        }

        return result;
    }

    // ==================== Helpers ====================

    private static void addIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private static LogEntry copyEntry(LogEntry entry) {
        return LogEntry.create(
                entry.getTimestamp(), entry.getDirection(), entry.getSequenceNumber(),
                entry.getStreamName(), entry.getMetadata(), entry.getRawMessage()
        );
    }

    private static long parseIsoTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) {
            throw new IllegalArgumentException("Timestamp is required");
        }
        return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME)
                .toInstant().toEpochMilli();
    }

    private static int getInt(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }
}
