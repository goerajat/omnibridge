package com.omnibridge.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The kernel transport: NIO sockets on one {@link Selector}. Polls are
 * {@code selectNow(Consumer)} (busy-spin) or {@code select(Consumer, timeout)} (normal mode),
 * both garbage-free. Registered endpoints are kept in an array the loop drains from; endpoints
 * closed from any thread are dropped from it at the next poll.
 */
public final class NioTransport implements Transport {

    private final Selector selector;
    private NioEndpoint[] endpoints = new NioEndpoint[16];
    private int endpointCount;
    private volatile boolean compactionNeeded;
    private Consumer<SelectionKey> keyConsumer;

    public NioTransport() throws IOException {
        this.selector = Selector.open();
    }

    @Override
    public String name() {
        return Transports.KERNEL;
    }

    @Override
    public void start(ReadyHandler handler) {
        this.keyConsumer = key -> {
            if (key.isValid()) {
                handler.onReady((NioEndpoint) key.attachment(), key.readyOps());
            }
        };
    }

    @Override
    public void run(BooleanSupplier iteration) {
        while (iteration.getAsBoolean()) {
            // the iteration does the work
        }
    }

    @Override
    public void poll(long timeoutMs) throws IOException {
        if (compactionNeeded) {
            compact();
        }
        if (timeoutMs == 0) {
            selector.selectNow(keyConsumer);
        } else if (timeoutMs < 0) {
            selector.select(keyConsumer);
        } else {
            selector.select(keyConsumer, timeoutMs);
        }
    }

    @Override
    public boolean supportsBlockingPoll() {
        return true;
    }

    @Override
    public boolean opensOnLoopThread() {
        return false;
    }

    @Override
    public Endpoint connect(InetSocketAddress address) throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        try {
            socketChannel.configureBlocking(false);
            socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            socketChannel.connect(address);
        } catch (IOException | RuntimeException e) {
            try {
                socketChannel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return new NioEndpoint(socketChannel, this);
    }

    @Override
    public Endpoint listen(InetSocketAddress address) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        try {
            serverChannel.configureBlocking(false);
            serverChannel.bind(address);
        } catch (IOException | RuntimeException e) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return new NioEndpoint(serverChannel, this);
    }

    @Override
    public void register(Endpoint endpoint, int interestOps) throws IOException {
        NioEndpoint ep = (NioEndpoint) endpoint;
        if (!ep.register(selector, interestOps)) {
            return; // already listed: interest set updated in place
        }
        if (endpointCount == endpoints.length) {
            endpoints = Arrays.copyOf(endpoints, endpointCount * 2);
        }
        endpoints[endpointCount++] = ep;
    }

    @Override
    public int endpointCount() {
        return endpointCount;
    }

    @Override
    public Endpoint endpointAt(int index) {
        return endpoints[index];
    }

    /** Called from {@link NioEndpoint#close()} on any thread. */
    void endpointClosed() {
        compactionNeeded = true;
    }

    private void compact() {
        compactionNeeded = false;
        int kept = 0;
        for (int i = 0; i < endpointCount; i++) {
            NioEndpoint ep = endpoints[i];
            if (ep.isOpen()) {
                endpoints[kept++] = ep;
            }
        }
        Arrays.fill(endpoints, kept, endpointCount, null);
        endpointCount = kept;
    }

    @Override
    public void wakeup() {
        selector.wakeup();
    }

    @Override
    public void close() throws IOException {
        selector.close();
    }

    Selector selector() {
        return selector;
    }
}
