package com.omnibridge.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Kernel endpoint: a {@link SocketChannel} (connection) or {@link ServerSocketChannel}
 * (listener) with its {@link SelectionKey} once registered. A channel built by hand (the SSL
 * delegate, tests) has no key and no transport: write-interest changes are then no-ops and
 * {@link #wakeup()} wakes nothing.
 */
final class NioEndpoint implements Endpoint {

    private static final String UNKNOWN = "unknown";

    private final SocketChannel socketChannel;      // null for a listener
    private final ServerSocketChannel serverChannel; // null for a connection
    private final NioTransport transport;            // null when built outside a transport

    // Resolved on first use and kept once known: a pending connect has no remote address yet
    private String remoteAddress;
    private String localAddress;
    private int localPort = -1;

    private SelectionKey key;
    private Object context;
    private volatile boolean closed;

    NioEndpoint(SocketChannel socketChannel, NioTransport transport) {
        this.socketChannel = socketChannel;
        this.serverChannel = null;
        this.transport = transport;
    }

    NioEndpoint(ServerSocketChannel serverChannel, NioTransport transport) {
        this.socketChannel = null;
        this.serverChannel = serverChannel;
        this.transport = transport;
        this.remoteAddress = UNKNOWN;
    }

    private static String format(InetSocketAddress address) {
        return address != null ? address.getHostString() + ":" + address.getPort() : UNKNOWN;
    }

    private InetSocketAddress remote() {
        try {
            return socketChannel != null ? (InetSocketAddress) socketChannel.getRemoteAddress() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private InetSocketAddress local() {
        try {
            return (InetSocketAddress) (socketChannel != null
                    ? socketChannel.getLocalAddress() : serverChannel.getLocalAddress());
        } catch (IOException e) {
            return null;
        }
    }

    /** The connection's socket, or null for a listener. */
    SocketChannel socketChannel() {
        return socketChannel;
    }

    SelectionKey selectionKey() {
        return key;
    }

    void selectionKey(SelectionKey key) {
        this.key = key;
    }

    /**
     * Register with the selector, or update the interest set if already registered.
     *
     * @return true if this is a new registration
     */
    boolean register(Selector selector, int ops) throws IOException {
        SelectionKey k = key;
        if (k != null && k.isValid() && k.selector() == selector) {
            k.interestOps(ops);
            k.attach(this);
            return false;
        }
        SelectableChannel channel = socketChannel != null ? socketChannel : serverChannel;
        this.key = channel.register(selector, ops, this);
        return true;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return socketChannel.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return socketChannel.write(src);
    }

    @Override
    public Endpoint accept() throws IOException {
        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) {
            return null;
        }
        accepted.configureBlocking(false);
        accepted.setOption(StandardSocketOptions.TCP_NODELAY, true);
        return new NioEndpoint(accepted, transport);
    }

    @Override
    public boolean finishConnect() throws IOException {
        return socketChannel.finishConnect();
    }

    @Override
    public void interestOps(int ops) {
        SelectionKey k = key;
        if (k != null && k.isValid()) {
            k.interestOps(ops);
        }
    }

    @Override
    public void writeInterest(boolean wanted) {
        SelectionKey k = key;
        if (k == null || !k.isValid()) {
            return;
        }
        int ops = k.interestOps();
        int updated = wanted ? ops | SelectionKey.OP_WRITE : ops & ~SelectionKey.OP_WRITE;
        if (updated != ops) {
            k.interestOps(updated);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SelectionKey k = key;
        if (k != null) {
            k.cancel();
        }
        try {
            if (socketChannel != null) {
                socketChannel.close();
            } else {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // nothing to do with a close failure
        }
        if (transport != null) {
            transport.endpointClosed();
        }
    }

    @Override
    public boolean isOpen() {
        return !closed && (socketChannel != null ? socketChannel.isOpen() : serverChannel.isOpen());
    }

    @Override
    public boolean isConnected() {
        return socketChannel != null && socketChannel.isConnected();
    }

    @Override
    public String remoteAddress() {
        String s = remoteAddress;
        if (s == null) {
            InetSocketAddress remote = remote();
            if (remote == null) {
                return UNKNOWN;
            }
            s = remoteAddress = format(remote);
        }
        return s;
    }

    @Override
    public String localAddress() {
        String s = localAddress;
        if (s == null) {
            InetSocketAddress local = local();
            if (local == null) {
                return UNKNOWN;
            }
            s = localAddress = format(local);
            localPort = local.getPort();
        }
        return s;
    }

    @Override
    public int localPort() {
        if (localAddress == null) {
            localAddress();
        }
        return localPort;
    }

    @Override
    public void wakeup() {
        SelectionKey k = key;
        if (k != null && k.selector() != null) {
            k.selector().wakeup();
        }
    }

    @Override
    public Object context() {
        return context;
    }

    @Override
    public void context(Object context) {
        this.context = context;
    }

    @Override
    public String toString() {
        return "NioEndpoint[" + localAddress + " -> " + remoteAddress + ", open=" + isOpen() + "]";
    }
}
