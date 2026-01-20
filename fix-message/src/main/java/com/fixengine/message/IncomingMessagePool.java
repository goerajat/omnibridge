package com.fixengine.message;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe pool of pre-allocated {@link IncomingFixMessage} instances.
 *
 * <p>This pool manages a fixed number of pre-allocated messages for parsing
 * incoming FIX messages. The pool uses an {@link ArrayBlockingQueue} internally
 * for thread-safe operations.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * IncomingMessagePool pool = new IncomingMessagePool(config);
 *
 * // In FixReader - acquire a message for parsing
 * IncomingFixMessage msg = pool.acquire();
 * msg.wrap(data, offset, length);
 *
 * // Process the message
 * handleMessage(msg);
 *
 * // Release back to pool
 * msg.release(); // or pool.release(msg)
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Acquire and release operations are thread-safe. However, each message
 * should only be accessed by one thread at a time.</p>
 */
public class IncomingMessagePool {

    private final ArrayBlockingQueue<IncomingFixMessage> pool;
    private final IncomingMessagePoolConfig config;
    private final int poolSize;

    /**
     * Create a new incoming message pool with default configuration.
     */
    public IncomingMessagePool() {
        this(IncomingMessagePoolConfig.builder().build());
    }

    /**
     * Create a new incoming message pool with the given configuration.
     *
     * @param config the pool configuration
     */
    public IncomingMessagePool(IncomingMessagePoolConfig config) {
        this.config = config;
        this.poolSize = config.getPoolSize();
        this.pool = new ArrayBlockingQueue<>(poolSize);

        // Pre-allocate all messages
        for (int i = 0; i < poolSize; i++) {
            IncomingFixMessage msg = new IncomingFixMessage(config.getMaxTagNumber());
            msg.setOwnerPool(this);
            pool.offer(msg);
        }
    }

    /**
     * Acquire a message from the pool, blocking if none are available.
     *
     * <p>The returned message is reset and ready for use. The caller is
     * responsible for releasing the message back to the pool when done.</p>
     *
     * @return a pooled message ready for use
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public IncomingFixMessage acquire() throws InterruptedException {
        IncomingFixMessage msg = pool.take();
        msg.markInUse();
        return msg;
    }

    /**
     * Try to acquire a message without blocking.
     *
     * @return a pooled message, or null if none are available
     */
    public IncomingFixMessage tryAcquire() {
        IncomingFixMessage msg = pool.poll();
        if (msg != null) {
            msg.markInUse();
        }
        return msg;
    }

    /**
     * Try to acquire a message with a timeout.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return a pooled message, or null if timeout elapsed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public IncomingFixMessage tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        IncomingFixMessage msg = pool.poll(timeout, unit);
        if (msg != null) {
            msg.markInUse();
        }
        return msg;
    }

    /**
     * Release a message back to the pool.
     *
     * <p>The message is automatically reset before being returned to the pool.
     * After calling release, the message should not be used further.</p>
     *
     * @param message the message to release
     * @throws IllegalArgumentException if the message is null
     * @throws IllegalStateException if the pool is full (should not happen in normal use)
     */
    public void release(IncomingFixMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Cannot release null message");
        }

        if (!pool.offer(message)) {
            // Pool is full - this indicates a bug (double release or wrong pool)
            throw new IllegalStateException("Pool is full - possible double release detected");
        }
    }

    /**
     * Get the number of messages currently available in the pool.
     *
     * @return the number of available messages
     */
    public int availableCount() {
        return pool.size();
    }

    /**
     * Get the total pool size (configured capacity).
     *
     * @return the pool size
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * Get the number of messages currently in use.
     *
     * @return the number of messages in use
     */
    public int inUseCount() {
        return poolSize - pool.size();
    }

    /**
     * Check if the pool is empty (all messages in use).
     *
     * @return true if no messages are available
     */
    public boolean isEmpty() {
        return pool.isEmpty();
    }

    /**
     * Get the pool configuration.
     *
     * @return the configuration
     */
    public IncomingMessagePoolConfig getConfig() {
        return config;
    }

    @Override
    public String toString() {
        return "IncomingMessagePool{" +
                "size=" + poolSize +
                ", available=" + pool.size() +
                ", inUse=" + inUseCount() +
                '}';
    }
}
