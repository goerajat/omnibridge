package com.fixengine.message;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe pool of pre-allocated {@link OutgoingFixMessage} instances.
 *
 * <p>This pool manages a fixed number of pre-allocated messages that can be
 * acquired and released for high-performance FIX message encoding. The pool
 * uses an {@link ArrayBlockingQueue} internally for thread-safe operations.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * MessagePool pool = new MessagePool(config);
 * pool.warmUp(); // Pre-touch memory pages
 *
 * // Acquire a message
 * OutgoingFixMessage msg = pool.acquire();
 * msg.setMsgType("D");
 * msg.setField(11, "ORDER-001");
 * // ... add more fields
 *
 * // Send the message
 * byte[] encoded = msg.prepareForSend(seqNum, timestamp);
 * channel.write(encoded, msg.getLength());
 *
 * // Release back to pool (or call msg.release())
 * pool.release(msg);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe. Multiple threads can safely acquire and
 * release messages concurrently.</p>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>Call {@link #warmUp()} after construction to pre-touch memory pages</li>
 *   <li>Size the pool appropriately for your concurrency needs</li>
 *   <li>Use {@link #tryAcquire()} to avoid blocking in latency-sensitive code</li>
 * </ul>
 */
public class MessagePool {

    private final ArrayBlockingQueue<OutgoingFixMessage> pool;
    private final MessagePoolConfig config;
    private final int poolSize;

    /**
     * Create a new message pool with the given configuration.
     *
     * @param config the pool configuration
     */
    public MessagePool(MessagePoolConfig config) {
        this.config = config;
        this.poolSize = config.getPoolSize();
        this.pool = new ArrayBlockingQueue<>(poolSize);

        // Pre-allocate all messages
        for (int i = 0; i < poolSize; i++) {
            OutgoingFixMessage msg = new OutgoingFixMessage(config);
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
    public OutgoingFixMessage acquire() throws InterruptedException {
        OutgoingFixMessage msg = pool.take();
        msg.markInUse();
        return msg;
    }

    /**
     * Try to acquire a message without blocking.
     *
     * @return a pooled message, or null if none are available
     */
    public OutgoingFixMessage tryAcquire() {
        OutgoingFixMessage msg = pool.poll();
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
    public OutgoingFixMessage tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        OutgoingFixMessage msg = pool.poll(timeout, unit);
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
    public void release(OutgoingFixMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Cannot release null message");
        }

        // Reset is already called by OutgoingFixMessage.release()
        // Just add back to pool
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
     * Warm up the pool by pre-touching all memory pages.
     *
     * <p>This method acquires each message, writes some data to force page faults,
     * and releases it back. This helps avoid latency spikes when the pool is first
     * used under load.</p>
     */
    public void warmUp() {
        // Acquire all messages to force memory page faults
        OutgoingFixMessage[] messages = new OutgoingFixMessage[poolSize];

        try {
            for (int i = 0; i < poolSize; i++) {
                messages[i] = pool.poll();
                if (messages[i] != null) {
                    // Touch the buffer to force page allocation
                    messages[i].setMsgType("0");  // Heartbeat
                    messages[i].setField(112, "WARMUP" + i);  // TestReqID
                    messages[i].prepareForSend(i, config.getClock().currentTimeMillis());
                    messages[i].reset();
                }
            }
        } finally {
            // Return all messages to the pool
            for (OutgoingFixMessage msg : messages) {
                if (msg != null) {
                    pool.offer(msg);
                }
            }
        }
    }

    /**
     * Get the pool configuration.
     *
     * @return the configuration
     */
    public MessagePoolConfig getConfig() {
        return config;
    }

    /**
     * Check if the pool is empty (all messages in use).
     *
     * @return true if no messages are available
     */
    public boolean isEmpty() {
        return pool.isEmpty();
    }

    @Override
    public String toString() {
        return "MessagePool{" +
                "size=" + poolSize +
                ", available=" + pool.size() +
                ", inUse=" + inUseCount() +
                '}';
    }
}
