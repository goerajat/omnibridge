package com.omnibridge.simulator.core.order;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe order ID generator.
 */
public class OrderIdGenerator {

    private final AtomicLong nextId;
    private final String prefix;

    public OrderIdGenerator() {
        this(1, "");
    }

    public OrderIdGenerator(long startId) {
        this(startId, "");
    }

    public OrderIdGenerator(long startId, String prefix) {
        this.nextId = new AtomicLong(startId);
        this.prefix = prefix != null ? prefix : "";
    }

    /**
     * Generate the next order ID as a long.
     */
    public long nextId() {
        return nextId.getAndIncrement();
    }

    /**
     * Generate the next order ID as a String with optional prefix.
     */
    public String nextIdString() {
        return prefix + nextId.getAndIncrement();
    }

    /**
     * Get the current counter value without incrementing.
     */
    public long peek() {
        return nextId.get();
    }

    /**
     * Reset the counter to a specific value.
     */
    public void reset(long value) {
        nextId.set(value);
    }
}
