package com.omnibridge.network;

import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ring buffer messages committed on the event loop reach the socket while connections come
 * and go: the drain loop works from a snapshot of the selector's keys, which must follow
 * registrations and closes.
 */
class NetworkEventLoopDrainTest {

    private NetworkEventLoop loop;

    @AfterEach
    void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    @ParameterizedTest(name = "busySpin={0}")
    @ValueSource(booleans = {true, false})
    void echoesAcrossReconnects(boolean busySpin) throws Exception {
        loop = new NetworkEventLoop("drain-test", -1, 4096, 4096, 10, busySpin);
        EchoHandler handler = new EchoHandler();
        TcpAcceptor acceptor = loop.createAcceptor("127.0.0.1", 0, handler);
        loop.start();
        int port = awaitPort(acceptor);

        try (SocketChannel a = connect(port)) {
            assertEquals("one", roundTrip(a, "one"));
        }
        assertTrue(handler.disconnected.await(5, TimeUnit.SECONDS), "first connection not closed");

        try (SocketChannel b = connect(port); SocketChannel c = connect(port)) {
            assertEquals("two", roundTrip(b, "two"));
            assertEquals("three", roundTrip(c, "three"));
            assertEquals("four", roundTrip(b, "four"));
        }
    }

    @Test
    void manyConnectionsGrowTheSnapshot() throws Exception {
        loop = new NetworkEventLoop("drain-test", -1, 4096, 4096, 10, true);
        TcpAcceptor acceptor = loop.createAcceptor("127.0.0.1", 0, new EchoHandler());
        loop.start();
        int port = awaitPort(acceptor);

        SocketChannel[] clients = new SocketChannel[40];
        try {
            for (int i = 0; i < clients.length; i++) {
                clients[i] = connect(port);
            }
            for (int i = 0; i < clients.length; i++) {
                assertEquals("m" + i, roundTrip(clients[i], "m" + i));
            }
        } finally {
            for (SocketChannel c : clients) {
                if (c != null) {
                    c.close();
                }
            }
        }
    }

    private static int awaitPort(TcpAcceptor acceptor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            int port = acceptor.getPort();
            if (port > 0) {
                return port;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("acceptor not bound");
    }

    private static SocketChannel connect(int port) throws IOException {
        SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        ch.socket().setSoTimeout(5000);
        return ch;
    }

    private static String roundTrip(SocketChannel ch, String text) throws IOException {
        byte[] out = text.getBytes(StandardCharsets.US_ASCII);
        ch.write(ByteBuffer.wrap(out));
        ByteBuffer in = ByteBuffer.allocate(out.length);
        var stream = ch.socket().getInputStream();
        while (in.hasRemaining()) {
            int b = stream.read();
            if (b < 0) {
                fail("connection closed after " + in.position() + " bytes");
            }
            in.put((byte) b);
        }
        return new String(in.array(), StandardCharsets.US_ASCII);
    }

    /** Echoes every read back through the channel's ring buffer, i.e. via the drain loop. */
    private static final class EchoHandler implements NetworkHandler {
        final CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void onConnected(TcpChannel channel) {
        }

        @Override
        public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
            byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            assertEquals(length, channel.writeRaw(copy, 0, length));
            return length;
        }

        @Override
        public void onDisconnected(TcpChannel channel, Throwable reason) {
            disconnected.countDown();
        }

        @Override
        public void onConnectFailed(String remoteAddress, Throwable reason) {
        }

        @Override
        public void onAcceptFailed(Throwable reason) {
        }
    }
}
