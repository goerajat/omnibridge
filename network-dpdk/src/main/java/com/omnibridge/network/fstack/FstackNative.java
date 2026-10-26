package com.omnibridge.network.fstack;

import java.lang.foreign.MemorySegment;

/**
 * The shim's {@code ob_*} functions (src/main/c/omnibridge_fstack.c), one method each, so
 * {@link FstackTransport} can be driven by a fake in tests. {@link FfmFstackNative} binds the
 * real library. All calls happen on the loop thread.
 */
public interface FstackNative {

    // epoll events (Linux values; F-Stack's ff_epoll uses the same)
    int EPOLLIN = 0x001;
    int EPOLLOUT = 0x004;
    int EPOLLERR = 0x008;
    int EPOLLHUP = 0x010;
    int EPOLL_CTL_ADD = 1;
    int EPOLL_CTL_DEL = 2;
    int EPOLL_CTL_MOD = 3;

    int EAGAIN = 11;
    int EWOULDBLOCK = 11;
    int EINPROGRESS = 115;

    /** {@code ff_init} with the given ini; 0 on success. */
    int init(String conf);

    /**
     * {@code ff_run}: F-Stack's poll loop on this thread, calling {@code callback} once per
     * NIC poll until {@link #stop()}.
     */
    void run(Runnable callback);

    /** {@code ff_stop_run}: makes {@link #run} return after the current poll. */
    void stop();

    int errno();

    int epollCreate();

    /** A non-blocking TCP socket with Nagle off, or -1. */
    int socket();

    /** 0 connected at once, 1 in progress, -1 failed (see {@link #errno()}). */
    int connect(int fd, int ipBigEndian, short portBigEndian);

    int bindListen(int fd, int ipBigEndian, short portBigEndian, int backlog);

    /**
     * Accept on a listener; the peer address is written into {@code addrOut} as
     * (int ip, short port), both network order.
     *
     * @return the accepted fd, or -1 (errno EAGAIN when none is pending)
     */
    int accept(int fd, MemorySegment addrOut);

    int epollCtl(int epfd, int op, int fd, int events);

    /**
     * Non-blocking wait: writes (int fd, int events) pairs into {@code eventsOut}.
     *
     * @return the number of pairs, or -1
     */
    int epollWait(int epfd, MemorySegment eventsOut, int max);

    /** Bytes read, 0 at end of stream, -1 with errno (EAGAIN: nothing available). */
    long read(int fd, long address, long length);

    /** Bytes written, -1 with errno (EAGAIN: socket full). */
    long write(int fd, long address, long length);

    int close(int fd);

    /** SO_ERROR after a connect completes; 0 when connected. */
    int soError(int fd);

    /** Local address into {@code addrOut} as (int ip, short port); -1 if unbound. */
    int localAddr(int fd, MemorySegment addrOut);

    int peerAddr(int fd, MemorySegment addrOut);
}
