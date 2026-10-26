package com.omnibridge.network;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.Singleton;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.network.affinity.CpuAffinity;
import com.omnibridge.network.config.NetworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Single-threaded non-blocking network event loop.
 * Handles all network I/O operations including accepting connections,
 * connecting to remote hosts, reading, and writing data.
 *
 * <p>All callbacks are invoked on the event loop thread.</p>
 *
 * <p>The sockets and the poller belong to a {@link Transport}: the kernel's through NIO
 * ({@link NioTransport}, the default) or a userspace stack chosen with
 * {@code network.transport}. The loop talks to it only through {@link Transport} and
 * {@link Endpoint}.</p>
 *
 * <p>Supports two modes of operation:</p>
 * <ul>
 *   <li><b>Normal mode</b>: Uses a blocking poll with timeout for power efficiency</li>
 *   <li><b>Busy spin mode</b>: Uses a non-blocking poll for minimal latency at cost of CPU usage</li>
 * </ul>
 */
@Singleton
public class NetworkEventLoop implements Runnable, AutoCloseable, Component {

    private static final Logger log = LoggerFactory.getLogger(NetworkEventLoop.class);

    private static final int DEFAULT_READ_BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_WRITE_BUFFER_SIZE = 64 * 1024;
    private static final long DEFAULT_SELECT_TIMEOUT_MS = 100;

    private final String name;
    private final int cpuAffinity;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final long selectTimeoutMs;
    private final boolean busySpinMode;
    private volatile boolean writeOnLoopCommit = true;

    private final Transport transport;
    private final Queue<Runnable> taskQueue;
    private final AtomicBoolean running;
    private final AtomicBoolean started;
    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;

    // Reusable callbacks so the poll and the loop allocate nothing per iteration
    private final Transport.ReadyHandler readyHandler = this::onReady;
    private final BooleanSupplier iteration = this::iterate;

    // Poller wakeups requested for work queued from outside the loop (commits, tasks)
    private final LongAdder selectorWakeups = new LongAdder();

    private volatile Thread eventLoopThread;
    // Run once per loop iteration, on the loop thread, before the ring drain: work that must
    // happen on the loop at a time of its own choosing (a paced sender). Null when unused.
    private volatile Runnable loopTask;
    private NetworkHandler defaultHandler;
    private ComponentProvider componentProvider;

    /**
     * Create a new NetworkEventLoop with default settings.
     *
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop() throws IOException {
        this("default", -1, DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_SELECT_TIMEOUT_MS, false);
    }

    /**
     * Create a new NetworkEventLoop with default settings.
     *
     * @param name the name of this event loop (used for thread naming)
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(String name) throws IOException {
        this(name, -1, DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_SELECT_TIMEOUT_MS, false);
    }

    /**
     * Create a new NetworkEventLoop with CPU affinity.
     *
     * @param name the name of this event loop
     * @param cpuAffinity the CPU core to bind to (-1 for no affinity)
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(String name, int cpuAffinity) throws IOException {
        this(name, cpuAffinity, DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_SELECT_TIMEOUT_MS, false);
    }

    /**
     * Create a new NetworkEventLoop from NetworkConfig.
     *
     * @param config the network configuration
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(NetworkConfig config) throws IOException {
        this(config, null);
    }

    /**
     * Create a new NetworkEventLoop from NetworkConfig and ComponentProvider. The transport is
     * the one named by {@code network.transport} (see {@link Transports}).
     *
     * @param config the network configuration
     * @param provider the component provider (may be null)
     * @throws IOException if unable to open the transport
     */
    public NetworkEventLoop(NetworkConfig config, ComponentProvider provider) throws IOException {
        this(config.getName(),
             config.getCpuAffinity(),
             config.getReadBufferSize(),
             config.getWriteBufferSize(),
             config.getSelectTimeoutMs(),
             config.isBusySpinMode(),
             Transports.create(config));
        this.writeOnLoopCommit = config.isWriteOnLoopCommit();
        this.componentProvider = provider;
    }

