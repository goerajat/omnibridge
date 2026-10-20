package com.omnibridge.simulator.response;

import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every ExecutionReport in a session must carry a distinct ExecID (tag 17):
 * counterparties dedupe executions on it.
 */
class FixResponseSenderExecIdTest {

    private static final Pattern EXEC_ID = Pattern.compile("\\|17=([^|]*)\\|");

    @Test
    void execIdsAreUniqueAcrossAckFillAndCancel() {
        CapturingSession session = new CapturingSession();
        FixResponseSender sender = new FixResponseSender();
        // FixProtocolHandler numbers fills from its own counter starting at 1
        AtomicLong handlerFillIds = new AtomicLong(1);

        Order filled = order(1, "ORD1");
        filled.accept();
        sender.sendOrderAccepted(session, filled);
        FillDecision partial = FillDecision.partialFill(40, 10.0);
        filled.fill(partial.getFillQty(), partial.getFillPrice());
        sender.sendFill(session, filled, partial, handlerFillIds.getAndIncrement());
        FillDecision rest = FillDecision.fullFill(60, 10.0);
        filled.fill(rest.getFillQty(), rest.getFillPrice());
        sender.sendFill(session, filled, rest, handlerFillIds.getAndIncrement());

        Order canceled = order(2, "ORD2");
        canceled.accept();
        sender.sendOrderAccepted(session, canceled);
        canceled.cancel();
        sender.sendOrderCanceled(session, canceled, "CXL2");

        List<String> execIds = session.execIds();
        assertEquals(5, execIds.size(), "one ExecutionReport per event: " + session.sent);
        assertEquals(execIds.size(), new HashSet<>(execIds).size(), "duplicate ExecID: " + execIds);
        assertTrue(session.sent.get(4).contains("|11=CXL2|41=ORD2|"), "cancel OrigClOrdID: " + session.sent.get(4));
    }

    private static Order order(long id, String clOrdId) {
        return Order.builder()
                .orderId(id)
                .clientOrderId(clOrdId)
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .qty(100)
                .price(10.0)
                .sessionId("SIM")
                .protocol("FIX")
                .build();
    }

    /** Session that hands out standalone messages and records each committed one. */
    private static class CapturingSession extends FixSession {
        final List<String> sent = new ArrayList<>();

        CapturingSession() {
            super(SessionConfig.builder()
                    .sessionName("execid-test")
                    .senderCompId("EXCH")
                    .targetCompId("CLIENT")
                    .acceptor()
                    .port(1)
                    .logMessages(false)
                    .build(), null);
        }

        @Override
        public RingBufferOutgoingMessage tryClaimMessage(String msgType) {
            RingBufferOutgoingMessage msg = new RingBufferOutgoingMessage();
            msg.wrap(new UnsafeBuffer(new byte[4096]), 0, 4096, 1, 0, "FIX.4.4", "EXCH", "CLIENT", msgType);
            return msg;
        }

        @Override
        public void commitMessage(RingBufferOutgoingMessage msg) {
            sent.add(msg.toString());
        }

        List<String> execIds() {
            List<String> ids = new ArrayList<>();
            for (String m : sent) {
                Matcher matcher = EXEC_ID.matcher(m);
                assertTrue(matcher.find(), "no ExecID in " + m);
                ids.add(matcher.group(1));
            }
            return ids;
        }
    }
}
