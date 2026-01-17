package com.fixengine.network;

import java.nio.ByteBuffer;

/**
 * Callback interface for network events.
 * All callbacks are invoked on the network I/O thread.
 */
public interface NetworkHandler {

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
     * The buffer is positioned at the start of received data and limited to the end.
     * The handler should consume the data it processes and compact/clear as needed.
     *
     * @param channel the channel that received data
     * @param buffer the buffer containing received data
     * @return the number of bytes consumed from the buffer
     */
    int onDataReceived(TcpChannel channel, ByteBuffer buffer);

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
