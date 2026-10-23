package com.omnibridge.network;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.ControlledMessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstraction over a TCP socket channel providing non-blocking read/write operations.
 * This class is not thread-safe - all operations should be performed on the network I/O thread.
 */
public class TcpChannel {

    /**
     * Callback for outgoing messages drained from the ring buffer.
     * Called on the event loop thread during {@link #drainRingBuffer()}.
     */
    @FunctionalInterface
    public interface OutgoingMessageListener {
        void onOutgoingMessage(org.agrona.DirectBuffer buffer, int offset, int length);
    }

    /**
     * Sees each ring buffer record on the event loop thread, in ring order, before its
     * first byte is written and before the {@link OutgoingMessageListener}. It may modify
     * the payload in place (e.g., to stamp a sequence number) or consume a control record.
     */
    @FunctionalInterface
    public interface OutgoingFrameHandler {
        /**
         * @param msgTypeId the type the record was claimed with
         * @return true to send the payload, false to consume the record without sending it
         */
        boolean onFrame(int msgTypeId, MutableDirectBuffer buffer, int offset, int length);
    }

    /**
     * Told on the event loop thread when the last byte of a sent record has been accepted
     * by the socket (used for latency stage timing; unset by default).
     */
    @FunctionalInterface
    public interface FrameWrittenListener {
        void onFrameWritten(int msgTypeId);
    }

    /** Record type of payloads sent as-is; the default for {@link #tryClaim(int)} and {@link #writeRaw}. */
    public static final int MSG_TYPE_RAW = 1;

    private static final Logger log = LoggerFactory.getLogger(TcpChannel.class);
    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    // Default ring buffer capacity (1MB)
    public static final int DEFAULT_RING_BUFFER_CAPACITY = 1024 * 1024;


    // Length prefix size in ring buffer messages
    private static final int LENGTH_PREFIX_SIZE = 4;

    private final long id;
    private final SocketChannel socketChannel;
    private final String remoteAddress;
    private final String localAddress;
    private final DirectByteBuffer readBuffer;

    // Ring buffer for outgoing messages (many producers, single consumer)
    private final ManyToOneRingBuffer ringBuffer;
    private final UnsafeBuffer ringBufferBackingBuffer;
    private final int ringBufferCapacity;

    // Reusable buffer for claims
    private final UnsafeBuffer claimBuffer = new UnsafeBuffer(new byte[0]);

    // Direct ByteBuffer view of ring buffer for zero-copy socket writes
    private final ByteBuffer ringBufferDirectView;

    private OutgoingMessageListener outgoingMessageListener;
    private OutgoingFrameHandler outgoingFrameHandler;
    private FrameWrittenListener frameWrittenListener;

    // Progress on the message at the head of the ring: bytes written, and whether it was announced
    private int partialSent;
    private boolean headNotified;
    private IOException drainFailure;
    private final ControlledMessageHandler drainHandler = this::drainRecord;

    private SelectionKey selectionKey;
    private volatile boolean connected;
    private volatile boolean closed;
    private Object attachment;

    /**
     * Create a TcpChannel wrapping an existing SocketChannel.
     *
     * @param socketChannel the underlying socket channel (must be in non-blocking mode)
     * @param readBufferSize size of the read buffer in bytes
     * @param writeBufferSize unused; partial writes resume from the ring buffer (kept for compatibility)
     * @throws IOException if unable to get socket addresses
     */
    public TcpChannel(SocketChannel socketChannel, int readBufferSize, int writeBufferSize) throws IOException {
        this(socketChannel, readBufferSize, writeBufferSize, DEFAULT_RING_BUFFER_CAPACITY);
    }