    /**
     * Create a new NetworkEventLoop with full configuration.
     *
     * @param name the name of this event loop
     * @param cpuAffinity the CPU core to bind to (-1 for no affinity)
     * @param readBufferSize the size of read buffers for each channel
     * @param writeBufferSize the size of write buffers for each channel
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(String name, int cpuAffinity, int readBufferSize, int writeBufferSize) throws IOException {
        this(name, cpuAffinity, readBufferSize, writeBufferSize, DEFAULT_SELECT_TIMEOUT_MS, false);
    }

    /**
     * Create a new NetworkEventLoop with full configuration on the kernel transport.
     *
     * @param name the name of this event loop
     * @param cpuAffinity the CPU core to bind to (-1 for no affinity)
     * @param readBufferSize the size of read buffers for each channel
     * @param writeBufferSize the size of write buffers for each channel
     * @param selectTimeoutMs the select timeout in milliseconds
     * @param busySpinMode whether to use busy spin mode (selectNow instead of select)
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(String name, int cpuAffinity, int readBufferSize, int writeBufferSize,
                            long selectTimeoutMs, boolean busySpinMode) throws IOException {
        this(name, cpuAffinity, readBufferSize, writeBufferSize, selectTimeoutMs, busySpinMode, new NioTransport());
    }

    /**
     * Create a new NetworkEventLoop with full configuration on the given transport.
     *
     * @param name the name of this event loop
     * @param cpuAffinity the CPU core to bind to (-1 for no affinity)
     * @param readBufferSize the size of read buffers for each channel
     * @param writeBufferSize the size of write buffers for each channel
     * @param selectTimeoutMs the poll timeout in milliseconds (normal mode)
     * @param busySpinMode whether to busy spin (non-blocking polls)
     * @param transport the transport that owns the sockets and the poller
     */
    public NetworkEventLoop(String name, int cpuAffinity, int readBufferSize, int writeBufferSize,
                            long selectTimeoutMs, boolean busySpinMode, Transport transport) {
        this.name = name;
        this.cpuAffinity = cpuAffinity;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.selectTimeoutMs = selectTimeoutMs;
        this.busySpinMode = busySpinMode;
        this.transport = transport;
        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.running = new AtomicBoolean(false);
        this.started = new AtomicBoolean(false);
        if (!busySpinMode && !transport.supportsBlockingPoll()) {
            throw new IllegalArgumentException("Transport '" + transport.name() + "' requires busy-spin mode");
        }
    }

    /**
     * Get the component provider.
     */
    public ComponentProvider getComponentProvider() {
        return componentProvider;
    }

    /**
     * Set the component provider.
     */
    public void setComponentProvider(ComponentProvider provider) {
        this.componentProvider = provider;
    }

    /**
     * Set the default network handler for this event loop.
     */
    public void setDefaultHandler(NetworkHandler handler) {
        this.defaultHandler = handler;
    }

    /**
     * Set CPU affinity (must be called before start).
     * @deprecated Use constructor with NetworkConfig instead
     */
    @Deprecated
    public void setCpuAffinity(int cpu) {
        // Note: this only takes effect if not already started
    }

    /**
     * Check if busy spin mode is enabled.
     */
    public boolean isBusySpinMode() {
        return busySpinMode;
    }

    /**
     * The transport this loop runs on.
     */
    public Transport getTransport() {
        return transport;
    }

    /**
     * Start the event loop in a new thread.
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            running.set(true);
            eventLoopThread = new Thread(this, "NetworkIO-" + name);
            eventLoopThread.setDaemon(true);
            eventLoopThread.start();
            log.info("NetworkEventLoop '{}' started (transport={}, busySpinMode={})",
                    name, transport.name(), busySpinMode);
        }
    }

    /**
     * Stop the event loop.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            transport.wakeup();
            try {
                if (eventLoopThread != null) {
                    eventLoopThread.join(5000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("NetworkEventLoop '{}' stopped", name);
        }
    }

    /**
     * Check if the event loop is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Check if the current thread is the event loop thread.
     */
    public boolean isInEventLoop() {
        return Thread.currentThread() == eventLoopThread;
    }

