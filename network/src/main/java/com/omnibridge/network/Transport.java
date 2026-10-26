package com.omnibridge.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;

/**
 * The stack under a {@link NetworkEventLoop}: opens, polls and closes {@link Endpoint}s.
 * One transport per loop. The kernel transport ({@link NioTransport}) wraps an NIO selector;
 * a userspace stack implements the same calls over its own sockets and poller.
 *
 * <p>Threading: {@link #start}, {@link #run}, {@link #poll}, {@link #register},
 * {@link #endpointCount}, {@link #endpointAt} and {@link #close} are called on the loop
 * thread. {@link #connect} and {@link #listen} are called on the caller's thread unless
 * {@link #opensOnLoopThread()} is true, in which case the loop calls them on its own thread.
 * {@link #wakeup} may be called from any thread.</p>
 */
public interface Transport extends AutoCloseable {

    /** Receives readiness events from {@link #poll}. */
    @FunctionalInterface
    interface ReadyHandler {
        /**
         * @param endpoint the ready endpoint (registered, open at the time of the poll)
         * @param readyOps the ready events, a combination of the {@link Endpoint} constants
         */
        void onReady(Endpoint endpoint, int readyOps);
    }

    /** The configured name of this transport ("kernel", "fstack"). */
    String name();

    /**
     * Called once on the loop thread, after CPU affinity is set and before the first
     * iteration. The handler receives every readiness event from {@link #poll}.
     */
    void start(ReadyHandler handler) throws IOException;

    /**
     * Run the loop on the current thread: call {@code iteration} until it returns false. The
     * kernel transport loops over it; a transport that owns the thread (a stack whose poller
     * must be the one calling back) calls it from its own loop and stops when it returns false.
     */
    void run(BooleanSupplier iteration);

    /**
     * Poll for readiness and dispatch every ready endpoint to the handler given to
     * {@link #start}. {@code timeoutMs == 0} must not block; {@code > 0} may block up to that
     * long and {@code < 0} until an event or a {@link #wakeup} (both only if
     * {@link #supportsBlockingPoll()}).
     */
    void poll(long timeoutMs) throws IOException;


    /** Whether {@link #poll} can block. A transport that must be busy-spun returns false. */
    boolean supportsBlockingPoll();

    /**
     * Whether {@link #connect} and {@link #listen} must run on the loop thread. When true the
     * loop calls them from a queued task and reports failures through the handler callbacks
     * instead of a synchronous exception.
     */
    boolean opensOnLoopThread();

    /**
     * Open a non-blocking connection to the address. The endpoint is not registered; it is
     * {@link Endpoint#isConnected() connected} already or will signal {@link Endpoint#CONNECT}
     * once registered for it.
     */
    Endpoint connect(InetSocketAddress address) throws IOException;

    /** Bind a listening endpoint (not yet registered). */
    Endpoint listen(InetSocketAddress address) throws IOException;

    /**
     * Register an endpoint for events; it then takes part in {@link #poll} and is listed by
     * {@link #endpointAt}. Registering an endpoint again replaces its interest set and must
     * not list it twice. Loop thread only.
     */
    void register(Endpoint endpoint, int interestOps) throws IOException;

    /**
     * Number of registered endpoints (connections, pending connects and listeners), including
     * ones closed since the last poll. Loop thread only.
     */
    int endpointCount();

    /** The registered endpoint at an index below {@link #endpointCount()}. Loop thread only. */
    Endpoint endpointAt(int index);

    /** Interrupt a blocking {@link #poll}. No-op when polls never block. Any thread. */
    void wakeup();

    /** Release the poller. Endpoints are closed by the loop before this. Loop thread only. */
    @Override
    void close() throws IOException;
}