    /**
     * Create a TcpChannel wrapping an existing SocketChannel with ring buffer.
     *
     * @param socketChannel the underlying socket channel (must be in non-blocking mode)
     * @param readBufferSize size of the read buffer in bytes
     * @param writeBufferSize unused; partial writes resume from the ring buffer (kept for compatibility)
     * @param ringBufferCapacity the ring buffer capacity (must be power of 2)
     * @throws IOException if unable to get socket addresses
     */
    public TcpChannel(SocketChannel socketChannel, int readBufferSize, int writeBufferSize,
                      int ringBufferCapacity) throws IOException {
        this.id = ID_GENERATOR.incrementAndGet();
        this.socketChannel = socketChannel;
        this.readBuffer = new DirectByteBuffer(readBufferSize);
        this.ringBufferCapacity = ringBufferCapacity;

        // Initialize ring buffer
        int totalBufferSize = ringBufferCapacity + RingBufferDescriptor.TRAILER_LENGTH;
        ByteBuffer ringBufferBacking = ByteBuffer.allocateDirect(totalBufferSize);
        this.ringBufferBackingBuffer = new UnsafeBuffer(ringBufferBacking);
        this.ringBuffer = new ManyToOneRingBuffer(ringBufferBackingBuffer);

        // Create a duplicate view of the ring buffer for zero-copy socket writes
        this.ringBufferDirectView = ringBufferBacking.duplicate();

        InetSocketAddress remote = (InetSocketAddress) socketChannel.getRemoteAddress();
        InetSocketAddress local = (InetSocketAddress) socketChannel.getLocalAddress();

        this.remoteAddress = remote != null ? remote.getHostString() + ":" + remote.getPort() : "unknown";
        this.localAddress = local != null ? local.getHostString() + ":" + local.getPort() : "unknown";
        this.connected = socketChannel.isConnected();
    }

    /**
     * Get the unique channel ID.
     */
    public long getId() {
        return id;
    }

    /**
     * Get the remote address as "host:port".
     */
    public String getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Get the local address as "host:port".
     */
    public String getLocalAddress() {
        return localAddress;
    }

    /**
     * Check if the channel is connected.
     */
    public boolean isConnected() {
        return connected && !closed && socketChannel.isConnected();
    }

    /**
     * Check if the channel is closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Get the underlying SocketChannel.
     */
    SocketChannel getSocketChannel() {
        return socketChannel;
    }

    /**
     * Get the read buffer for this channel.
     * Use this for accessing data via DirectBuffer interface.
     */
    DirectByteBuffer getReadBuffer() {
        return readBuffer;
    }

    /**
     * Get the underlying ByteBuffer for socket I/O operations.
     * Use this for SocketChannel.read() operations.
     */
    ByteBuffer getReadByteBuffer() {
        return readBuffer.byteBuffer();
    }

    /**
     * Set the SelectionKey for this channel.
     */
    void setSelectionKey(SelectionKey key) {
        this.selectionKey = key;
    }

    /**
     * Get the SelectionKey for this channel.
     */
    SelectionKey getSelectionKey() {
        return selectionKey;
    }

    /**
     * Mark the channel as connected.
     */
    void markConnected() {
        this.connected = true;
    }

    /**
     * Set a listener to be notified of outgoing messages during ring buffer drain.
     * The listener is called on the event loop thread.
     */
    public void setOutgoingMessageListener(OutgoingMessageListener listener) {
        this.outgoingMessageListener = listener;
    }

    /**
     * Get the outgoing message listener.
     */
    public OutgoingMessageListener getOutgoingMessageListener() {
        return outgoingMessageListener;
    }

    /**
     * Set the handler that sees each ring record before it is sent.
     * The handler is called on the event loop thread.
     */
    public void setOutgoingFrameHandler(OutgoingFrameHandler handler) {
        this.outgoingFrameHandler = handler;
    }

    public OutgoingFrameHandler getOutgoingFrameHandler() {
        return outgoingFrameHandler;
    }

    /**
     * Set the listener told when each sent record has been fully written, or null for none.
     */
    public void setFrameWrittenListener(FrameWrittenListener listener) {
        this.frameWrittenListener = listener;
    }

    // ==================== Ring Buffer Methods ====================

    /**
     * Get the ring buffer for this channel.
     *
     * @return the ring buffer
     */
    public ManyToOneRingBuffer getRingBuffer() {
        return ringBuffer;
    }

