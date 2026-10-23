package com.omnibridge.fix.engine.timing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session record of where each message's time goes, one row of {@code System.nanoTime()}
 * stamps per message, kept in preallocated rings so recording allocates nothing.
 *
 * <p>Send row (one per outgoing message claimed with {@code tryClaimMessage}):</p>
 * <ol start="0">
 *   <li>{@link #SEND_CLAIM} ring buffer claim, on the sending thread</li>
 *   <li>{@link #SEND_COMMIT} commit called (the application has encoded its fields)</li>
 *   <li>{@link #SEND_PREPARED} SendingTime, BodyLength and CheckSum written, just before the ring commit</li>
 *   <li>{@link #SEND_DRAIN} the event loop picks the record from the ring</li>
 *   <li>{@link #SEND_BEFORE_WRITE} after MsgSeqNum stamping and outbound logging/persistence</li>
 *   <li>{@link #SEND_WRITTEN} the last byte was accepted by the socket</li>
 * </ol>
 * <p>Receive row (one per incoming message that reaches the message listeners):</p>
 * <ol start="0">
 *   <li>{@link #RECV_READ} the bytes were handed to the session after a socket read</li>
 *   <li>{@link #RECV_FRAMED} the reader found a complete message with a valid checksum</li>
 *   <li>{@link #RECV_PARSED} its fields were indexed</li>
 *   <li>{@link #RECV_DISPATCH} session checks and inbound persistence done, listeners about to run</li>
 *   <li>{@link #RECV_DONE} listeners returned</li>
 * </ol>
 *
 * <p>Rows are written only on the event loop thread. The ring keeps the most recent
 * {@code capacity} rows of each kind. Reading ({@link #sendStamp}, {@link #receiveStamp}) is
 * meant for after a run, when the session is quiet; reads overlapping writes can see a
 * partly written newest row. The time the sending thread spends in the ring commit itself
 * (including any selector wakeup) is kept separately by {@link #recordCommitCall}.</p>
 */
public final class StageTimingRecorder {

    /** TestReqID of a TestRequest that resets stage timing on the receiving side. */
    public static final String RESET_TEST_REQ_ID = "OMNI-STAGE-RESET";

    public static final int SEND_CLAIM = 0;
    public static final int SEND_COMMIT = 1;
    public static final int SEND_PREPARED = 2;
    public static final int SEND_DRAIN = 3;
    public static final int SEND_BEFORE_WRITE = 4;
    public static final int SEND_WRITTEN = 5;
    public static final int SEND_STAGES = 6;

    public static final int RECV_READ = 0;
    public static final int RECV_FRAMED = 1;
    public static final int RECV_PARSED = 2;
    public static final int RECV_DISPATCH = 3;
    public static final int RECV_DONE = 4;
    public static final int RECV_STAGES = 5;

    /** Default ring size: rows of each kind kept. */
    public static final int DEFAULT_CAPACITY = 1 << 17;

    private final int capacity;
    private final int mask;
    private final long[] send;
    private final long[] receive;
    private final long[] commitCalls;

    // Written only by the event loop; published with release stores for readers
    private long sendCount;
    private long receiveCount;
    private final AtomicLong publishedSendCount = new AtomicLong();
    private final AtomicLong publishedReceiveCount = new AtomicLong();
    // Any committing thread
    private final AtomicLong commitCallCount = new AtomicLong();

    private volatile boolean resetRequested;
    private volatile boolean reported;

    /**
     * @param capacity rows of each kind to keep; rounded up to a power of two
     */
    public StageTimingRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        int size = Integer.highestOneBit(capacity);
        if (size < capacity) {
            size <<= 1;
        }
        this.capacity = size;
        this.mask = size - 1;
        this.send = new long[size * SEND_STAGES];
        this.receive = new long[size * RECV_STAGES];
        this.commitCalls = new long[size];
    }

    public int getCapacity() {
        return capacity;
    }

    // ==================== Writers (event loop) ====================

    public void recordSend(long claim, long commit, long prepared, long drain, long beforeWrite, long written) {
        applyPendingReset();
        int base = (int) (sendCount & mask) * SEND_STAGES;
        send[base + SEND_CLAIM] = claim;
        send[base + SEND_COMMIT] = commit;
        send[base + SEND_PREPARED] = prepared;
        send[base + SEND_DRAIN] = drain;
        send[base + SEND_BEFORE_WRITE] = beforeWrite;
        send[base + SEND_WRITTEN] = written;
        publishedSendCount.lazySet(++sendCount);
    }

    public void recordReceive(long read, long framed, long parsed, long dispatch, long done) {
        applyPendingReset();
        int base = (int) (receiveCount & mask) * RECV_STAGES;
        receive[base + RECV_READ] = read;
        receive[base + RECV_FRAMED] = framed;
        receive[base + RECV_PARSED] = parsed;
        receive[base + RECV_DISPATCH] = dispatch;
        receive[base + RECV_DONE] = done;
        publishedReceiveCount.lazySet(++receiveCount);
    }

    /**
     * Record how long the sending thread spent committing to the ring (any thread).
     */
    public void recordCommitCall(long nanos) {
        long n = commitCallCount.getAndIncrement();
        commitCalls[(int) (n & mask)] = nanos;
    }

    private void applyPendingReset() {
        if (resetRequested) {
            resetRequested = false;
            sendCount = 0;
            receiveCount = 0;
            publishedSendCount.lazySet(0);
            publishedReceiveCount.lazySet(0);
            commitCallCount.set(0);
        }
    }

    // ==================== Control (any thread) ====================

    /**
     * Discard what has been recorded. Applied by the event loop before it records the next row.
     */
    public void requestReset() {
        reported = false;
        resetRequested = true;
    }

    /** Note that the current samples have been reported, so a later Logout does not repeat them. */
    public void markReported() {
        reported = true;
    }

    public boolean isReported() {
        return reported;
    }

    // ==================== Readers ====================

    /** Send rows recorded since the last reset (may exceed the capacity kept). */
    public long getSendCount() {
        return resetRequested ? 0 : publishedSendCount.get();
    }

    /** Receive rows recorded since the last reset (may exceed the capacity kept). */
    public long getReceiveCount() {
        return resetRequested ? 0 : publishedReceiveCount.get();
    }

    public long getCommitCallCount() {
        return resetRequested ? 0 : commitCallCount.get();
    }

    /**
     * @param row 0 for the oldest row kept, up to {@code min(count, capacity) - 1}
     */
    public long sendStamp(int row, int stage) {
        long count = publishedSendCount.get();
        long first = Math.max(0, count - capacity);
        return send[(int) ((first + row) & mask) * SEND_STAGES + stage];
    }

    public long receiveStamp(int row, int stage) {
        long count = publishedReceiveCount.get();
        long first = Math.max(0, count - capacity);
        return receive[(int) ((first + row) & mask) * RECV_STAGES + stage];
    }

    public long commitCall(int row) {
        long count = commitCallCount.get();
        long first = Math.max(0, count - capacity);
        return commitCalls[(int) ((first + row) & mask)];
    }
}
