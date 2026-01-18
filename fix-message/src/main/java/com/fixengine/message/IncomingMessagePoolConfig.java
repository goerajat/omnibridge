package com.fixengine.message;

/**
 * Configuration for an {@link IncomingMessagePool}.
 *
 * <p>This class defines the settings for incoming message pooling:</p>
 * <ul>
 *   <li>Pool size - number of pre-allocated messages</li>
 *   <li>Buffer size - initial capacity for message buffers</li>
 *   <li>Max tag number - maximum tag number to support for indexing</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * IncomingMessagePoolConfig config = IncomingMessagePoolConfig.builder()
 *     .poolSize(32)
 *     .bufferSize(4096)
 *     .maxTagNumber(10000)
 *     .build();
 * }</pre>
 */
public class IncomingMessagePoolConfig {

    private int poolSize = 32;
    private int bufferSize = 4096;
    private int maxTagNumber = 10000;

    /**
     * Create a new builder for IncomingMessagePoolConfig.
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
     * Get the initial buffer size for each message.
     *
     * @return the buffer size in bytes
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Get the maximum tag number to support for indexing.
     *
     * @return the maximum tag number
     */
    public int getMaxTagNumber() {
        return maxTagNumber;
    }

    /**
     * Builder for IncomingMessagePoolConfig.
     */
    public static class Builder {
        private final IncomingMessagePoolConfig config = new IncomingMessagePoolConfig();

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
         * Set the initial buffer size for each message.
         *
         * @param bufferSize the buffer size in bytes (must be at least 256)
         * @return this builder
         */
        public Builder bufferSize(int bufferSize) {
            if (bufferSize < 256) {
                throw new IllegalArgumentException("Buffer size must be at least 256: " + bufferSize);
            }
            config.bufferSize = bufferSize;
            return this;
        }

        /**
         * Set the maximum tag number to support.
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
         * Build the configuration.
         *
         * @return the configured IncomingMessagePoolConfig
         */
        public IncomingMessagePoolConfig build() {
            return config;
        }
    }

    @Override
    public String toString() {
        return "IncomingMessagePoolConfig{" +
                "poolSize=" + poolSize +
                ", bufferSize=" + bufferSize +
                ", maxTagNumber=" + maxTagNumber +
                '}';
    }
}
