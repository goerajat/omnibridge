package com.omnibridge.fix.reference.initiator;

/**
 * Configuration for the Reference FIX Initiator.
 */
public class InitiatorConfig {

    private final String host;
    private final int port;
    private final String senderCompId;
    private final String targetCompId;
    private final String beginString;
    private final int heartbeatInterval;
    private final boolean resetOnLogon;
    private final int reconnectInterval;
    private final boolean customConfig;
    private final String defaultApplVerID;  // FIX 5.0+

    private InitiatorConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.senderCompId = builder.senderCompId;
        this.targetCompId = builder.targetCompId;
        this.beginString = builder.beginString;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.resetOnLogon = builder.resetOnLogon;
        this.reconnectInterval = builder.reconnectInterval;
        this.customConfig = builder.customConfig;
        this.defaultApplVerID = builder.defaultApplVerID;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getSenderCompId() {
        return senderCompId;
    }

    public String getTargetCompId() {
        return targetCompId;
    }

    public String getBeginString() {
        return beginString;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public boolean isResetOnLogon() {
        return resetOnLogon;
    }

    public int getReconnectInterval() {
        return reconnectInterval;
    }

    public boolean isCustomConfig() {
        return customConfig;
    }

    public String getDefaultApplVerID() {
        return defaultApplVerID;
    }

    /**
     * Check if this is a FIX 5.0+ (FIXT.1.1) session.
     */
    public boolean usesFixt() {
        return "FIXT.1.1".equals(beginString);
    }

    public static class Builder {
        private String host = "localhost";
        private int port = 9880;
        private String senderCompId = "CLIENT";
        private String targetCompId = "SERVER";
        private String beginString = "FIX.4.4";
        private int heartbeatInterval = 30;
        private boolean resetOnLogon = true;
        private int reconnectInterval = 5;
        private boolean customConfig = true;
        private String defaultApplVerID = null;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder senderCompId(String senderCompId) {
            this.senderCompId = senderCompId;
            return this;
        }

        public Builder targetCompId(String targetCompId) {
            this.targetCompId = targetCompId;
            return this;
        }

        public Builder beginString(String beginString) {
            this.beginString = beginString;
            return this;
        }

        public Builder heartbeatInterval(int heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        public Builder resetOnLogon(boolean resetOnLogon) {
            this.resetOnLogon = resetOnLogon;
            return this;
        }

        public Builder reconnectInterval(int reconnectInterval) {
            this.reconnectInterval = reconnectInterval;
            return this;
        }

        public Builder customConfig(boolean customConfig) {
            this.customConfig = customConfig;
            return this;
        }

        /**
         * Set the DefaultApplVerID for FIX 5.0+ sessions.
         * Valid values: "9" (FIX50), "10" (FIX50SP1), "11" (FIX50SP2)
         */
        public Builder defaultApplVerID(String defaultApplVerID) {
            this.defaultApplVerID = defaultApplVerID;
            return this;
        }

        /**
         * Configure for FIX 5.0 using FIXT.1.1 transport.
         * Sets beginString to FIXT.1.1 and defaultApplVerID to "9" (FIX 5.0).
         */
        public Builder fix50() {
            this.beginString = "FIXT.1.1";
            this.defaultApplVerID = "9";  // FIX 5.0
            return this;
        }

        /**
         * Configure for FIX 5.0 SP1 using FIXT.1.1 transport.
         */
        public Builder fix50SP1() {
            this.beginString = "FIXT.1.1";
            this.defaultApplVerID = "10";  // FIX 5.0 SP1
            return this;
        }

        /**
         * Configure for FIX 5.0 SP2 using FIXT.1.1 transport.
         */
        public Builder fix50SP2() {
            this.beginString = "FIXT.1.1";
            this.defaultApplVerID = "11";  // FIX 5.0 SP2
            return this;
        }

        public InitiatorConfig build() {
            return new InitiatorConfig(this);
        }
    }
}