    /**
     * Try to claim space in the ring buffer for a message.
     *
     * <p>This method is thread-safe and can be called from multiple threads concurrently.</p>
     *
     * @param length the required length for the message (including length prefix)
     * @return the claim index if successful, or -1 if the buffer is full
     */
    public int tryClaim(int length) {
        return ringBuffer.tryClaim(MSG_TYPE_RAW, length);
    }

    /**
     * Try to claim space for a record of the given type, which the {@link OutgoingFrameHandler}
     * receives when the record is drained.
     *
     * @param msgTypeId the record type (must be positive)
     * @param length the required length for the message (including length prefix)
     * @return the claim index if successful, or -1 if the buffer is full
     */
    public int tryClaim(int msgTypeId, int length) {
        return ringBuffer.tryClaim(msgTypeId, length);
    }

    /**
     * Queue a control record carrying one int. It is delivered to the
     * {@link OutgoingFrameHandler} in ring order with the messages around it and is
     * never written to the socket if the handler consumes it.
     *
     * @return false if the ring buffer is full
     */
    public boolean writeControl(int msgTypeId, int value) {
        int claimIndex = ringBuffer.tryClaim(msgTypeId, LENGTH_PREFIX_SIZE + Integer.BYTES);
        if (claimIndex < 0) {
            return false;
        }
        MutableDirectBuffer buf = ringBuffer.buffer();
        buf.putInt(claimIndex, Integer.BYTES);
        buf.putInt(claimIndex + LENGTH_PREFIX_SIZE, value);
        commit(claimIndex);
        return true;
    }

    /**
     * Get the buffer for a claimed region.
     *
     * <p>The returned buffer is valid until {@link #commit(int)} or {@link #abort(int)} is called.</p>
     *
     * @return the buffer for direct writing
     */
    public MutableDirectBuffer buffer() {
        return ringBuffer.buffer();
    }

    /**
     * Commit a claimed message, making it available for consumption.
     *
     * <p>After calling this method, the message will be sent to the socket when
     * {@link #flush()} is called. The selector is woken up to ensure prompt delivery.</p>
     *
     * @param index the claim index returned by {@link #tryClaim(int)}
     */
    public void commit(int index) {
        ringBuffer.commit(index);
        // Wake up the selector so the event loop can drain the ring buffer immediately
        if (selectionKey != null && selectionKey.selector() != null) {
            selectionKey.selector().wakeup();
        }
    }

    /**
     * Abort a claimed message, discarding it.
     *
     * <p>Use this method to rollback a claim if encoding fails.</p>
     *
     * @param index the claim index returned by {@link #tryClaim(int)}
     */
    public void abort(int index) {
        ringBuffer.abort(index);
    }

    /**
     * Get the ring buffer capacity.
     *
     * @return the capacity in bytes
     */
    public int getRingBufferCapacity() {
        return ringBufferCapacity;
    }

    /**
     * Check if the ring buffer has pending messages to drain.
     *
     * @return true if there are messages in the ring buffer
     */
    public boolean hasRingBufferMessages() {
        return ringBuffer.size() > 0;
    }

    /**
     * Write raw bytes to the ring buffer for sending.
     *
     * <p>This method is used for pre-encoded messages (like resends) that need
     * to go through the ring buffer. The bytes are copied into a claimed region
     * and committed for sending.</p>
     *
     * @param data the raw bytes to write
     * @param offset the offset within the array
     * @param length the number of bytes to write
     * @return the number of bytes written, or -1 if the ring buffer is full
     */
    public int writeRaw(byte[] data, int offset, int length) {
        if (closed) {
            return -1;
        }

        // Claim space: length prefix + payload
        int claimSize = LENGTH_PREFIX_SIZE + length;
        int claimIndex = tryClaim(claimSize);
        if (claimIndex < 0) {
            return -1;
        }

        // Write length prefix
        MutableDirectBuffer buf = ringBuffer.buffer();
        buf.putInt(claimIndex, length);

        // Copy payload
        buf.putBytes(claimIndex + LENGTH_PREFIX_SIZE, data, offset, length);

        // Commit
        commit(claimIndex);

        return length;
    }