    /**
     * Execute a task on the event loop thread.
     * If called from the event loop thread, executes immediately.
     * Otherwise, queues the task for later execution.
     *
     * @param task the task to execute
     */
    public void execute(Runnable task) {
        if (isInEventLoop()) {
            task.run();
        } else {
            taskQueue.add(task);
            wakeup();
        }
    }

    /**
     * Set a task the loop runs once per iteration on its own thread, before draining the
     * rings, so a message the task commits is written in the same iteration (with
     * write-on-loop-commit, inside the commit). In busy-spin mode an iteration takes well
     * under a microsecond, so the task can pace sends by {@code System.nanoTime()} without a
     * thread hand-off. The task must return quickly: it holds up every channel on the loop.
     *
     * @param task the task, or null to remove it
     */
    public void setLoopTask(Runnable task) {
        this.loopTask = task;
    }

    /**
     * Wake the poller so the loop picks up work queued from another thread
     * (a ring buffer commit or a task).
     *
     * <p>No-op when the loop busy-spins (the poll never blocks) or when called on the
     * loop thread, which drains ring buffers after processing events and tasks. A wakeup is
     * a system call plus an extra poll return, once per message otherwise.</p>
     */
    void wakeup() {
        if (busySpinMode || Thread.currentThread() == eventLoopThread) {
            return;
        }
        selectorWakeups.increment();
        transport.wakeup();
    }

    /**
     * Whether a commit made now should write the ring at once: only on the loop thread, which
     * otherwise drains it only after the current callback and the rest of the iteration.
     */
    boolean writesOnCommit() {
        return writeOnLoopCommit && Thread.currentThread() == eventLoopThread;
    }

    /**
     * Write messages committed on the loop thread before commit() returns (default on).
     */
    public void setWriteOnLoopCommit(boolean enabled) {
        this.writeOnLoopCommit = enabled;
    }

    /**
     * Number of poller wakeups requested through {@link #wakeup()} (for tests).
     */
    long getSelectorWakeupCount() {
        return selectorWakeups.sum();
    }

    /**
     * Create a TCP acceptor that listens for incoming connections.
     *
     * <p>On the kernel transport the socket is bound here, on the caller's thread, so a port in
     * use is an {@link IOException} to the caller. On a transport that opens sockets on the loop
     * thread the bind runs there and a failure is reported through
     * {@link NetworkHandler#onAcceptFailed}.</p>
     *
     * @param host the host to bind to (null for any)
     * @param port the port to listen on
     * @param handler the handler for connection events
     * @return the TcpAcceptor
     * @throws IOException if unable to bind
     */
    public TcpAcceptor createAcceptor(String host, int port, NetworkHandler handler) throws IOException {
        InetSocketAddress address = host != null ?
                new InetSocketAddress(host, port) :
                new InetSocketAddress(port);
        NetworkHandler effectiveHandler = handler != null ? handler : defaultHandler;

        if (transport.opensOnLoopThread()) {
            TcpAcceptor acceptor = new TcpAcceptor(address, effectiveHandler, readBufferSize, writeBufferSize);
            execute(() -> {
                Endpoint listener = null;
                try {
                    listener = transport.listen(address);
                    acceptor.listening(listener);
                    registerListener(listener, acceptor);
                } catch (Exception e) {
                    log.error("Failed to listen on {}: {}", address, e.getMessage());
                    if (listener != null) {
                        listener.close();
                    }
                    acceptor.close();
                    effectiveHandler.onAcceptFailed(e);
                }
            });
            return acceptor;
        }

        Endpoint listener = transport.listen(address);
        TcpAcceptor acceptor = new TcpAcceptor(listener, effectiveHandler, readBufferSize, writeBufferSize);

        execute(() -> {
            try {
                registerListener(listener, acceptor);
            } catch (IOException e) {
                log.error("Failed to register acceptor: {}", e.getMessage());
            }
        });

        return acceptor;
    }

