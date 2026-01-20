package com.fixengine.config;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for FIX engine sessions.
 * Contains session configurations only. Network and persistence are independent configs.
 */
public final class FixEngineConfig {

    private final List<EngineSessionConfig> sessions;

    private FixEngineConfig(Builder builder) {
        this.sessions = Collections.unmodifiableList(new ArrayList<>(builder.sessions));
    }

    /**
     * Create configuration from Typesafe Config.
     */
    public static FixEngineConfig fromConfig(Config config) {
        Config fixEngineConfig = config.getConfig("fix-engine");

        Builder builder = builder();

        if (fixEngineConfig.hasPath("sessions")) {
            List<? extends Config> sessionConfigs = fixEngineConfig.getConfigList("sessions");
            for (Config sessionConfig : sessionConfigs) {
                builder.addSession(EngineSessionConfig.fromConfig(sessionConfig));
            }
        }

        return builder.build();
    }

    public List<EngineSessionConfig> getSessions() {
        return sessions;
    }

    /**
     * Find a session configuration by name.
     */
    public EngineSessionConfig getSession(String sessionName) {
        return sessions.stream()
                .filter(s -> s.getSessionName().equals(sessionName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find a session configuration by session ID.
     */
    public EngineSessionConfig getSessionById(String sessionId) {
        return sessions.stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst()
                .orElse(null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<EngineSessionConfig> sessions = new ArrayList<>();

        private Builder() {}

        public Builder addSession(EngineSessionConfig session) {
            this.sessions.add(session);
            return this;
        }

        public Builder sessions(List<EngineSessionConfig> sessions) {
            this.sessions.clear();
            this.sessions.addAll(sessions);
            return this;
        }

        public FixEngineConfig build() {
            return new FixEngineConfig(this);
        }
    }

    @Override
    public String toString() {
        return "FixEngineConfig{" +
                "sessions=" + sessions.size() +
                '}';
    }
}
