package com.connectivity.omniview.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orchestrates Claude API conversations with MCP tool calling and SSE streaming.
 * <p>
 * Flow: user message -> Claude API (streaming) -> tool_use -> MCP REST -> tool_result -> Claude -> text response
 */
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_TOOL_ROUNDS = 5;

    private final ChatConfig config;
    private final McpToolClient mcpClient;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // Tool definitions for Claude (matches MCP server tools)
    private static final String TOOLS_JSON = """
            [
              {
                "name": "list_streams",
                "description": "Lists all available FIX message streams with entry counts, time ranges, and CompID mappings. Use publisher_id to filter streams from a specific Aeron publisher.",
                "input_schema": {
                  "type": "object",
                  "properties": {
                    "publisher_id": {
                      "type": "string",
                      "description": "Optional. Filter streams by Aeron publisher ID (matches pub~{id}~ prefix)"
                    }
                  }
                }
              },
              {
                "name": "query_fix_messages",
                "description": "Query FIX messages from the persistence store with time range, field filters, and format options. Returns matching messages with metadata. Use start_time and end_time (ISO-8601) to bound the search.",
                "input_schema": {
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
                      "description": "Filter by stream name"
                    },
                    "publisher_id": {
                      "type": "string",
                      "description": "Filter streams by Aeron publisher ID"
                    },
                    "direction": {
                      "type": "string",
                      "enum": ["INBOUND", "OUTBOUND"],
                      "description": "Filter by message direction"
                    },
                    "msg_type": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Filter by FIX MsgType values (e.g., [\\"D\\", \\"8\\", \\"F\\"])"
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
                      "description": "Filter by ExecType (tag 150)"
                    },
                    "text_contains": {
                      "type": "string",
                      "description": "Search for text in raw message content"
                    },
                    "from_seq": {
                      "type": "integer",
                      "description": "Filter messages with sequence number >= this value"
                    },
                    "to_seq": {
                      "type": "integer",
                      "description": "Filter messages with sequence number <= this value"
                    },
                    "skip_admin": {
                      "type": "boolean",
                      "description": "Skip admin/session messages (Heartbeat, Logon, etc). Default: true"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "Max messages to return. Default: 100"
                    },
                    "offset": {
                      "type": "integer",
                      "description": "Skip first N matching messages"
                    },
                    "format": {
                      "type": "string",
                      "enum": ["summary", "full", "raw"],
                      "description": "Output format. summary=key fields, full=all tags, raw=original message"
                    }
                  },
                  "required": ["start_time", "end_time"]
                },
                "cache_control": {"type": "ephemeral"}
              },
              {
                "name": "get_session_status",
                "description": "Get session health status including logon/logout events, message counts by type, sequence number gaps, and session duration. Analyzes a single day's activity.",
                "input_schema": {
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
              }
            ]
            """;

    private static String buildSystemPrompt() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return """
                You are a FIX protocol message analyst for OmniView. You help users query and \
                analyze FIX messages stored in a persistence system.

                Today's date (UTC): %s

                Guidelines:
                - "today" = %s, "last hour" = compute from now UTC, "recent" = last 2 hours
                - Default to skip_admin: true unless user asks about heartbeats/admin messages
                - Start with summary format; offer full/raw if user needs more detail
                - For order lifecycle, query by clordid to get all related messages
                - Present clear summaries with key fields: symbol, side, quantity, price, status
                - Summarize patterns for large result sets rather than listing every message
                - When showing times, convert from ISO-8601 to a human-readable format
                - If a query returns no results, suggest broadening the time range or filters
                """.formatted(today, today);
    }

    public ChatService(ChatConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mcpClient = new McpToolClient(config.mcpServerUrl());
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * SSE event sent to the browser.
     */
    public record SseEvent(String type, Object data) {}

    /**
     * Process a chat request, streaming SSE events to the given consumer.
     *
     * @param messages conversation history [{role, content}, ...]
     * @param eventSink receives SSE events to write to the response
     */
    public void processChat(List<Map<String, Object>> messages, Consumer<SseEvent> eventSink) {
        try {
            eventSink.accept(new SseEvent("status", Map.of("message", "Analyzing...")));
            processWithToolLoop(messages, eventSink, 0);
            eventSink.accept(new SseEvent("done", Map.of()));
        } catch (Exception e) {
            LOG.error("Chat processing failed", e);
            eventSink.accept(new SseEvent("error", Map.of("message", e.getMessage())));
            eventSink.accept(new SseEvent("done", Map.of()));
        }
    }

    private void processWithToolLoop(List<Map<String, Object>> messages,
                                      Consumer<SseEvent> eventSink,
                                      int round) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) {
            eventSink.accept(new SseEvent("text", Map.of("content",
                    "I've reached the maximum number of tool calls. Here's what I found so far.")));
            return;
        }

        // Build and send Claude API request
        String requestBody = buildClaudeRequest(messages);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CLAUDE_API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            LOG.error("Claude API returned {}: {}", response.statusCode(), errorBody);
            throw new RuntimeException("Claude API error: " + response.statusCode());
        }

        // Parse SSE stream from Claude
        ParseResult parseResult = parseClaudeStream(response.body(), eventSink);

        // If Claude requested tool calls, execute them and continue
        if (!parseResult.toolCalls.isEmpty()) {
            // Build assistant message with all content blocks
            ArrayNode assistantContent = mapper.createArrayNode();
            for (ContentBlock block : parseResult.contentBlocks) {
                if ("text".equals(block.type)) {
                    ObjectNode textNode = mapper.createObjectNode();
                    textNode.put("type", "text");
                    textNode.put("text", block.text);
                    assistantContent.add(textNode);
                } else if ("tool_use".equals(block.type)) {
                    ObjectNode toolNode = mapper.createObjectNode();
                    toolNode.put("type", "tool_use");
                    toolNode.put("id", block.toolUseId);
                    toolNode.put("name", block.toolName);
                    toolNode.set("input", mapper.readTree(block.toolInput));
                    assistantContent.add(toolNode);
                }
            }

            // Add assistant message to conversation
            Map<String, Object> assistantMsg = Map.of("role", "assistant", "content", assistantContent);
            messages.add(assistantMsg);

            // Execute tools and build tool_result messages
            ArrayNode toolResults = mapper.createArrayNode();
            for (ToolCall tc : parseResult.toolCalls) {
                eventSink.accept(new SseEvent("tool_call", Map.of(
                        "tool", tc.name, "id", tc.id, "args", mapper.readTree(tc.inputJson))));

                String toolResult;
                boolean isError = false;
                try {
                    toolResult = mcpClient.callTool(tc.name, tc.inputJson);
                } catch (Exception e) {
                    LOG.warn("Tool call failed: {} - {}", tc.name, e.getMessage());
                    toolResult = "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
                    isError = true;
                }

                eventSink.accept(new SseEvent("tool_result", Map.of(
                        "tool", tc.name, "id", tc.id,
                        "data", mapper.readTree(toolResult),
                        "is_error", isError)));

                ObjectNode resultNode = mapper.createObjectNode();
                resultNode.put("type", "tool_result");
                resultNode.put("tool_use_id", tc.id);
                resultNode.put("content", toolResult);
                if (isError) resultNode.put("is_error", true);
                toolResults.add(resultNode);
            }

            // Add tool results as user message
            messages.add(Map.of("role", "user", "content", toolResults));

            // Continue conversation
            processWithToolLoop(messages, eventSink, round + 1);
        }
    }

    private String buildClaudeRequest(List<Map<String, Object>> messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.model());
        root.put("max_tokens", 4096);
        root.put("stream", true);

        // System prompt
        ArrayNode systemArray = mapper.createArrayNode();
        ObjectNode systemBlock = mapper.createObjectNode();
        systemBlock.put("type", "text");
        systemBlock.put("text", buildSystemPrompt());
        systemBlock.set("cache_control", mapper.readTree("{\"type\":\"ephemeral\"}"));
        systemArray.add(systemBlock);
        root.set("system", systemArray);

        // Tools
        root.set("tools", mapper.readTree(TOOLS_JSON));

        // Messages
        root.set("messages", mapper.valueToTree(messages));

        return mapper.writeValueAsString(root);
    }

    // --- SSE parsing ---

    private record ToolCall(String id, String name, String inputJson) {}
    private record ContentBlock(String type, String text, String toolUseId, String toolName, String toolInput) {}

    private record ParseResult(List<ToolCall> toolCalls, List<ContentBlock> contentBlocks) {}

    private ParseResult parseClaudeStream(java.io.InputStream inputStream,
                                           Consumer<SseEvent> eventSink) throws Exception {
        java.util.ArrayList<ToolCall> toolCalls = new java.util.ArrayList<>();
        java.util.ArrayList<ContentBlock> contentBlocks = new java.util.ArrayList<>();

        // Track current content block state
        String currentBlockType = null;
        int currentBlockIndex = -1;
        StringBuilder currentText = new StringBuilder();
        StringBuilder currentToolInput = new StringBuilder();
        String currentToolId = null;
        String currentToolName = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.isEmpty()) continue;

                    JsonNode event;
                    try {
                        event = mapper.readTree(data);
                    } catch (Exception e) {
                        continue; // skip malformed events
                    }

                    String type = event.has("type") ? event.get("type").asText() : "";

                    switch (type) {
                        case "content_block_start" -> {
                            JsonNode block = event.get("content_block");
                            currentBlockIndex = event.get("index").asInt();
                            currentBlockType = block.get("type").asText();
                            currentText.setLength(0);
                            currentToolInput.setLength(0);

                            if ("tool_use".equals(currentBlockType)) {
                                currentToolId = block.get("id").asText();
                                currentToolName = block.get("name").asText();
                            }
                        }
                        case "content_block_delta" -> {
                            JsonNode delta = event.get("delta");
                            String deltaType = delta.get("type").asText();

                            if ("text_delta".equals(deltaType)) {
                                String text = delta.get("text").asText();
                                currentText.append(text);
                                eventSink.accept(new SseEvent("text", Map.of("content", text)));
                            } else if ("input_json_delta".equals(deltaType)) {
                                currentToolInput.append(delta.get("partial_json").asText());
                            }
                        }
                        case "content_block_stop" -> {
                            if ("text".equals(currentBlockType)) {
                                contentBlocks.add(new ContentBlock("text", currentText.toString(),
                                        null, null, null));
                            } else if ("tool_use".equals(currentBlockType)) {
                                String inputJson = currentToolInput.toString();
                                if (inputJson.isEmpty()) inputJson = "{}";
                                contentBlocks.add(new ContentBlock("tool_use", null,
                                        currentToolId, currentToolName, inputJson));
                                toolCalls.add(new ToolCall(currentToolId, currentToolName, inputJson));
                            }
                            currentBlockType = null;
                        }
                        case "message_delta" -> {
                            JsonNode delta = event.get("delta");
                            if (delta.has("stop_reason")) {
                                String stopReason = delta.get("stop_reason").asText();
                                LOG.debug("Claude stop_reason: {}", stopReason);
                            }
                            // Log cache usage if available
                            if (event.has("usage")) {
                                JsonNode usage = event.get("usage");
                                LOG.info("Token usage: output={}{}{}",
                                        usage.has("output_tokens") ? usage.get("output_tokens").asInt() : "?",
                                        usage.has("cache_read_input_tokens") ?
                                                ", cache_read=" + usage.get("cache_read_input_tokens").asInt() : "",
                                        usage.has("cache_creation_input_tokens") ?
                                                ", cache_write=" + usage.get("cache_creation_input_tokens").asInt() : "");
                            }
                        }
                        case "message_start" -> {
                            if (event.has("message") && event.get("message").has("usage")) {
                                JsonNode usage = event.get("message").get("usage");
                                LOG.info("Input tokens: {}{}{}",
                                        usage.has("input_tokens") ? usage.get("input_tokens").asInt() : "?",
                                        usage.has("cache_read_input_tokens") ?
                                                ", cache_read=" + usage.get("cache_read_input_tokens").asInt() : "",
                                        usage.has("cache_creation_input_tokens") ?
                                                ", cache_write=" + usage.get("cache_creation_input_tokens").asInt() : "");
                            }
                        }
                        case "error" -> {
                            String errorMsg = event.has("error") ?
                                    event.get("error").get("message").asText() : "Unknown error";
                            LOG.error("Claude stream error: {}", errorMsg);
                            throw new RuntimeException("Claude API error: " + errorMsg);
                        }
                        // ping, message_stop — ignore
                    }
                }
            }
        }

        return new ParseResult(toolCalls, contentBlocks);
    }
}