    private void registerListener(Endpoint listener, TcpAcceptor acceptor) throws IOException {
        listener.context(acceptor);
        transport.register(listener, Endpoint.ACCEPT);
        log.info("TcpAcceptor listening on {}", listener.localAddress());
    }

    /**
     * Create a TCP acceptor that listens on all interfaces.
     *
     * @param port the port to listen on
     * @param handler the handler for connection events
     * @return the TcpAcceptor
     */
    public TcpAcceptor createAcceptor(int port, NetworkHandler handler) {
        try {
            return createAcceptor(null, port, handler);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create acceptor on port " + port, e);
        }
    }

    /**
     * Connect to a remote host asynchronously.
     *
     * @param address the remote address
     * @param handler the handler for connection events
     */
    public void connect(InetSocketAddress address, NetworkHandler handler) {
        try {
            connect(address.getHostString(), address.getPort(), handler);
        } catch (IOException e) {
            handler.onConnectFailed(address.toString(), e);
        }
    }

    /**
     * Connect to a remote host asynchronously.
     *
     * @param host the remote host
     * @param port the remote port
     * @param handler the handler for connection events
     * @throws IOException if unable to initiate connection
     */
    public void connect(String host, int port, NetworkHandler handler) throws IOException {
        connect(host, port, handler, null);
    }

    /**
     * Connect to a remote host asynchronously with SSL.
     *
     * <p>The host name is resolved here, on the caller's thread. On the kernel transport the
     * socket is opened here too, so a failure to start the connect is an {@link IOException}
     * to the caller; on a transport that opens sockets on the loop thread every failure is
     * reported through {@link NetworkHandler#onConnectFailed}.</p>
     *
     * @param host the remote host
     * @param port the remote port
     * @param handler the handler for connection events
     * @param sslConfig the SSL configuration (null for plain connection)
     * @throws IOException if unable to initiate connection
     */
    public void connect(String host, int port, NetworkHandler handler, SslConfig sslConfig) throws IOException {
        boolean ssl = sslConfig != null && sslConfig.isEnabled();
        if (ssl && !(transport instanceof NioTransport)) {
            throw new IOException("SSL connections need the kernel transport (network.transport = kernel), not '"
                    + transport.name() + "'");
        }

        InetSocketAddress address = new InetSocketAddress(host, port);
        NetworkHandler effectiveHandler = handler != null ? handler : defaultHandler;
        String remoteAddress = address.toString();

        if (transport.opensOnLoopThread()) {
            execute(() -> {
                Endpoint endpoint = null;
                try {
                    endpoint = transport.connect(address);
                    registerConnect(endpoint, remoteAddress, host, effectiveHandler, sslConfig);
                } catch (Exception e) {
                    if (endpoint != null) {
                        endpoint.close();
                    }
                    effectiveHandler.onConnectFailed(remoteAddress, e);
                }
            });
            return;
        }

        Endpoint endpoint = transport.connect(address);

        execute(() -> {
            try {
                registerConnect(endpoint, remoteAddress, host, effectiveHandler, sslConfig);
            } catch (Exception e) {
                endpoint.close();
                effectiveHandler.onConnectFailed(remoteAddress, e);
            }
        });
    }

    /** Loop thread: register a freshly opened endpoint, connected already or still connecting. */
    private void registerConnect(Endpoint endpoint, String remoteAddress, String host,
                                 NetworkHandler handler, SslConfig sslConfig) throws Exception {
        if (endpoint.isConnected()) {
            // Immediate connection (unlikely but possible)
            if (sslConfig != null && sslConfig.isEnabled()) {
                handleSslConnected((NioEndpoint) endpoint, host, handler, sslConfig);
            } else {
                TcpChannel channel = new TcpChannel(endpoint, readBufferSize, writeBufferSize);
                endpoint.context(new ChannelContext(channel, handler));
                transport.register(endpoint, Endpoint.READ);
                channel.setEventLoop(this);
                channel.markConnected();
                handler.onConnected(channel);
            }
        } else {
            // Connection in progress
            endpoint.context(new ConnectContext(endpoint, remoteAddress, handler, host, sslConfig));
            transport.register(endpoint, Endpoint.CONNECT);
        }
    }

