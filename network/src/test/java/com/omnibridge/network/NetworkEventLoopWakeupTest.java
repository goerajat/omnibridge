package com.omnibridge.network;

import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A selector wakeup is a system call (plus an extra select return on the loop), so a commit
 * asks for one only when the loop may be blocked in select(): never when the loop busy-spins
 * and never from the loop thread itself, which drains after processing keys and tasks.
 * Commits from other threads into a loop blocked in select() must still go out at once.
 */
class NetworkEventLoopWakeupTest {

    private static final long LONG_SELECT_TIMEOUT_MS = 60_000;

    private NetworkEventLoop loop;

    @AfterEach
    void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    @Test
    void busySpinCommitsFromAnotherThreadDoNotWakeTheSelector() throws Exception {
        loop = new NetworkEventLoop("wakeup-test", -1, 4096, 4096, 10, true);
        CapturingHandler handler = new CapturingHandler(false);
        try (SocketChannel client = start(handler)) {
            TcpChannel server = handler.awaitChannel();
            long before = loop.getSelectorWakeupCount();

            int messages = 1000;
            for (int i = 0; i < messages; i++) {
                while (server.writeRaw(new byte[] {(byte) i}, 0, 1) < 0) {
                    Thread.onSpinWait();
                }
            }
            byte[] received = readFully(client, messages);
            for (int i = 0; i < messages; i++) {
                assertEquals((byte) i, received[i], "byte " + i);
            }

            assertEquals(0, loop.getSelectorWakeupCount() - before, "selector wakeups for " + messages + " commits");
        }
    }

    @ParameterizedTest(name = "busySpin={0}")
    @ValueSource(booleans = {true, false})
    void commitsOnTheLoopThreadDoNotWakeTheSelector(boolean busySpin) throws Exception {
        loop = new NetworkEventLoop("wakeup-test", -1, 4096, 4096, LONG_SELECT_TIMEOUT_MS, busySpin);
        CapturingHandler handler = new CapturingHandler(true);
        try (SocketChannel client = start(handler)) {
            handler.awaitChannel();
            long before = loop.getSelectorWakeupCount();

            int roundTrips = 200;
            for (int i = 0; i < roundTrips; i++) {
                client.write(ByteBuffer.wrap(new byte[] {(byte) i}));
                assertEquals((byte) i, readFully(client, 1)[0], "echo " + i);
            }

            assertEquals(0, loop.getSelectorWakeupCount() - before,
                    "selector wakeups for " + roundTrips + " echoes committed on the loop thread");
        }
    }

    @Test
    void busySpinTasksFromAnotherThreadRunWithoutWakeup() throws Exception {
        loop = new NetworkEventLoop("wakeup-test", -1, 4096, 4096, 10, true);
        loop.start();
        long before = loop.getSelectorWakeupCount();
        for (int i = 0; i < 100; i++) {
            CountDownLatch ran = new CountDownLatch(1);
            loop.execute(ran::countDown);
            assertTrue(ran.await(1, TimeUnit.SECONDS), "task " + i + " did not run");
        }
        assertEquals(0, loop.getSelectorWakeupCount() - before, "selector wakeups for 100 tasks");
    }

    /**
     * The loop sits in select() with a 60 s timeout; every commit from another thread,
     * at random moments relative to the loop's iteration, must be on the wire within a second.
     */
    @Test
    void commitsFromAnotherThreadReachABlockedLoopPromptly() throws Exception {
        loop = new NetworkEventLoop("wakeup-test", -1, 4096, 4096, LONG_SELECT_TIMEOUT_MS, false);
        CapturingHandler handler = new CapturingHandler(false);
        try (SocketChannel client = start(handler)) {
            client.socket().setSoTimeout(1000);
            TcpChannel server = handler.awaitChannel();
            long before = loop.getSelectorWakeupCount();

            Random random = new Random(7);
            int messages = 2000;
            for (int i = 0; i < messages; i++) {
                if (random.nextInt(4) == 0) {
                    Thread.sleep(0, random.nextInt(200_000));
                }
                long sent = System.nanoTime();
                assertEquals(1, server.writeRaw(new byte[] {(byte) i}, 0, 1));
                assertEquals((byte) i, readFully(client, 1)[0], "message " + i);
                long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - sent);
                assertTrue(micros < 1_000_000, "message " + i + " took " + micros + " us");
            }
            assertTrue(loop.getSelectorWakeupCount() - before > 0, "a blocked loop must still be woken");
        }
    }

    @Test
    void tasksFromAnotherThreadReachABlockedLoopPromptly() throws Exception {
        loop = new NetworkEventLoop("wakeup-test", -1, 4096, 4096, LONG_SELECT_TIMEOUT_MS, false);
        loop.start();
        for (int i = 0; i < 100; i++) {
            CountDownLatch ran = new CountDownLatch(1);
            loop.execute(ran::countDown);
            assertTrue(ran.await(1, TimeUnit.SECONDS), "task " + i + " did not run");
        }
    }

    private SocketChannel start(CapturingHandler handler) throws Exception {
        TcpAcceptor acceptor = loop.createAcceptor("127.0.0.1", 0, handler);
        loop.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (acceptor.getPort() <= 0) {
            assertTrue(System.nanoTime() < deadline, "acceptor not bound");
            Thread.sleep(5);
        }
        SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", acceptor.getPort()));
        client.socket().setSoTimeout(5000);
        return client;
    }

    private static byte[] readFully(SocketChannel ch, int length) throws IOException {
        byte[] out = new byte[length];
        InputStream in = ch.socket().getInputStream();
        int pos = 0;
        while (pos < length) {
            int n = in.read(out, pos, length - pos);
            if (n < 0) {
                fail("connection closed after " + pos + " bytes");
            }
            pos += n;
        }
        return out;
    }

    /** Keeps the accepted channel; optionally echoes every read back through its ring buffer. */
    private static final class CapturingHandler implements NetworkHandler {
        private final boolean echo;
        private final AtomicReference<TcpChannel> channel = new AtomicReference<>();
        private final CountDownLatch connected = new CountDownLatch(1);

        CapturingHandler(boolean echo) {
            this.echo = echo;
        }

        TcpChannel awaitChannel() throws InterruptedException {
            assertTrue(connected.await(5, TimeUnit.SECONDS), "not connected");
            return channel.get();
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
        }

        @Override
        public void onConnectFailed(String remoteAddress, Throwable reason) {
        }

        @Override
        public void onAcceptFailed(Throwable reason) {
        }
    }
}
