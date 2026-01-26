package com.omnibridge.simulator.handler;

import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.message.*;
import com.omnibridge.ouch.message.v50.*;
import com.omnibridge.simulator.adapter.OuchOrderAdapter;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.fill.FillEngine;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderBook;
import com.omnibridge.simulator.core.order.OrderIdGenerator;
import com.omnibridge.simulator.response.OuchResponseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Protocol handler for OUCH messages.
 * Handles both OUCH 4.2 and 5.0 protocols.
 */
public class OuchProtocolHandler implements OuchMessageListener<OuchSession> {

    private static final Logger log = LoggerFactory.getLogger(OuchProtocolHandler.class);

    private final OrderBook orderBook;
    private final FillEngine fillEngine;
    private final OrderIdGenerator orderIdGenerator;
    private final OuchOrderAdapter adapter;
    private final OuchResponseSender responseSender;
    private final AtomicLong matchNumberCounter = new AtomicLong(1);

    // Statistics
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersCanceled = new AtomicLong(0);
    private final AtomicLong ordersReplaced = new AtomicLong(0);

    public OuchProtocolHandler(OrderBook orderBook, FillEngine fillEngine, OrderIdGenerator orderIdGenerator) {
        this.orderBook = orderBook;
        this.fillEngine = fillEngine;
        this.orderIdGenerator = orderIdGenerator;
        this.adapter = new OuchOrderAdapter();
        this.responseSender = new OuchResponseSender();
    }

    // ==================== OUCH 4.2 Handlers ====================

    @Override
    public void onEnterOrder(OuchSession session, EnterOrderMessage msg) {
        ordersReceived.incrementAndGet();

        long orderId = orderIdGenerator.nextId();
        Order order = adapter.adapt(msg, session, orderId);

        log.debug("New OUCH order: token={}, symbol={}, side={}, qty={}, price={}",
                order.getClientOrderId(), order.getSymbol(), order.getSide(),
                order.getOriginalQty(), order.getPrice());

        if (!orderBook.addOrder(order)) {
            log.error("Failed to add order to book: duplicate orderId {}", orderId);
            return;
        }

        order.accept();
        ordersAccepted.incrementAndGet();

        if (responseSender.sendOrderAccepted(session, order)) {
            log.debug("OUCH Order Accepted: token={}", order.getClientOrderId());
        }

        // Evaluate for fill
        FillDecision decision = fillEngine.evaluate(order);
        if (decision.shouldFill()) {
            processFill(session, order, decision);
        }
    }

    @Override
    public void onCancelOrder(OuchSession session, CancelOrderMessage msg) {
        String token = msg.getOrderToken();

        log.debug("OUCH Cancel: token={}", token);

        Optional<Order> orderOpt = orderBook.getOrderByClientId(token, session.getSessionId());
        if (orderOpt.isEmpty()) {
            log.warn("Cancel for unknown order: {}", token);
            return;
        }

        Order order = orderOpt.get();
        if (order.cancel()) {
            ordersCanceled.incrementAndGet();
            responseSender.sendOrderCanceled(session, order, token);
            log.debug("OUCH Order Canceled: token={}", token);
        }
    }

    @Override
    public void onReplaceOrder(OuchSession session, ReplaceOrderMessage msg) {
        String existingToken = msg.getExistingOrderToken();
        String newToken = msg.getReplacementOrderToken();

        log.debug("OUCH Replace: existingToken={}, newToken={}", existingToken, newToken);

        Optional<Order> oldOrderOpt = orderBook.getOrderByClientId(existingToken, session.getSessionId());
        if (oldOrderOpt.isEmpty()) {
            log.warn("Replace for unknown order: {}", existingToken);
            return;
        }

        Order oldOrder = oldOrderOpt.get();

        long newOrderId = orderIdGenerator.nextId();
        Order newOrder = Order.builder()
                .orderId(newOrderId)
                .clientOrderId(newToken)
                .symbol(oldOrder.getSymbol())
                .side(oldOrder.getSide())
                .orderType(oldOrder.getOrderType())
                .qty(msg.getShares())
                .price(msg.getPriceAsDouble())
                .sessionId(session.getSessionId())
                .protocol("OUCH")
                .build();

        if (oldOrder.markReplaced()) {
            orderBook.addOrder(newOrder);
            newOrder.accept();
            ordersReplaced.incrementAndGet();

            responseSender.sendOrderReplaced(session, oldOrder, newOrder);
            log.debug("OUCH Order Replaced: {} -> {}", existingToken, newToken);

            FillDecision decision = fillEngine.evaluate(newOrder);
            if (decision.shouldFill()) {
                processFill(session, newOrder, decision);
            }
        }
    }

