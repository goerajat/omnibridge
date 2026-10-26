package com.omnibridge.network.fstack;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.omnibridge.network.fstack.FstackNative.EAGAIN;
import static com.omnibridge.network.fstack.FstackNative.EPOLLIN;
import static com.omnibridge.network.fstack.FstackNative.EPOLLOUT;

/**
 * An in-memory stand-in for the shim: sockets are maps of byte queues, epoll readiness is
 * computed from them, and the test plays the peer. Exercises everything in the transport
 * except the native calls themselves.
 */
final class FakeFstackNative implements FstackNative {

    static final class Socket {
        final int fd;
        boolean listening;
        boolean connected;
        boolean connectPending;
        int soError;
        int epollEvents = -1; // -1: not in the epoll set
        final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
        int inboundRead;
        boolean eof;
        final ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        int writeCapacity = Integer.MAX_VALUE;
        final ConcurrentLinkedQueue<Socket> accepts = new ConcurrentLinkedQueue<>();
        int peerIp;
        short peerPort;
        int localIp;
        short localPort;

        Socket(int fd) {
            this.fd = fd;
        }

        synchronized void feed(byte[] bytes) {
            inbound.write(bytes, 0, bytes.length);
        }

        synchronized byte[] written() {
            return outbound.toByteArray();
        }

        synchronized int writtenLength() {
            return outbound.size();
        }

        boolean wantsWrite() {
            return (epollEvents & EPOLLOUT) != 0;
        }

        boolean wantsRead() {
            return (epollEvents & EPOLLIN) != 0;
        }
    }

    final Map<Integer, Socket> sockets = new HashMap<>();
    final List<Socket> created = new ArrayList<>();
    private int nextFd = 16;
    private int errno;
    int initCalls;
    String initConf;
    volatile boolean stopped;
    volatile Thread runThread;
    boolean failConnects;
    boolean failBinds;

    Socket socket(int fd) {
        return sockets.get(fd);
    }

    /** Make a pending connect complete (0) or fail (errno). */
    void completeConnect(Socket s, int soError) {
        s.connectPending = false;
        s.soError = soError;
        s.connected = soError == 0;
    }

    Socket enqueueAccept(Socket listener, int peerIp, short peerPort) {
        Socket peer = new Socket(nextFd++);
        peer.connected = true;
        peer.peerIp = peerIp;
        peer.peerPort = peerPort;
        sockets.put(peer.fd, peer);
        listener.accepts.add(peer);
        return peer;
    }

    @Override
    public int init(String conf) {
        initCalls++;
        initConf = conf;
        return 0;
    }

    @Override
    public void run(Runnable callback) {
        runThread = Thread.currentThread();
        stopped = false;
        while (!stopped) {
            callback.run();
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public int errno() {
        return errno;
    }

    @Override
    public int epollCreate() {
        return 3;
    }

    @Override
    public int socket() {
        Socket s = new Socket(nextFd++);
        sockets.put(s.fd, s);
        created.add(s);
        return s.fd;
    }

    @Override
    public int connect(int fd, int ipBigEndian, short portBigEndian) {
        if (failConnects) {
            errno = 111; // ECONNREFUSED
            return -1;
        }
        Socket s = sockets.get(fd);
        s.connectPending = true;
        s.peerIp = ipBigEndian;
        s.peerPort = portBigEndian;
        return 1;
    }

    @Override
    public int bindListen(int fd, int ipBigEndian, short portBigEndian, int backlog) {
        if (failBinds) {
            errno = 98; // EADDRINUSE
            return -1;
        }
        Socket s = sockets.get(fd);
        s.listening = true;
        s.localIp = ipBigEndian;
        s.localPort = portBigEndian;
        return 0;
    }

    @Override
    public int accept(int fd, MemorySegment addrOut) {
        Socket peer = sockets.get(fd).accepts.poll();
        if (peer == null) {
            errno = EAGAIN;
            return -1;
        }
        addrOut.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, peer.peerIp);
        addrOut.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 4, peer.peerPort);
        return peer.fd;
    }

    @Override
    public int epollCtl(int epfd, int op, int fd, int events) {
        Socket s = sockets.get(fd);
        if (s == null) {
            errno = 9; // EBADF
            return -1;
        }
        s.epollEvents = op == EPOLL_CTL_DEL ? -1 : events;
        return 0;
    }

    @Override
    public int epollWait(int epfd, MemorySegment eventsOut, int max) {
        int count = 0;
        for (Socket s : sockets.values()) {
            if (s.epollEvents < 0 || count == max) {
                continue;
            }
            int ready = 0;
            if (s.listening) {
                if (!s.accepts.isEmpty()) {
                    ready |= EPOLLIN;
                }
            } else {
                if (!s.connectPending && s.connected) {
                    synchronized (s) {
                        if (s.inbound.size() > s.inboundRead || s.eof) {
                            ready |= EPOLLIN;
                        }
                    }
                    if (s.writeCapacity > 0) {
                        ready |= EPOLLOUT;
                    }
                } else if (!s.connectPending && s.soError != 0) {
                    ready |= EPOLLOUT | EPOLLERR;
                }
            }
            ready &= s.epollEvents | EPOLLERR;
            if (ready != 0) {
                eventsOut.setAtIndex(ValueLayout.JAVA_INT, 2L * count, s.fd);
                eventsOut.setAtIndex(ValueLayout.JAVA_INT, 2L * count + 1, ready);
                count++;
            }
        }
        return count;
    }

    @Override
    public long read(int fd, long address, long length) {
        Socket s = sockets.get(fd);
        synchronized (s) {
            int available = s.inbound.size() - s.inboundRead;
            if (available == 0) {
                if (s.eof) {
                    return 0;
                }
                errno = EAGAIN;
                return -1;
            }
            int n = (int) Math.min(available, length);
            MemorySegment.ofAddress(address).reinterpret(n)
                    .copyFrom(MemorySegment.ofArray(s.inbound.toByteArray()).asSlice(s.inboundRead, n));
            s.inboundRead += n;
            return n;
        }
    }

    @Override
    public long write(int fd, long address, long length) {
        Socket s = sockets.get(fd);
        synchronized (s) {
            int n = (int) Math.min(length, s.writeCapacity);
            if (n <= 0) {
                errno = EAGAIN;
                return -1;
            }
            byte[] chunk = MemorySegment.ofAddress(address).reinterpret(n).toArray(ValueLayout.JAVA_BYTE);
            s.outbound.write(chunk, 0, n);
            if (s.writeCapacity != Integer.MAX_VALUE) {
                s.writeCapacity -= n;
            }
            return n;
        }
    }

    @Override
    public int close(int fd) {
        return sockets.remove(fd) != null ? 0 : -1;
    }

    @Override
    public int soError(int fd) {
        return sockets.get(fd).soError;
    }

    @Override
    public int localAddr(int fd, MemorySegment addrOut) {
        Socket s = sockets.get(fd);
        addrOut.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, s.localIp);
        addrOut.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 4, s.localPort);
        return 0;
    }

    @Override
    public int peerAddr(int fd, MemorySegment addrOut) {
        Socket s = sockets.get(fd);
        addrOut.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, s.peerIp);
        addrOut.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 4, s.peerPort);
        return 0;
    }
}
