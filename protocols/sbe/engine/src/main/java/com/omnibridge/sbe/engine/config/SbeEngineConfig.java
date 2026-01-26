package com.omnibridge.sbe.engine.config;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Base configuration for an SBE engine.
 * <p>
 * Protocol-specific implementations (iLink 3, Optiq) should extend this class
 * to add protocol-specific configuration options.
 *
 * @param <S> the session config type
 */
public abstract class SbeEngineConfig<S extends SbeSessionConfig> {

    private String persistencePath;
    private long maxLogFileSize = 1024L * 1024 * 1024; // 1GB
    private int cpuAffinity = -1;
    private boolean busySpinMode = false;
    private final List<S> sessions = new ArrayList<>();

    public SbeEngineConfig() {
    }

    /**
     * Load base configuration from HOCON config.
     * Subclasses should call this method and then load additional fields.
     *
     * @param config the HOCON configuration
     */
    protected void loadFromConfig(Config config) {
        if (config.hasPath("persistence.path")) {
            this.persistencePath = config.getString("persistence.path");
        }
        if (config.hasPath("persistence.max-log-file-size")) {
            this.maxLogFileSize = config.getBytes("persistence.max-log-file-size");
        }
        if (config.hasPath("cpu-affinity")) {
            this.cpuAffinity = config.getInt("cpu-affinity");
        }
        if (config.hasPath("busy-spin-mode")) {
            this.busySpinMode = config.getBoolean("busy-spin-mode");
        }
    }

    /**
     * Creates a session config from the given HOCON config.
     * Subclasses must implement this to create protocol-specific session configs.
     *
     * @param config the HOCON configuration for a single session
     * @return the session configuration
     */
    protected abstract S createSessionConfig(Config config);

    /**
     * Loads sessions from the HOCON config.
     *
     * @param config the HOCON configuration containing "sessions" list
     */
    protected void loadSessionsFromConfig(Config config) {
        if (config.hasPath("sessions")) {
            for (Config sessionConfig : config.getConfigList("sessions")) {
                addSession(createSessionConfig(sessionConfig));
            }
        }
    }

    // Getters and setters

    public String getPersistencePath() {
        return persistencePath;
    }

    public void setPersistencePath(String persistencePath) {
        this.persistencePath = persistencePath;
    }

    public long getMaxLogFileSize() {
        return maxLogFileSize;
    }

    public void setMaxLogFileSize(long maxLogFileSize) {
        this.maxLogFileSize = maxLogFileSize;
    }

    public int getCpuAffinity() {
        return cpuAffinity;
    }

    public void setCpuAffinity(int cpuAffinity) {
        this.cpuAffinity = cpuAffinity;
    }

    public boolean isBusySpinMode() {
        return busySpinMode;
    }

    public void setBusySpinMode(boolean busySpinMode) {
        this.busySpinMode = busySpinMode;
    }

    public List<S> getSessions() {
        return sessions;
    }

    public void addSession(S session) {
        this.sessions.add(session);
    }

    public S getSession(String sessionId) {
        return sessions.stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst()
                .orElse(null);
    }
}
