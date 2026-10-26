package com.omnibridge.network;

import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event loop talks to its sockets only through {@link Transport} and {@link Endpoint}:
 * with an in-memory transport it connects, accepts, reads, writes (including a partial write
 * held back by the socket), closes and reports failures, without a kernel socket in sight.
 */
class NetworkEventLoopFakeTransportTest {

    private NetworkEventLoop loop;
    private FakeTransport transport;

    @AfterEach
    void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    private void start(boolean busySpin, boolean opensOnLoopThread) {
        transport = new FakeTransport(opensOnLoopThread);
        loop = new NetworkEventLoop("fake-test", -1, 4096, 4096, 10, busySpin, transport);
        loop.start();
    }

    @ParameterizedTest(name = "busySpin={0}")
    @ValueSource(booleans = {true, false})
    void connectReadEchoAndRemoteClose(boolean busySpin) throws Exception {
        start(busySpin, false);
        Handler handler = new Handler(true);
        loop.connect("peer", 9, handler);

        FakeTransport.FakeEndpoint ep = awaitOpened(0);
        assertSame(Thread.currentThread(), transport.openThread, "kernel-style transport opens on the caller's thread");
        await("registered for connect", () -> (ep.interest & Endpoint.CONNECT) != 0);
        assertFalse(handler.connected.getCount() == 0, "connected before the connect completed");

        ep.completeConnect();
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS), "onConnected");
        await("read interest after connect", () -> ep.interest == Endpoint.READ);
        TcpChannel channel = handler.channel.get();
        assertTrue(channel.isConnected());
        assertEquals("peer:9", channel.getRemoteAddress());

        ep.feed("hello".getBytes(StandardCharsets.US_ASCII));
        await("echo written", () -> "hello".equals(new String(ep.written(), StandardCharsets.US_ASCII)));

        ep.endOfStream();
        assertTrue(handler.disconnected.await(5, TimeUnit.SECONDS), "onDisconnected on EOF");
        assertFalse(ep.isOpen(), "endpoint closed");
        assertTrue(channel.isClosed());
        await("dropped by the transport", () -> transport.endpointCount() == 0);
    }

    @Test
    void partialWriteHoldsWriteInterestUntilTheSocketDrains() throws Exception {
        start(true, false);
        Handler handler = new Handler(false);
        loop.connect("peer", 9, handler);
        FakeTransport.FakeEndpoint ep = awaitOpened(0);
        ep.completeConnect();
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS));
        TcpChannel channel = handler.channel.get();

        ep.writeCapacity(3);
        byte[] msg = "0123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(msg.length, channel.writeRaw(msg, 0, msg.length), "committed from the test thread");

        await("first three bytes out", () -> ep.writtenLength() == 3);
        await("write interest held", ep::wantsWrite);
        Thread.sleep(20);
        assertEquals(3, ep.writtenLength(), "nothing more while the socket is full");

        ep.writeCapacity(100);
        await("rest written on WRITE", () -> ep.writtenLength() == msg.length);
        assertArrayEquals(msg, ep.written());
        await("write interest dropped", () -> !ep.wantsWrite());
        assertFalse(channel.hasRingBufferMessages());
    }

    @Test
    void listenAcceptsAndReadsFromThePeer() throws Exception {
        start(true, false);
        Handler handler = new Handler(true);
        TcpAcceptor acceptor = loop.createAcceptor("0.0.0.0", 5, handler);
        assertEquals(5, acceptor.getPort());
        FakeTransport.FakeEndpoint listener = awaitOpened(0);
        await("listener registered for accept", () -> listener.interest == Endpoint.ACCEPT);

        FakeTransport.FakeEndpoint peer = listener.enqueueAccept("client:7");
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS), "accepted");
        assertEquals("client:7", handler.channel.get().getRemoteAddress());
        await("accepted endpoint reads", () -> peer.interest == Endpoint.READ);

        peer.feed("ping".getBytes(StandardCharsets.US_ASCII));
        await("echoed", () -> "ping".equals(new String(peer.written(), StandardCharsets.US_ASCII)));

        acceptor.close();
        assertFalse(listener.isOpen());
        await("listener dropped, connection kept", () -> transport.endpointCount() == 1);
    }

    @Test
    void failedConnectIsReportedAndTheEndpointClosed() throws Exception {
        start(true, false);
        Handler handler = new Handler(false);
        loop.connect("peer", 9, handler);
        FakeTransport.FakeEndpoint ep = awaitOpened(0);
        ep.failConnect(new IOException("no route"));
        assertTrue(handler.connectFailed.await(5, TimeUnit.SECONDS), "onConnectFailed");
        assertEquals("no route", handler.failure.get().getMessage());
        assertFalse(ep.isOpen());
        await("dropped", () -> transport.endpointCount() == 0);
    }

    @Test
    void closeFromAnotherThreadIsDroppedAtTheNextPoll() throws Exception {
        start(true, false);
        Handler handler = new Handler(false);
        loop.connect("peer", 9, handler);
        FakeTransport.FakeEndpoint ep = awaitOpened(0);
        ep.completeConnect();
        assertTrue(handler.connected.await(5, TimeUnit.SECONDS));
        await("listed", () -> transport.endpointCount() == 1);

        handler.channel.get().close();
        assertFalse(ep.isOpen());
        await("dropped", () -> transport.endpointCount() == 0);
    }

    /** A transport that opens sockets on the loop thread: failures arrive through the callbacks. */
    @Test
    void transportOpeningOnTheLoopThreadReportsFailuresAsynchronously() throws Exception {
        start(true, true);
        Handler handler = new Handler(false);

        loop.connect(FakeTransport.FAILING_HOST, 9, handler);
        assertTrue(handler.connectFailed.await(5, TimeUnit.SECONDS), "connect failure through onConnectFailed");
        assertEquals("connect refused by the fake peer", handler.failure.get().getMessage());

        Handler acceptHandler = new Handler(false);
        TcpAcceptor acceptor = loop.createAcceptor(FakeTransport.FAILING_HOST, 6, acceptHandler);
        assertEquals(6, acceptor.getPort(), "requested port until bound");
        assertTrue(acceptHandler.acceptFailed.await(5, TimeUnit.SECONDS), "bind failure through onAcceptFailed");
        assertTrue(acceptor.isClosed());

        Handler good = new Handler(false);
        loop.connect("peer", 9, good);
        FakeTransport.FakeEndpoint ep = awaitOpened(0);
        assertSame(loopThread(), transport.openThread, "socket opened on the loop thread");
        ep.completeConnect();
        assertTrue(good.connected.await(5, TimeUnit.SECONDS));

        TcpAcceptor bound = loop.createAcceptor("0.0.0.0", 0, new Handler(false));
        await("bound on the loop thread", () -> bound.getPort() == 40000);
    }

    @Test
    void transportThatCannotBlockRequiresBusySpin() {
        Transport noBlock = new FakeTransport(false) {
            @Override
            public boolean supportsBlockingPoll() {
                return false;
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new NetworkEventLoop("x", -1, 4096, 4096, 10, false, noBlock));
        assertDoesNotThrow(() -> new NetworkEventLoop("x", -1, 4096, 4096, 10, true, noBlock));
    }

    private Thread loopThread() throws Exception {
        AtomicReference<Thread> t = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        loop.execute(() -> {
            t.set(Thread.currentThread());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return t.get();
    }

    private FakeTransport.FakeEndpoint awaitOpened(int index) throws InterruptedException {
        await("endpoint " + index + " opened", () -> transport.opened().size() > index);
        return transport.opened().get(index);
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
