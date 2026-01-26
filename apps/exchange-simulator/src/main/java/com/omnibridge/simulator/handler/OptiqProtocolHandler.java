package com.omnibridge.simulator.handler;

import com.omnibridge.optiq.engine.OptiqSession;
import com.omnibridge.optiq.message.order.NewOrderMessage;
import com.omnibridge.optiq.message.order.CancelOrderMessage;
import com.omnibridge.optiq.message.order.ExecutionReportMessage;
import com.omnibridge.sbe.engine.session.SbeSession;
import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.fill.FillEngine;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderBook;
import com.omnibridge.simulator.core.order.OrderIdGenerator;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Protocol handler for Optiq messages.
 */
public class OptiqProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(OptiqProtocolHandler.class);

    private static final byte ORD_STATUS_NEW = 0;
    private static final byte ORD_STATUS_PARTIAL = 1;
    private static final byte ORD_STATUS_FILLED = 2;
    private static final byte ORD_STATUS_CANCELED = 4;

    private final OrderBook orderBook;
    private final FillEngine fillEngine;
    private final OrderIdGenerator orderIdGenerator;
    private final AtomicLong execIdCounter = new AtomicLong(1);

    // Statistics
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersCanceled = new AtomicLong(0);

    public OptiqProtocolHandler(OrderBook orderBook, FillEngine fillEngine, OrderIdGenerator orderIdGenerator) {
        this.orderBook = orderBook;
        this.fillEngine = fillEngine;
        this.orderIdGenerator = orderIdGenerator;
    }

    public void onMessage(SbeSession<?> session, SbeMessage message) {
        if (message instanceof NewOrderMessage order) {
            handleNewOrder((OptiqSession) session, order);
        } else if (message instanceof CancelOrderMessage cancel) {
            handleCancelOrder((OptiqSession) session, cancel);
        }
    }

    private void handleNewOrder(OptiqSession session, NewOrderMessage msg) {
        ordersReceived.incrementAndGet();

        long orderId = orderIdGenerator.nextId();
        long clientOrderId = msg.getClientOrderId();
        double price = msg.getOrderPx();
        long qty = msg.getOrderQty();
        byte side = msg.getOrderSide();

        Order order = Order.builder()
                .orderId(orderId)
                .clientOrderId(String.valueOf(clientOrderId))
                .symbol(String.valueOf(msg.getSymbolIndex()))
                .side(OrderSide.fromByte(side))
                .orderType(OrderType.LIMIT)
                .qty(qty)
                .price(price)
                .sessionId(session.getSessionId())
                .protocol("OPTIQ")
                .build();

        log.debug("New Optiq order: clientOrderId={}, qty={}, price={}", clientOrderId, qty, price);

        if (!orderBook.addOrder(order)) {
            log.error("Failed to add order to book: duplicate orderId {}", orderId);
            return;
        }

        order.accept();
        ordersAccepted.incrementAndGet();

        sendExecutionReport(session, order, ORD_STATUS_NEW);

        FillDecision decision = fillEngine.evaluate(order);
        if (decision.shouldFill()) {
            processFill(session, order, decision);
        }
    }

    private void handleCancelOrder(OptiqSession session, CancelOrderMessage msg) {
        String clientOrderId = String.valueOf(msg.getClientOrderId());

        log.debug("Optiq Cancel: clientOrderId={}", clientOrderId);

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clientOrderId, session.getSessionId());
        if (orderOpt.isEmpty()) {
            log.warn("Cancel for unknown Optiq order: {}", clientOrderId);
            return;
        }

        Order order = orderOpt.get();
        if (order.cancel()) {
            ordersCanceled.incrementAndGet();
            sendExecutionReport(session, order, ORD_STATUS_CANCELED);
            log.debug("Optiq Order Canceled: clientOrderId={}", clientOrderId);
        }
    }

    private void sendExecutionReport(OptiqSession session, Order order, byte ordStatus) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for order {}", order.getOrderId());
            return;
        }

        try {
            execReport.writeHeader();
            execReport.setMsgSeqNum(session.getOutboundSeqNum())
                     .setFirmId(session.getConfig().getFirmId())
                     .setClientOrderId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setSymbolIndex(Integer.parseInt(order.getSymbol()))
                     .setOrderSide(order.getSide() == OrderSide.BUY ? (byte) 1 : (byte) 2)
                     .setOrderStatus(ordStatus)
                     .setOrderPx(order.getPrice())
                     .setOrderQty((long) order.getOriginalQty())
                     .setLeavesQty((long) order.getLeavesQty())
                     .setCumQty((long) order.getFilledQty())
                     .setOegIn(System.nanoTime())
                     .setOegOut(System.nanoTime());

            session.commit(execReport);
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport", e);
        }
    }

    private void processFill(OptiqSession session, Order order, FillDecision decision) {
        long execId = execIdCounter.getAndIncrement();

        if (order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ordersFilled.incrementAndGet();
            byte ordStatus = order.getLeavesQty() <= 0 ? ORD_STATUS_FILLED : ORD_STATUS_PARTIAL;
            sendExecutionReport(session, order, ordStatus);
            log.debug("Optiq Order Filled: clientOrderId={}, qty={}, price={}",
                    order.getClientOrderId(), decision.getFillQty(), decision.getFillPrice());
        }
    }

    // Statistics getters
    public long getOrdersReceived() { return ordersReceived.get(); }
    public long getOrdersAccepted() { return ordersAccepted.get(); }
    public long getOrdersFilled() { return ordersFilled.get(); }
    public long getOrdersCanceled() { return ordersCanceled.get(); }
}
