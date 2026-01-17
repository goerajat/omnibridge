package com.fixengine.engine.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the FIX engine.
 */
public class EngineConfig {

    private String persistencePath;
    private long maxLogFileSize = 1024L * 1024L * 1024L; // 1GB default
    private int cpuAffinity = -1; // -1 = no affinity
    private List<SessionConfig> sessions = new ArrayList<>();

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final EngineConfig config = new EngineConfig();

        public Builder persistencePath(String path) {
            config.persistencePath = path;
            return this;
        }

        public Builder maxLogFileSize(long size) {
            config.maxLogFileSize = size;
            return this;
        }

        public Builder cpuAffinity(int cpu) {
            config.cpuAffinity = cpu;
            return this;
        }

        public Builder addSession(SessionConfig sessionConfig) {
            config.sessions.add(sessionConfig);
            return this;
        }

        public Builder sessions(List<SessionConfig> sessions) {
            config.sessions = new ArrayList<>(sessions);
            return this;
        }

        public EngineConfig build() {
            return config;
        }
    }

    // ==================== Config File Loading ====================

    /**
     * Load engine configuration from a JSON file.
     */
    public static EngineConfig fromJsonFile(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new File(path));

        Builder builder = builder();

        // Engine settings
        if (root.has("persistencePath")) {
            builder.persistencePath(root.get("persistencePath").asText());
        }
        if (root.has("maxLogFileSize")) {
            builder.maxLogFileSize(root.get("maxLogFileSize").asLong());
        }
        if (root.has("cpuAffinity")) {
            builder.cpuAffinity(root.get("cpuAffinity").asInt());
        }

        // Sessions
        if (root.has("sessions")) {
            for (JsonNode sessionNode : root.get("sessions")) {
                builder.addSession(parseSessionConfig(sessionNode));
            }
        }

        return builder.build();
    }

    private static SessionConfig parseSessionConfig(JsonNode node) {
        SessionConfig.Builder builder = SessionConfig.builder();

        // Required fields
        builder.sessionName(node.get("sessionName").asText());
        builder.senderCompId(node.get("senderCompId").asText());
        builder.targetCompId(node.get("targetCompId").asText());
        builder.port(node.get("port").asInt());

        // Role
        String role = node.get("role").asText();
        if ("INITIATOR".equalsIgnoreCase(role)) {
            builder.initiator();
            if (node.has("host")) {
                builder.host(node.get("host").asText());
            }
        } else {
            builder.acceptor();
        }

        // Optional fields
        if (node.has("beginString")) {
            builder.beginString(node.get("beginString").asText());
        }
        if (node.has("heartbeatInterval")) {
            builder.heartbeatInterval(node.get("heartbeatInterval").asInt());
        }
        if (node.has("startTime")) {
            builder.startTime(LocalTime.parse(node.get("startTime").asText()));
        }
        if (node.has("endTime")) {
            builder.endTime(LocalTime.parse(node.get("endTime").asText()));
        }
        if (node.has("timeZone")) {
            builder.timeZone(node.get("timeZone").asText());
        }
        if (node.has("resetOnLogon")) {
            builder.resetOnLogon(node.get("resetOnLogon").asBoolean());
        }
        if (node.has("resetOnLogout")) {
            builder.resetOnLogout(node.get("resetOnLogout").asBoolean());
        }
        if (node.has("resetOnDisconnect")) {
            builder.resetOnDisconnect(node.get("resetOnDisconnect").asBoolean());
        }
        if (node.has("reconnectInterval")) {
            builder.reconnectInterval(node.get("reconnectInterval").asInt());
        }
        if (node.has("maxReconnectAttempts")) {
            builder.maxReconnectAttempts(node.get("maxReconnectAttempts").asInt());
        }
        if (node.has("logMessages")) {
            builder.logMessages(node.get("logMessages").asBoolean());
        }
        if (node.has("persistencePath")) {
            builder.persistencePath(node.get("persistencePath").asText());
        }

        return builder.build();
    }

    // ==================== Getters ====================

    public String getPersistencePath() {
        return persistencePath;
    }

    public long getMaxLogFileSize() {
        return maxLogFileSize;
    }

    public int getCpuAffinity() {
        return cpuAffinity;
    }

    public List<SessionConfig> getSessions() {
        return sessions;
    }

    @Override
    public String toString() {
        return "EngineConfig{" +
                "persistencePath='" + persistencePath + '\'' +
                ", maxLogFileSize=" + maxLogFileSize +
                ", cpuAffinity=" + cpuAffinity +
                ", sessions=" + sessions.size() +
                '}';
    }
}
