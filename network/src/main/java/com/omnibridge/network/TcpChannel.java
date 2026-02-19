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

    private static final Logger log = LoggerFactory.getLogger(TcpChannel.class);
    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    // Default ring buffer capacity (1MB)
    public static final int DEFAULT_RING_BUFFER_CAPACITY = 1024 * 1024;

    // Message type ID for ring buffer (arbitrary constant)
    private static final int MSG_TYPE_FIX_MESSAGE = 1;

    // Length prefix size in ring buffer messages
    private static final int LENGTH_PREFIX_SIZE = 4;

    private final long id;
    private final SocketChannel socketChannel;
    private final String remoteAddress;
    private final String localAddress;
    private final DirectByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

    // Ring buffer for outgoing messages (many producers, single consumer)
    private final ManyToOneRingBuffer ringBuffer;
    private final UnsafeBuffer ringBufferBackingBuffer;
    private final int ringBufferCapacity;

    // Reusable buffer for claims
    private final UnsafeBuffer claimBuffer = new UnsafeBuffer(new byte[0]);

    // Direct ByteBuffer view of ring buffer for zero-copy socket writes
    private final ByteBuffer ringBufferDirectView;

    private OutgoingMessageListener outgoingMessageListener;

    private SelectionKey selectionKey;
    private volatile boolean connected;
    private volatile boolean closed;
    private Object attachment;

    /**
     * Create a TcpChannel wrapping an existing SocketChannel.
     *
     * @param socketChannel the underlying socket channel (must be in non-blocking mode)
     * @param readBufferSize size of the read buffer in bytes
     * @param writeBufferSize size of the write buffer in bytes
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
     * @param writeBufferSize size of the write buffer in bytes
     * @param ringBufferCapacity the ring buffer capacity (must be power of 2)
     * @throws IOException if unable to get socket addresses
     */
    public TcpChannel(SocketChannel socketChannel, int readBufferSize, int writeBufferSize,
                      int ringBufferCapacity) throws IOException {
        this.id = ID_GENERATOR.incrementAndGet();
        this.socketChannel = socketChannel;
        this.readBuffer = new DirectByteBuffer(readBufferSize);
        this.writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);
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
        return ringBuffer.tryClaim(MSG_TYPE_FIX_MESSAGE, length);
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
     * Flush any pending write data, including ring buffer messages.
     * Called by the event loop when the channel becomes writable.
     *
     * <p>This method first drains all messages from the ring buffer to the socket,
     * then flushes any remaining data in the write buffer.</p>
     *
     * @return true if all data was flushed, false if more data remains
     * @throws IOException if an I/O error occurs
     */
    boolean flush() throws IOException {
        // First, drain the ring buffer
        drainRingBuffer();

        // Then flush the write buffer
        if (writeBuffer.position() == 0) {
            return true;
        }

        writeBuffer.flip();
        try {
            socketChannel.write(writeBuffer);
            if (writeBuffer.hasRemaining()) {
                writeBuffer.compact();
                return false;
            } else {
                writeBuffer.clear();
                // Remove write interest
              //  if (selectionKey != null && selectionKey.isValid()) {
              //      selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
               // }
                return true;
            }
        } catch (IOException e) {
            writeBuffer.clear();
            throw e;
        }
    }

    /**
     * Drain all messages from the ring buffer to the socket (zero-copy).
     *
     * <p>Each message in the ring buffer has the format:</p>
     * <pre>
     * [4 bytes: FIX message length][N bytes: FIX message payload]
     * </pre>
     *
     * <p>Only the FIX message payload is sent to the socket. The length prefix
     * is used internally to know how many bytes to send.</p>
     *
     * <p>This method writes directly from the ring buffer to the socket without
     * intermediate copies. If the socket buffer becomes full during a write,
     * the remaining bytes are copied to the write buffer and processing stops.</p>
     *
     * @throws IOException if an I/O error occurs
     */
    private void drainRingBuffer() throws IOException {
        // If there's pending data in writeBuffer from a previous partial write,
        // don't drain more until it's flushed
        if (writeBuffer.position() > 0) {
            return;
        }

        ringBuffer.controlledRead((msgTypeId, buffer, index, length) -> {
            try {
                // First 4 bytes = payload length (not sent)
                int payloadLength = buffer.getInt(index);
                int payloadOffset = index + LENGTH_PREFIX_SIZE;

                // Notify listener (e.g., for persistence logging)
                if (outgoingMessageListener != null) {
                    outgoingMessageListener.onOutgoingMessage(buffer, payloadOffset, payloadLength);
                }

                // Set up direct view of payload (zero-copy)
                ringBufferDirectView.limit(payloadOffset + payloadLength);
                ringBufferDirectView.position(payloadOffset);

                // Write directly to socket from ring buffer
                while (ringBufferDirectView.hasRemaining()) {
                    int written = socketChannel.write(ringBufferDirectView);
                    if (written == 0) {
                        // Socket buffer full - copy remaining to writeBuffer
                        // (unavoidable since ring buffer memory will be reclaimed)
                        if (writeBuffer.remaining() < ringBufferDirectView.remaining()) {
                            log.error("Write buffer overflow during ring buffer drain");
                            return ControlledMessageHandler.Action.BREAK;
                        }
                        writeBuffer.put(ringBufferDirectView);
                        // Register for write interest
                        if (selectionKey != null && selectionKey.isValid()) {
                            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                        }
                        return ControlledMessageHandler.Action.BREAK; // Stop draining until socket is writable
                    }
                }
                return ControlledMessageHandler.Action.CONTINUE;
            } catch (IOException e) {
                log.error("Error draining ring buffer to socket: {}", e.getMessage());
                return ControlledMessageHandler.Action.BREAK;
            }
        });
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
