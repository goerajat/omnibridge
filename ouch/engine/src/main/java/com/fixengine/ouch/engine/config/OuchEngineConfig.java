package com.fixengine.ouch.engine.config;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the OUCH engine.
 */
public class OuchEngineConfig {

    private String persistencePath;
    private long maxLogFileSize = 1024L * 1024 * 1024; // 1GB
    private int cpuAffinity = -1;
    private boolean busySpinMode = false;
    private List<OuchSessionConfig> sessions = new ArrayList<>();

    public OuchEngineConfig() {
    }

    /**
     * Load configuration from HOCON config.
     */
    public static OuchEngineConfig fromConfig(Config config) {
        OuchEngineConfig engineConfig = new OuchEngineConfig();

        if (config.hasPath("persistence.path")) {
            engineConfig.setPersistencePath(config.getString("persistence.path"));
        }
        if (config.hasPath("persistence.max-log-file-size")) {
            engineConfig.setMaxLogFileSize(config.getBytes("persistence.max-log-file-size"));
        }
        if (config.hasPath("cpu-affinity")) {
            engineConfig.setCpuAffinity(config.getInt("cpu-affinity"));
        }
        if (config.hasPath("busy-spin-mode")) {
            engineConfig.setBusySpinMode(config.getBoolean("busy-spin-mode"));
        }

        // Load sessions
        if (config.hasPath("sessions")) {
            for (Config sessionConfig : config.getConfigList("sessions")) {
                engineConfig.addSession(OuchSessionConfig.fromConfig(sessionConfig));
            }
        }

        return engineConfig;
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

    public List<OuchSessionConfig> getSessions() {
        return sessions;
    }

    public void setSessions(List<OuchSessionConfig> sessions) {
        this.sessions = sessions;
    }

    public void addSession(OuchSessionConfig session) {
        this.sessions.add(session);
    }

    public OuchSessionConfig getSession(String sessionId) {
        return sessions.stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst()
                .orElse(null);
    }
}
