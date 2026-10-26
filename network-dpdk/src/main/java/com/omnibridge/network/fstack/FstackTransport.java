package com.omnibridge.network.fstack;

import com.omnibridge.network.Endpoint;
import com.omnibridge.network.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import static com.omnibridge.network.fstack.FstackNative.EAGAIN;
import static com.omnibridge.network.fstack.FstackNative.EPOLLERR;
import static com.omnibridge.network.fstack.FstackNative.EPOLLHUP;
import static com.omnibridge.network.fstack.FstackNative.EPOLLIN;
import static com.omnibridge.network.fstack.FstackNative.EPOLLOUT;
import static com.omnibridge.network.fstack.FstackNative.EPOLL_CTL_ADD;
import static com.omnibridge.network.fstack.FstackNative.EPOLL_CTL_DEL;
import static com.omnibridge.network.fstack.FstackNative.EPOLL_CTL_MOD;
import static com.omnibridge.network.fstack.FstackNative.EWOULDBLOCK;

/**
 * {@code network.transport = fstack}: FreeBSD TCP on DPDK (F-Stack, library mode).
 *
 * <p>F-Stack owns its poll loop ({@code ff_run}), so {@link #run} hands the engine's
 * iteration to it as the per-poll callback: each poll of the NIC is followed by one event
 * loop iteration on the same pinned thread. {@link #poll} is {@code ff_epoll_wait} with a
 * zero timeout; the stack never blocks, so the loop must busy-spin. Every F-Stack call is
 * made from that thread: sockets are opened inside the loop's registration task
 * ({@link #opensOnLoopThread()}) and a close from another thread is only flagged, the fd
 * being released at the next poll.</p>
 */