    /**
     * Handle SSL connection setup after TCP connect completes (kernel transport only).
     */
    private void handleSslConnected(NioEndpoint endpoint, String hostname,
                                     NetworkHandler handler, SslConfig sslConfig) throws Exception {
        SSLContext sslContext = sslConfig.getSSLContext();
        SslTcpChannel sslChannel = new SslTcpChannel(endpoint.socketChannel(), sslContext, true,
                hostname, readBufferSize, writeBufferSize);

        // Start SSL handshake
        sslChannel.beginHandshake();

        // Register for both read and write during handshake
        endpoint.context(new SslChannelContext(sslChannel, handler, true));
        transport.register(endpoint, Endpoint.READ | Endpoint.WRITE);
        sslChannel.setSelectionKey(endpoint.selectionKey());

        log.debug("SSL connection initiated to {}", sslChannel.getRemoteAddress());
    }

    @Override
    public void run() {
        // Set CPU affinity if configured
        if (cpuAffinity >= 0) {
            CpuAffinity.setAffinity(cpuAffinity);
        }

        log.info("NetworkEventLoop '{}' thread started (transport={}, cpuAffinity={}, busySpinMode={})",
                name, transport.name(), cpuAffinity, busySpinMode);

        try {
            transport.start(readyHandler);
            transport.run(iteration);
        } catch (Exception e) {
            log.error("Transport '{}' failed: {}", transport.name(), e.getMessage(), e);
            running.set(false);
        }

        // Cleanup
        closeAllChannels();
        try {
            transport.close();
        } catch (IOException e) {
            log.debug("Error closing transport: {}", e.getMessage());
        }

        log.info("NetworkEventLoop '{}' thread exited", name);
    }

    /**
     * One iteration of the loop: queued tasks, the loop task, a drain of every ring to its
     * socket, one poll (blocking with the configured timeout in normal mode, non-blocking in
     * busy-spin mode) dispatching ready endpoints, and a second drain so replies committed
     * while processing go out in the same iteration.
     *
     * @return false once the loop has been stopped
     */
    private boolean iterate() {
        if (!running.get()) {
            return false;
        }
        try {
            // Process any queued tasks
            processTasks();
            runLoopTask();

            // Drain ring buffers to sockets (for messages from other threads)
            drainAllChannelRingBuffers();

            // Wait for or check for events
            transport.poll(busySpinMode ? 0 : (selectTimeoutMs > 0 ? selectTimeoutMs : -1));

            // Drain again after processing for immediate response sending
            drainAllChannelRingBuffers();
        } catch (IOException e) {
            if (running.get()) {
                log.error("Error in event loop: {}", e.getMessage(), e);
            }
        }
        return running.get();
    }

