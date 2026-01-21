package com.fixengine.engine.config;

import com.fixengine.message.Clock;
import com.fixengine.message.SystemClock;

import java.time.LocalTime;

/**
 * Configuration for a FIX session.
 */
public class SessionConfig {

    /**
     * Session role - initiator or acceptor.
     */
    public enum Role {
        INITIATOR,
        ACCEPTOR
    }

    private String sessionName;
    private String beginString = "FIX.4.4";
    private String senderCompId;
    private String targetCompId;
    private Role role;

    // Network settings
    private String host;
    private int port;

    // Session schedule
    private LocalTime startTime;
    private LocalTime endTime;
    private String timeZone = "UTC";

    // End of Day settings
    private LocalTime eodTime;           // End of Day time for sequence reset
    private boolean resetOnEod = false;  // Whether to reset sequence numbers at EOD

    // Session behavior
    private int heartbeatInterval = 30;
    private boolean resetOnLogon = false;
    private boolean resetOnLogout = false;
    private boolean resetOnDisconnect = false;

    // Persistence
    private String persistencePath;

    // Reconnection
    private int reconnectInterval = 5;
    private int maxReconnectAttempts = -1; // -1 = unlimited

    // Logging
    private boolean logMessages = true;

    // Message encoding
    private int maxMessageLength = 4096;
    private int maxTagNumber = 1000;

    // Ring buffer capacity for outgoing messages (must be power of 2)
    private int ringBufferCapacity = 1048576; // 1MB default

    // Clock for time sources (allows testing with mock clocks)
    private Clock clock = SystemClock.INSTANCE;

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SessionConfig config = new SessionConfig();

        public Builder sessionName(String name) {
            config.sessionName = name;
            return this;
        }

        public Builder beginString(String beginString) {
            config.beginString = beginString;
            return this;
        }

        public Builder senderCompId(String senderCompId) {
            config.senderCompId = senderCompId;
            return this;
        }

        public Builder targetCompId(String targetCompId) {
            config.targetCompId = targetCompId;
            return this;
        }

        public Builder role(Role role) {
            config.role = role;
            return this;
        }

        public Builder initiator() {
            config.role = Role.INITIATOR;
            return this;
        }

        public Builder acceptor() {
            config.role = Role.ACCEPTOR;
            return this;
        }

        public Builder host(String host) {
            config.host = host;
            return this;
        }

        public Builder port(int port) {
            config.port = port;
            return this;
        }

        public Builder startTime(LocalTime startTime) {
            config.startTime = startTime;
            return this;
        }

        public Builder endTime(LocalTime endTime) {
            config.endTime = endTime;
            return this;
        }

        public Builder timeZone(String timeZone) {
            config.timeZone = timeZone;
            return this;
        }

        public Builder eodTime(LocalTime eodTime) {
            config.eodTime = eodTime;
            return this;
        }

        public Builder resetOnEod(boolean resetOnEod) {
            config.resetOnEod = resetOnEod;
            return this;
        }

        public Builder heartbeatInterval(int seconds) {
            config.heartbeatInterval = seconds;
            return this;
        }

        public Builder resetOnLogon(boolean reset) {
            config.resetOnLogon = reset;
            return this;
        }

        public Builder resetOnLogout(boolean reset) {
            config.resetOnLogout = reset;
            return this;
        }

        public Builder resetOnDisconnect(boolean reset) {
            config.resetOnDisconnect = reset;
            return this;
        }

        public Builder persistencePath(String path) {
            config.persistencePath = path;
            return this;
        }

        public Builder reconnectInterval(int seconds) {
            config.reconnectInterval = seconds;
            return this;
        }

        public Builder maxReconnectAttempts(int attempts) {
            config.maxReconnectAttempts = attempts;
            return this;
        }

        public Builder logMessages(boolean log) {
            config.logMessages = log;
            return this;
        }

        public Builder maxMessageLength(int length) {
            config.maxMessageLength = length;
            return this;
        }

        public Builder maxTagNumber(int maxTag) {
            config.maxTagNumber = maxTag;
            return this;
        }

        public Builder ringBufferCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Ring buffer capacity must be positive: " + capacity);
            }
            // Validate power of 2
            if ((capacity & (capacity - 1)) != 0) {
                throw new IllegalArgumentException("Ring buffer capacity must be power of 2: " + capacity);
            }
            config.ringBufferCapacity = capacity;
            return this;
        }

        public Builder clock(Clock clock) {
            if (clock == null) {
                throw new IllegalArgumentException("Clock cannot be null");
            }
            config.clock = clock;
            return this;
        }

        public SessionConfig build() {
            validate();
            return config;
        }

        private void validate() {
            if (config.sessionName == null || config.sessionName.isEmpty()) {
                throw new IllegalArgumentException("Session name is required");
            }
            if (config.senderCompId == null || config.senderCompId.isEmpty()) {
                throw new IllegalArgumentException("SenderCompID is required");
            }
            if (config.targetCompId == null || config.targetCompId.isEmpty()) {
                throw new IllegalArgumentException("TargetCompID is required");
            }
            if (config.role == null) {
                throw new IllegalArgumentException("Role is required");
            }
            if (config.role == Role.INITIATOR && (config.host == null || config.host.isEmpty())) {
                throw new IllegalArgumentException("Host is required for initiator");
            }
            if (config.port <= 0) {
                throw new IllegalArgumentException("Port must be positive");
            }
        }
    }

    // ==================== Getters ====================

    public String getSessionName() {
        return sessionName;
    }

    public String getBeginString() {
        return beginString;
    }

    public String getSenderCompId() {
        return senderCompId;
    }

    public String getTargetCompId() {
        return targetCompId;
    }

    public Role getRole() {
        return role;
    }

    public boolean isInitiator() {
        return role == Role.INITIATOR;
    }

    public boolean isAcceptor() {
        return role == Role.ACCEPTOR;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public LocalTime getEodTime() {
        return eodTime;
    }

    public boolean isResetOnEod() {
        return resetOnEod;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public boolean isResetOnLogon() {
        return resetOnLogon;
    }

    public boolean isResetOnLogout() {
        return resetOnLogout;
    }

    public boolean isResetOnDisconnect() {
        return resetOnDisconnect;
    }

    public String getPersistencePath() {
        return persistencePath;
    }

    public int getReconnectInterval() {
        return reconnectInterval;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public boolean isLogMessages() {
        return logMessages;
    }

    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    public int getMaxTagNumber() {
        return maxTagNumber;
    }

    /**
     * Get the ring buffer capacity for outgoing messages.
     *
     * @return the ring buffer capacity in bytes
     */
    public int getRingBufferCapacity() {
        return ringBufferCapacity;
    }

    /**
     * Get the clock used for timestamps and timing.
     *
     * @return the clock instance
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Get the session ID string (SenderCompID->TargetCompID).
     */
    public String getSessionId() {
        return senderCompId + "->" + targetCompId;
    }

    @Override
    public String toString() {
        return "SessionConfig{" +
                "sessionName='" + sessionName + '\'' +
                ", senderCompId='" + senderCompId + '\'' +
                ", targetCompId='" + targetCompId + '\'' +
                ", role=" + role +
                ", host='" + host + '\'' +
                ", port=" + port +
                '}';
    }
}
