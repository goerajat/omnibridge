package com.fixengine.network;

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

    private final long id;
    private final SocketChannel socketChannel;
    private final String remoteAddress;
    private final String localAddress;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

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
        this.id = ID_GENERATOR.incrementAndGet();
        this.socketChannel = socketChannel;
        this.readBuffer = ByteBuffer.allocateDirect(readBufferSize);
        this.writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);

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
     * Flush any pending write data.
     * Called by the event loop when the channel becomes writable.
     *
     * @return true if all data was flushed, false if more data remains
     * @throws IOException if an I/O error occurs
     */
    boolean flush() throws IOException {
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
                if (selectionKey != null && selectionKey.isValid()) {
                    selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                }
                return true;
            }
        } catch (IOException e) {
            writeBuffer.clear();
            throw e;
        }
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
