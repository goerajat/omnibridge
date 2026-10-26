package com.omnibridge.network.fstack;

import com.omnibridge.network.NetworkEventLoop;
import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpAcceptor;
import com.omnibridge.network.TcpChannel;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The F-Stack transport under the real event loop, with the shim replaced by an in-memory
 * fake: the stack is initialised and run on the loop thread, sockets are opened there,
 * epoll events become the loop's readiness, reads and writes go through buffer addresses,
 * and closes from other threads release the fd at the next poll.
 */
class FstackTransportTest {

    private FakeFstackNative fake;
    private FstackTransport transport;
    private NetworkEventLoop loop;

    @BeforeEach
    void start() {
        fake = new FakeFstackNative();
        transport = new FstackTransport(fake, "/etc/fstack.ini");
        loop = new NetworkEventLoop("fstack-test", -1, 4096, 4096, 10, true, transport);
        loop.start();
    }

    @AfterEach
    void stop() {
        loop.stop();
        assertTrue(fake.stopped, "ff_stop_run called when the loop stops");
    }

    @Test
    void stackIsInitialisedAndRunOnTheLoopThread() throws Exception {
        await("ff_run on the loop thread", () -> fake.runThread != null);
        assertEquals(1, fake.initCalls);
        assertEquals("/etc/fstack.ini", fake.initConf);
        assertEquals("NetworkIO-fstack-test", fake.runThread.getName());
    }