    // ==================== OUCH 5.0 Handlers ====================

    @Override
    public void onEnterOrderV50(OuchSession session, V50EnterOrderMessage msg) {
        ordersReceived.incrementAndGet();

        long orderId = orderIdGenerator.nextId();
        Order order = adapter.adaptV50(msg, session, orderId);

        log.debug("New OUCH 5.0 order: userRef={}, symbol={}, side={}, qty={}, price={}",
                order.getClientOrderId(), order.getSymbol(), order.getSide(),
                order.getOriginalQty(), order.getPrice());

        if (!orderBook.addOrder(order)) {
            log.error("Failed to add order to book: duplicate orderId {}", orderId);
            return;
        }

        order.accept();
        ordersAccepted.incrementAndGet();

        if (responseSender.sendOrderAccepted(session, order)) {
            log.debug("OUCH 5.0 Order Accepted: userRef={}", order.getClientOrderId());
        }

        FillDecision decision = fillEngine.evaluate(order);
        if (decision.shouldFill()) {
            processFill(session, order, decision);
        }
    }

    @Override
    public void onCancelOrderV50(OuchSession session, V50CancelOrderMessage msg) {
        String token = String.valueOf(msg.getUserRefNum());

        log.debug("OUCH 5.0 Cancel: userRef={}", token);

        Optional<Order> orderOpt = orderBook.getOrderByClientId(token, session.getSessionId());
        if (orderOpt.isEmpty()) {
            log.warn("Cancel for unknown order (V50): {}", token);
            return;
        }

        Order order = orderOpt.get();
        if (order.cancel()) {
            ordersCanceled.incrementAndGet();
            responseSender.sendOrderCanceled(session, order, token);
            log.debug("OUCH 5.0 Order Canceled: userRef={}", token);
        }
    }

    @Override
    public void onReplaceOrderV50(OuchSession session, V50ReplaceOrderMessage msg) {
        String existingToken = String.valueOf(msg.getExistingUserRefNum());
        String newToken = String.valueOf(msg.getUserRefNum());

        log.debug("OUCH 5.0 Replace: existingUserRef={}, newUserRef={}", existingToken, newToken);

        Optional<Order> oldOrderOpt = orderBook.getOrderByClientId(existingToken, session.getSessionId());
        if (oldOrderOpt.isEmpty()) {
            log.warn("Replace for unknown order (V50): {}", existingToken);
            return;
        }

        Order oldOrder = oldOrderOpt.get();

        long newOrderId = orderIdGenerator.nextId();
        Order newOrder = Order.builder()
                .orderId(newOrderId)
                .clientOrderId(newToken)
                .symbol(oldOrder.getSymbol())
                .side(oldOrder.getSide())
                .orderType(oldOrder.getOrderType())
                .qty(msg.getQuantity())
                .price(msg.getPriceAsDouble())
                .sessionId(session.getSessionId())
                .protocol("OUCH50")
                .build();

        if (oldOrder.markReplaced()) {
            orderBook.addOrder(newOrder);
            newOrder.accept();
            ordersReplaced.incrementAndGet();

            responseSender.sendOrderReplaced(session, oldOrder, newOrder);
            log.debug("OUCH 5.0 Order Replaced: {} -> {}", existingToken, newToken);

            FillDecision decision = fillEngine.evaluate(newOrder);
            if (decision.shouldFill()) {
                processFill(session, newOrder, decision);
            }
        }
    }

    @Override
    public void onMessage(OuchSession session, OuchMessage message) {
        log.debug("Received OUCH message: {}", message.getMessageType());
    }

    private void processFill(OuchSession session, Order order, FillDecision decision) {
        long matchNumber = matchNumberCounter.getAndIncrement();

        if (order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ordersFilled.incrementAndGet();
            responseSender.sendFill(session, order, decision, matchNumber);
            log.debug("OUCH Order Filled: token={}, qty={}, price={}",
                    order.getClientOrderId(), decision.getFillQty(), decision.getFillPrice());
        }
    }

    // Statistics getters
    public long getOrdersReceived() { return ordersReceived.get(); }
    public long getOrdersAccepted() { return ordersAccepted.get(); }
    public long getOrdersFilled() { return ordersFilled.get(); }
    public long getOrdersCanceled() { return ordersCanceled.get(); }
    public long getOrdersReplaced() { return ordersReplaced.get(); }
}