    /**
     * Flush pending ring buffer messages to the socket.
     * Called by the event loop when the channel becomes writable.
     *
     * @return true if the ring buffer was fully drained, false if a message is still partly written
     * @throws IOException if an I/O error occurs
     */
    boolean flush() throws IOException {
        drainRingBuffer();
        return !headNotified;
    }

    /**
     * Drain messages from the ring buffer to the socket (zero-copy).
     *
     * <p>Each message in the ring buffer has the format:</p>
     * <pre>
     * [4 bytes: message length][N bytes: message payload]
     * </pre>
     *
     * <p>Only the payload is sent. When the socket accepts part of a message, the
     * message stays at the head of the ring and the next drain resumes from the
     * byte where this one stopped; OP_WRITE interest is held until it completes.</p>
     *
     * @throws IOException if writing to the socket fails; the message being written stays in the ring
     */
    private void drainRingBuffer() throws IOException {
        ringBuffer.controlledRead(drainHandler);

        IOException failure = drainFailure;
        if (failure != null) {
            drainFailure = null;
            throw failure;
        }
        setWriteInterest(headNotified);
    }

    private ControlledMessageHandler.Action drainRecord(int msgTypeId, MutableDirectBuffer buffer,
                                                        int index, int length) {
        int payloadLength = buffer.getInt(index);
        int payloadOffset = index + LENGTH_PREFIX_SIZE;

        // Notify once per message, before its first byte goes out (e.g., for persistence logging)
        if (!headNotified) {
            if (outgoingFrameHandler != null
                    && !outgoingFrameHandler.onFrame(msgTypeId, buffer, payloadOffset, payloadLength)) {
                return ControlledMessageHandler.Action.CONTINUE;
            }
            headNotified = true;
            if (outgoingMessageListener != null) {
                outgoingMessageListener.onOutgoingMessage(buffer, payloadOffset, payloadLength);
            }
        }

        ringBufferDirectView.limit(payloadOffset + payloadLength);
        ringBufferDirectView.position(payloadOffset + partialSent);
        try {
            while (ringBufferDirectView.hasRemaining()) {
                if (socketChannel.write(ringBufferDirectView) == 0) {
                    partialSent = ringBufferDirectView.position() - payloadOffset;
                    return ControlledMessageHandler.Action.ABORT;
                }
            }
        } catch (IOException e) {
            partialSent = ringBufferDirectView.position() - payloadOffset;
            drainFailure = e;
            return ControlledMessageHandler.Action.ABORT;
        }
        partialSent = 0;
        headNotified = false;
        if (frameWrittenListener != null) {
            frameWrittenListener.onFrameWritten(msgTypeId);
        }
        return ControlledMessageHandler.Action.CONTINUE;
    }

    private void setWriteInterest(boolean wanted) {
        SelectionKey key = selectionKey;
        if (key == null || !key.isValid()) {
            return;
        }
        int ops = key.interestOps();
        int updated = wanted ? ops | SelectionKey.OP_WRITE : ops & ~SelectionKey.OP_WRITE;
        if (updated != ops) {
            key.interestOps(updated);
        }
    }

    /**
     * Drain all messages from the ring buffer (for use by NetworkEventLoop).
     *
     * @throws IOException if an I/O error occurs
     */
    public void drainRingBufferToSocket() throws IOException {
        drainRingBuffer();
    }

    /**
     * Close the channel.
     */
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        connected = false;

        try {
            if (selectionKey != null) {
                selectionKey.cancel();
            }
            socketChannel.close();
        } catch (IOException e) {
            log.debug("Error closing channel {}: {}", id, e.getMessage());
        }

        log.debug("Channel {} closed: {} -> {}", id, localAddress, remoteAddress);
    }

    /**
     * Set a user attachment on this channel.
     */
    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }

    /**
     * Get the user attachment from this channel.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttachment() {
        return (T) attachment;
    }

    @Override
    public String toString() {
        return "TcpChannel[id=" + id + ", " + localAddress + " -> " + remoteAddress +
                ", connected=" + connected + ", closed=" + closed + "]";
    }
}
