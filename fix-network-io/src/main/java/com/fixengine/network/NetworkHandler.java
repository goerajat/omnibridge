package com.fixengine.network;

import org.agrona.DirectBuffer;

/**
 * Callback interface for network events.
 * All callbacks are invoked on the network I/O thread.
 */
public interface NetworkHandler {

    /**
     * Default number of bytes to read when no specific amount is requested.
     */
    int DEFAULT_BYTES_TO_READ = 8192;

    /**
     * Get the number of bytes the handler wants to read from the network.
     * This allows protocol-aware handlers to specify exact byte counts for
     * efficient message framing.
     *
     * <p>For FIX protocol handlers:</p>
     * <ul>
     *   <li>Return a small amount (e.g., 25 bytes) when starting to read a new message,
     *       enough to parse the header and determine message length</li>
     *   <li>After parsing the header, return the exact number of remaining bytes needed</li>
     *   <li>Once the message is complete, return the header size again for the next message</li>
     * </ul>
     *
     * @param channel the channel to read from
     * @return the number of bytes to read, or DEFAULT_BYTES_TO_READ if unspecified
     */
    default int getNumBytesToRead(TcpChannel channel) {
        return DEFAULT_BYTES_TO_READ;
    }

    /**
     * Called when a connection is established.
     * For acceptors, this is called for each accepted connection.
     * For connectors, this is called when the connection completes.
     *
     * @param channel the connected channel
     */
    void onConnected(TcpChannel channel);

    /**
     * Called when data is received on a channel.
     *
     * <p>The buffer contains received data starting at the given offset with the given length.
     * The handler should process the data and return the number of bytes consumed.</p>
     *
     * @param channel the channel that received data
     * @param buffer the DirectBuffer containing received data
     * @param offset the offset in the buffer where data starts
     * @param length the number of bytes of data available
     * @return the number of bytes consumed from the buffer
     */
    int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length);

    /**
     * Called when a channel is disconnected.
     * This may be due to remote close, local close, or error.
     *
     * @param channel the disconnected channel
     * @param reason the reason for disconnection (may be null for clean close)
     */
    void onDisconnected(TcpChannel channel, Throwable reason);

    /**
     * Called when a connection attempt fails.
     *
     * @param remoteAddress the address that failed to connect
     * @param reason the cause of the failure
     */
    void onConnectFailed(String remoteAddress, Throwable reason);

    /**
     * Called when an acceptor fails to accept a connection.
     *
     * @param reason the cause of the failure
     */
    void onAcceptFailed(Throwable reason);
}
