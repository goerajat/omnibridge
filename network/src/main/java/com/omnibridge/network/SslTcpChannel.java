package com.omnibridge.network;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSL-enabled TCP channel for non-blocking secure connections.
 *
 * <p>This class wraps a {@link SocketChannel} with an {@link SSLEngine} to provide
 * transparent SSL/TLS encryption for network I/O operations. It handles the SSL
 * handshake process and all data encryption/decryption.</p>
 *
 * <p>The channel operates in non-blocking mode and integrates with the
 * {@link NetworkEventLoop} for SSL handshake state management.</p>
 */
public class SslTcpChannel {

    private static final Logger log = LoggerFactory.getLogger(SslTcpChannel.class);
    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    /**
     * SSL handshake state.
     */
    public enum SslState {
        /** Not yet started */
        NOT_STARTED,
        /** Handshake in progress */
        HANDSHAKING,
        /** Handshake complete, ready for data */
        ESTABLISHED,
        /** Shutdown in progress */
        CLOSING,
        /** SSL connection closed */
        CLOSED
    }

    private final long id;
    private final SocketChannel socketChannel;
    private final SSLEngine sslEngine;
    private final String remoteAddress;
    private final String localAddress;

    // SSL buffers
    private final ByteBuffer netInBuffer;      // Encrypted data from network
    private final ByteBuffer netOutBuffer;     // Encrypted data to network
    private final ByteBuffer appInBuffer;      // Decrypted data for application
    private final ByteBuffer appOutBuffer;     // Plaintext data to encrypt

    // Buffer for application to read from (backed by appInBuffer)
    private final DirectByteBuffer readBuffer;

    // Ring buffer for outgoing messages
    private final TcpChannel delegateChannel;

    private SelectionKey selectionKey;
    private volatile SslState sslState = SslState.NOT_STARTED;
    private volatile boolean closed;
    private Object attachment;

    // Hostname for SNI
    private final String hostname;

    /**
     * Create an SSL TCP channel.
     *
     * @param socketChannel the underlying socket channel
     * @param sslContext the SSL context to use
     * @param clientMode true if acting as SSL client
     * @param hostname the remote hostname (for SNI)
     * @param readBufferSize the read buffer size
     * @param writeBufferSize the write buffer size
     * @throws IOException if unable to initialize
     */
    public SslTcpChannel(SocketChannel socketChannel, SSLContext sslContext, boolean clientMode,
                         String hostname, int readBufferSize, int writeBufferSize) throws IOException {
        this.id = ID_GENERATOR.incrementAndGet();
        this.socketChannel = socketChannel;
        this.hostname = hostname;

        // Create SSL engine
        if (hostname != null && clientMode) {
            this.sslEngine = sslContext.createSSLEngine(hostname, socketChannel.socket().getPort());
        } else {
            this.sslEngine = sslContext.createSSLEngine();
        }
        sslEngine.setUseClientMode(clientMode);

        // Enable SNI for client mode
        if (clientMode && hostname != null) {
            SSLParameters params = sslEngine.getSSLParameters();
            params.setServerNames(java.util.List.of(new SNIHostName(hostname)));
            sslEngine.setSSLParameters(params);
        }

        // Allocate SSL buffers based on SSL session sizes
        SSLSession session = sslEngine.getSession();
        int appBufferSize = session.getApplicationBufferSize();
        int netBufferSize = session.getPacketBufferSize();

        this.netInBuffer = ByteBuffer.allocateDirect(netBufferSize);
        this.netOutBuffer = ByteBuffer.allocateDirect(netBufferSize);
        this.appInBuffer = ByteBuffer.allocateDirect(Math.max(appBufferSize, readBufferSize));
        this.appOutBuffer = ByteBuffer.allocateDirect(Math.max(appBufferSize, writeBufferSize));

        // Create DirectByteBuffer view for application reads
        this.readBuffer = new DirectByteBuffer(appInBuffer.capacity());

        // Create delegate channel for ring buffer operations
        this.delegateChannel = new TcpChannel(socketChannel, readBufferSize, writeBufferSize);

        // Get addresses
        var remote = socketChannel.getRemoteAddress();
        var local = socketChannel.getLocalAddress();
        this.remoteAddress = remote != null ? remote.toString() : "unknown";
        this.localAddress = local != null ? local.toString() : "unknown";
    }

