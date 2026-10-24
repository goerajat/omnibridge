package com.omnibridge.fix.engine.session;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.TcpChannel;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A FIX session asks the network for up to its read cap per read, not for the 25 header bytes
 * and then the rest of one message: several messages and a partial one may arrive together.
 * A read that fills the cap is counted (more data was probably waiting); the first one per
 * connection is logged at WARN, later ones only counted.
 */
class FixSessionReadCapTest {

    private static final int DEFAULT_CAP = 2048;
    private static final String SESSION_ID = "CLIENT->BROKER";

    private ServerSocketChannel server;
    private SocketChannel client;
    private SocketChannel peer;
    private TcpChannel channel;
    private final List<String> delivered = new ArrayList<>();

    private FixSession newSession(SessionConfig.Builder builder) throws Exception {
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        client = SocketChannel.open();
        client.connect(server.getLocalAddress());
        peer = server.accept();
        client.configureBlocking(false);
        channel = new TcpChannel(client, 64 * 1024, 64 * 1024, 1024 * 1024);

        FixSession session = new FixSession(builder.build(), null);
        session.setChannel(channel);
        forceLoggedOn(session);
        session.addMessageListener(new MessageListener() {
            @Override
            public void onMessage(FixSession s, IncomingFixMessage message) {
                delivered.add(message.getSeqNum() + ":" + message.getCharSequence(FixTags.ClOrdID));
            }

            @Override
            public void onReject(FixSession s, int refSeqNum, String refMsgType, int reason, String text) {
                fail("reject " + refSeqNum + ": " + text);
            }
        });
        return session;
    }

