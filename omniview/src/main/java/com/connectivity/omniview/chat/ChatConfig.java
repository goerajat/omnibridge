package com.connectivity.omniview.chat;

/**
 * Configuration for the chat agent system.
 * Reads from environment variables and system properties.
 */
public record ChatConfig(
        String apiKey,
        String mcpServerUrl,
        String model,
        boolean enabled
) {
    private static final String DEFAULT_MCP_URL = "http://10.0.3.231:8090";
    private static final String DEFAULT_MODEL = "claude-haiku-4-5-20251001";

    public static ChatConfig load() {
        String apiKey = resolve("ANTHROPIC_API_KEY", "omniview.anthropic.api.key", null);
        String mcpUrl = resolve("MCP_SERVER_URL", "omniview.mcp.url", DEFAULT_MCP_URL);
        String model = resolve("OMNIVIEW_CHAT_MODEL", "omniview.chat.model", DEFAULT_MODEL);
        boolean enabled = apiKey != null && !apiKey.isBlank();

        return new ChatConfig(apiKey, mcpUrl, model, enabled);
    }

    private static String resolve(String envVar, String sysProp, String defaultValue) {
        String value = System.getProperty(sysProp);
        if (value != null && !value.isBlank()) return value;
        value = System.getenv(envVar);
        if (value != null && !value.isBlank()) return value;
        return defaultValue;
    }
}