    /**
     * Get the unique channel ID.
     */
    public long getId() {
        return id;
    }

    /**
     * Get the SSL state.
     */
    public SslState getSslState() {
        return sslState;
    }

    /**
     * Check if the SSL handshake is complete.
     */
    public boolean isHandshakeComplete() {
        return sslState == SslState.ESTABLISHED;
    }

    /**
     * Get the underlying socket channel.
     */
    SocketChannel getSocketChannel() {
        return socketChannel;
    }

    /**
     * Get the SSL engine.
     */
    SSLEngine getSSLEngine() {
        return sslEngine;
    }

    /**
     * Get the remote address.
     */
    public String getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Get the local address.
     */
    public String getLocalAddress() {
        return localAddress;
    }

    /**
     * Check if connected and handshake complete.
     */
    public boolean isConnected() {
        return !closed && socketChannel.isConnected() && sslState == SslState.ESTABLISHED;
    }

    /**
     * Check if closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Get the read buffer.
     */
    DirectByteBuffer getReadBuffer() {
        return readBuffer;
    }

    /**
     * Set the selection key.
     */
    void setSelectionKey(SelectionKey key) {
        this.selectionKey = key;
        delegateChannel.setSelectionKey(key);
    }

    /**
     * Get the selection key.
     */
    SelectionKey getSelectionKey() {
        return selectionKey;
    }

    /**
     * Begin the SSL handshake.
     *
     * @throws IOException if handshake initiation fails
     */
    public void beginHandshake() throws IOException {
        if (sslState != SslState.NOT_STARTED) {
            throw new IllegalStateException("Handshake already started: " + sslState);
        }

        sslState = SslState.HANDSHAKING;
        sslEngine.beginHandshake();
        log.debug("Channel {}: SSL handshake started", id);
    }

