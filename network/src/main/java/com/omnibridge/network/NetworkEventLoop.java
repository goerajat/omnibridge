package com.omnibridge.network;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.network.affinity.CpuAffinity;
import com.omnibridge.network.config.NetworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Single-threaded non-blocking network event loop.
 * Handles all network I/O operations including accepting connections,
 * connecting to remote hosts, reading, and writing data.
 *
 * <p>All callbacks are invoked on the event loop thread.</p>
 *
 * <p>Supports two modes of operation:</p>
 * <ul>
 *   <li><b>Normal mode</b>: Uses blocking select() with timeout for power efficiency</li>
 *   <li><b>Busy spin mode</b>: Uses selectNow() for minimal latency at cost of CPU usage</li>
 * </ul>
 */
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

    private final Selector selector;
    private final Queue<Runnable> taskQueue;
    private final AtomicBoolean running;
    private final AtomicBoolean started;
    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;

    // Reusable consumer to avoid garbage in busy spin mode
    private final Consumer<SelectionKey> keyProcessor;

    private Thread eventLoopThread;
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
     * Create a new NetworkEventLoop from NetworkConfig and ComponentProvider.
     *
     * @param config the network configuration
     * @param provider the component provider (may be null)
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(NetworkConfig config, ComponentProvider provider) throws IOException {
        this(config.getName(),
             config.getCpuAffinity(),
             config.getReadBufferSize(),
             config.getWriteBufferSize(),
             config.getSelectTimeoutMs(),
             config.isBusySpinMode());
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
     * Create a new NetworkEventLoop with full configuration.
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
        this.name = name;
        this.cpuAffinity = cpuAffinity;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.selectTimeoutMs = selectTimeoutMs;
        this.busySpinMode = busySpinMode;
        this.selector = Selector.open();
        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.running = new AtomicBoolean(false);
        this.started = new AtomicBoolean(false);

        // Create reusable consumer to avoid garbage in selectNow path
        this.keyProcessor = this::processKey;
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
     * Start the event loop in a new thread.
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            running.set(true);
            eventLoopThread = new Thread(this, "NetworkIO-" + name);
            eventLoopThread.setDaemon(true);
            eventLoopThread.start();
            log.info("NetworkEventLoop '{}' started (busySpinMode={})", name, busySpinMode);
        }
    }

    /**
     * Stop the event loop.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            selector.wakeup();
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
            selector.wakeup();
        }
    }

    /**
     * Create a TCP acceptor that listens for incoming connections.
     *
     * @param host the host to bind to (null for any)
     * @param port the port to listen on
     * @param handler the handler for connection events
     * @return the TcpAcceptor
     * @throws IOException if unable to bind
     */
    public TcpAcceptor createAcceptor(String host, int port, NetworkHandler handler) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);

        InetSocketAddress address = host != null ?
                new InetSocketAddress(host, port) :
                new InetSocketAddress(port);
        serverChannel.bind(address);

        TcpAcceptor acceptor = new TcpAcceptor(serverChannel, handler != null ? handler : defaultHandler,
                readBufferSize, writeBufferSize);

        execute(() -> {
            try {
                SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT, acceptor);
                acceptor.setSelectionKey(key);
                log.info("TcpAcceptor listening on {}", address);
            } catch (ClosedChannelException e) {
                log.error("Failed to register acceptor: {}", e.getMessage());
            }
        });

        return acceptor;
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
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);

        InetSocketAddress address = new InetSocketAddress(host, port);
        boolean connected = socketChannel.connect(address);

        NetworkHandler effectiveHandler = handler != null ? handler : defaultHandler;

        execute(() -> {
            try {
                if (connected) {
                    // Immediate connection (unlikely but possible)
                    TcpChannel channel = new TcpChannel(socketChannel, readBufferSize, writeBufferSize);
                    SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ,
                            new ChannelContext(channel, effectiveHandler));
                    channel.setSelectionKey(key);
                    channel.markConnected();
                    effectiveHandler.onConnected(channel);
                } else {
                    // Connection in progress
                    ConnectContext ctx = new ConnectContext(socketChannel, address.toString(), effectiveHandler);
                    socketChannel.register(selector, SelectionKey.OP_CONNECT, ctx);
                }
            } catch (IOException e) {
                try {
                    socketChannel.close();
                } catch (IOException ignored) {
                }
                effectiveHandler.onConnectFailed(address.toString(), e);
            }
        });
    }

    @Override
    public void run() {
        // Set CPU affinity if configured
        if (cpuAffinity >= 0) {
            CpuAffinity.setAffinity(cpuAffinity);
        }

        log.info("NetworkEventLoop '{}' thread started (cpuAffinity={}, busySpinMode={})",
                name, cpuAffinity, busySpinMode);

        if (busySpinMode) {
            runBusySpinLoop();
        } else {
            runNormalLoop();
        }

        // Cleanup
        closeAllChannels();
        try {
            selector.close();
        } catch (IOException e) {
            log.debug("Error closing selector: {}", e.getMessage());
        }

        log.info("NetworkEventLoop '{}' thread exited", name);
    }

    /**
     * Normal event loop using blocking select with timeout.
     */
    private void runNormalLoop() {
        while (running.get()) {
            try {
                // Process any queued tasks
                processTasks();

                // Drain ring buffers BEFORE select (for messages from other threads)
                drainAllChannelRingBuffers();

                // Wait for events
                int selected = selector.select(selectTimeoutMs);

                if (selected > 0) {
                    processSelectedKeysNormal();
                }

                // Drain ring buffers AFTER processing (for immediate response sending)
                // This ensures responses committed during message processing are sent
                // immediately, not on the next iteration
                drainAllChannelRingBuffers();
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error in event loop: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Busy spin event loop using selectNow for minimal latency.
     * Uses selectNow(Consumer) to avoid Iterator allocation.
     */
    private void runBusySpinLoop() {
        while (running.get()) {
            try {
                // Process any queued tasks
                processTasks();

                // Drain ring buffers to sockets
                drainAllChannelRingBuffers();

                // Non-blocking select with consumer to avoid garbage
                selector.selectNow(keyProcessor);

                // Drain again after processing for immediate response sending
                drainAllChannelRingBuffers();

            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error in event loop: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Drain all channel ring buffers to their sockets.
     *
     * <p>This method iterates through all registered channels and drains
     * any pending messages from their ring buffers to the sockets.</p>
     */
    private void drainAllChannelRingBuffers() {
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) {
                continue;
            }

            Object attachment = key.attachment();
            if (attachment instanceof ChannelContext ctx) {
                TcpChannel channel = ctx.channel();
                if (channel.hasRingBufferMessages()) {
                    try {
                        channel.drainRingBufferToSocket();
                    } catch (IOException e) {
                        log.error("Error draining ring buffer for channel {}: {}",
                                channel.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Process a single selection key (used by busy spin mode).
     */
    private void processKey(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }

        try {
            if (key.isAcceptable()) {
                handleAccept(key);
            } else if (key.isConnectable()) {
                handleConnect(key);
            } else {
                if (key.isReadable()) {
                    handleRead(key);
                }
                if (key.isValid() && key.isWritable()) {
                    handleWrite(key);
                }
            }
        } catch (Exception e) {
            log.error("Error processing key: {}", e.getMessage(), e);
            handleError(key, e);
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

    /**
     * Process selected keys using iterator (normal mode).
     */
    private void processSelectedKeysNormal() {
        var selectedKeys = selector.selectedKeys();
        var iterator = selectedKeys.iterator();

        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();
            processKey(key);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        TcpAcceptor acceptor = (TcpAcceptor) key.attachment();
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

        SocketChannel socketChannel = serverChannel.accept();
        if (socketChannel != null) {
            socketChannel.configureBlocking(false);
            socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);

            TcpChannel channel = new TcpChannel(socketChannel, readBufferSize, writeBufferSize);
            SelectionKey channelKey = socketChannel.register(selector, SelectionKey.OP_READ,
                    new ChannelContext(channel, acceptor.getHandler()));
            channel.setSelectionKey(channelKey);
            channel.markConnected();

            log.debug("Accepted connection from {}", channel.getRemoteAddress());
            acceptor.getHandler().onConnected(channel);
        }
    }

    private void handleConnect(SelectionKey key) throws IOException {
        ConnectContext ctx = (ConnectContext) key.attachment();
        SocketChannel socketChannel = ctx.socketChannel;

        try {
            if (socketChannel.finishConnect()) {
                TcpChannel channel = new TcpChannel(socketChannel, readBufferSize, writeBufferSize);
                key.interestOps(SelectionKey.OP_READ);
                key.attach(new ChannelContext(channel, ctx.handler));
                channel.setSelectionKey(key);
                channel.markConnected();

                log.debug("Connected to {}", channel.getRemoteAddress());
                ctx.handler.onConnected(channel);
            }
        } catch (IOException e) {
            key.cancel();
            socketChannel.close();
            ctx.handler.onConnectFailed(ctx.remoteAddress, e);
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        ChannelContext ctx = (ChannelContext) key.attachment();
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
            bytesRead = channel.getSocketChannel().read(byteBuffer);
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

    private void handleWrite(SelectionKey key) throws IOException {
        ChannelContext ctx = (ChannelContext) key.attachment();
        TcpChannel channel = ctx.channel;

        try {
            channel.flush();
        } catch (IOException e) {
            closeChannel(channel, ctx.handler, e);
        }
    }

    private void handleError(SelectionKey key, Exception e) {
        Object attachment = key.attachment();
        if (attachment instanceof ChannelContext ctx) {
            closeChannel(ctx.channel, ctx.handler, e);
        } else if (attachment instanceof ConnectContext ctx) {
            key.cancel();
            try {
                ctx.socketChannel.close();
            } catch (IOException ignored) {
            }
            ctx.handler.onConnectFailed(ctx.remoteAddress, e);
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
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof ChannelContext ctx) {
                closeChannel(ctx.channel, ctx.handler, null);
            } else if (key.attachment() instanceof TcpAcceptor acceptor) {
                acceptor.close();
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
     * Context for pending connections.
     */
    private record ConnectContext(SocketChannel socketChannel, String remoteAddress, NetworkHandler handler) {
    }
}
