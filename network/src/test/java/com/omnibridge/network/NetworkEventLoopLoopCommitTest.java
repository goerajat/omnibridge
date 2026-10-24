package com.omnibridge.network;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A message committed on the event-loop thread (a reply built in a read callback) is written
 * before commit() returns, through the ring so that anything queued ahead of it goes first,
 * instead of waiting for the loop to finish the callback and come round to drain.
 */
class NetworkEventLoopLoopCommitTest {

    /** TcpChannel record prefix: the payload length. */
    private static final int PREFIX = 4;

    private NetworkEventLoop loop;

    @AfterEach
    void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    @ParameterizedTest(name = "busySpin={0}")
    @ValueSource(booleans = {true, false})
    void replyCommittedInTheReadCallbackIsWrittenBeforeCommitReturns(boolean busySpin) throws Exception {
        loop = new NetworkEventLoop("loop-commit-test", -1, 4096, 4096, 10, busySpin);
        List<Boolean> writtenInCallback = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler((ch, bytes) -> {
            int before = written.get();
            assertEquals(bytes.length, ch.writeRaw(bytes, 0, bytes.length));
            writtenInCallback.add(written.get() == before + 1 && !ch.hasRingBufferMessages());
        });
        try (SocketChannel client = start(handler)) {
            for (int i = 0; i < 100; i++) {
                client.write(java.nio.ByteBuffer.wrap(new byte[] {(byte) i}));
                assertEquals((byte) i, readFully(client, 1)[0], "echo " + i);
            }
            assertEquals(100, writtenInCallback.size());
            assertFalse(writtenInCallback.contains(false), "every echo written inside its read callback");
        }
    }

    @Test
    void switchedOffTheReplyWaitsForTheLoopsDrain() throws Exception {
        loop = new NetworkEventLoop("loop-commit-test", -1, 4096, 4096, 10, true);
        loop.setWriteOnLoopCommit(false);
        List<Boolean> writtenInCallback = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler((ch, bytes) -> {
            int before = written.get();
            ch.writeRaw(bytes, 0, bytes.length);
            writtenInCallback.add(written.get() != before);
        });
        try (SocketChannel client = start(handler)) {
            for (int i = 0; i < 20; i++) {
                client.write(java.nio.ByteBuffer.wrap(new byte[] {(byte) i}));
                assertEquals((byte) i, readFully(client, 1)[0], "echo " + i);
            }
            assertFalse(writtenInCallback.contains(true), "nothing written before the loop drains");
        }
    }

    /** A record claimed earlier but not yet committed holds back a later one: ring order is kept. */
    @Test
    void anEarlierUncommittedClaimHoldsTheReplyBack() throws Exception {
        loop = new NetworkEventLoop("loop-commit-test", -1, 4096, 4096, 10, true);
        List<Boolean> heldBack = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler((ch, bytes) -> {
            // "A" claimed first (as another thread might have), "B" committed while A is open
            int a = ch.tryClaim(PREFIX + 1);
            assertTrue(a >= 0);
            MutableDirectBuffer buf = ch.buffer();
            buf.putInt(a, 1);
            buf.putByte(a + PREFIX, (byte) 'A');
            int before = written.get();
            assertEquals(1, ch.writeRaw(new byte[] {'B'}, 0, 1));
            heldBack.add(written.get() == before);
            ch.commit(a);
        });
        try (SocketChannel client = start(handler)) {
            client.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
            assertArrayEquals(new byte[] {'A', 'B'}, readFully(client, 2));
            assertEquals(List.of(true), heldBack, "B not written ahead of the uncommitted A");
        }
    }

