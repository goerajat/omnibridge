package com.omnibridge.simulator.handler;

import com.omnibridge.ilink3.engine.ILink3Session;
import com.omnibridge.ilink3.message.order.NewOrderSingleMessage;
import com.omnibridge.ilink3.message.order.OrderCancelRequestMessage;
import com.omnibridge.ilink3.message.order.OrderCancelReplaceRequestMessage;
import com.omnibridge.ilink3.message.order.ExecutionReportNewMessage;
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
 * Protocol handler for iLink3 messages.
 */
public class ILink3ProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ILink3ProtocolHandler.class);

    private final OrderBook orderBook;
    private final FillEngine fillEngine;
    private final OrderIdGenerator orderIdGenerator;
    private final AtomicLong execIdCounter = new AtomicLong(1);

    // Statistics
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersCanceled = new AtomicLong(0);

    public ILink3ProtocolHandler(OrderBook orderBook, FillEngine fillEngine, OrderIdGenerator orderIdGenerator) {
        this.orderBook = orderBook;
        this.fillEngine = fillEngine;
        this.orderIdGenerator = orderIdGenerator;
    }

    public void onMessage(SbeSession<?> session, SbeMessage message) {
        if (message instanceof NewOrderSingleMessage order) {
            handleNewOrder((ILink3Session) session, order);
        } else if (message instanceof OrderCancelRequestMessage cancel) {
            handleCancelOrder((ILink3Session) session, cancel);
        } else if (message instanceof OrderCancelReplaceRequestMessage replace) {
            handleReplaceOrder((ILink3Session) session, replace);
        }
    }

    private void handleNewOrder(ILink3Session session, NewOrderSingleMessage msg) {
        ordersReceived.incrementAndGet();

        long orderId = orderIdGenerator.nextId();
        String clOrdId = msg.getClOrdId();
        double price = msg.getPrice();
        int qty = msg.getOrderQty();
        byte side = msg.getSide();

        Order order = Order.builder()
                .orderId(orderId)
                .clientOrderId(clOrdId)
                .symbol("")
                .side(OrderSide.fromByte(side))
                .orderType(OrderType.LIMIT)
                .qty(qty)
                .price(price)
                .sessionId(session.getSessionId())
                .protocol("ILINK3")
                .build();

        log.debug("New iLink3 order: clOrdId={}, qty={}, price={}", clOrdId, qty, price);

        if (!orderBook.addOrder(order)) {
            log.error("Failed to add order to book: duplicate orderId {}", orderId);
            return;
        }

        order.accept();
        ordersAccepted.incrementAndGet();

        // Send ExecutionReportNew
        sendExecutionReportNew(session, order);

        FillDecision decision = fillEngine.evaluate(order);
        if (decision.shouldFill()) {
            processFill(session, order, decision);
        }
    }

    private void handleCancelOrder(ILink3Session session, OrderCancelRequestMessage msg) {
        String clOrdId = msg.getClOrdId();

        log.debug("iLink3 Cancel: clOrdId={}", clOrdId);

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clOrdId, session.getSessionId());
        if (orderOpt.isEmpty()) {
            log.warn("Cancel for unknown iLink3 order: {}", clOrdId);
            return;
        }

        Order order = orderOpt.get();
        if (order.cancel()) {
            ordersCanceled.incrementAndGet();
            // Send cancel confirmation via ExecutionReportNew (simplified)
            log.debug("iLink3 Order Canceled: clOrdId={}", clOrdId);
        }
    }

    private void handleReplaceOrder(ILink3Session session, OrderCancelReplaceRequestMessage msg) {
        String clOrdId = msg.getClOrdId();
        long orderId = msg.getOrderId();

        log.debug("iLink3 Replace: clOrdId={}, orderId={}", clOrdId, orderId);

        Optional<Order> oldOrderOpt = orderBook.getOrder(orderId);
        if (oldOrderOpt.isEmpty()) {
            oldOrderOpt = orderBook.getOrderByClientId(clOrdId, session.getSessionId());
        }

        if (oldOrderOpt.isEmpty()) {
            log.warn("Replace for unknown iLink3 order: clOrdId={}, orderId={}", clOrdId, orderId);
            return;
        }

        Order oldOrder = oldOrderOpt.get();
        long newOrderId = orderIdGenerator.nextId();

        Order newOrder = Order.builder()
                .orderId(newOrderId)
                .clientOrderId(clOrdId)
                .symbol(oldOrder.getSymbol())
                .side(oldOrder.getSide())
                .orderType(oldOrder.getOrderType())
                .qty(msg.getOrderQty())
                .price(msg.getPrice())
                .sessionId(session.getSessionId())
                .protocol("ILINK3")
                .build();

        if (oldOrder.markReplaced()) {
            orderBook.addOrder(newOrder);
            newOrder.accept();

            sendExecutionReportNew(session, newOrder);
            log.debug("iLink3 Order Replaced: {} -> {}", oldOrder.getClientOrderId(), clOrdId);

            FillDecision decision = fillEngine.evaluate(newOrder);
            if (decision.shouldFill()) {
                processFill(session, newOrder, decision);
            }
        }
    }

    private void sendExecutionReportNew(ILink3Session session, Order order) {
        ExecutionReportNewMessage execReport = session.tryClaim(ExecutionReportNewMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReportNew for order {}", order.getOrderId());
            return;
        }

        try {
            execReport.writeHeader();
            execReport.setSeqNum((int) session.getOutboundSeqNum())
                     .setUuid(session.getUuid())
                     .setClOrdId(order.getClientOrderId())
                     .setOrderId(order.getOrderId())
                     .setPrice(order.getPrice())
                     .setOrderQty((int) order.getOriginalQty())
                     .setLeavesQty((int) order.getLeavesQty())
                     .setCumQty((int) order.getFilledQty())
                     .setTransactTime(System.nanoTime())
                     .setSendingTimeEpoch(System.nanoTime());

            session.commit(execReport);
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReportNew", e);
        }
    }

    private void processFill(ILink3Session session, Order order, FillDecision decision) {
        long execId = execIdCounter.getAndIncrement();

        if (order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ordersFilled.incrementAndGet();
            // Send fill via ExecutionReportNew with updated cumQty/leavesQty
            sendExecutionReportNew(session, order);
            log.debug("iLink3 Order Filled: clOrdId={}, qty={}, price={}",
                    order.getClientOrderId(), decision.getFillQty(), decision.getFillPrice());
        }
    }

    // Statistics getters
    public long getOrdersReceived() { return ordersReceived.get(); }
    public long getOrdersAccepted() { return ordersAccepted.get(); }
    public long getOrdersFilled() { return ordersFilled.get(); }
    public long getOrdersCanceled() { return ordersCanceled.get(); }
}
