package com.omnibridge.simulator.handler;

import com.omnibridge.apps.common.demo.DemoOrderTracker;
import com.omnibridge.apps.common.demo.MessageEvent;
import com.omnibridge.apps.common.demo.TrackedOrder;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.simulator.adapter.FixOrderAdapter;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.fill.FillEngine;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderBook;
import com.omnibridge.simulator.core.order.OrderIdGenerator;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;
import com.omnibridge.simulator.response.FixResponseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Protocol handler for FIX messages.
 * Handles NewOrderSingle, OrderCancelRequest, and OrderCancelReplaceRequest.
 */
public class FixProtocolHandler implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(FixProtocolHandler.class);

    private final OrderBook orderBook;
    private final FillEngine fillEngine;
    private final OrderIdGenerator orderIdGenerator;
    private final FixOrderAdapter adapter;
    private final FixResponseSender responseSender;
    private final AtomicLong execIdCounter = new AtomicLong(1);

    // Demo order tracker (nullable, for demo UI)
    private DemoOrderTracker tracker;

    // Statistics
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersCanceled = new AtomicLong(0);
    private final AtomicLong ordersReplaced = new AtomicLong(0);

    public FixProtocolHandler(OrderBook orderBook, FillEngine fillEngine, OrderIdGenerator orderIdGenerator) {
        this.orderBook = orderBook;
        this.fillEngine = fillEngine;
        this.orderIdGenerator = orderIdGenerator;
        this.adapter = new FixOrderAdapter();
        this.responseSender = new FixResponseSender();
    }

    public void setTracker(DemoOrderTracker tracker) {
        this.tracker = tracker;
    }

    public FixResponseSender getResponseSender() {
        return responseSender;
    }

    @Override
    public void onMessage(FixSession session, IncomingFixMessage message) {
        String msgType = message.getMsgType();

        switch (msgType) {
            case FixTags.MsgTypes.NewOrderSingle:
                handleNewOrder(session, message);
                break;
            case FixTags.MsgTypes.OrderCancelRequest:
                handleCancelOrder(session, message);
                break;
            case FixTags.MsgTypes.OrderCancelReplaceRequest:
                handleReplaceOrder(session, message);
                break;
            default:
                log.debug("Ignoring message type: {}", msgType);
        }
    }

    private void handleNewOrder(FixSession session, IncomingFixMessage message) {
        ordersReceived.incrementAndGet();

        long orderId = orderIdGenerator.nextId();
        Order order = adapter.adapt(message, session, orderId);

        log.debug("New FIX order: clOrdId={}, symbol={}, side={}, qty={}, price={}",
                order.getClientOrderId(), order.getSymbol(), order.getSide(),
                order.getOriginalQty(), order.getPrice());

        // Track incoming message
        if (tracker != null && tracker.isEnabled()) {
            TrackedOrder tracked = new TrackedOrder(
                    order.getClientOrderId(), orderId, order.getSymbol(),
                    order.getSide().name(), order.getOrderType().name(),
                    order.getOriginalQty(), order.getPrice(), "PENDING",
                    session.getConfig().getSessionId());
            tracker.trackOrder(tracked);
            tracker.addMessage(order.getClientOrderId(), new MessageEvent(
                    System.currentTimeMillis(), "RECEIVED",
                    FixTags.MsgTypes.NewOrderSingle, "NewOrderSingle",
                    extractFields(message), message.toString()));
        }

        // Add to order book
        if (!orderBook.addOrder(order)) {
            log.error("Failed to add order to book: duplicate orderId {}", orderId);
            return;
        }

        // Accept the order
        order.accept();
        ordersAccepted.incrementAndGet();

        // Send order accepted
        responseSender.sendOrderAccepted(session, order);

        // Track acceptance
        if (tracker != null && tracker.isEnabled()) {
            tracker.updateOrder(order.getClientOrderId(), order.getState().name(),
                    order.getFilledQty(), order.getLeavesQty(), order.getAvgFillPrice());
            tracker.addMessage(order.getClientOrderId(), new MessageEvent(
                    System.currentTimeMillis(), "SENT",
                    FixTags.MsgTypes.ExecutionReport, "ExecutionReport (New)",
                    Map.of("ExecType", "NEW", "OrdStatus", "NEW"),
                    ""));
        }

        // Evaluate for fill
        FillDecision decision = fillEngine.evaluate(order);
        if (decision.shouldFill()) {
            processFill(session, order, decision);
        }
    }

    private void handleCancelOrder(FixSession session, IncomingFixMessage message) {
        String clOrdId = adapter.getClientOrderId(message);
        String origClOrdId = adapter.getOrigClientOrderId(message);

        log.debug("Cancel request: clOrdId={}, origClOrdId={}", clOrdId, origClOrdId);

        // Track incoming cancel request
        if (tracker != null && tracker.isEnabled()) {
            tracker.addMessage(origClOrdId, new MessageEvent(
                    System.currentTimeMillis(), "RECEIVED",
                    FixTags.MsgTypes.OrderCancelRequest, "OrderCancelRequest",
                    extractFields(message), message.toString()));
        }

        Optional<Order> orderOpt = orderBook.getOrderByClientId(origClOrdId, session.getConfig().getSessionId());
        if (orderOpt.isEmpty()) {
            log.warn("Cancel request for unknown order: {}", origClOrdId);
            return;
        }

        Order order = orderOpt.get();
        if (order.cancel()) {
            ordersCanceled.incrementAndGet();
            responseSender.sendOrderCanceled(session, order, clOrdId);
            log.debug("Order canceled: {}", origClOrdId);

            if (tracker != null && tracker.isEnabled()) {
                tracker.updateOrder(origClOrdId, order.getState().name(),
                        order.getFilledQty(), order.getLeavesQty(), order.getAvgFillPrice());
                tracker.addMessage(origClOrdId, new MessageEvent(
                        System.currentTimeMillis(), "SENT",
                        FixTags.MsgTypes.ExecutionReport, "ExecutionReport (Canceled)",
                        Map.of("ExecType", "CANCELED"),
                        ""));
            }
        } else {
            log.warn("Cannot cancel order in state {}: {}", order.getState(), origClOrdId);
        }
    }

    private void handleReplaceOrder(FixSession session, IncomingFixMessage message) {
        String clOrdId = adapter.getClientOrderId(message);
        String origClOrdId = adapter.getOrigClientOrderId(message);

        log.debug("Replace request: clOrdId={}, origClOrdId={}", clOrdId, origClOrdId);

        // Track incoming replace request
        if (tracker != null && tracker.isEnabled()) {
            tracker.addMessage(origClOrdId, new MessageEvent(
                    System.currentTimeMillis(), "RECEIVED",
                    FixTags.MsgTypes.OrderCancelReplaceRequest, "OrderCancelReplaceRequest",
                    extractFields(message), message.toString()));
        }

        Optional<Order> oldOrderOpt = orderBook.getOrderByClientId(origClOrdId, session.getConfig().getSessionId());
        if (oldOrderOpt.isEmpty()) {
            log.warn("Replace request for unknown order: {}", origClOrdId);
            return;
        }

        Order oldOrder = oldOrderOpt.get();

        // Create new order from replace message
        CharSequence symbol = message.getCharSequence(FixTags.Symbol);
        char side = message.getChar(FixTags.Side);
        int orderQty = message.getInt(FixTags.OrderQty);
        char ordType = message.getChar(FixTags.OrdType);
        double price = message.getDouble(FixTags.Price);

        long newOrderId = orderIdGenerator.nextId();
        Order newOrder = Order.builder()
                .orderId(newOrderId)
                .clientOrderId(clOrdId)
                .symbol(symbol != null ? symbol.toString() : oldOrder.getSymbol())
                .side(side != 0 ? OrderSide.fromFixValue(side) : oldOrder.getSide())
                .orderType(ordType != 0 ? OrderType.fromFixValue(ordType) : oldOrder.getOrderType())
                .qty(orderQty > 0 ? orderQty : oldOrder.getOriginalQty())
                .price(price > 0 ? price : oldOrder.getPrice())
                .sessionId(session.getConfig().getSessionId())
                .protocol("FIX")
                .build();

        if (oldOrder.markReplaced()) {
            orderBook.addOrder(newOrder);
            newOrder.accept();
            ordersReplaced.incrementAndGet();

            responseSender.sendOrderReplaced(session, oldOrder, newOrder);
            log.debug("Order replaced: {} -> {}", origClOrdId, clOrdId);

            // Track the replacement
            if (tracker != null && tracker.isEnabled()) {
                tracker.updateOrder(origClOrdId, "REPLACED",
                        oldOrder.getFilledQty(), oldOrder.getLeavesQty(), oldOrder.getAvgFillPrice());
                tracker.addMessage(origClOrdId, new MessageEvent(
                        System.currentTimeMillis(), "SENT",
                        FixTags.MsgTypes.ExecutionReport, "ExecutionReport (Replaced)",
                        Map.of("ExecType", "REPLACED", "NewClOrdId", clOrdId),
                        ""));

                // Track new order
                TrackedOrder tracked = new TrackedOrder(
                        clOrdId, newOrderId, newOrder.getSymbol(),
                        newOrder.getSide().name(), newOrder.getOrderType().name(),
                        newOrder.getOriginalQty(), newOrder.getPrice(), "NEW",
                        session.getConfig().getSessionId());
                tracker.trackOrder(tracked);
            }

            // Evaluate new order for fill
            FillDecision decision = fillEngine.evaluate(newOrder);
            if (decision.shouldFill()) {
                processFill(session, newOrder, decision);
            }
        } else {
            log.warn("Cannot replace order in state {}: {}", oldOrder.getState(), origClOrdId);
        }
    }

    private void processFill(FixSession session, Order order, FillDecision decision) {
        long execId = execIdCounter.getAndIncrement();

        // Apply fill to order
        if (order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ordersFilled.incrementAndGet();
            responseSender.sendFill(session, order, decision, execId);
            log.debug("Order filled: clOrdId={}, qty={}, price={}",
                    order.getClientOrderId(), decision.getFillQty(), decision.getFillPrice());

            if (tracker != null && tracker.isEnabled()) {
                String execType = decision.isFullFill() ? "FILL" : "PARTIAL_FILL";
                tracker.updateOrder(order.getClientOrderId(), order.getState().name(),
                        order.getFilledQty(), order.getLeavesQty(), order.getAvgFillPrice());
                tracker.addMessage(order.getClientOrderId(), new MessageEvent(
                        System.currentTimeMillis(), "SENT",
                        FixTags.MsgTypes.ExecutionReport, "ExecutionReport (" + execType + ")",
                        Map.of("ExecType", execType,
                                "LastQty", String.valueOf(decision.getFillQty()),
                                "LastPx", String.valueOf(decision.getFillPrice())),
                        ""));
            }
        }
    }

    @Override
    public void onReject(FixSession session, int refSeqNum, String refMsgType, int rejectReason, String text) {
        log.warn("Message {} (type {}) rejected: {} - {}", refSeqNum, refMsgType, rejectReason, text);
    }

    private Map<String, String> extractFields(IncomingFixMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        putField(fields, "ClOrdID", message.getCharSequence(FixTags.ClOrdID));
        putField(fields, "Symbol", message.getCharSequence(FixTags.Symbol));
        char side = message.getChar(FixTags.Side);
        if (side != 0) fields.put("Side", side == FixTags.SIDE_BUY ? "BUY" : "SELL");
        int qty = message.getInt(FixTags.OrderQty);
        if (qty > 0) fields.put("OrderQty", String.valueOf(qty));
        double price = message.getDouble(FixTags.Price);
        if (price > 0) fields.put("Price", String.valueOf(price));
        char ordType = message.getChar(FixTags.OrdType);
        if (ordType != 0) fields.put("OrdType", ordType == FixTags.ORD_TYPE_LIMIT ? "LIMIT" : "MARKET");
        putField(fields, "OrigClOrdID", message.getCharSequence(41));
        return fields;
    }

    private void putField(Map<String, String> fields, String name, CharSequence value) {
        if (value != null) fields.put(name, value.toString());
    }

    // Statistics getters
    public long getOrdersReceived() { return ordersReceived.get(); }
    public long getOrdersAccepted() { return ordersAccepted.get(); }
    public long getOrdersFilled() { return ordersFilled.get(); }
    public long getOrdersCanceled() { return ordersCanceled.get(); }
    public long getOrdersReplaced() { return ordersReplaced.get(); }
}