    /** A commit made while the channel is draining (from a written-frame callback) does not drain again. */
    @Test
    void commitInsideADrainCallbackIsDeliveredWithoutNesting() throws Exception {
        loop = new NetworkEventLoop("loop-commit-test", -1, 4096, 4096, 10, true);
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger maxDepth = new AtomicInteger();
        Handler handler = new Handler((ch, bytes) -> ch.writeRaw(bytes, 0, bytes.length));
        handler.onWritten = ch -> {
            int d = depth.incrementAndGet();
            maxDepth.accumulateAndGet(d, Math::max);
            if (handler.followUps.getAndIncrement() < 3) {
                ch.writeRaw(new byte[] {'F'}, 0, 1);
            }
            depth.decrementAndGet();
        };
        try (SocketChannel client = start(handler)) {
            client.write(java.nio.ByteBuffer.wrap(new byte[] {'E'}));
            assertArrayEquals(new byte[] {'E', 'F', 'F', 'F'}, readFully(client, 4));
            assertEquals(1, maxDepth.get(), "frame callbacks nested");
        }
    }

    /**
     * With the peer not reading, loop-thread commits fill the socket and then the ring: none of
     * them blocks or spins, and once the peer reads, every message arrives in order.
     */
    @Test
    void fullSocketKeepsMessagesQueuedInOrder() throws Exception {
        loop = new NetworkEventLoop("loop-commit-test", -1, 4096, 4096, 10, true);
        Handler handler = new Handler((ch, bytes) -> { });
        try (SocketChannel client = start(handler)) {
            client.socket().setReceiveBufferSize(4096);
            TcpChannel server = handler.awaitChannel();
            int size = 1000;
            AtomicInteger committed = new AtomicInteger();
            AtomicReference<Long> slowestCommitNanos = new AtomicReference<>(0L);
            CountDownLatch done = new CountDownLatch(1);
            loop.execute(() -> {
                byte[] msg = new byte[size];
                for (int i = 0; ; i++) {
                    java.util.Arrays.fill(msg, (byte) i);
                    long t = System.nanoTime();
                    if (server.writeRaw(msg, 0, size) < 0) {
                        break;
                    }
                    slowestCommitNanos.accumulateAndGet(System.nanoTime() - t, Math::max);
                    committed.incrementAndGet();
                }
                done.countDown();
            });
            assertTrue(done.await(10, TimeUnit.SECONDS), "loop-thread commits blocked");
            assertTrue(committed.get() > 500, "ring filled after " + committed.get() + " messages");
            assertTrue(slowestCommitNanos.get() < TimeUnit.MILLISECONDS.toNanos(100),
                    "slowest commit " + slowestCommitNanos.get() / 1000 + " us");

            client.socket().setSoTimeout(10_000);
            for (int i = 0; i < committed.get(); i++) {
                byte[] got = readFully(client, size);
                for (byte b : got) {
                    assertEquals((byte) i, b, "message " + i);
                }
            }
        }
    }

    private SocketChannel start(Handler handler) throws Exception {
        TcpAcceptor acceptor = loop.createAcceptor("127.0.0.1", 0, handler);
        loop.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (acceptor.getPort() <= 0) {
            assertTrue(System.nanoTime() < deadline, "acceptor not bound");
            Thread.sleep(5);
        }
        SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", acceptor.getPort()));
        client.socket().setSoTimeout(5000);
        handler.awaitChannel();
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

    /** Frames fully written by the accepted channel, counted on the loop thread. */
    private final AtomicInteger written = new AtomicInteger();

    private final class Handler implements NetworkHandler {
        private final BiConsumer<TcpChannel, byte[]> onData;
        private final AtomicReference<TcpChannel> channel = new AtomicReference<>();
        private final CountDownLatch connected = new CountDownLatch(1);
        final AtomicInteger followUps = new AtomicInteger();
        volatile java.util.function.Consumer<TcpChannel> onWritten;

        Handler(BiConsumer<TcpChannel, byte[]> onData) {
            this.onData = onData;
        }

        TcpChannel awaitChannel() throws InterruptedException {
            assertTrue(connected.await(5, TimeUnit.SECONDS), "not connected");
            return channel.get();
        }

        @Override
        public void onConnected(TcpChannel ch) {
            ch.setFrameWrittenListener(type -> {
                written.incrementAndGet();
                java.util.function.Consumer<TcpChannel> callback = onWritten;
                if (callback != null) {
                    callback.accept(ch);
                }
            });
            channel.set(ch);
            connected.countDown();
        }

        @Override
        public int onDataReceived(TcpChannel ch, DirectBuffer buffer, int offset, int length) {
            byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            onData.accept(ch, copy);
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
