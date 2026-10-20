package com.omnibridge.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MCP server parses each tool's INPUT_SCHEMA at startup; one malformed schema
 * stops the whole server, so every schema must be valid JSON describing an object.
 */
class ToolSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void assertValidSchema(String schema) throws Exception {
        JsonNode node = MAPPER.readTree(schema);
        assertEquals("object", node.path("type").asText());
        assertTrue(node.path("properties").isObject(), "schema has no properties object");
    }

    @Test
    void listStreamsSchemaIsValidJson() throws Exception {
        assertValidSchema(ListStreamsTool.INPUT_SCHEMA);
    }

    @Test
    void queryFixMessagesSchemaIsValidJson() throws Exception {
        assertValidSchema(QueryFixMessagesTool.INPUT_SCHEMA);
    }

    @Test
    void getSessionStatusSchemaIsValidJson() throws Exception {
        assertValidSchema(GetSessionStatusTool.INPUT_SCHEMA);
    }

    @Test
    void msgTypeExampleKeepsItsQuotes() throws Exception {
        String description = MAPPER.readTree(QueryFixMessagesTool.INPUT_SCHEMA)
                .path("properties").path("msg_type").path("description").asText();
        assertTrue(description.contains("[\"D\", \"8\", \"F\"]"), description);
    }
}