    /**
     * Drain all channel ring buffers to their sockets.
     *
     * <p>Walks the transport's registered endpoints and drains any pending messages from
     * their ring buffers. Endpoints closed from another thread are skipped until the
     * transport drops them.</p>
     */
    private void drainAllChannelRingBuffers() {
        final Transport t = transport;
        for (int i = 0, n = t.endpointCount(); i < n; i++) {
            Endpoint endpoint = t.endpointAt(i);
            if (!endpoint.isOpen()) {
                continue;
            }

            Object context = endpoint.context();
            if (context instanceof ChannelContext ctx) {
                TcpChannel channel = ctx.channel();
                if (channel.hasRingBufferMessages()) {
                    try {
                        channel.drainRingBufferToSocket();
                    } catch (IOException e) {
                        log.error("Error draining ring buffer for channel {}: {}",
                                channel.getId(), e.getMessage());
                        closeChannel(channel, ctx.handler(), e);
                    }
                }
            } else if (context instanceof SslChannelContext ctx) {
                SslTcpChannel sslChannel = ctx.sslChannel();
                if (sslChannel.isHandshakeComplete() && sslChannel.hasRingBufferMessages()) {
                    try {
                        sslChannel.flush();
                    } catch (IOException e) {
                        log.error("Error draining ring buffer for SSL channel {}: {}",
                                sslChannel.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Dispatch one ready endpoint (called by the transport's poll).
     */
    private void onReady(Endpoint endpoint, int readyOps) {
        Object context = endpoint.context();

        try {
            if ((readyOps & Endpoint.ACCEPT) != 0) {
                handleAccept(endpoint, (TcpAcceptor) context);
            } else if ((readyOps & Endpoint.CONNECT) != 0) {
                handleConnect(endpoint, (ConnectContext) context);
            } else if (context instanceof SslChannelContext sslCtx) {
                // Handle SSL channels differently
                if ((readyOps & (Endpoint.READ | Endpoint.WRITE)) != 0) {
                    handleSslIO((NioEndpoint) endpoint, sslCtx, readyOps);
                }
            } else if (context instanceof ChannelContext ctx) {
                if ((readyOps & Endpoint.READ) != 0) {
                    handleRead(endpoint, ctx);
                }
                if (endpoint.isOpen() && (readyOps & Endpoint.WRITE) != 0) {
                    handleWrite(ctx);
                }
            }
        } catch (Exception e) {
            log.error("Error processing endpoint: {}", e.getMessage(), e);
            handleError(endpoint, e);
        }
    }

    /**
     * Handle SSL channel I/O.
     */
    private void handleSslIO(NioEndpoint endpoint, SslChannelContext ctx, int readyOps) {
        SslTcpChannel sslChannel = ctx.sslChannel;

        try {
            // If handshake not complete, process handshake
            if (!sslChannel.isHandshakeComplete()) {
                boolean complete = sslChannel.processHandshake();
                if (complete) {
                    // Handshake complete - switch to read-only interest
                    endpoint.interestOps(Endpoint.READ);
                    log.debug("SSL handshake complete for {}", sslChannel.getRemoteAddress());

                    // Notify handler (use TcpChannel-compatible wrapper)
                    ctx.handler.onConnected(wrapSslChannel(sslChannel));
                }
                return;
            }

            // Normal SSL read
            if ((readyOps & Endpoint.READ) != 0) {
                handleSslRead(ctx);
            }

            // Normal SSL write
            if (endpoint.isOpen() && (readyOps & Endpoint.WRITE) != 0) {
                handleSslWrite(endpoint, ctx);
            }

        } catch (Exception e) {
            log.error("SSL I/O error for channel {}: {}", sslChannel.getId(), e.getMessage());
            closeSslChannel(sslChannel, ctx.handler, e);
        }
    }

    /**
     * Handle SSL read.
     */
    private void handleSslRead(SslChannelContext ctx) throws IOException {
        SslTcpChannel sslChannel = ctx.sslChannel;
        DirectByteBuffer directBuffer = sslChannel.getReadBuffer();
        ByteBuffer byteBuffer = directBuffer.byteBuffer();

        int bytesRead = sslChannel.read();

        if (bytesRead < 0) {
            // Remote closed
            closeSslChannel(sslChannel, ctx.handler, null);
            return;
        }

        if (bytesRead > 0) {
            int dataLength = byteBuffer.position();
            TcpChannel wrapper = wrapSslChannel(sslChannel);
            try {
                int consumed = ctx.handler.onDataReceived(wrapper, directBuffer, 0, dataLength);
                if (consumed > 0 && consumed < dataLength) {
                    directBuffer.putBytes(0, directBuffer, consumed, dataLength - consumed);
                    byteBuffer.position(dataLength - consumed);
                } else {
                    byteBuffer.clear();
                }
            } catch (Exception e) {
                log.error("Error in onDataReceived for SSL channel: {}", e.getMessage(), e);
                byteBuffer.clear();
            }
        }
    }

    /**
     * Handle SSL write.
     */
    private void handleSslWrite(NioEndpoint endpoint, SslChannelContext ctx) throws IOException {
        if (ctx.sslChannel.flush()) {
            // All data flushed, remove write interest
            endpoint.writeInterest(false);
        }
    }

    /**
     * Wrap an SslTcpChannel as TcpChannel for handler compatibility.
     *
     * <p>This creates a lightweight wrapper that delegates to the SSL channel.
     * The wrapper maintains the same API as TcpChannel.</p>
     */
    private TcpChannel wrapSslChannel(SslTcpChannel sslChannel) {
        // For now, store the SslTcpChannel in the attachment of a minimal TcpChannel wrapper
        // This allows handlers to use the same interface
        try {
            TcpChannel wrapper = new TcpChannel(sslChannel.getSocketChannel(), readBufferSize, writeBufferSize);
            wrapper.setAttachment(sslChannel);
            return wrapper;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create SSL channel wrapper", e);
        }
    }

    /**
     * Close an SSL channel.
     */
    private void closeSslChannel(SslTcpChannel channel, NetworkHandler handler, Throwable reason) {
        if (!channel.isClosed()) {
            channel.close();
            try {
                TcpChannel wrapper = wrapSslChannel(channel);
                handler.onDisconnected(wrapper, reason);
            } catch (Exception e) {
                log.error("Error in onDisconnected for SSL channel: {}", e.getMessage(), e);
            }
        }
    }

    private void runLoopTask() {
        Runnable task = loopTask;
        if (task != null) {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error in loop task: {}", e.getMessage(), e);
            }
        }
    }

    private void processTasks() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error executing task: {}", e.getMessage(), e);
            }
        }
    }

    private void handleAccept(Endpoint listener, TcpAcceptor acceptor) throws IOException {
        Endpoint accepted = listener.accept();
        if (accepted != null) {
            TcpChannel channel = new TcpChannel(accepted, readBufferSize, writeBufferSize);
            accepted.context(new ChannelContext(channel, acceptor.getHandler()));
            transport.register(accepted, Endpoint.READ);
            channel.setEventLoop(this);
            channel.markConnected();

            log.debug("Accepted connection from {}", channel.getRemoteAddress());
            acceptor.getHandler().onConnected(channel);
        }
    }

    private void handleConnect(Endpoint endpoint, ConnectContext ctx) throws IOException {
        try {
            if (endpoint.finishConnect()) {
                // Check if SSL is enabled
                if (ctx.sslConfig != null && ctx.sslConfig.isEnabled()) {
                    try {
                        // Re-registers the same endpoint for the handshake (the connect
                        // registration is updated in place, not cancelled)
                        handleSslConnected((NioEndpoint) endpoint, ctx.hostname, ctx.handler, ctx.sslConfig);
                    } catch (Exception e) {
                        endpoint.close();
                        ctx.handler.onConnectFailed(ctx.remoteAddress, e);
                    }
                } else {
                    TcpChannel channel = new TcpChannel(endpoint, readBufferSize, writeBufferSize);
                    endpoint.context(new ChannelContext(channel, ctx.handler));
                    endpoint.interestOps(Endpoint.READ);
                    channel.setEventLoop(this);
                    channel.markConnected();

                    log.debug("Connected to {}", channel.getRemoteAddress());
                    ctx.handler.onConnected(channel);
                }
            }
        } catch (IOException e) {
            endpoint.close();
            ctx.handler.onConnectFailed(ctx.remoteAddress, e);
        }
    }

    private void handleRead(Endpoint endpoint, ChannelContext ctx) throws IOException {
        TcpChannel channel = ctx.channel;
        DirectByteBuffer directBuffer = channel.getReadBuffer();
        ByteBuffer byteBuffer = directBuffer.byteBuffer();

        // Ask handler how many bytes it needs
        int bytesToRead = ctx.handler.getNumBytesToRead(channel);

        // Limit buffer to read only the requested number of bytes
        int originalLimit = byteBuffer.limit();
        int newLimit = Math.min(byteBuffer.position() + bytesToRead, byteBuffer.capacity());
        byteBuffer.limit(newLimit);

        int bytesRead;
        try {
            bytesRead = endpoint.read(byteBuffer);
        } catch (IOException e) {
            byteBuffer.limit(originalLimit); // Restore limit before closing
            closeChannel(channel, ctx.handler, e);
            return;
        } finally {
            // Restore original limit
            byteBuffer.limit(originalLimit);
        }

        if (bytesRead == -1) {
            // Remote closed
            closeChannel(channel, ctx.handler, null);
            return;
        }

        if (bytesRead > 0) {
            // Data available: pass DirectBuffer with offset and length to handler
            int dataLength = byteBuffer.position();
            try {
                // Pass DirectBuffer interface for efficient access
                int consumed = ctx.handler.onDataReceived(channel, directBuffer, 0, dataLength);
                if (consumed > 0 && consumed < dataLength) {
                    // Compact: move unconsumed data to start
                    directBuffer.putBytes(0, directBuffer, consumed, dataLength - consumed);
                    byteBuffer.position(dataLength - consumed);
                } else {
                    byteBuffer.clear();
                }
            } catch (Exception e) {
                log.error("Error in onDataReceived: {}", e.getMessage(), e);
                byteBuffer.clear();
            }
        }
    }

    private void handleWrite(ChannelContext ctx) {
        TcpChannel channel = ctx.channel;

        try {
            channel.flush();
        } catch (IOException e) {
            closeChannel(channel, ctx.handler, e);
        }
    }

    private void handleError(Endpoint endpoint, Exception e) {
        Object context = endpoint.context();
        if (context instanceof ChannelContext ctx) {
            closeChannel(ctx.channel, ctx.handler, e);
        } else if (context instanceof SslChannelContext ctx) {
            closeSslChannel(ctx.sslChannel, ctx.handler, e);
        } else if (context instanceof ConnectContext ctx) {
            endpoint.close();
            ctx.handler.onConnectFailed(ctx.remoteAddress, e);
        } else {
            endpoint.close();
        }
    }

    private void closeChannel(TcpChannel channel, NetworkHandler handler, Throwable reason) {
        if (!channel.isClosed()) {
            channel.close();
            try {
                handler.onDisconnected(channel, reason);
            } catch (Exception e) {
                log.error("Error in onDisconnected: {}", e.getMessage(), e);
            }
        }
    }

    private void closeAllChannels() {
        final Transport t = transport;
        for (int i = 0, n = t.endpointCount(); i < n; i++) {
            Endpoint endpoint = t.endpointAt(i);
            Object context = endpoint.context();
            if (context instanceof ChannelContext ctx) {
                closeChannel(ctx.channel, ctx.handler, null);
            } else if (context instanceof TcpAcceptor acceptor) {
                acceptor.close();
            } else {
                endpoint.close();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== Component Interface ====================

    @Override
    public void initialize() throws Exception {
        if (componentState != ComponentState.UNINITIALIZED) {
            throw new IllegalStateException("Cannot initialize from state: " + componentState);
        }
        componentState = ComponentState.INITIALIZED;
        log.debug("[{}] Component initialized", name);
    }

    @Override
    public void startActive() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + componentState);
        }
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void startStandby() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + componentState);
        }
        // In standby mode, initialize but don't start the event loop
        componentState = ComponentState.STANDBY;
        log.info("[{}] Started in STANDBY mode", name);
    }

    @Override
    public void becomeActive() throws Exception {
        if (componentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + componentState);
        }
        start();
        componentState = ComponentState.ACTIVE;
        log.info("[{}] Transitioned to ACTIVE mode", name);
    }

    @Override
    public void becomeStandby() throws Exception {
        if (componentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + componentState);
        }
        stop();
        componentState = ComponentState.STANDBY;
        log.info("[{}] Transitioned to STANDBY mode", name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }

    /**
     * Context for active channels.
     */
    private record ChannelContext(TcpChannel channel, NetworkHandler handler) {
    }

    /**
     * Context for SSL channels.
     */
    private record SslChannelContext(SslTcpChannel sslChannel, NetworkHandler handler, boolean clientMode) {
    }

    /**
     * Context for pending connections.
     */
    private record ConnectContext(Endpoint endpoint, String remoteAddress, NetworkHandler handler,
                                  String hostname, SslConfig sslConfig) {
    }
}
