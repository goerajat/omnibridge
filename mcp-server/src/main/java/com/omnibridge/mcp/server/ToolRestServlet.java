package com.omnibridge.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibridge.mcp.tools.GetSessionStatusTool;
import com.omnibridge.mcp.tools.ListStreamsTool;
import com.omnibridge.mcp.tools.QueryFixMessagesTool;
import com.omnibridge.mcp.util.JsonResultBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * REST servlet that exposes MCP tools as simple POST endpoints.
 * <p>
 * POST /api/tools/list_streams         — list available streams
 * POST /api/tools/query_fix_messages   — query FIX messages with filters
 * POST /api/tools/get_session_status   — get session health status
 */
public class ToolRestServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ToolRestServlet.class);
    private static final ObjectMapper MAPPER = JsonResultBuilder.getObjectMapper();

    private final ListStreamsTool listStreamsTool;
    private final QueryFixMessagesTool queryFixMessagesTool;
    private final GetSessionStatusTool getSessionStatusTool;

    public ToolRestServlet(ListStreamsTool listStreamsTool,
                           QueryFixMessagesTool queryFixMessagesTool,
                           GetSessionStatusTool getSessionStatusTool) {
        this.listStreamsTool = listStreamsTool;
        this.queryFixMessagesTool = queryFixMessagesTool;
        this.getSessionStatusTool = getSessionStatusTool;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setContentType("application/json");

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            sendError(resp, 400, "Missing tool name in path. Use /api/tools/{toolName}");
            return;
        }

        String toolName = pathInfo.substring(1); // strip leading /
        Map<String, Object> args;
        try {
            String body = new String(req.getInputStream().readAllBytes());
            if (body.isBlank()) {
                args = Map.of();
            } else {
                args = MAPPER.readValue(body, new TypeReference<>() {});
            }
        } catch (Exception e) {
            sendError(resp, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }

        log.info("REST tool call: {} args={}", toolName, args);

        McpSchema.CallToolResult result;
        try {
            result = switch (toolName) {
                case ListStreamsTool.NAME -> listStreamsTool.execute(args);
                case QueryFixMessagesTool.NAME -> queryFixMessagesTool.execute(args);
                case GetSessionStatusTool.NAME -> getSessionStatusTool.execute(args);
                default -> {
                    sendError(resp, 404, "Unknown tool: " + toolName);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            sendError(resp, 500, "Tool execution failed: " + e.getMessage());
            return;
        }

        if (result == null) return; // already sent 404

        // Extract text content from MCP result
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                sb.append(tc.text());
            }
        }

        resp.setStatus(result.isError() != null && result.isError() ? 500 : 200);
        resp.getWriter().write(sb.toString());
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(204);
    }

    private void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
    }
}