    /**
     * Process the SSL handshake.
     *
     * <p>This method should be called when the channel is readable or writable
     * during the handshake phase.</p>
     *
     * @return true if handshake is complete, false if more I/O is needed
     * @throws IOException if an I/O error occurs
     * @throws SSLException if an SSL error occurs
     */
    public boolean processHandshake() throws IOException {
        if (sslState != SslState.HANDSHAKING) {
            return sslState == SslState.ESTABLISHED;
        }

        SSLEngineResult.HandshakeStatus status = sslEngine.getHandshakeStatus();

        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
               status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            switch (status) {
                case NEED_UNWRAP -> {
                    int read = socketChannel.read(netInBuffer);
                    if (read < 0) {
                        throw new IOException("Connection closed during handshake");
                    }

                    netInBuffer.flip();
                    SSLEngineResult result = sslEngine.unwrap(netInBuffer, appInBuffer);
                    netInBuffer.compact();

                    status = result.getHandshakeStatus();

                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // Need more network data
                        return false;
                    }
                }

                case NEED_WRAP -> {
                    appOutBuffer.clear();
                    SSLEngineResult result = sslEngine.wrap(appOutBuffer, netOutBuffer);
                    status = result.getHandshakeStatus();

                    netOutBuffer.flip();
                    while (netOutBuffer.hasRemaining()) {
                        int written = socketChannel.write(netOutBuffer);
                        if (written == 0) {
                            // Socket buffer full, need to wait for writable
                            return false;
                        }
                    }
                    netOutBuffer.clear();
                }

                case NEED_TASK -> {
                    // Run delegated tasks
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    status = sslEngine.getHandshakeStatus();
                }

                default -> {
                    throw new SSLException("Unexpected handshake status: " + status);
                }
            }
        }

        // Handshake complete
        sslState = SslState.ESTABLISHED;
        log.info("Channel {}: SSL handshake complete, cipher: {}", id,
                sslEngine.getSession().getCipherSuite());
        return true;
    }

    /**
     * Read decrypted data into the read buffer.
     *
     * @return number of bytes read, -1 on end of stream
     * @throws IOException if an I/O error occurs
     */
    public int read() throws IOException {
        if (sslState != SslState.ESTABLISHED) {
            throw new IllegalStateException("SSL not established: " + sslState);
        }

        // Read from network
        int bytesRead = socketChannel.read(netInBuffer);
        if (bytesRead < 0) {
            return -1;
        }

        // Decrypt
        netInBuffer.flip();
        ByteBuffer appBuffer = readBuffer.byteBuffer();

        int totalDecrypted = 0;
        while (netInBuffer.hasRemaining()) {
            SSLEngineResult result = sslEngine.unwrap(netInBuffer, appBuffer);

            switch (result.getStatus()) {
                case OK -> totalDecrypted += result.bytesProduced();
                case BUFFER_UNDERFLOW -> {
                    // Need more network data
                    netInBuffer.compact();
                    return totalDecrypted;
                }
                case BUFFER_OVERFLOW -> {
                    // App buffer full, caller should consume data
                    netInBuffer.compact();
                    return totalDecrypted;
                }
                case CLOSED -> {
                    handleClose();
                    return -1;
                }
            }
        }

        netInBuffer.compact();
        return totalDecrypted;
    }

    /**
     * Write data from the ring buffer.
     *
     * @return true if all data was flushed
     * @throws IOException if an I/O error occurs
     */
    public boolean flush() throws IOException {
        if (sslState != SslState.ESTABLISHED) {
            return false;
        }

        // Drain ring buffer and encrypt
        // For now, use a simple approach - copy data to appOutBuffer and encrypt
        if (!delegateChannel.hasRingBufferMessages()) {
            return true;
        }

        // Get data from ring buffer via delegate
        delegateChannel.getRingBuffer().controlledRead((msgTypeId, buffer, index, length) -> {
            try {
                int payloadLength = buffer.getInt(index);
                int payloadOffset = index + 4;

                // Copy payload to appOutBuffer
                appOutBuffer.clear();
                for (int i = 0; i < payloadLength; i++) {
                    appOutBuffer.put(buffer.getByte(payloadOffset + i));
                }
                appOutBuffer.flip();

                // Encrypt and write
                while (appOutBuffer.hasRemaining()) {
                    SSLEngineResult result = sslEngine.wrap(appOutBuffer, netOutBuffer);
                    if (result.getStatus() != SSLEngineResult.Status.OK) {
                        log.error("SSL wrap failed: {}", result.getStatus());
                        return org.agrona.concurrent.ControlledMessageHandler.Action.BREAK;
                    }

                    netOutBuffer.flip();
                    while (netOutBuffer.hasRemaining()) {
                        int written = socketChannel.write(netOutBuffer);
                        if (written == 0) {
                            // Socket buffer full
                            if (selectionKey != null && selectionKey.isValid()) {
                                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                            }
                            return org.agrona.concurrent.ControlledMessageHandler.Action.BREAK;
                        }
                    }
                    netOutBuffer.clear();
                }

                return org.agrona.concurrent.ControlledMessageHandler.Action.CONTINUE;
            } catch (IOException e) {
                log.error("Error writing SSL data", e);
                return org.agrona.concurrent.ControlledMessageHandler.Action.BREAK;
            }
        });

        return !delegateChannel.hasRingBufferMessages();
    }

    /**
     * Write encrypted data directly (for pre-encrypted data).
     *
     * @param data the plaintext data
     * @param offset offset in the data array
     * @param length number of bytes to write
     * @return number of bytes written, -1 if buffer full
     * @throws IOException if an I/O error occurs
     */
    public int write(byte[] data, int offset, int length) throws IOException {
        if (sslState != SslState.ESTABLISHED) {
            return -1;
        }

        appOutBuffer.clear();
        appOutBuffer.put(data, offset, length);
        appOutBuffer.flip();

        int totalWritten = 0;
        while (appOutBuffer.hasRemaining()) {
            SSLEngineResult result = sslEngine.wrap(appOutBuffer, netOutBuffer);
            if (result.getStatus() != SSLEngineResult.Status.OK) {
                return -1;
            }

            netOutBuffer.flip();
            while (netOutBuffer.hasRemaining()) {
                int written = socketChannel.write(netOutBuffer);
                if (written == 0) {
                    // Socket buffer full
                    return totalWritten;
                }
                totalWritten += result.bytesConsumed();
            }
            netOutBuffer.clear();
        }

        return totalWritten;
    }

    /**
     * Write raw bytes through the ring buffer.
     *
     * @param data the data to write
     * @param offset offset in data
     * @param length number of bytes
     * @return bytes queued, or -1 if buffer full
     */
    public int writeRaw(byte[] data, int offset, int length) {
        if (closed || sslState != SslState.ESTABLISHED) {
            return -1;
        }
        return delegateChannel.writeRaw(data, offset, length);
    }

    /**
     * Get the ring buffer for this channel.
     */
    public org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer getRingBuffer() {
        return delegateChannel.getRingBuffer();
    }

    /**
     * Try to claim space in the ring buffer.
     */
    public int tryClaim(int length) {
        return delegateChannel.tryClaim(length);
    }

    /**
     * Get the buffer for writing.
     */
    public MutableDirectBuffer buffer() {
        return delegateChannel.buffer();
    }

    /**
     * Commit a claimed message.
     */
    public void commit(int index) {
        delegateChannel.commit(index);
    }

    /**
     * Abort a claimed message.
     */
    public void abort(int index) {
        delegateChannel.abort(index);
    }

    /**
     * Check if ring buffer has messages.
     */
    public boolean hasRingBufferMessages() {
        return delegateChannel.hasRingBufferMessages();
    }

    /**
     * Handle SSL close.
     */
    private void handleClose() {
        sslState = SslState.CLOSING;
        try {
            sslEngine.closeInbound();
        } catch (SSLException e) {
            log.debug("Error closing SSL inbound", e);
        }
        sslEngine.closeOutbound();
    }

    /**
     * Initiate SSL shutdown.
     */
    public void closeOutbound() throws IOException {
        if (sslState == SslState.CLOSED || sslState == SslState.CLOSING) {
            return;
        }

        sslState = SslState.CLOSING;
        sslEngine.closeOutbound();

        // Flush close_notify
        appOutBuffer.clear();
        appOutBuffer.flip();
        SSLEngineResult result = sslEngine.wrap(appOutBuffer, netOutBuffer);
        netOutBuffer.flip();
        while (netOutBuffer.hasRemaining()) {
            socketChannel.write(netOutBuffer);
        }
        netOutBuffer.clear();
    }

    /**
     * Close the channel.
     */
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        sslState = SslState.CLOSED;

        try {
            closeOutbound();
        } catch (IOException e) {
            log.debug("Error during SSL close", e);
        }

        try {
            if (selectionKey != null) {
                selectionKey.cancel();
            }
            socketChannel.close();
        } catch (IOException e) {
            log.debug("Error closing channel {}: {}", id, e.getMessage());
        }

        delegateChannel.close();
        log.debug("SSL channel {} closed", id);
    }

    /**
     * Set a user attachment.
     */
    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }

    /**
     * Get the user attachment.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttachment() {
        return (T) attachment;
    }

    @Override
    public String toString() {
        return "SslTcpChannel[id=" + id + ", " + localAddress + " -> " + remoteAddress +
                ", sslState=" + sslState + ", closed=" + closed + "]";
    }
}
