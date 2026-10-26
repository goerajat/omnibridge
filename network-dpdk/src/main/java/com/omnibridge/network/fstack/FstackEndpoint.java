package com.omnibridge.network.fstack;

import com.omnibridge.network.Endpoint;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static com.omnibridge.network.fstack.FstackNative.EAGAIN;
import static com.omnibridge.network.fstack.FstackNative.EWOULDBLOCK;

/**
 * An F-Stack socket. Reads and writes pass the direct buffer's address straight to
 * {@code ff_read}/{@code ff_write}; the address of each buffer is resolved once and kept
 * (the loop always reads into the channel's one read buffer and writes from the ring's one
 * direct view), so the data path allocates nothing.
 */
final class FstackEndpoint implements Endpoint {

    private final FstackTransport transport;
    private final FstackNative n;
    final int fd;
    private final boolean listener;

    private volatile boolean closed;
    private volatile boolean connected;
    boolean connectPending;
    int interest;
    private Object context;

    private String remoteAddress;
    private String localAddress;
    private int localPort = -1;

    private ByteBuffer lastReadBuffer;
    private long lastReadAddress;
    private ByteBuffer lastWriteBuffer;
    private long lastWriteAddress;

    FstackEndpoint(FstackTransport transport, int fd, boolean listener, boolean connected, String remoteAddress) {
        this.transport = transport;
        this.n = transport.nativeApi();
        this.fd = fd;
        this.listener = listener;
        this.connected = connected;
        this.connectPending = !listener && !connected;
        this.remoteAddress = remoteAddress;
    }

    boolean isListener() {
        return listener;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (dst != lastReadBuffer) {
            // ofBuffer starts at the buffer's position: keep the base so any position works
            lastReadBuffer = dst;
            lastReadAddress = MemorySegment.ofBuffer(dst).address() - dst.position();
        }
        int position = dst.position();
        int remaining = dst.limit() - position;
        if (remaining == 0) {
            return 0;
        }
        long got = n.read(fd, lastReadAddress + position, remaining);
        if (got > 0) {
            dst.position(position + (int) got);
            return (int) got;
        }
        if (got == 0) {
            return -1; // end of stream
        }
        int err = n.errno();
        if (err == EAGAIN || err == EWOULDBLOCK) {
            return 0;
        }
        throw new IOException("ff_read failed on fd " + fd + ": errno " + err);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (src != lastWriteBuffer) {
            lastWriteBuffer = src;
            lastWriteAddress = MemorySegment.ofBuffer(src).address() - src.position();
        }
        int position = src.position();
        int remaining = src.limit() - position;
        if (remaining == 0) {
            return 0;
        }
        long sent = n.write(fd, lastWriteAddress + position, remaining);
        if (sent >= 0) {
            src.position(position + (int) sent);
            return (int) sent;
        }
        int err = n.errno();
        if (err == EAGAIN || err == EWOULDBLOCK) {
            return 0;
        }
        throw new IOException("ff_write failed on fd " + fd + ": errno " + err);
    }

    @Override
    public Endpoint accept() throws IOException {
        return transport.accept(this);
    }

    @Override
    public boolean finishConnect() throws IOException {
        if (connected) {
            return true;
        }
        int err = n.soError(fd);
        if (err != 0) {
            throw new IOException("connect to " + remoteAddress + " failed: errno " + err);
        }
        connectPending = false;
        connected = true;
        return true;
    }

    @Override
    public void interestOps(int ops) {
        if (closed) {
            return;
        }
        interest = ops;
        transport.updateInterest(this);
    }

    @Override
    public void writeInterest(boolean wanted) {
        int updated = wanted ? interest | WRITE : interest & ~WRITE;
        if (updated != interest) {
            interestOps(updated);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connected = false;
        transport.endpointClosed(this);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String remoteAddress() {
        String s = remoteAddress;
        if (s == null) {
            s = transport.peerAddress(fd);
            if (s != null) {
                remoteAddress = s;
            } else {
                return "unknown";
            }
        }
        return s;
    }

    @Override
    public String localAddress() {
        String s = localAddress;
        if (s == null) {
            s = transport.localAddress(fd);
            if (s == null) {
                return "unknown";
            }
            localAddress = s;
            localPort = Integer.parseInt(s.substring(s.lastIndexOf(':') + 1));
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
        // the stack is busy-polled; nothing blocks
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
        return "FstackEndpoint[fd=" + fd + ", " + localAddress() + " -> " + remoteAddress() + ", open=" + !closed + "]";
    }
}
