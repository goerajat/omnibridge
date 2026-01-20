package com.fixengine.network;

import org.agrona.MutableDirectBuffer;
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
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

    // Ring buffer for outgoing messages (many producers, single consumer)
    private final ManyToOneRingBuffer ringBuffer;
    private final UnsafeBuffer ringBufferBackingBuffer;
    private final int ringBufferCapacity;

    // Reusable buffer for claims
    private final UnsafeBuffer claimBuffer = new UnsafeBuffer(new byte[0]);

    // Temporary buffer for draining to socket
    private final ByteBuffer drainBuffer;

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
        this.readBuffer = ByteBuffer.allocateDirect(readBufferSize);
        this.writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);
        this.ringBufferCapacity = ringBufferCapacity;

        // Initialize ring buffer
        int totalBufferSize = ringBufferCapacity + RingBufferDescriptor.TRAILER_LENGTH;
        this.ringBufferBackingBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(totalBufferSize));
        this.ringBuffer = new ManyToOneRingBuffer(ringBufferBackingBuffer);

        // Drain buffer for writing to socket
        this.drainBuffer = ByteBuffer.allocateDirect(writeBufferSize);

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
     */
    ByteBuffer getReadBuffer() {
        return readBuffer;
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

    // ==================== Write Methods ====================

    /**
     * Write data to the channel.
     * This method is non-blocking and may not write all data immediately.
     * Data is queued in the write buffer and will be flushed when the channel becomes writable.
     *
     * @param data the data to write
     * @return the number of bytes queued for writing
     * @throws IOException if the channel is closed or an I/O error occurs
     */
    public int write(ByteBuffer data) throws IOException {
        if (closed) {
            throw new IOException("Channel is closed");
        }

        int bytesToWrite = data.remaining();
        if (bytesToWrite == 0) {
            return 0;
        }

        // Try direct write first
        int written = socketChannel.write(data);

        if (data.hasRemaining()) {
            // Buffer remaining data for later
            if (writeBuffer.remaining() < data.remaining()) {
                throw new IOException("Write buffer overflow - data: " + data.remaining() +
                        ", available: " + writeBuffer.remaining());
            }
            writeBuffer.put(data);

            // Register for write interest
            if (selectionKey != null && selectionKey.isValid()) {
                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
            }
        }

        return bytesToWrite;
    }

    /**
     * Write data to the channel from a byte array.
     *
     * @param data the data to write
     * @param offset the offset in the array
     * @param length the number of bytes to write
     * @return the number of bytes queued for writing
     * @throws IOException if the channel is closed or an I/O error occurs
     */
    public int write(byte[] data, int offset, int length) throws IOException {
        return write(ByteBuffer.wrap(data, offset, length));
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
     * Drain all messages from the ring buffer to the socket.
     *
     * <p>Each message in the ring buffer has the format:</p>
     * <pre>
     * [4 bytes: FIX message length][N bytes: FIX message payload]
     * </pre>
     *
     * <p>Only the FIX message payload is sent to the socket. The length prefix
     * is used internally to know how many bytes to send.</p>
     *
     * @throws IOException if an I/O error occurs
     */
    private void drainRingBuffer() throws IOException {
        ringBuffer.read((msgTypeId, buffer, index, length) -> {
            try {
                // First 4 bytes = payload length (not sent)
                int payloadLength = buffer.getInt(index);
                int payloadOffset = index + LENGTH_PREFIX_SIZE;

                // Copy to drain buffer and send
                drainBuffer.clear();
                buffer.getBytes(payloadOffset, drainBuffer, payloadLength);
                drainBuffer.flip();

                // Write to socket
                while (drainBuffer.hasRemaining()) {
                    int written = socketChannel.write(drainBuffer);
                    if (written == 0) {
                        // Socket buffer full, need to buffer remaining data
                        if (writeBuffer.remaining() < drainBuffer.remaining()) {
                            log.error("Write buffer overflow during ring buffer drain");
                            return;
                        }
                        writeBuffer.put(drainBuffer);
                        // Register for write interest
                        if (selectionKey != null && selectionKey.isValid()) {
                            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                        }
                        return;
                    }
                }
            } catch (IOException e) {
                log.error("Error draining ring buffer to socket: {}", e.getMessage());
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