    private static SessionConfig.Builder config() {
        return SessionConfig.builder()
                .sessionName("read-cap-test")
                .senderCompId("CLIENT")
                .targetCompId("BROKER")
                .acceptor()
                .port(1)
                .logMessages(false)
                .maxMessageLength(256);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
            peer.close();
            server.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void forceLoggedOn(FixSession session) throws Exception {
        Field f = FixSession.class.getDeclaredField("state");
        f.setAccessible(true);
        ((AtomicReference<SessionState>) f.get(session)).set(SessionState.LOGGED_ON);
    }

    @Test
    void asksForTheCapAtMessageStartAndMidMessage() throws Exception {
        FixSession session = newSession(config());
        assertEquals(DEFAULT_CAP, session.getNumBytesToRead(channel), "at the start of a message");

        byte[] m = execReport(1);
        session.onDataReceived(channel, new UnsafeBuffer(m), 0, 30);
        assertEquals(DEFAULT_CAP, session.getNumBytesToRead(channel), "after the header of a message");
        session.onDataReceived(channel, new UnsafeBuffer(m), 30, m.length - 30);
        assertEquals(List.of("1:TEST1"), delivered);
    }

    @Test
    void sessionCapOverridesTheDefault() throws Exception {
        FixSession session = newSession(config().readCapBytes(512));
        assertEquals(512, session.getNumBytesToRead(channel));
        assertEquals(512, session.getReadCapBytes());

        UnsafeBuffer burst = concat(execReport(1), execReport(2), execReport(3), execReport(4), execReport(5));
        session.onDataReceived(channel, burst, 0, 512);
        session.onDataReceived(channel, burst, 512, burst.capacity() - 512);
        assertEquals(5, delivered.size());
        assertEquals(1, session.getReadCapHits());
    }

    @Test
    void threeMessagesAndHalfAFourthInOneRead() throws Exception {
        FixSession session = newSession(config());
        byte[] fourth = execReport(4);
        int half = fourth.length / 2;
        UnsafeBuffer first = concat(execReport(1), execReport(2), execReport(3), slice(fourth, 0, half));

        session.onDataReceived(channel, first, 0, first.capacity());
        assertEquals(List.of("1:TEST1", "2:TEST2", "3:TEST3"), delivered);

        UnsafeBuffer rest = concat(slice(fourth, half, fourth.length - half), execReport(5));
        session.onDataReceived(channel, rest, 0, rest.capacity());
        assertEquals(List.of("1:TEST1", "2:TEST2", "3:TEST3", "4:TEST4", "5:TEST5"), delivered);
        assertEquals(0, session.getReadCapHits());
    }

    @Test
    void burstLargerThanTheCapIsDeliveredAndCounted() throws Exception {
        FixSession session = newSession(config());
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            int count = 100;
            byte[][] messages = new byte[count][];
            for (int i = 0; i < count; i++) {
                messages[i] = execReport(i + 1);
            }
            UnsafeBuffer burst = concat(messages);
            int total = burst.capacity();
            assertTrue(total > 3 * DEFAULT_CAP, "burst of " + total + " bytes");

            // Feed it as the network would: reads of at most the requested size
            int reads = 0;
            int fullReads = 0;
            for (int pos = 0; pos < total; reads++) {
                int n = Math.min(session.getNumBytesToRead(channel), total - pos);
                if (n == DEFAULT_CAP) {
                    fullReads++;
                }
                session.onDataReceived(channel, burst, pos, n);
                pos += n;
            }

            assertEquals(count, delivered.size(), "messages delivered");
            for (int i = 0; i < count; i++) {
                assertEquals((i + 1) + ":TEST" + (i + 1), delivered.get(i));
            }
            assertEquals((total + DEFAULT_CAP - 1) / DEFAULT_CAP, reads, "reads of " + total + " bytes");
            assertEquals(fullReads, session.getReadCapHits(), "reads that filled the cap");
            List<String> warnings = logs.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(1, warnings.stream().filter(m -> m.startsWith("[" + SESSION_ID + "] Read cap reached")).count(),
                    "WARN lines about the read cap: " + warnings);

            // At Logout or disconnect, one summary line with the count
            ListAppender<ILoggingEvent> timing = captureLogs("com.omnibridge.fix.engine.timing");
            try {
                session.onDisconnected(channel, null);
                session.onDisconnected(channel, null);
                assertEquals(List.of("[" + SESSION_ID + "] read cap reached " + fullReads + " times, cap " + DEFAULT_CAP),
                        timing.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            } finally {
                releaseLogs(timing, "com.omnibridge.fix.engine.timing");
            }
        } finally {
            releaseLogs(logs);
        }
    }

    // ==================== helpers ====================

    private static ListAppender<ILoggingEvent> captureLogs() {
        return captureLogs(FixSession.class.getName());
    }

    private static void releaseLogs(ListAppender<ILoggingEvent> appender) {
        releaseLogs(appender, FixSession.class.getName());
    }

    private static ListAppender<ILoggingEvent> captureLogs(String logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(logger)).addAppender(appender);
        return appender;
    }

    private static void releaseLogs(ListAppender<ILoggingEvent> appender, String logger) {
        ((Logger) LoggerFactory.getLogger(logger)).detachAppender(appender);
    }

    private static byte[] execReport(int seqNum) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        RingBufferOutgoingMessage msg = new RingBufferOutgoingMessage(MessagePoolConfig.builder()
                .maxMessageLength(256).beginString("FIX.4.4").senderCompId("BROKER").targetCompId("CLIENT")
                .build());
        msg.wrap(buffer, 0, 256, seqNum, 0, "FIX.4.4", "BROKER", "CLIENT", FixTags.MsgTypes.ExecutionReport);
        msg.setField(FixTags.OrderID, "O" + seqNum);
        msg.setField(FixTags.ClOrdID, "TEST" + seqNum);
        msg.setField(FixTags.ExecType, FixTags.EXEC_TYPE_NEW);
        msg.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_NEW);
        int length = msg.prepareForSend(System.currentTimeMillis());
        byte[] bytes = new byte[length];
        buffer.getBytes(RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE, bytes);
        return bytes;
    }

    private static byte[] slice(byte[] bytes, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(bytes, from, out, 0, length);
        return out;
    }

    private static UnsafeBuffer concat(byte[]... messages) {
        int total = 0;
        for (byte[] m : messages) {
            total += m.length;
        }
        UnsafeBuffer out = new UnsafeBuffer(new byte[total]);
        int pos = 0;
        for (byte[] m : messages) {
            out.putBytes(pos, m);
            pos += m.length;
        }
        return out;
    }
}
