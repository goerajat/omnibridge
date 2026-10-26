package com.omnibridge.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * TCP acceptor that listens for incoming connections: the event loop's context for a
 * listening {@link Endpoint}. On the kernel transport the endpoint is bound before this
 * object exists; on a transport that opens sockets on the loop thread it arrives through
 * {@link #listening} and, until then, {@link #getPort()} is the requested port.
 */
public class TcpAcceptor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpAcceptor.class);

    private final NetworkHandler handler;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final String requestedAddress;
    private final int requestedPort;

    private volatile Endpoint listener;
    private volatile boolean closed;

    TcpAcceptor(Endpoint listener, NetworkHandler handler, int readBufferSize, int writeBufferSize) {
        this.handler = handler;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.requestedAddress = listener.localAddress();
        this.requestedPort = listener.localPort();
        this.listener = listener;
    }

    TcpAcceptor(InetSocketAddress requested, NetworkHandler handler, int readBufferSize, int writeBufferSize) {
        this.handler = handler;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.requestedAddress = requested.getHostString() + ":" + requested.getPort();
        this.requestedPort = requested.getPort();
    }

    /**
     * Get the bind address as "host:port".
     */
    public String getBindAddress() {
        Endpoint l = listener;
        return l != null ? l.localAddress() : requestedAddress;
    }

    /**
     * Get the port this acceptor is listening on (the requested port until it is bound).
     */
    public int getPort() {
        Endpoint l = listener;
        return l != null ? l.localPort() : requestedPort;
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

    /** The listening endpoint, or null until bound. */
    Endpoint listener() {
        return listener;
    }

    /** Called on the loop thread once a transport that binds there has the listener. */
    void listening(Endpoint endpoint) {
        this.listener = endpoint;
        if (closed) {
            endpoint.close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        Endpoint l = listener;
        if (l != null) {
            l.close();
        }

        log.info("TcpAcceptor closed: {}", getBindAddress());
    }

    @Override
    public String toString() {
        return "TcpAcceptor[" + getBindAddress() + ", closed=" + closed + "]";
    }
}
