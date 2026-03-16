package com.connectivity.omniview.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jetty Handler.Wrapper for /api/chat routes.
 * <ul>
 *   <li>POST /api/chat — SSE streaming chat</li>
 *   <li>GET /api/chat/health — health check</li>
 * </ul>
 */
public class ChatHandler extends Handler.Wrapper {

    private static final Logger LOG = LoggerFactory.getLogger(ChatHandler.class);

    private final ChatConfig config;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatHandler(Handler handler, ObjectMapper objectMapper) {
        super(handler);
        this.config = ChatConfig.load();
        this.objectMapper = objectMapper;
        this.chatService = config.enabled()
                ? new ChatService(config, objectMapper)
                : null;

        LOG.info("Chat agent {} (model={}, mcp={})",
                config.enabled() ? "enabled" : "disabled (no API key)",
                config.model(), config.mcpServerUrl());
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();

        if (path.equals("/api/chat/health") && "GET".equals(method)) {
            return handleHealth(response, callback);
        }

        if (path.equals("/api/chat") && "POST".equals(method)) {
            return handleChat(request, response, callback);
        }

        // Not a chat route — delegate to next handler
        return super.handle(request, response, callback);
    }

    private boolean handleHealth(Response response, Callback callback) throws Exception {
        response.getHeaders().put("Content-Type", "application/json");
        response.getHeaders().put("Access-Control-Allow-Origin", "*");
        response.setStatus(200);

        boolean mcpReachable = false;
        if (config.enabled()) {
            try {
                mcpReachable = new McpToolClient(config.mcpServerUrl()).isReachable();
            } catch (Exception e) {
                LOG.debug("MCP health check failed: {}", e.getMessage());
            }
        }

        Map<String, Object> health = Map.of(
                "enabled", config.enabled(),
                "mcpServerReachable", mcpReachable,
                "model", config.model()
        );

        String json = objectMapper.writeValueAsString(health);
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
        return true;
    }

    private boolean handleChat(Request request, Response response, Callback callback) throws Exception {
        response.getHeaders().put("Access-Control-Allow-Origin", "*");

        if (!config.enabled()) {
            response.setStatus(503);
            response.getHeaders().put("Content-Type", "application/json");
            String json = "{\"error\":\"Chat is disabled. Set ANTHROPIC_API_KEY to enable.\"}";
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
            return true;
        }

        // Parse request body
        String body;
        try (InputStream is = Request.asInputStream(request)) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, Object> requestMap = objectMapper.readValue(body,
                new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) requestMap.get("messages");
        if (messages == null || messages.isEmpty()) {
            response.setStatus(400);
            response.getHeaders().put("Content-Type", "application/json");
            String json = "{\"error\":\"messages array is required\"}";
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
            return true;
        }

        // Make a mutable copy
        messages = new ArrayList<>(messages);

        // Set up SSE response
        response.setStatus(200);
        response.getHeaders().put("Content-Type", "text/event-stream");
        response.getHeaders().put("Cache-Control", "no-cache");
        response.getHeaders().put("Connection", "keep-alive");

        // Process chat in a background thread to not block Jetty threads
        List<Map<String, Object>> finalMessages = messages;
        new Thread(() -> {
            try {
                chatService.processChat(finalMessages, event -> {
                    try {
                        String sseData = "data: " + objectMapper.writeValueAsString(
                                Map.of("type", event.type(), "data", event.data())) + "\n\n";
                        response.write(false,
                                ByteBuffer.wrap(sseData.getBytes(StandardCharsets.UTF_8)),
                                Callback.NOOP);
                    } catch (Exception e) {
                        LOG.error("Failed to write SSE event", e);
                    }
                });
                // Final empty write to complete the response
                response.write(true, ByteBuffer.wrap(new byte[0]), callback);
            } catch (Exception e) {
                LOG.error("Chat handler error", e);
                try {
                    String errorSse = "data: " + objectMapper.writeValueAsString(
                            Map.of("type", "error", "data", Map.of("message", e.getMessage()))) + "\n\n";
                    response.write(false,
                            ByteBuffer.wrap(errorSse.getBytes(StandardCharsets.UTF_8)),
                            Callback.NOOP);
                    response.write(true, ByteBuffer.wrap(new byte[0]), callback);
                } catch (Exception ex) {
                    callback.failed(ex);
                }
            }
        }, "chat-handler").start();

        return true;
    }
}
