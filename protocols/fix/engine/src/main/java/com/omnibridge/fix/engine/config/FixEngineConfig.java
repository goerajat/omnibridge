package com.omnibridge.fix.engine.config;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for FIX engine sessions.
 * Contains session configurations only. Network and persistence are independent configs.
 */
public final class FixEngineConfig {

    /** Default rows of stage timestamps kept per session when stage timing is enabled. */
    public static final int DEFAULT_STAGE_TIMING_CAPACITY = 1 << 17;

    private final List<EngineSessionConfig> sessions;
    private final int stageTimingCapacity;

    private FixEngineConfig(Builder builder) {
        this.sessions = Collections.unmodifiableList(new ArrayList<>(builder.sessions));
        this.stageTimingCapacity = builder.stageTimingCapacity;
    }

    /**
     * Create configuration from Typesafe Config.
     */
    public static FixEngineConfig fromConfig(Config config) {
        Config fixEngineConfig = config.getConfig("fix-engine");

        Builder builder = builder();

        // fix-engine.stage-timing { enabled, capacity }: engine-wide, so -D can switch it on
        if (fixEngineConfig.hasPath("stage-timing.enabled")
                && fixEngineConfig.getBoolean("stage-timing.enabled")) {
            builder.stageTimingCapacity(fixEngineConfig.hasPath("stage-timing.capacity")
                    ? fixEngineConfig.getInt("stage-timing.capacity") : DEFAULT_STAGE_TIMING_CAPACITY);
        }

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

    /** Rows of stage timestamps each session keeps; 0 when stage timing is off. */
    public int getStageTimingCapacity() {
        return stageTimingCapacity;
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
        private int stageTimingCapacity;

        private Builder() {}

        public Builder stageTimingCapacity(int capacity) {
            this.stageTimingCapacity = capacity;
            return this;
        }

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
