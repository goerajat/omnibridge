package com.omnibridge.network.fstack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * FFM bindings of {@code libomnibridge-fstack.so}. The data-path calls ({@code read},
 * {@code write}, {@code epoll_wait}) are bound with {@link Linker.Option#critical}: no
 * thread-state transition, which is safe because they never block or call back into Java.
 * {@code ob_run} is an ordinary downcall since it upcalls the loop iteration.
 */
public final class FfmFstackNative implements FstackNative {

    private static final Logger log = LoggerFactory.getLogger(FfmFstackNative.class);

    private final Arena arena = Arena.ofShared();
    private final MethodHandle init;
    private final MethodHandle run;
    private final MethodHandle stop;
    private final MethodHandle errno;
    private final MethodHandle epollCreate;
    private final MethodHandle socket;
    private final MethodHandle connect;
    private final MethodHandle bindListen;
    private final MethodHandle accept;
    private final MethodHandle epollCtl;
    private final MethodHandle epollWait;
    private final MethodHandle read;
    private final MethodHandle write;
    private final MethodHandle close;
    private final MethodHandle soError;
    private final MethodHandle localAddr;
    private final MethodHandle peerAddr;

    private volatile Runnable loopCallback;
    private MemorySegment loopStub;

    public FfmFstackNative(Path library) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
        Linker.Option critical = Linker.Option.critical(false);

        init = bind(linker, lookup, "ob_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        run = bind(linker, lookup, "ob_run", FunctionDescriptor.ofVoid(ADDRESS));
        stop = bind(linker, lookup, "ob_stop", FunctionDescriptor.ofVoid());
        errno = bind(linker, lookup, "ob_errno", FunctionDescriptor.of(JAVA_INT));
        epollCreate = bind(linker, lookup, "ob_epoll_create", FunctionDescriptor.of(JAVA_INT));
        socket = bind(linker, lookup, "ob_socket", FunctionDescriptor.of(JAVA_INT));
        connect = bind(linker, lookup, "ob_connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_SHORT));
        bindListen = bind(linker, lookup, "ob_bind_listen",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_SHORT, JAVA_INT));
        accept = bind(linker, lookup, "ob_accept", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        epollCtl = bind(linker, lookup, "ob_epoll_ctl",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        epollWait = bind(linker, lookup, "ob_epoll_wait",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), critical);
        read = bind(linker, lookup, "ob_read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), critical);
        write = bind(linker, lookup, "ob_write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), critical);
        close = bind(linker, lookup, "ob_close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        soError = bind(linker, lookup, "ob_so_error", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        localAddr = bind(linker, lookup, "ob_local_addr", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        peerAddr = bind(linker, lookup, "ob_peer_addr", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        log.info("F-Stack shim loaded from {}", library);
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name,
                                     FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("Symbol not found in F-Stack shim: " + name));
        return linker.downcallHandle(symbol, descriptor, options);
    }

    @Override
    public int init(String conf) {
        try (Arena call = Arena.ofConfined()) {
            return (int) init.invokeExact(call.allocateFrom(conf));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void run(Runnable callback) {
        this.loopCallback = callback;
        try {
            if (loopStub == null) {
                MethodHandle target = MethodHandles.lookup().findVirtual(FfmFstackNative.class, "onLoop",
                        MethodType.methodType(void.class)).bindTo(this);
                loopStub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(), arena);
            }
            run.invokeExact(loopStub);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The upcall: an exception escaping it would take the JVM down, so none may. */
    private void onLoop() {
        try {
            loopCallback.run();
        } catch (Throwable t) {
            log.error("Loop iteration failed inside ff_run; stopping the stack", t);
            stop();
        }
    }

    @Override
    public void stop() {
        try {
            stop.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int errno() {
        try {
            return (int) errno.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int epollCreate() {
        try {
            return (int) epollCreate.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int socket() {
        try {
            return (int) socket.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int connect(int fd, int ipBigEndian, short portBigEndian) {
        try {
            return (int) connect.invokeExact(fd, ipBigEndian, portBigEndian);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int bindListen(int fd, int ipBigEndian, short portBigEndian, int backlog) {
        try {
            return (int) bindListen.invokeExact(fd, ipBigEndian, portBigEndian, backlog);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int accept(int fd, MemorySegment addrOut) {
        try {
            return (int) accept.invokeExact(fd, addrOut, addrOut.asSlice(4, 2));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int epollCtl(int epfd, int op, int fd, int events) {
        try {
            return (int) epollCtl.invokeExact(epfd, op, fd, events);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int epollWait(int epfd, MemorySegment eventsOut, int max) {
        try {
            return (int) epollWait.invokeExact(epfd, eventsOut, max);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public long read(int fd, long address, long length) {
        try {
            return (long) read.invokeExact(fd, address, length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public long write(int fd, long address, long length) {
        try {
            return (long) write.invokeExact(fd, address, length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int close(int fd) {
        try {
            return (int) close.invokeExact(fd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int soError(int fd) {
        try {
            return (int) soError.invokeExact(fd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int localAddr(int fd, MemorySegment addrOut) {
        try {
            return (int) localAddr.invokeExact(fd, addrOut, addrOut.asSlice(4, 2));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int peerAddr(int fd, MemorySegment addrOut) {
        try {
            return (int) peerAddr.invokeExact(fd, addrOut, addrOut.asSlice(4, 2));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("F-Stack shim call failed", t);
    }
}
