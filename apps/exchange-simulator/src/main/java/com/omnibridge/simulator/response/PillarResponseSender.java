package com.omnibridge.simulator.response;

import com.omnibridge.pillar.engine.PillarSession;
import com.omnibridge.pillar.message.order.OrderAckMessage;
import com.omnibridge.pillar.message.order.ExecutionReportMessage;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Response sender for Pillar protocol.
 */
public class PillarResponseSender implements ResponseSender<PillarSession> {

    private static final Logger log = LoggerFactory.getLogger(PillarResponseSender.class);

    private static final byte ORD_STATUS_NEW = 0;
    private static final byte ORD_STATUS_PARTIAL = 1;
    private static final byte ORD_STATUS_FILLED = 2;
    private static final byte ORD_STATUS_CANCELED = 4;
    private static final byte ORD_STATUS_REJECTED = 8;

    private final AtomicLong execIdSequence = new AtomicLong(1);

    @Override
    public boolean sendOrderAccepted(PillarSession session, Order order) {
        OrderAckMessage ack = session.tryClaim(OrderAckMessage.class);
        if (ack == null) {
            log.error("Could not claim OrderAck for order {}", order.getOrderId());
            return false;
        }

        try {
            ack.writeHeader();
            ack.setClOrdId(Long.parseLong(order.getClientOrderId()))
               .setOrderId(order.getOrderId())
               .setSymbol(order.getSymbol())
               .setSide(order.getSide() == OrderSide.BUY ? (byte) 1 : (byte) 2)
               .setPrice(order.getPrice())
               .setOrderQty((long) order.getOriginalQty())
               .setOrdStatus(ORD_STATUS_NEW)
               .setTransactTime(System.nanoTime());

            session.commit(ack);
            return true;
        } catch (Exception e) {
            session.abort(ack);
            log.error("Error sending OrderAck", e);
            return false;
        }
    }

    @Override
    public boolean sendFill(PillarSession session, Order order, FillDecision decision, long execId) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for fill {}", order.getOrderId());
            return false;
        }

        try {
            byte ordStatus = order.getLeavesQty() <= 0 ? ORD_STATUS_FILLED : ORD_STATUS_PARTIAL;

            execReport.writeHeader();
            execReport.setClOrdId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setExecId(execId)
                     .setSymbol(order.getSymbol())
                     .setSide(order.getSide() == OrderSide.BUY ? (byte) 1 : (byte) 2)
                     .setOrdStatus(ordStatus)
                     .setExecType((byte) 'F')
                     .setRawPrice(toRawPrice(order.getPrice()))
                     .setRawLastPx(toRawPrice(decision.getFillPrice()))
                     .setOrderQty((long) order.getOriginalQty())
                     .setLastQty((long) decision.getFillQty())
                     .setCumQty((long) order.getFilledQty())
                     .setLeavesQty((long) order.getLeavesQty())
                     .setTransactTime(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (Fill)", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderRejected(PillarSession session, Order order, String reason) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for reject {}", order.getOrderId());
            return false;
        }

        try {
            execReport.writeHeader();
            execReport.setClOrdId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setExecId(execIdSequence.getAndIncrement())
                     .setOrdStatus(ORD_STATUS_REJECTED)
                     .setExecType((byte) '8')
                     .setTransactTime(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (Reject)", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderCanceled(PillarSession session, Order order, String clOrdId) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for cancel {}", order.getOrderId());
            return false;
        }

        try {
            execReport.writeHeader();
            execReport.setClOrdId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setExecId(execIdSequence.getAndIncrement())
                     .setOrdStatus(ORD_STATUS_CANCELED)
                     .setExecType((byte) '4')
                     .setLeavesQty(0)
                     .setCumQty((long) order.getFilledQty())
                     .setTransactTime(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (Cancel)", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderReplaced(PillarSession session, Order oldOrder, Order newOrder) {
        return sendOrderAccepted(session, newOrder);
    }

    @Override
    public String getProtocol() {
        return "PILLAR";
    }

    private long toRawPrice(double price) {
        // Pillar uses fixed-point pricing (8 decimal places)
        return (long) (price * 100_000_000);
    }
}
