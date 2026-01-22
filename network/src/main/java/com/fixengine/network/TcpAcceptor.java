package com.fixengine.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

/**
 * TCP acceptor that listens for incoming connections.
 */
public class TcpAcceptor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpAcceptor.class);

    private final ServerSocketChannel serverChannel;
    private final NetworkHandler handler;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final String bindAddress;

    private SelectionKey selectionKey;
    private volatile boolean closed;

    TcpAcceptor(ServerSocketChannel serverChannel, NetworkHandler handler,
                int readBufferSize, int writeBufferSize) throws IOException {
        this.serverChannel = serverChannel;
        this.handler = handler;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;

        InetSocketAddress local = (InetSocketAddress) serverChannel.getLocalAddress();
        this.bindAddress = local.getHostString() + ":" + local.getPort();
    }

    /**
     * Get the bind address as "host:port".
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * Get the port this acceptor is listening on.
     */
    public int getPort() {
        try {
            InetSocketAddress local = (InetSocketAddress) serverChannel.getLocalAddress();
            return local.getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Check if this acceptor is closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Get the handler for this acceptor.
     */
    NetworkHandler getHandler() {
        return handler;
    }

    /**
     * Get the read buffer size for accepted connections.
     */
    int getReadBufferSize() {
        return readBufferSize;
    }

    /**
     * Get the write buffer size for accepted connections.
     */
    int getWriteBufferSize() {
        return writeBufferSize;
    }

    void setSelectionKey(SelectionKey key) {
        this.selectionKey = key;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        try {
            if (selectionKey != null) {
                selectionKey.cancel();
            }
            serverChannel.close();
        } catch (IOException e) {
            log.debug("Error closing acceptor: {}", e.getMessage());
        }

        log.info("TcpAcceptor closed: {}", bindAddress);
    }

    @Override
    public String toString() {
        return "TcpAcceptor[" + bindAddress + ", closed=" + closed + "]";
    }
}
