package com.fixengine.message;

/**
 * Configuration for a {@link MessagePool}.
 *
 * <p>This class defines the settings for pre-allocated message pooling, including:</p>
 * <ul>
 *   <li>Pool size - number of pre-allocated messages</li>
 *   <li>Message buffer size - maximum size of each message</li>
 *   <li>Tag tracking - maximum tag number for duplicate detection</li>
 *   <li>Session identifiers - pre-populated CompIDs</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * MessagePoolConfig config = MessagePoolConfig.builder()
 *     .poolSize(64)
 *     .maxMessageLength(4096)
 *     .maxTagNumber(1000)
 *     .beginString("FIX.4.4")
 *     .senderCompId("SENDER")
 *     .targetCompId("TARGET")
 *     .build();
 * }</pre>
 */
public class MessagePoolConfig {

    private int poolSize = 64;
    private int maxMessageLength = 4096;
    private int maxTagNumber = 1000;
    private String beginString = "FIX.4.4";
    private String senderCompId;
    private String targetCompId;
    private int senderCompIdMaxLength = 16;
    private int targetCompIdMaxLength = 16;
    private int bodyLengthDigits = 5;
    private int seqNumDigits = 8;
    private Clock clock = SystemClock.INSTANCE;

    /**
     * Create a new builder for MessagePoolConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the pool size (number of pre-allocated messages).
     *
     * @return the pool size
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * Get the maximum message length in bytes.
     *
     * @return the maximum message length
     */
    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    /**
     * Get the maximum tag number for duplicate detection BitSet.
     *
     * @return the maximum tag number
     */
    public int getMaxTagNumber() {
        return maxTagNumber;
    }

    /**
     * Get the FIX protocol version string (e.g., "FIX.4.4").
     *
     * @return the begin string
     */
    public String getBeginString() {
        return beginString;
    }

    /**
     * Get the sender comp ID.
     *
     * @return the sender comp ID
     */
    public String getSenderCompId() {
        return senderCompId;
    }

    /**
     * Get the target comp ID.
     *
     * @return the target comp ID
     */
    public String getTargetCompId() {
        return targetCompId;
    }

    /**
     * Get the maximum length for SenderCompID field (for padding).
     *
     * @return the maximum sender comp ID length
     */
    public int getSenderCompIdMaxLength() {
        return senderCompIdMaxLength;
    }

    /**
     * Get the maximum length for TargetCompID field (for padding).
     *
     * @return the maximum target comp ID length
     */
    public int getTargetCompIdMaxLength() {
        return targetCompIdMaxLength;
    }

    /**
     * Get the number of digits for BodyLength field.
     *
     * @return the body length digits
     */
    public int getBodyLengthDigits() {
        return bodyLengthDigits;
    }

    /**
     * Get the number of digits for MsgSeqNum field.
     *
     * @return the sequence number digits
     */
    public int getSeqNumDigits() {
        return seqNumDigits;
    }

    /**
     * Get the clock used for timestamps.
     *
     * @return the clock instance
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Builder for MessagePoolConfig.
     */
    public static class Builder {
        private final MessagePoolConfig config = new MessagePoolConfig();

        /**
         * Set the pool size (number of pre-allocated messages).
         *
         * @param poolSize the pool size (must be positive)
         * @return this builder
         */
        public Builder poolSize(int poolSize) {
            if (poolSize <= 0) {
                throw new IllegalArgumentException("Pool size must be positive: " + poolSize);
            }
            config.poolSize = poolSize;
            return this;
        }

        /**
         * Set the maximum message length in bytes.
         *
         * @param maxMessageLength the maximum length (must be at least 256)
         * @return this builder
         */
        public Builder maxMessageLength(int maxMessageLength) {
            if (maxMessageLength < 256) {
                throw new IllegalArgumentException("Max message length must be at least 256: " + maxMessageLength);
            }
            config.maxMessageLength = maxMessageLength;
            return this;
        }

        /**
         * Set the maximum tag number for duplicate detection.
         *
         * @param maxTagNumber the maximum tag number (must be positive)
         * @return this builder
         */
        public Builder maxTagNumber(int maxTagNumber) {
            if (maxTagNumber <= 0) {
                throw new IllegalArgumentException("Max tag number must be positive: " + maxTagNumber);
            }
            config.maxTagNumber = maxTagNumber;
            return this;
        }

