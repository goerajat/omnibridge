package com.omnibridge.simulator.handler;

import com.omnibridge.pillar.engine.PillarSession;
import com.omnibridge.pillar.message.order.NewOrderMessage;
import com.omnibridge.pillar.message.order.CancelOrderMessage;
import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.simulator.adapter.PillarOrderAdapter;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.fill.FillEngine;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderBook;
import com.omnibridge.simulator.core.order.OrderIdGenerator;
import com.omnibridge.simulator.response.PillarResponseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Protocol handler for NYSE Pillar messages.
 */
public class PillarProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(PillarProtocolHandler.class);

    private final OrderBook orderBook;
    private final FillEngine fillEngine;
    private final OrderIdGenerator orderIdGenerator;
    private final PillarOrderAdapter adapter;
    private final PillarResponseSender responseSender;
    private final AtomicLong execIdCounter = new AtomicLong(1);

    // Statistics
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersCanceled = new AtomicLong(0);

    public PillarProtocolHandler(OrderBook orderBook, FillEngine fillEngine, OrderIdGenerator orderIdGenerator) {
        this.orderBook = orderBook;
        this.fillEngine = fillEngine;
        this.orderIdGenerator = orderIdGenerator;
        this.adapter = new PillarOrderAdapter();
        this.responseSender = new PillarResponseSender();
    }

    public void onMessage(PillarSession session, SbeMessage message) {
        if (message instanceof NewOrderMessage order) {
            handleNewOrder(session, order);
        } else if (message instanceof CancelOrderMessage cancel) {
            handleCancelOrder(session, cancel);
        }
    }

    private void handleNewOrder(PillarSession session, NewOrderMessage msg) {
        ordersReceived.incrementAndGet();

        long orderId = orderIdGenerator.nextId();
        Order order = adapter.adapt(msg, session, orderId);

        log.debug("New Pillar order: clOrdId={}, symbol={}, qty={}, price={}",
                order.getClientOrderId(), order.getSymbol(), order.getOriginalQty(), order.getPrice());

        if (!orderBook.addOrder(order)) {
            log.error("Failed to add order to book: duplicate orderId {}", orderId);
            return;
        }

        order.accept();
        ordersAccepted.incrementAndGet();

        if (responseSender.sendOrderAccepted(session, order)) {
            log.debug("Pillar Order Accepted: clOrdId={}", order.getClientOrderId());
        }

        FillDecision decision = fillEngine.evaluate(order);
        if (decision.shouldFill()) {
            processFill(session, order, decision);
        }
    }

    private void handleCancelOrder(PillarSession session, CancelOrderMessage msg) {
        String clOrdId = String.valueOf(msg.getClOrdId());

        log.debug("Pillar Cancel: clOrdId={}", clOrdId);

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clOrdId, session.getSessionId());
        if (orderOpt.isEmpty()) {
            log.warn("Cancel for unknown Pillar order: {}", clOrdId);
            return;
        }

        Order order = orderOpt.get();
        if (order.cancel()) {
            ordersCanceled.incrementAndGet();
            responseSender.sendOrderCanceled(session, order, clOrdId);
            log.debug("Pillar Order Canceled: clOrdId={}", clOrdId);
        }
    }

    private void processFill(PillarSession session, Order order, FillDecision decision) {
        long execId = execIdCounter.getAndIncrement();

        if (order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ordersFilled.incrementAndGet();
            responseSender.sendFill(session, order, decision, execId);
            log.debug("Pillar Order Filled: clOrdId={}, qty={}, price={}",
                    order.getClientOrderId(), decision.getFillQty(), decision.getFillPrice());
        }
    }

    // Statistics getters
    public long getOrdersReceived() { return ordersReceived.get(); }
    public long getOrdersAccepted() { return ordersAccepted.get(); }
    public long getOrdersFilled() { return ordersFilled.get(); }
    public long getOrdersCanceled() { return ordersCanceled.get(); }
}
