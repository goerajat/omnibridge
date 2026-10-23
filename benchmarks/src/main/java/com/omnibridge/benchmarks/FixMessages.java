package com.omnibridge.benchmarks;

import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Builds the messages the benchmarks encode and parse, matching the latency test:
 * a NewOrderSingle from CLIENT and the ExecutionReport (New) the acceptor sends back.
 */
final class FixMessages {

    static final String BEGIN_STRING = "FIX.4.4";
    static final String SENDER = "CLIENT";
    static final String TARGET = "EXCHANGE";
    static final int MAX_MESSAGE_LENGTH = 512;
    static final long SENDING_TIME_MILLIS = 1_792_000_000_000L;

    private FixMessages() {
    }

    static MessagePoolConfig poolConfig() {
        return MessagePoolConfig.builder()
                .maxMessageLength(MAX_MESSAGE_LENGTH)
                .beginString(BEGIN_STRING)
                .senderCompId(SENDER)
                .targetCompId(TARGET)
                .build();
    }

    /** A direct ring-like buffer to encode into. */
    static UnsafeBuffer newBuffer() {
        return new UnsafeBuffer(ByteBuffer.allocateDirect(64 * 1024));
    }

    /** Fill a NewOrderSingle body the way LatencyTestRunner does. */
    static void newOrderFields(RingBufferOutgoingMessage msg, CharSequence clOrdId, int qty) {
        msg.setField(FixTags.ClOrdID, clOrdId);
        msg.setField(FixTags.Symbol, "TEST");
        msg.setField(FixTags.Side, FixTags.SIDE_BUY);
        msg.setField(FixTags.OrderQty, qty);
        msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
        msg.setField(FixTags.Price, 100.00, 2);
        msg.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
    }

    /**
     * Encoded ExecutionReport (New) bytes, as the acceptor sends them.
     */
    static byte[] executionReport(int seqNum, String clOrdId) {
        UnsafeBuffer buffer = newBuffer();
        RingBufferOutgoingMessage msg = new RingBufferOutgoingMessage(
                MessagePoolConfig.builder()
                        .maxMessageLength(MAX_MESSAGE_LENGTH)
                        .beginString(BEGIN_STRING)
                        .senderCompId(TARGET)
                        .targetCompId(SENDER)
                        .build());
        msg.wrap(buffer, 0, MAX_MESSAGE_LENGTH, seqNum, 0, BEGIN_STRING, TARGET, SENDER,
                FixTags.MsgTypes.ExecutionReport);
        msg.setField(FixTags.OrderID, "ORD" + seqNum);
        msg.setField(FixTags.ExecID, "EXEC" + seqNum);
        msg.setField(FixTags.ClOrdID, clOrdId);
        msg.setField(FixTags.ExecType, FixTags.EXEC_TYPE_NEW);
        msg.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_NEW);
        msg.setField(FixTags.Symbol, "TEST");
        msg.setField(FixTags.Side, FixTags.SIDE_BUY);
        msg.setField(FixTags.OrderQty, 100);
        msg.setField(FixTags.Price, 100.00, 2);
        msg.setField(FixTags.CumQty, 0);
        msg.setField(FixTags.LeavesQty, 100);
        msg.setField(FixTags.AvgPx, 0.0, 2);
        int length = msg.prepareForSend(SENDING_TIME_MILLIS);
        byte[] bytes = new byte[length];
        buffer.getBytes(RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE, bytes);
        return bytes;
    }

    /** {@code count} ExecutionReports back to back, as one socket read would deliver them. */
    static UnsafeBuffer executionReports(int count) {
        ByteBuffer all = ByteBuffer.allocateDirect(count * MAX_MESSAGE_LENGTH);
        for (int i = 0; i < count; i++) {
            all.put(executionReport(i + 1, "TEST" + i));
        }
        UnsafeBuffer buffer = new UnsafeBuffer(all, 0, all.position());
        return buffer;
    }
}
