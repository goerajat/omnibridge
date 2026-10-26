package com.omnibridge.network;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One connection or listening socket of a {@link Transport}: the seam between the event loop
 * and the stack that moves the bytes (the kernel through NIO, or a userspace stack).
 *
 * <p>All methods except {@link #close()}, {@link #wakeup()}, {@link #isOpen()} and
 * {@link #isConnected()} are called on the event loop thread only. The readiness constants
 * share the values of {@link java.nio.channels.SelectionKey} so the kernel transport passes
 * them through.</p>
 */
public interface Endpoint {

    /** Readable: data or EOF waiting, or an accepted connection on a listener. */
    int READ = 1;
    /** Writable: the socket accepts more bytes (requested with {@link #writeInterest}). */
    int WRITE = 4;
    /** A pending connect has completed (successfully or not: see {@link #finishConnect()}). */
    int CONNECT = 8;
    /** A listener has a connection to {@link #accept()}. */
    int ACCEPT = 16;

    /**
     * Read into the buffer from its position up to its limit.
     *
     * @return bytes read, 0 if none are available, -1 at end of stream
     */
    int read(ByteBuffer dst) throws IOException;

    /**
     * Write the buffer from its position up to its limit.
     *
     * @return bytes written; 0 when the socket cannot take more now
     */
    int write(ByteBuffer src) throws IOException;

    /**
     * Accept a connection on a listener. The new endpoint is non-blocking with TCP_NODELAY set
     * and not yet registered for events.
     *
     * @return the accepted endpoint, or null if none is pending
     */
    Endpoint accept() throws IOException;

    /**
     * Complete a pending connect once {@link #CONNECT} is signalled.
     *
     * @return true when connected
     * @throws IOException if the connect failed
     */
    boolean finishConnect() throws IOException;

    /**
     * Replace the set of events this endpoint is registered for (a combination of the
     * readiness constants). No-op on an endpoint that is not registered.
     */
    void interestOps(int ops);

    /**
     * Add or remove {@link #WRITE} interest, leaving the other interests as they are.
     * No-op on an endpoint that is not registered.
     */
    void writeInterest(boolean wanted);

    /**
     * Close the endpoint. May be called from any thread; the transport drops the endpoint on
     * the loop thread afterwards. Idempotent.
     */
    void close();

    /** False once closed (locally or by the transport). */
    boolean isOpen();

    /** True once a connection is established (false for listeners and pending connects). */
    boolean isConnected();

    /** The remote address as "host:port", or "unknown". */
    String remoteAddress();

    /** The local address as "host:port", or "unknown". */
    String localAddress();

    /** The local port, or -1 if unbound. */
    int localPort();

    /**
     * Wake the transport's poll if it may be blocked (a commit from another thread into a
     * channel that has no event loop set). No-op for a transport that never blocks.
     */
    void wakeup();

    /** The event loop's context object for this endpoint (set once it is registered). */
    Object context();

    void context(Object context);
}
