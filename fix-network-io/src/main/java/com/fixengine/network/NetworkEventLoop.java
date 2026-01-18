package com.fixengine.network;

import com.fixengine.network.affinity.CpuAffinity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-threaded non-blocking network event loop.
 * Handles all network I/O operations including accepting connections,
 * connecting to remote hosts, reading, and writing data.
 *
 * <p>All callbacks are invoked on the event loop thread.</p>
 */
public class NetworkEventLoop implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkEventLoop.class);

    private static final int DEFAULT_READ_BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_WRITE_BUFFER_SIZE = 64 * 1024;
    private static final long SELECT_TIMEOUT_MS = 100;

    private final String name;
    private final int cpuAffinity;
    private final int readBufferSize;
    private final int writeBufferSize;

    private final Selector selector;
    private final Queue<Runnable> taskQueue;
    private final AtomicBoolean running;
    private final AtomicBoolean started;

    private Thread eventLoopThread;
    private NetworkHandler defaultHandler;

    /**
     * Create a new NetworkEventLoop with default settings.
     *
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop() throws IOException {
        this("default", -1, DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE);
    }

    /**
     * Create a new NetworkEventLoop with default settings.
     *
     * @param name the name of this event loop (used for thread naming)
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(String name) throws IOException {
        this(name, -1, DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE);
    }

    /**
     * Create a new NetworkEventLoop with CPU affinity.
     *
     * @param name the name of this event loop
     * @param cpuAffinity the CPU core to bind to (-1 for no affinity)
     * @throws IOException if unable to open the selector
     */
    public NetworkEventLoop(String name, int cpuAffinity) throws IOException {
        this(name, cpuAffinity, DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE);
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
        this.name = name;
        this.cpuAffinity = cpuAffinity;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.selector = Selector.open();
        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.running = new AtomicBoolean(false);
        this.started = new AtomicBoolean(false);
    }

    /**
     * Set the default network handler for this event loop.
     */
    public void setDefaultHandler(NetworkHandler handler) {
        this.defaultHandler = handler;
    }

    /**
     * Set CPU affinity (must be called before start).
     */
    public void setCpuAffinity(int cpu) {
        // Note: this only takes effect if not already started
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
            log.info("NetworkEventLoop '{}' started", name);
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

        log.info("NetworkEventLoop '{}' thread started (cpuAffinity={})", name, cpuAffinity);

        while (running.get()) {
            try {
                // Process any queued tasks
                processTasks();

                // Wait for events
                int selected = selector.select(SELECT_TIMEOUT_MS);

                if (selected > 0) {
                    processSelectedKeys();
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error in event loop: {}", e.getMessage(), e);
                }
            }
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

    private void processSelectedKeys() {
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = selectedKeys.iterator();

        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();

            if (!key.isValid()) {
                continue;
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
        ByteBuffer buffer = channel.getReadBuffer();

        int bytesRead;
        try {
            bytesRead = channel.getSocketChannel().read(buffer);
        } catch (IOException e) {
            closeChannel(channel, ctx.handler, e);
            return;
        }

        if (bytesRead == -1) {
            // Remote closed
            closeChannel(channel, ctx.handler, null);
            return;
        }

        if (bytesRead > 0) {
            buffer.flip();
            try {
                int consumed = ctx.handler.onDataReceived(channel, buffer);
                if (consumed > 0 && buffer.hasRemaining()) {
                    buffer.compact();
                } else {
                    buffer.clear();
                }
            } catch (Exception e) {
                log.error("Error in onDataReceived: {}", e.getMessage(), e);
                buffer.clear();
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
