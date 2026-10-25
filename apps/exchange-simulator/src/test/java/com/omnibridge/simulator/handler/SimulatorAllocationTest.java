package com.omnibridge.simulator.handler;

import com.omnibridge.config.ConfigLoader;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import java.util.List;
import com.omnibridge.simulator.core.fill.FillConfig;
import com.omnibridge.simulator.core.fill.RuleBasedFillEngine;
import com.omnibridge.simulator.core.order.DefaultOrderBook;
import com.omnibridge.simulator.core.order.OrderIdGenerator;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulator event loop's work per latency-test order: frame and parse a NewOrderSingle,
 * run {@link FixProtocolHandler} (order book, fill rules from the real config, no demo tracker),
 * encode the ExecutionReport, write it to the socket. The order itself is kept in the book, so
 * the budget is what one retained order costs; nothing else should allocate, or the young GCs
 * show up in the tail.
 */
class SimulatorAllocationTest {

    private static final int WARMUP = 30_000;
    private static final int MEASURED = 20_000;
    /**
     * What one retained order costs, measured at 312 B: the Order (112 B), its ClOrdID String
     * (24 B + a 32 B byte[], plus the 32 B copy String's constructor makes from the off-heap
     * bytes), the boxed Long key (16 B) and the two ConcurrentHashMap nodes (32 B each). Before
     * the order path was made allocation-free it was 1,076 B.
     */
    private static final double MAX_BYTES_PER_ORDER = 320;

    private ServerSocketChannel server;
    private SocketChannel client;
    private SocketChannel peer;
    private LogStore logStore;
    private Path storeDir;

    /** A Chronicle store in a temporary directory, as the sessions run with log-messages = true. */
    private LogStore newStore() throws IOException {
        storeDir = Files.createTempDirectory("alloc-test-store");
        logStore = new ChronicleLogStore(storeDir.toFile());
        return logStore;
    }

    private void closeStore() throws IOException {
        if (logStore != null) {
            logStore.close();
            try (Stream<Path> paths = Files.walk(storeDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
            peer.close();
            server.close();
        }
        closeStore();
    }

    /** With persistence on, every order and ack is also written to the store, still within the budget. */
    @ParameterizedTest(name = "persistence={0}")
    @ValueSource(booleans = {false, true})
    void ordersAndAcksAllocateOnlyTheRetainedOrder(boolean persistence) throws Exception {
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
                .logMessages(persistence)
                .traceMessages(false)   // trace-messages defaults to log-messages: a log line per message
                .build(), persistence ? newStore() : null);
        session.setChannel(channel);
        forceLoggedOn(session);

        int total = WARMUP + MEASURED;
        RuleBasedFillEngine fillEngine = new RuleBasedFillEngine();
        FillConfig.fromConfig(ConfigLoader.load(List.of("exchange-simulator.conf")).getConfig("fill-engine"))
                .getRules().forEach(fillEngine::addRule);
        FixProtocolHandler handler = new FixProtocolHandler(
                new DefaultOrderBook(total, false), fillEngine, new OrderIdGenerator(1_000_000));
        session.addMessageListener(handler);

        UnsafeBuffer orders = newOrders(total);
        ByteBuffer sink = ByteBuffer.allocateDirect(1024 * 1024);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        int pos = 0;
        pos = feed(session, channel, orders, pos, WARMUP, sink);
        long before = threads.getCurrentThreadAllocatedBytes();
        pos = feed(session, channel, orders, pos, MEASURED, sink);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertEquals(total, handler.getOrdersAccepted());
        assertEquals(0, handler.getOrdersFilled(), "NOFILL orders filled");
        double perOrder = (double) allocated / MEASURED;
        System.out.printf("simulator: %.1f bytes per order%n", perOrder);
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

    /** The latency test's NewOrderSingles (Symbol NOFILL, ClOrdID TEST<i>), each prefixed by its length. */
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
            msg.setField(FixTags.ClOrdID, "TEST" + i);
            msg.setField(FixTags.Symbol, "NOFILL");
            msg.setField(FixTags.Side, FixTags.SIDE_BUY);
            msg.setField(FixTags.OrderQty, 100 + i);
            msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
            msg.setField(FixTags.Price, 100.00, 2);
            msg.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
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
