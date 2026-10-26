package com.omnibridge.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * An in-memory {@link Transport} for driving {@link NetworkEventLoop} without sockets: the
 * test plays the peer by feeding bytes, completing connects, queueing accepts and rationing
 * the write capacity.
 */
class FakeTransport implements Transport {

    /** Host name whose connects and listens fail with an IOException. */
    static final String FAILING_HOST = "fail";

    private final boolean opensOnLoopThread;
    private final List<FakeEndpoint> registered = new ArrayList<>();
    private final List<FakeEndpoint> opened = new CopyOnWriteArrayList<>();
    private ReadyHandler handler;
    volatile Thread openThread;

    FakeTransport(boolean opensOnLoopThread) {
        this.opensOnLoopThread = opensOnLoopThread;
    }

    /** Every endpoint handed out by connect, listen or accept, in order. */
    List<FakeEndpoint> opened() {
        return opened;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public void start(ReadyHandler handler) {
        this.handler = handler;
    }

    @Override
    public void run(BooleanSupplier iteration) {
        while (iteration.getAsBoolean()) {
            // the iteration does the work
        }
    }

    @Override
    public void poll(long timeoutMs) {
        int kept = 0;
        for (FakeEndpoint ep : registered) {
            if (ep.isOpen()) {
                registered.set(kept++, ep);
            }
        }
        while (registered.size() > kept) {
            registered.remove(registered.size() - 1);
        }
        boolean any = false;
        for (int i = 0, n = registered.size(); i < n; i++) {
            FakeEndpoint ep = registered.get(i);
            int ready = ep.readyOps();
            if (ready != 0 && ep.isOpen()) {
                any = true;
                handler.onReady(ep, ready);
            }
        }
        if (!any && timeoutMs != 0) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean supportsBlockingPoll() {
        return true;
    }

    @Override
    public boolean opensOnLoopThread() {
        return opensOnLoopThread;
    }

    @Override
    public Endpoint connect(InetSocketAddress address) throws IOException {
        openThread = Thread.currentThread();
        if (FAILING_HOST.equals(address.getHostString())) {
            throw new IOException("connect refused by the fake peer");
        }
        FakeEndpoint ep = new FakeEndpoint(false, address.getHostString() + ":" + address.getPort());
        ep.connectPending = true;
        opened.add(ep);
        return ep;
    }

    @Override
    public Endpoint listen(InetSocketAddress address) throws IOException {
        openThread = Thread.currentThread();
        if (FAILING_HOST.equals(address.getHostString())) {
            throw new IOException("address in use on the fake stack");
        }
        FakeEndpoint ep = new FakeEndpoint(true, "0.0.0.0:" + address.getPort());
        ep.port = address.getPort() == 0 ? 40000 : address.getPort();
        opened.add(ep);
        return ep;
    }

    @Override
    public void register(Endpoint endpoint, int interestOps) {
        FakeEndpoint ep = (FakeEndpoint) endpoint;
        ep.interest = interestOps;
        if (!registered.contains(ep)) {
            registered.add(ep);
        }
    }

    @Override
    public int endpointCount() {
        return registered.size();
    }

    @Override
    public Endpoint endpointAt(int index) {
        return registered.get(index);
    }

    @Override
    public void wakeup() {
    }

    @Override
    public void close() {
    }

    /** One fake socket. The peer-side methods are safe to call from the test thread. */
    static final class FakeEndpoint implements Endpoint {
        private final boolean listener;
        private final String remote;
        volatile int port = 1;
        volatile int interest;
        private Object context;
        private volatile boolean closed;
        private volatile boolean connected;
        private volatile boolean connectPending;
        private volatile IOException connectFailure;

        private final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
        private int inboundRead;
        private volatile boolean eof;
        private final ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        private volatile int writeCapacity = Integer.MAX_VALUE;
        private final ConcurrentLinkedQueue<FakeEndpoint> accepts = new ConcurrentLinkedQueue<>();

        FakeEndpoint(boolean listener, String remote) {
            this.listener = listener;
            this.remote = remote;
        }

        // ---- the peer, driven by the test ----

        synchronized void feed(byte[] bytes) {
            inbound.write(bytes, 0, bytes.length);
        }

        void endOfStream() {
            eof = true;
        }

        synchronized byte[] written() {
            return outbound.toByteArray();
        }

        synchronized int writtenLength() {
            return outbound.size();
        }

        void writeCapacity(int bytes) {
            writeCapacity = bytes;
        }

        void completeConnect() {
            connectPending = false;
            connected = true;
        }

        void failConnect(IOException e) {
            connectPending = false;
            connectFailure = e;
        }

        /** Queue a peer on a listener; returns the endpoint the loop will accept. */
        FakeEndpoint enqueueAccept(String remote) {
            FakeEndpoint peer = new FakeEndpoint(false, remote);
            peer.connected = true;
            accepts.add(peer);
            return peer;
        }

        boolean wantsWrite() {
            return (interest & WRITE) != 0;
        }

        int readyOps() {
            int ready = 0;
            if (listener) {
                if (!accepts.isEmpty()) {
                    ready |= ACCEPT;
                }
            } else {
                if (!connectPending) {
                    ready |= CONNECT; // completed or failed; masked out once the loop switches to READ
                }
                if (connected) {
                    synchronized (this) {
                        if (inbound.size() > inboundRead || eof) {
                            ready |= READ;
                        }
                    }
                    if (writeCapacity > 0) {
                        ready |= WRITE;
                    }
                }
            }
            return ready & interest;
        }

        // ---- Endpoint ----

        @Override
        public synchronized int read(ByteBuffer dst) {
            int available = inbound.size() - inboundRead;
            if (available == 0) {
                return eof ? -1 : 0;
            }
            int n = Math.min(available, dst.remaining());
            dst.put(inbound.toByteArray(), inboundRead, n);
            inboundRead += n;
            return n;
        }

        @Override
        public synchronized int write(ByteBuffer src) {
            int n = Math.min(src.remaining(), writeCapacity);
            if (n <= 0) {
                return 0;
            }
            byte[] chunk = new byte[n];
            src.get(chunk);
            outbound.write(chunk, 0, n);
            if (writeCapacity != Integer.MAX_VALUE) {
                writeCapacity -= n;
            }
            return n;
        }

        @Override
        public Endpoint accept() {
            return accepts.poll();
        }

        @Override
        public boolean finishConnect() throws IOException {
            if (connectFailure != null) {
                throw connectFailure;
            }
            return connected;
        }

        @Override
        public void interestOps(int ops) {
            interest = ops;
        }

        @Override
        public void writeInterest(boolean wanted) {
            interest = wanted ? interest | WRITE : interest & ~WRITE;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
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
            return remote;
        }

        @Override
        public String localAddress() {
            return "fake:" + port;
        }

        @Override
        public int localPort() {
            return port;
        }

        @Override
        public void wakeup() {
        }

        @Override
        public Object context() {
            return context;
        }

        @Override
        public void context(Object context) {
            this.context = context;
        }
    }
}
