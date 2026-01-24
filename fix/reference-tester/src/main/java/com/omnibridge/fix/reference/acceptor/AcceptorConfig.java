package com.omnibridge.fix.reference.acceptor;

/**
 * Configuration for the Reference FIX Acceptor.
 */
public class AcceptorConfig {

    private final int port;
    private final String senderCompId;
    private final String targetCompId;
    private final String beginString;
    private final int heartbeatInterval;
    private final boolean resetOnLogon;
    private final double fillRate;
    private final int fillDelayMs;
    private final boolean autoAck;
    private final boolean customConfig;

    private AcceptorConfig(Builder builder) {
        this.port = builder.port;
        this.senderCompId = builder.senderCompId;
        this.targetCompId = builder.targetCompId;
        this.beginString = builder.beginString;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.resetOnLogon = builder.resetOnLogon;
        this.fillRate = builder.fillRate;
        this.fillDelayMs = builder.fillDelayMs;
        this.autoAck = builder.autoAck;
        this.customConfig = builder.customConfig;
    }

    public static Builder builder() {
        return new Builder();
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

    public double getFillRate() {
        return fillRate;
    }

    public int getFillDelayMs() {
        return fillDelayMs;
    }

    public boolean isAutoAck() {
        return autoAck;
    }

    public boolean isCustomConfig() {
        return customConfig;
    }

    public static class Builder {
        private int port = 9880;
        private String senderCompId = "EXCHANGE";
        private String targetCompId = "CLIENT";
        private String beginString = "FIX.4.4";
        private int heartbeatInterval = 30;
        private boolean resetOnLogon = true;
        private double fillRate = 1.0;
        private int fillDelayMs = 0;
        private boolean autoAck = true;
        private boolean customConfig = true;

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

        public Builder fillRate(double fillRate) {
            this.fillRate = fillRate;
            return this;
        }

        public Builder fillDelayMs(int fillDelayMs) {
            this.fillDelayMs = fillDelayMs;
            return this;
        }

        public Builder autoAck(boolean autoAck) {
            this.autoAck = autoAck;
            return this;
        }

        public Builder customConfig(boolean customConfig) {
            this.customConfig = customConfig;
            return this;
        }

        public AcceptorConfig build() {
            return new AcceptorConfig(this);
        }
    }
}
