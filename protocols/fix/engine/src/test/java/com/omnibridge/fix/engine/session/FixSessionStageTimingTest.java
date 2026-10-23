package com.omnibridge.fix.engine.session;

import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.timing.StageTimingRecorder;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.TcpChannel;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.omnibridge.fix.engine.timing.StageTimingRecorder.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage timing records one ordered row per sent and received message, carries the sending
 * thread's stamps inside the ring record without putting them on the wire, and is reset by a
 * counterparty's marker TestRequest.
 */
class FixSessionStageTimingTest {

    private ServerSocketChannel server;
    private SocketChannel client;
    private SocketChannel peer;
    private TcpChannel channel;

    private FixSession newSession(int stageTimingCapacity) throws Exception {
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        client = SocketChannel.open();
        client.setOption(java.net.StandardSocketOptions.SO_SNDBUF, 4 * 1024 * 1024);
        client.connect(server.getLocalAddress());
        peer = server.accept();
        client.configureBlocking(false);
        channel = new TcpChannel(client, 64 * 1024, 64 * 1024, 1024 * 1024);

        FixSession session = new FixSession(SessionConfig.builder()
                .sessionName("timing-test")
                .senderCompId("CLIENT")
                .targetCompId("BROKER")
                .acceptor()
                .port(1)
                .logMessages(false)
                .maxMessageLength(256)
                .stageTimingCapacity(stageTimingCapacity)
                .build(), null);
        session.setChannel(channel);
        forceLoggedOn(session);
        return session;
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
    void offByDefault() throws Exception {
        FixSession session = newSession(0);
        assertNull(session.getStageTiming());
        assertFalse(session.requestStageTimingReset());
    }

    @Test
    void sendRowsAreOrderedAndStampsStayOffTheWire() throws Exception {
        FixSession session = newSession(1024);
        int count = 50;
        for (int i = 0; i < count; i++) {
            RingBufferOutgoingMessage msg = session.tryClaimMessage("D");
            msg.setField(FixTags.ClOrdID, "ORD" + i);
            session.commitMessage(msg);
        }
        while (channel.hasRingBufferMessages()) {
            channel.drainRingBufferToSocket();
        }

        List<String> wire = readMessages(count);
        for (int i = 0; i < count; i++) {
            String m = wire.get(i);
            assertTrue(m.matches("(?s).*\u000134=0*" + (i + 1) + "\u0001.*"), m);
            assertTrue(m.contains("\u000111=ORD" + i + "\u0001"), m);
            assertTrue(validChecksum(m), "checksum of " + m);
        }

        StageTimingRecorder timing = session.getStageTiming();
        assertEquals(count, timing.getSendCount());
        assertEquals(count, timing.getCommitCallCount());
        for (int r = 0; r < count; r++) {
            for (int s = 1; s < SEND_STAGES; s++) {
                assertTrue(timing.sendStamp(r, s) >= timing.sendStamp(r, s - 1),
                        "row " + r + " stage " + s + " before stage " + (s - 1));
            }
            assertTrue(timing.sendStamp(r, SEND_CLAIM) > 0);
        }
    }

    @Test
    void receiveRowsAndCounterpartyReset() throws Exception {
        FixSession session = newSession(1024);
        AtomicInteger delivered = new AtomicInteger();
        session.addMessageListener(new MessageListener() {
            @Override
            public void onMessage(FixSession s, com.omnibridge.fix.message.IncomingFixMessage message) {
                delivered.incrementAndGet();
            }

            @Override
            public void onReject(FixSession s, int refSeqNum, String refMsgType, int reason, String text) {
            }
        });
        StageTimingRecorder timing = session.getStageTiming();

        // Three ExecutionReports in one read
        UnsafeBuffer batch = concat(execReport(1), execReport(2), execReport(3));
        session.onDataReceived(channel, batch, 0, batch.capacity());
        assertEquals(3, delivered.get());
        assertEquals(3, timing.getReceiveCount());
        for (int r = 0; r < 3; r++) {
            for (int s = 1; s < RECV_STAGES; s++) {
                assertTrue(timing.receiveStamp(r, s) >= timing.receiveStamp(r, s - 1),
                        "row " + r + " stage " + s);
            }
        }
        // All three came from the same read
        assertEquals(timing.receiveStamp(0, RECV_READ), timing.receiveStamp(2, RECV_READ));

        // The marker TestRequest resets; its own row is the first one after the reset
        UnsafeBuffer marker = concat(testRequest(4, StageTimingRecorder.RESET_TEST_REQ_ID));
        session.onDataReceived(channel, marker, 0, marker.capacity());
        assertEquals(1, timing.getReceiveCount());
        assertFalse(timing.isReported());

        UnsafeBuffer more = concat(execReport(5));
        session.onDataReceived(channel, more, 0, more.capacity());
        assertEquals(2, timing.getReceiveCount());
    }

    // ==================== helpers ====================

    private static boolean validChecksum(String m) {
        int idx = m.lastIndexOf("\u000110=");
        int sum = 0;
        for (int i = 0; i <= idx; i++) {
            sum += m.charAt(i);
        }
        return Integer.parseInt(m.substring(idx + 4, idx + 7)) == (sum & 0xFF);
    }

    private List<String> readMessages(int count) throws IOException {
        InputStream in = peer.socket().getInputStream();
        StringBuilder sb = new StringBuilder();
        List<String> out = new ArrayList<>();
        byte[] buf = new byte[8192];
        while (out.size() < count) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
            int end;
            while ((end = sb.indexOf("\u000110=")) >= 0 && sb.length() >= end + 8) {
                out.add(sb.substring(0, end + 8));
                sb.delete(0, end + 8);
            }
        }
        assertEquals(0, sb.length(), "bytes after the last message (stamps leaked onto the wire?)");
        return out;
    }

    private static RingBufferOutgoingMessage brokerMessage(UnsafeBuffer buffer, int seqNum, String msgType) {
        RingBufferOutgoingMessage msg = new RingBufferOutgoingMessage(MessagePoolConfig.builder()
                .maxMessageLength(256).beginString("FIX.4.4").senderCompId("BROKER").targetCompId("CLIENT")
                .build());
        msg.wrap(buffer, 0, 256, seqNum, 0, "FIX.4.4", "BROKER", "CLIENT", msgType);
        return msg;
    }

    private static byte[] finish(UnsafeBuffer buffer, RingBufferOutgoingMessage msg) {
        int length = msg.prepareForSend(System.currentTimeMillis());
        byte[] bytes = new byte[length];
        buffer.getBytes(RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE, bytes);
        return bytes;
    }

    private static byte[] execReport(int seqNum) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        RingBufferOutgoingMessage msg = brokerMessage(buffer, seqNum, FixTags.MsgTypes.ExecutionReport);
        msg.setField(FixTags.OrderID, "O" + seqNum);
        msg.setField(FixTags.ClOrdID, "TEST" + seqNum);
        msg.setField(FixTags.ExecType, FixTags.EXEC_TYPE_NEW);
        msg.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_NEW);
        return finish(buffer, msg);
    }

    private static byte[] testRequest(int seqNum, String testReqId) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        RingBufferOutgoingMessage msg = brokerMessage(buffer, seqNum, FixTags.MSG_TYPE_TEST_REQUEST);
        msg.setField(FixTags.TEST_REQ_ID, testReqId);
        return finish(buffer, msg);
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
