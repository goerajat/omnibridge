package com.omnibridge.mcp.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson helpers for building JSON results for MCP tool responses.
 */
public final class JsonResultBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonResultBuilder() {}

    /**
     * Serialize an object to a JSON string.
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"JSON serialization failed: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Serialize an object to a pretty-printed JSON string.
     */
    public static String toJsonPretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"JSON serialization failed: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get the shared ObjectMapper for MCP transport use.
     */
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }
}