public final class FstackTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(FstackTransport.class);

    static final String NAME = "fstack";
    private static final int MAX_EVENTS = 256;
    private static final int LISTEN_BACKLOG = 128;

    private final FstackNative n;
    private final String conf;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment events = arena.allocate(ValueLayout.JAVA_INT, 2L * MAX_EVENTS);
    private final MemorySegment addrScratch = arena.allocate(8);

    private ReadyHandler handler;
    private Thread loopThread;
    private int epfd = -1;
    private boolean initialised;

    private FstackEndpoint[] endpoints = new FstackEndpoint[16];
    private int endpointCount;
    private FstackEndpoint[] byFd = new FstackEndpoint[1024];
    private volatile boolean compactionNeeded;

    public FstackTransport(FstackNative nativeApi, String conf) {
        this.n = nativeApi;
        this.conf = conf;
    }

    FstackNative nativeApi() {
        return n;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(ReadyHandler handler) throws IOException {
        this.handler = handler;
        this.loopThread = Thread.currentThread();
        int rc = n.init(conf);
        if (rc != 0) {
            throw new IOException("ff_init failed (" + rc + ") with " + conf);
        }
        initialised = true;
        epfd = n.epollCreate();
        if (epfd < 0) {
            throw new IOException("ff_epoll_create failed: errno " + n.errno());
        }
        log.info("F-Stack initialised from {} on thread {}", conf, loopThread.getName());
    }

    /**
     * Once the loop is stopped the stack is asked to stop; the shim leaves it polling (its
     * shutdown path aborts the process in this F-Stack revision), so {@code ff_run} does not
     * return and the loop thread lives until the process exits. The engines' sessions are
     * disconnected before the loop is stopped, so nothing is left to close here.
     */
    @Override
    public void run(BooleanSupplier iteration) {
        n.run(() -> {
            if (!iteration.getAsBoolean()) {
                n.stop();
            }
        });
    }

    @Override
    public void poll(long timeoutMs) throws IOException {
        if (compactionNeeded) {
            compact();
        }
        int count = n.epollWait(epfd, events, MAX_EVENTS);
        if (count < 0) {
            throw new IOException("ff_epoll_wait failed: errno " + n.errno());
        }
        for (int i = 0; i < count; i++) {
            int fd = events.getAtIndex(ValueLayout.JAVA_INT, 2L * i);
            int ev = events.getAtIndex(ValueLayout.JAVA_INT, 2L * i + 1);
            FstackEndpoint ep = fd >= 0 && fd < byFd.length ? byFd[fd] : null;
            if (ep == null || !ep.isOpen()) {
                continue;
            }
            int ready = toReadyOps(ep, ev);
            if (ready != 0) {
                handler.onReady(ep, ready);
            }
        }
    }

    /** epoll events to the loop's readiness, by what the endpoint is waiting for. */
    static int toReadyOps(FstackEndpoint ep, int epollEvents) {
        int ready = 0;
        boolean errorOrHangup = (epollEvents & (EPOLLERR | EPOLLHUP)) != 0;
        if (ep.isListener()) {
            if ((epollEvents & EPOLLIN) != 0 || errorOrHangup) {
                ready |= Endpoint.ACCEPT;
            }
        } else if (ep.connectPending) {
            if ((epollEvents & EPOLLOUT) != 0 || errorOrHangup) {
                ready |= Endpoint.CONNECT;
            }
        } else {
            if ((epollEvents & EPOLLIN) != 0 || errorOrHangup) {
                ready |= Endpoint.READ;
            }
            if ((epollEvents & EPOLLOUT) != 0) {
                ready |= Endpoint.WRITE;
            }
        }
        return ready & (ep.interest | (errorOrHangup ? Endpoint.READ | Endpoint.CONNECT : 0));
    }

    static int toEpollEvents(int interestOps) {
        int events = 0;
        if ((interestOps & (Endpoint.READ | Endpoint.ACCEPT)) != 0) {
            events |= EPOLLIN;
        }
        if ((interestOps & (Endpoint.WRITE | Endpoint.CONNECT)) != 0) {
            events |= EPOLLOUT;
        }
        return events;
    }

    @Override
    public boolean supportsBlockingPoll() {
        return false;
    }

    /**
     * rte_eal_init pins the calling thread to the main lcore (fstack.ini's lcore_mask, which
     * setup-fstack-host.sh writes from network.cpu-affinity) and gives EAL's control threads
     * the rest of the thread's affinity at that moment, so the loop must arrive unpinned.
     */
    @Override
    public boolean pinsLoopThread() {
        return true;
    }

    @Override
    public boolean opensOnLoopThread() {
        return true;
    }

    @Override
    public Endpoint connect(InetSocketAddress address) throws IOException {
        int fd = n.socket();
        if (fd < 0) {
            throw new IOException("ff_socket failed: errno " + n.errno());
        }
        int rc = n.connect(fd, ipv4(address), portBe(address.getPort()));
        if (rc < 0) {
            int err = n.errno();
            n.close(fd);
            throw new IOException("ff_connect to " + address + " failed: errno " + err);
        }
        return new FstackEndpoint(this, fd, false, rc == 0,
                address.getAddress().getHostAddress() + ":" + address.getPort());
    }

    @Override
    public Endpoint listen(InetSocketAddress address) throws IOException {
        int fd = n.socket();
        if (fd < 0) {
            throw new IOException("ff_socket failed: errno " + n.errno());
        }
        int ip = address.getAddress() == null || address.getAddress().isAnyLocalAddress() ? 0 : ipv4(address);
        if (n.bindListen(fd, ip, portBe(address.getPort()), LISTEN_BACKLOG) < 0) {
            int err = n.errno();
            n.close(fd);
            throw new IOException("ff_bind/listen on " + address + " failed: errno " + err);
        }
        return new FstackEndpoint(this, fd, true, false, "unknown");
    }

    Endpoint accept(FstackEndpoint listener) throws IOException {
        int fd = n.accept(listener.fd, addrScratch);
        if (fd < 0) {
            int err = n.errno();
            if (err == EAGAIN || err == EWOULDBLOCK) {
                return null;
            }
            throw new IOException("ff_accept failed: errno " + err);
        }
        return new FstackEndpoint(this, fd, false, true, formatAddress(addrScratch));
    }

    @Override
    public void register(Endpoint endpoint, int interestOps) throws IOException {
        FstackEndpoint ep = (FstackEndpoint) endpoint;
        boolean listed = ep.fd < byFd.length && byFd[ep.fd] == ep;
        ep.interest = interestOps;
        if (n.epollCtl(epfd, listed ? EPOLL_CTL_MOD : EPOLL_CTL_ADD, ep.fd, toEpollEvents(interestOps)) < 0) {
            throw new IOException("ff_epoll_ctl failed for fd " + ep.fd + ": errno " + n.errno());
        }
        if (listed) {
            return;
        }
        if (ep.fd >= byFd.length) {
            byFd = Arrays.copyOf(byFd, Math.max(byFd.length * 2, ep.fd + 1));
        }
        byFd[ep.fd] = ep;
        if (endpointCount == endpoints.length) {
            endpoints = Arrays.copyOf(endpoints, endpointCount * 2);
        }
        endpoints[endpointCount++] = ep;
    }

    void updateInterest(FstackEndpoint ep) {
        if (ep.fd < byFd.length && byFd[ep.fd] == ep) {
            if (n.epollCtl(epfd, EPOLL_CTL_MOD, ep.fd, toEpollEvents(ep.interest)) < 0) {
                log.warn("ff_epoll_ctl MOD failed for fd {}: errno {}", ep.fd, n.errno());
            }
        }
    }

    /** From {@link FstackEndpoint#close()}: release now on the loop thread, else at the next poll. */
    void endpointClosed(FstackEndpoint ep) {
        if (Thread.currentThread() == loopThread) {
            release(ep);
        }
        compactionNeeded = true; // drop it from the list at the next poll
    }

    private void release(FstackEndpoint ep) {
        if (ep.fd < byFd.length && byFd[ep.fd] == ep) {
            byFd[ep.fd] = null;
            n.epollCtl(epfd, EPOLL_CTL_DEL, ep.fd, 0);
        }
        n.close(ep.fd);
        ep.connectPending = false;
    }

    private void compact() {
        compactionNeeded = false;
        int kept = 0;
        for (int i = 0; i < endpointCount; i++) {
            FstackEndpoint ep = endpoints[i];
            if (ep.isOpen()) {
                endpoints[kept++] = ep;
            } else if (ep.fd < byFd.length && byFd[ep.fd] == ep) {
                release(ep); // closed from another thread: the fd is still ours
            }
        }
        Arrays.fill(endpoints, kept, endpointCount, null);
        endpointCount = kept;
    }

    @Override
    public int endpointCount() {
        // an endpoint closed on the loop thread is released at once but stays listed until compaction
        return endpointCount;
    }

    @Override
    public Endpoint endpointAt(int index) {
        return endpoints[index];
    }

    @Override
    public void wakeup() {
        // ff_epoll_wait(…, 0) never blocks
    }

    @Override
    public void close() {
        if (initialised && epfd >= 0) {
            n.close(epfd);
            epfd = -1;
        }
    }

    String localAddress(int fd) {
        return n.localAddr(fd, addrScratch) == 0 ? formatAddress(addrScratch) : null;
    }

    String peerAddress(int fd) {
        return n.peerAddr(fd, addrScratch) == 0 ? formatAddress(addrScratch) : null;
    }

    /** (int ip, short port), both network byte order, to "a.b.c.d:port". */
    static String formatAddress(MemorySegment addr) {
        int ip = addr.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 0);
        int port = addr.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 4) & 0xFFFF;
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "." + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF)
                + ":" + port;
    }

    /** The port in network byte order, as the shim's uint16_t sees it (htons). */
    static short portBe(int port) {
        return (short) (((port & 0xFF) << 8) | ((port >> 8) & 0xFF));
    }

    /** The IPv4 address in network byte order, as the shim's uint32_t sees it. */
    static int ipv4(InetSocketAddress address) throws IOException {
        InetAddress inet = address.getAddress();
        if (!(inet instanceof Inet4Address)) {
            throw new IOException("F-Stack transport needs an IPv4 address: " + address);
        }
        byte[] b = inet.getAddress();
        // the shim stores the uint32 in memory as b0 b1 b2 b3; on a little-endian host that is
        // the int (b3 << 24 | b2 << 16 | b1 << 8 | b0)
        return (b[3] & 0xFF) << 24 | (b[2] & 0xFF) << 16 | (b[1] & 0xFF) << 8 | (b[0] & 0xFF);
    }
}
