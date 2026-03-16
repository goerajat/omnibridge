package com.connectivity.omniview.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for calling MCP server tool REST endpoints.
 */
public class McpToolClient {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;

    public McpToolClient(String mcpServerUrl) {
        this.baseUrl = mcpServerUrl.endsWith("/") ? mcpServerUrl : mcpServerUrl + "/";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Call a tool on the MCP server REST API.
     *
     * @param toolName the tool name (e.g., "list_streams")
     * @param argsJson JSON string of tool arguments
     * @return JSON response string
     */
    public String callTool(String toolName, String argsJson) throws Exception {
        String url = baseUrl + "api/tools/" + toolName;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(argsJson))
                .build();

        LOG.debug("Calling MCP tool: {} args={}", toolName, argsJson);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            LOG.warn("MCP tool {} returned status {}: {}", toolName, response.statusCode(), response.body());
            throw new RuntimeException("MCP tool " + toolName + " failed with status " + response.statusCode()
                    + ": " + response.body());
        }

        return response.body();
    }

    /**
     * Check if the MCP server is reachable.
     */
    public boolean isReachable() {
        try {
            String result = callTool("list_streams", "{}");
            return result != null && !result.isEmpty();
        } catch (Exception e) {
            LOG.debug("MCP server not reachable: {}", e.getMessage());
            return false;
        }
    }
}