        /**
         * Set the FIX protocol version string.
         *
         * @param beginString the protocol version (e.g., "FIX.4.4")
         * @return this builder
         */
        public Builder beginString(String beginString) {
            if (beginString == null || beginString.isEmpty()) {
                throw new IllegalArgumentException("Begin string cannot be null or empty");
            }
            config.beginString = beginString;
            return this;
        }

        /**
         * Set the sender comp ID.
         *
         * @param senderCompId the sender identifier
         * @return this builder
         */
        public Builder senderCompId(String senderCompId) {
            if (senderCompId == null || senderCompId.isEmpty()) {
                throw new IllegalArgumentException("SenderCompId cannot be null or empty");
            }
            config.senderCompId = senderCompId;
            return this;
        }

        /**
         * Set the target comp ID.
         *
         * @param targetCompId the target identifier
         * @return this builder
         */
        public Builder targetCompId(String targetCompId) {
            if (targetCompId == null || targetCompId.isEmpty()) {
                throw new IllegalArgumentException("TargetCompId cannot be null or empty");
            }
            config.targetCompId = targetCompId;
            return this;
        }

        /**
         * Set the maximum length for SenderCompID field.
         *
         * @param maxLength the maximum length
         * @return this builder
         */
        public Builder senderCompIdMaxLength(int maxLength) {
            if (maxLength <= 0) {
                throw new IllegalArgumentException("SenderCompId max length must be positive: " + maxLength);
            }
            config.senderCompIdMaxLength = maxLength;
            return this;
        }

        /**
         * Set the maximum length for TargetCompID field.
         *
         * @param maxLength the maximum length
         * @return this builder
         */
        public Builder targetCompIdMaxLength(int maxLength) {
            if (maxLength <= 0) {
                throw new IllegalArgumentException("TargetCompId max length must be positive: " + maxLength);
            }
            config.targetCompIdMaxLength = maxLength;
            return this;
        }

        /**
         * Set the number of digits for BodyLength field.
         *
         * @param digits the number of digits (1-7)
         * @return this builder
         */
        public Builder bodyLengthDigits(int digits) {
            if (digits < 1 || digits > 7) {
                throw new IllegalArgumentException("Body length digits must be 1-7: " + digits);
            }
            config.bodyLengthDigits = digits;
            return this;
        }

        /**
         * Set the number of digits for MsgSeqNum field.
         *
         * @param digits the number of digits (1-10)
         * @return this builder
         */
        public Builder seqNumDigits(int digits) {
            if (digits < 1 || digits > 10) {
                throw new IllegalArgumentException("Sequence number digits must be 1-10: " + digits);
            }
            config.seqNumDigits = digits;
            return this;
        }

        /**
         * Set the clock to use for timestamps.
         *
         * @param clock the clock instance
         * @return this builder
         */
        public Builder clock(Clock clock) {
            if (clock == null) {
                throw new IllegalArgumentException("Clock cannot be null");
            }
            config.clock = clock;
            return this;
        }

        /**
         * Build the configuration.
         *
         * @return the configured MessagePoolConfig
         * @throws IllegalStateException if required fields are not set
         */
        public MessagePoolConfig build() {
            if (config.senderCompId == null) {
                throw new IllegalStateException("SenderCompId is required");
            }
            if (config.targetCompId == null) {
                throw new IllegalStateException("TargetCompId is required");
            }
            if (config.senderCompId.length() > config.senderCompIdMaxLength) {
                throw new IllegalStateException("SenderCompId length (" + config.senderCompId.length() +
                        ") exceeds max length (" + config.senderCompIdMaxLength + ")");
            }
            if (config.targetCompId.length() > config.targetCompIdMaxLength) {
                throw new IllegalStateException("TargetCompId length (" + config.targetCompId.length() +
                        ") exceeds max length (" + config.targetCompIdMaxLength + ")");
            }
            return config;
        }
    }

    @Override
    public String toString() {
        return "MessagePoolConfig{" +
                "poolSize=" + poolSize +
                ", maxMessageLength=" + maxMessageLength +
                ", maxTagNumber=" + maxTagNumber +
                ", beginString='" + beginString + '\'' +
                ", senderCompId='" + senderCompId + '\'' +
                ", targetCompId='" + targetCompId + '\'' +
                '}';
    }
}
