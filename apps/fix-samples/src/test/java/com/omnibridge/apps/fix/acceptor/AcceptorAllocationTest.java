package com.omnibridge.apps.fix.acceptor;

import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.TcpChannel;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event loop's work per order, as in the latency test: frame and parse a NewOrderSingle,
 * run the acceptor's listener in latency mode, encode the ExecutionReport, write it to the
 * socket. None of it should allocate, or the young GCs it causes show up in the tail.
 */
class AcceptorAllocationTest {

    private static final int WARMUP = 30_000;
    private static final int MEASURED = 20_000;
    /** Measurement noise allowance (the MXBean call itself, TLAB accounting). */
    private static final double MAX_BYTES_PER_ORDER = 8;

    private ServerSocketChannel server;
    private SocketChannel client;
    private SocketChannel peer;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
            peer.close();
            server.close();
        }
    }

    @Test
    void ordersAndAcksAllocateNothingOnTheEventLoop() throws Exception {
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        client = SocketChannel.open();
        client.connect(server.getLocalAddress());
        peer = server.accept();
        client.configureBlocking(false);
        peer.configureBlocking(false);
        TcpChannel channel = new TcpChannel(client, 64 * 1024, 64 * 1024, 1024 * 1024);

        FixSession session = new FixSession(SessionConfig.builder()
                .sessionName("alloc-test")
                .senderCompId("EXCH")
                .targetCompId("CLIENT")
                .acceptor()
                .port(1)
                .logMessages(false)
                .build(), null);
        session.setChannel(channel);
        forceLoggedOn(session);
        AcceptorMessageListener listener = new AcceptorMessageListener(0.0, true);
        session.addMessageListener(listener);

        int total = WARMUP + MEASURED;
        UnsafeBuffer orders = newOrders(total);
        ByteBuffer sink = ByteBuffer.allocateDirect(1024 * 1024);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        int pos = 0;
        pos = feed(session, channel, orders, pos, WARMUP, sink);
        long before = threads.getCurrentThreadAllocatedBytes();
        pos = feed(session, channel, orders, pos, MEASURED, sink);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertEquals(total, listener.getOrdersReceived());
        assertEquals(total, listener.getExecutionReportsSent());
        double perOrder = (double) allocated / MEASURED;
        assertTrue(perOrder <= MAX_BYTES_PER_ORDER,
                String.format("allocated %.1f bytes per order (%d bytes over %d orders)", perOrder, allocated, MEASURED));
    }

    /** One order per read, then drain the ack to the socket and discard it at the peer. */
    private int feed(FixSession session, TcpChannel channel, UnsafeBuffer orders, int pos, int count,
                     ByteBuffer sink) throws IOException {
        for (int i = 0; i < count; i++) {
            int length = orders.getInt(pos);
            session.onDataReceived(channel, orders, pos + 4, length);
            pos += 4 + length;
            channel.drainRingBufferToSocket();
            sink.clear();
            peer.read(sink);
        }
        return pos;
    }

    /** NewOrderSingles with MsgSeqNum 1..count, each prefixed by its length. */
    private static UnsafeBuffer newOrders(int count) {
        UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        RingBufferOutgoingMessage msg = new RingBufferOutgoingMessage(MessagePoolConfig.builder()
                .maxMessageLength(512).beginString("FIX.4.4").senderCompId("CLIENT").targetCompId("EXCH")
                .build());
        UnsafeBuffer out = new UnsafeBuffer(new byte[count * 256]);
        int pos = 0;
        long now = System.currentTimeMillis();
        for (int i = 1; i <= count; i++) {
            msg.wrap(scratch, 0, 512, i, 0, "FIX.4.4", "CLIENT", "EXCH", FixTags.MsgTypes.NewOrderSingle);
            msg.setField(FixTags.ClOrdID, "CL" + i);
            msg.setField(FixTags.Symbol, "AAPL");
            msg.setField(FixTags.Side, FixTags.SIDE_BUY);
            msg.setField(FixTags.OrderQty, 100);
            msg.setField(FixTags.OrdType, '2');
            msg.setField(FixTags.Price, 150.25, 2);
            int length = msg.prepareForSend(now);
            out.putInt(pos, length);
            out.putBytes(pos + 4, scratch, RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE, length);
            pos += 4 + length;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void forceLoggedOn(FixSession session) throws Exception {
        Field f = FixSession.class.getDeclaredField("state");
        f.setAccessible(true);
        ((AtomicReference<SessionState>) f.get(session)).set(SessionState.LOGGED_ON);
    }
}
