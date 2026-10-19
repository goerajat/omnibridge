package com.omnibridge.fix.engine.session;

import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.TcpChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Outgoing MsgSeqNum must reach the wire in order with no gaps or repeats,
 * however many threads send and whether or not some claims are aborted.
 */
class FixSessionSequenceNumberTest {

    private ServerSocketChannel server;
    private SocketChannel client;
    private SocketChannel peer;
    private TcpChannel channel;
    private FixSession session;

    @BeforeEach
    void setUp() throws Exception {
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        client = SocketChannel.open();
        // Large enough that the drainer never meets a full socket; partial writes are tested in the network module.
        client.setOption(java.net.StandardSocketOptions.SO_SNDBUF, 8 * 1024 * 1024);
        client.connect(server.getLocalAddress());
        peer = server.accept();
        client.configureBlocking(false);
        channel = new TcpChannel(client, 64 * 1024, 64 * 1024, 4 * 1024 * 1024);

        session = new FixSession(SessionConfig.builder()
                .sessionName("seq-test")
                .senderCompId("CLIENT")
                .targetCompId("BROKER")
                .acceptor()
                .port(1)
                .logMessages(false)
                .maxMessageLength(256)
                .build(), null);
        session.setChannel(channel);
        forceLoggedOn(session);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        peer.close();
        server.close();
    }

    @SuppressWarnings("unchecked")
    private static void forceLoggedOn(FixSession session) throws Exception {
        Field f = FixSession.class.getDeclaredField("state");
        f.setAccessible(true);
        ((AtomicReference<SessionState>) f.get(session)).set(SessionState.LOGGED_ON);
    }

    @Test
    void concurrentSendersProduceContiguousSeqNumsOnTheWire() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        int total = threads * perThread;

        CompletableFuture<List<Integer>> wire = CompletableFuture.supplyAsync(() -> readSeqNums(total));

        AtomicBoolean sendersDone = new AtomicBoolean();
        Thread drainer = new Thread(() -> {
            try {
                while (!sendersDone.get() || channel.hasRingBufferMessages()) {
                    channel.drainRingBufferToSocket();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        drainer.start();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    RingBufferOutgoingMessage msg;
                    while ((msg = session.tryClaimMessage("D")) == null) {
                        Thread.onSpinWait();
                    }
                    msg.setField(11, "ORD");
                    session.commitMessage(msg);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        sendersDone.set(true);
        drainer.join();

        List<Integer> seqNums = wire.get(30, TimeUnit.SECONDS);
        assertEquals(total, seqNums.size(), "messages received");

        int outOfOrder = 0;
        int firstBad = -1;
        for (int i = 0; i < seqNums.size(); i++) {
            if (seqNums.get(i) != i + 1) {
                outOfOrder++;
                if (firstBad < 0) {
                    firstBad = i;
                }
            }
        }
        int from = Math.max(0, firstBad - 2);
        assertEquals(0, outOfOrder, "messages whose MsgSeqNum is not its wire position; first at index "
                + firstBad + ": " + (firstBad < 0 ? "" : seqNums.subList(from, Math.min(seqNums.size(), firstBad + 3))));
    }

    @Test
    void abortedClaimDoesNotReuseAnotherThreadsSeqNum() throws Exception {
        ExecutorService threadA = Executors.newSingleThreadExecutor();
        ExecutorService threadB = Executors.newSingleThreadExecutor();
        try {
            RingBufferOutgoingMessage a = threadA.submit(() -> session.tryClaimMessage("D")).get();

            threadB.submit(() -> {
                RingBufferOutgoingMessage b = session.tryClaimMessage("D");
                b.setField(11, "ORD-B");
                session.commitMessage(b);
            }).get();

            threadA.submit(() -> session.abortMessage(a)).get();

            sendOrder("ORD-C");
            channel.drainRingBufferToSocket();

            List<IncomingFixMessage> received = readParsed(2);
            assertEquals("ORD-B", received.get(0).getCharSequence(11).toString());
            assertEquals(1, received.get(0).getSeqNum());
            assertEquals("ORD-C", received.get(1).getCharSequence(11).toString());
            assertEquals(2, received.get(1).getSeqNum(), "the aborted claim must neither reuse nor skip a number");
        } finally {
            threadA.shutdownNow();
            threadB.shutdownNow();
        }
    }

    @Test
    void resetAppliesBetweenTheMessagesItWasIssuedBetween() throws Exception {
        sendOrder("ORD-A");
        session.setOutgoingSeqNum(100);
        sendOrder("ORD-B");
        channel.drainRingBufferToSocket();

        List<IncomingFixMessage> received = readParsed(2);
        assertEquals(1, received.get(0).getSeqNum());
        assertEquals(100, received.get(1).getSeqNum());
        assertEquals(101, session.getOutgoingSeqNum());
    }

    @Test
    void stampedMessagesCarryValidChecksums() throws Exception {
        session.setOutgoingSeqNum(98);
        for (int i = 0; i < 5; i++) {
            sendOrder("ORD-" + i);
        }
        channel.drainRingBufferToSocket();

        // FixReader rejects a message whose CheckSum does not match its bytes
        List<IncomingFixMessage> received = readParsed(5);
        for (int i = 0; i < 5; i++) {
            assertEquals(98 + i, received.get(i).getSeqNum());
            assertEquals("ORD-" + i, received.get(i).getCharSequence(11).toString());
        }
    }

    private void sendOrder(String clOrdId) {
        RingBufferOutgoingMessage msg = session.tryClaimMessage("D");
        msg.setField(11, clOrdId);
        session.commitMessage(msg);
    }

    private List<IncomingFixMessage> readParsed(int expected) throws IOException {
        List<IncomingFixMessage> parsed = new ArrayList<>();
        com.omnibridge.fix.message.FixReader reader = new com.omnibridge.fix.message.FixReader();
        byte[] buf = new byte[64 * 1024];
        peer.socket().setSoTimeout(5_000);
        InputStream in = peer.socket().getInputStream();
        while (parsed.size() < expected) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            // Copy each chunk so earlier messages stay readable after the next addData
            byte[] chunk = java.util.Arrays.copyOf(buf, n);
            reader.addData(new org.agrona.concurrent.UnsafeBuffer(chunk), 0, n);
            IncomingFixMessage msg = new IncomingFixMessage();
            while (reader.readIncomingMessage(msg)) {
                IncomingFixMessage copy = new IncomingFixMessage();
                byte[] raw = msg.getRawMessage();
                copy.wrap(raw, 0, raw.length);
                parsed.add(copy);
            }
        }
        assertEquals(expected, parsed.size(), "messages that parsed with a valid checksum");
        return parsed;
    }

    // Plain text split rather than FixReader, so the reader's own buffering can't affect the result.
    private List<Integer> readSeqNums(int expected) {
        List<Integer> seqNums = new ArrayList<>(expected);
        StringBuilder pending = new StringBuilder();
        byte[] buf = new byte[64 * 1024];
        try {
            peer.socket().setSoTimeout(10_000);
            InputStream in = peer.socket().getInputStream();
            while (seqNums.size() < expected) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                pending.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                int end;
                while ((end = pending.indexOf("\u000110=")) >= 0 && pending.length() >= end + 8) {
                    String m = pending.substring(0, end + 8);
                    pending.delete(0, end + 8);
                    int p = m.indexOf("\u000134=") + 4;
                    int q = m.indexOf('\u0001', p);
                    seqNums.add(Integer.parseInt(m.substring(p, q)));
                }
            }
        } catch (IOException e) {
            // timed out: return what arrived
        }
        return seqNums;
    }
}
