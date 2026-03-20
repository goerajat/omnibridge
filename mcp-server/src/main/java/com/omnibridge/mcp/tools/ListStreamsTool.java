package com.omnibridge.mcp.tools;

import com.omnibridge.mcp.index.SecondaryIndex;
import com.omnibridge.mcp.util.FIXMessageParser;
import com.omnibridge.mcp.util.JsonResultBuilder;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * MCP tool: list_streams
 *
 * <p>Lists all streams with entry counts, time ranges, and CompID mappings.</p>
 */
public class ListStreamsTool {

    private static final Logger log = LoggerFactory.getLogger(ListStreamsTool.class);

    public static final String NAME = "list_streams";
    public static final String DESCRIPTION =
            "Lists all available FIX message streams with entry counts, time ranges, and CompID mappings. " +
            "Use publisher_id to filter streams from a specific Aeron publisher.";

    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "publisher_id": {
                  "type": "string",
                  "description": "Optional. Filter streams by Aeron publisher ID (matches pub~{id}~ prefix)"
                }
              }
            }
            """;

    private final LogStore logStore;
    private final SecondaryIndex index;

    public ListStreamsTool(LogStore logStore, SecondaryIndex index) {
        this.logStore = logStore;
        this.index = index;
    }

    public McpSchema.CallToolResult execute(Map<String, Object> args) {
        String publisherId = args != null ? (String) args.get("publisher_id") : null;

        try {
            Collection<String> streams = logStore.getStreamNames();
            List<Map<String, Object>> results = new ArrayList<>();

            for (String stream : streams.stream().sorted().toList()) {
                // Filter by publisher_id prefix
                if (publisherId != null && !stream.startsWith("pub~" + publisherId + "~")) {
                    continue;
                }

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("stream", stream);

                long count = logStore.getEntryCount(stream);
                info.put("entry_count", count);

                // Get time range from first and last entries
                LogEntry latestIn = logStore.getLatest(stream, LogEntry.Direction.INBOUND);
                LogEntry latestOut = logStore.getLatest(stream, LogEntry.Direction.OUTBOUND);

                long[] firstTimestamp = {Long.MAX_VALUE};
                long[] lastTimestamp = {0};
                String[] senderCompId = {null};
                String[] targetCompId = {null};

                // Replay first few entries to get CompIDs and first timestamp
                logStore.replay(stream, null, 0, 0, entry -> {
                    if (firstTimestamp[0] == Long.MAX_VALUE) {
                        firstTimestamp[0] = entry.getTimestamp();
                    }
                    lastTimestamp[0] = entry.getTimestamp();

                    if (senderCompId[0] == null) {
                        byte[] raw = entry.getRawMessage();
                        if (raw != null) {
                            senderCompId[0] = FIXMessageParser.extractSenderCompId(raw);
                            targetCompId[0] = FIXMessageParser.extractTargetCompId(raw);
                        }
                    }
                    // Only read first 10 entries for comp IDs, but iterate all for time range
                    return true;
                });

                if (firstTimestamp[0] != Long.MAX_VALUE) {
                    info.put("first_timestamp", Instant.ofEpochMilli(firstTimestamp[0]).toString());
                }
                if (lastTimestamp[0] > 0) {
                    info.put("last_timestamp", Instant.ofEpochMilli(lastTimestamp[0]).toString());
                }

                if (latestIn != null) {
                    info.put("last_inbound_seq", latestIn.getSequenceNumber());
                }
                if (latestOut != null) {
                    info.put("last_outbound_seq", latestOut.getSequenceNumber());
                }

                if (senderCompId[0] != null) info.put("sender_comp_id", senderCompId[0]);
                if (targetCompId[0] != null) info.put("target_comp_id", targetCompId[0]);

                // Index status
                if (index != null && index.isOpen()) {
                    try {
                        long indexedPos = index.getLastIndexedPosition(stream);
                        info.put("last_indexed_position", indexedPos);
                    } catch (Exception e) {
                        info.put("index_status", "error");
                    }
                }

                results.add(info);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total_streams", results.size());
            response.put("store_path", logStore.getStorePath());
            response.put("streams", results);

            String json = JsonResultBuilder.toJson(response);
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false);

        } catch (Exception e) {
            log.error("list_streams failed", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
        }
    }
}