    @Test
    void connectCompletesThroughEpollOutAndEchoesThroughBufferAddresses() throws Exception {
        Handler handler = new Handler(true);
        loop.connect("10.0.0.2", 9876, handler);
        FakeFstackNative.Socket s = awaitSocket(0);
        assertSame(fake.runThread, transportOpenThread(), "socket opened on the loop thread");
        await("registered for EPOLLOUT while connecting", s::wantsWrite);
        assertEquals(1, handler.connected.getCount(), "not connected before EPOLLOUT");

        fake.completeConnect(s, 0);
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS), "onConnected after EPOLLOUT");
        await("switched to EPOLLIN", () -> s.wantsRead() && !s.wantsWrite());
        TcpChannel channel = handler.channel.get();
        assertEquals("10.0.0.2:9876", channel.getRemoteAddress());

        s.feed("hello".getBytes(StandardCharsets.US_ASCII));
        await("echoed", () -> "hello".equals(new String(s.written(), StandardCharsets.US_ASCII)));

        synchronized (s) {
            s.eof = true;
        }
        assertTrue(handler.disconnected.await(5, TimeUnit.SECONDS), "EOF (ff_read 0) disconnects");
        await("fd closed and dropped", () -> fake.socket(s.fd) == null && transport.endpointCount() == 0);
    }

    @Test
    void partialWriteHoldsEpollOutUntilTheStackDrains() throws Exception {
        Handler handler = new Handler(false);
        loop.connect("10.0.0.2", 9876, handler);
        FakeFstackNative.Socket s = awaitSocket(0);
        fake.completeConnect(s, 0);
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS));
        TcpChannel channel = handler.channel.get();

        synchronized (s) {
            s.writeCapacity = 4;
        }
        byte[] msg = "0123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(msg.length, channel.writeRaw(msg, 0, msg.length));
        await("four bytes out", () -> s.writtenLength() == 4);
        await("EPOLLOUT held", s::wantsWrite);
        Thread.sleep(20);
        assertEquals(4, s.writtenLength());

        synchronized (s) {
            s.writeCapacity = 100;
        }
        await("rest written", () -> s.writtenLength() == msg.length);
        assertArrayEquals(msg, s.written());
        await("EPOLLOUT dropped", () -> !s.wantsWrite());
    }

    @Test
    void failedConnectIsReportedWithTheSocketError() throws Exception {
        Handler handler = new Handler(false);
        loop.connect("10.0.0.2", 9876, handler);
        FakeFstackNative.Socket s = awaitSocket(0);
        fake.completeConnect(s, 111);
        assertTrue(handler.connectFailed.await(5, TimeUnit.SECONDS), "onConnectFailed");
        assertTrue(handler.failure.get().getMessage().contains("errno 111"));
        await("fd closed", () -> fake.socket(s.fd) == null);
    }

    @Test
    void refusedConnectAndBindFailuresArriveThroughTheCallbacks() throws Exception {
        fake.failConnects = true;
        Handler handler = new Handler(false);
        loop.connect("10.0.0.2", 9876, handler);
        assertTrue(handler.connectFailed.await(5, TimeUnit.SECONDS), "ff_connect failure through onConnectFailed");
        assertTrue(handler.failure.get().getMessage().contains("errno 111"));
        await("socket closed again", () -> fake.sockets.isEmpty());

        fake.failBinds = true;
        Handler acceptHandler = new Handler(false);
        TcpAcceptor acceptor = loop.createAcceptor("10.0.0.1", 9876, acceptHandler);
        assertEquals(9876, acceptor.getPort());
        assertTrue(acceptHandler.acceptFailed.await(5, TimeUnit.SECONDS), "bind failure through onAcceptFailed");
        assertTrue(acceptor.isClosed());
    }

    @Test
    void listenerAcceptsPeersFromTheStack() throws Exception {
        Handler handler = new Handler(true);
        TcpAcceptor acceptor = loop.createAcceptor("10.0.0.1", 9876, handler);
        FakeFstackNative.Socket listener = awaitSocket(0);
        await("listening for EPOLLIN", () -> listener.listening && listener.wantsRead());
        assertEquals(9876, acceptor.getPort());

        FakeFstackNative.Socket peer = fake.enqueueAccept(listener, ip("10.0.0.7"), (short) htons(40001));
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS), "accepted");
        assertEquals("10.0.0.7:40001", handler.channel.get().getRemoteAddress());
        await("accepted socket reads", peer::wantsRead);

        peer.feed("ping".getBytes(StandardCharsets.US_ASCII));
        await("echoed", () -> "ping".equals(new String(peer.written(), StandardCharsets.US_ASCII)));

        acceptor.close();
        await("listener fd closed", () -> fake.socket(listener.fd) == null);
    }

    @Test
    void closeFromAnotherThreadReleasesTheFdAtTheNextPoll() throws Exception {
        Handler handler = new Handler(false);
        loop.connect("10.0.0.2", 9876, handler);
        FakeFstackNative.Socket s = awaitSocket(0);
        fake.completeConnect(s, 0);
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS));

        handler.channel.get().close(); // test thread, as FixSession.disconnect() would
        await("fd released on the loop thread", () -> fake.socket(s.fd) == null);
        await("dropped from the list", () -> transport.endpointCount() == 0);
    }

    @Test
    void addressesAndEventsConvert() throws Exception {
        assertEquals(ip("10.0.0.2"), FstackTransport.ipv4(new InetSocketAddress("10.0.0.2", 1)));
        assertEquals((short) htons(9876), FstackTransport.portBe(9876));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(8);
            addr.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, ip("192.168.1.20"));
            addr.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 4, (short) htons(9876));
            assertEquals("192.168.1.20:9876", FstackTransport.formatAddress(addr));
        }
        assertEquals(FstackNative.EPOLLIN, FstackTransport.toEpollEvents(com.omnibridge.network.Endpoint.READ));
        assertEquals(FstackNative.EPOLLIN | FstackNative.EPOLLOUT,
                FstackTransport.toEpollEvents(com.omnibridge.network.Endpoint.READ | com.omnibridge.network.Endpoint.WRITE));
        assertEquals(FstackNative.EPOLLOUT, FstackTransport.toEpollEvents(com.omnibridge.network.Endpoint.CONNECT));
        assertEquals(FstackNative.EPOLLIN, FstackTransport.toEpollEvents(com.omnibridge.network.Endpoint.ACCEPT));
    }

    /** The shim's uint32 as the Java int: network order bytes stored little-endian. */
    private static int ip(String dotted) {
        String[] p = dotted.split("\\.");
        return (Integer.parseInt(p[3]) & 0xFF) << 24 | (Integer.parseInt(p[2]) & 0xFF) << 16
                | (Integer.parseInt(p[1]) & 0xFF) << 8 | (Integer.parseInt(p[0]) & 0xFF);
    }

    private static int htons(int port) {
        return ((port & 0xFF) << 8) | ((port >> 8) & 0xFF);
    }

    private Thread transportOpenThread() {
        return fake.runThread;
    }

    private FakeFstackNative.Socket awaitSocket(int index) throws InterruptedException {
        await("socket " + index + " created", () -> fake.created.size() > index);
        return fake.created.get(index);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + what);
            }
            Thread.sleep(1);
        }
    }

    private static final class Handler implements NetworkHandler {
        private final boolean echo;
        final AtomicReference<TcpChannel> channel = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final CountDownLatch connectFailed = new CountDownLatch(1);
        final CountDownLatch acceptFailed = new CountDownLatch(1);

        Handler(boolean echo) {
            this.echo = echo;
        }

        @Override
        public void onConnected(TcpChannel ch) {
            channel.set(ch);
            connected.countDown();
        }

        @Override
        public int onDataReceived(TcpChannel ch, DirectBuffer buffer, int offset, int length) {
            if (echo) {
                byte[] copy = new byte[length];
                buffer.getBytes(offset, copy);
                assertEquals(length, ch.writeRaw(copy, 0, length));
            }
            return length;
        }

        @Override
        public void onDisconnected(TcpChannel ch, Throwable reason) {
            disconnected.countDown();
        }

        @Override
        public void onConnectFailed(String remoteAddress, Throwable reason) {
            failure.set(reason);
            connectFailed.countDown();
        }

        @Override
        public void onAcceptFailed(Throwable reason) {
            failure.set(reason);
            acceptFailed.countDown();
        }
    }
}
