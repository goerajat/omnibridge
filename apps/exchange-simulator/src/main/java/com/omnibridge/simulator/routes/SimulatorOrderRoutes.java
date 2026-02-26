package com.omnibridge.simulator.routes;

import com.omnibridge.admin.routes.RouteProvider;
import com.omnibridge.apps.common.demo.DemoOrderTracker;
import com.omnibridge.apps.common.demo.MessageEvent;
import com.omnibridge.apps.common.demo.TrackedOrder;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderBook;
import com.omnibridge.simulator.core.order.OrderState;
import com.omnibridge.simulator.response.FixResponseSender;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * REST API routes for exchange simulator order management.
 * Provides order listing, inspection, and manual action endpoints (fill, reject, cancel).
 */
public class SimulatorOrderRoutes implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatorOrderRoutes.class);

    private final OrderBook orderBook;
    private final DemoOrderTracker tracker;
    private final FixResponseSender responseSender;
    private final Supplier<FixEngine> fixEngineSupplier;
    private final AtomicLong execIdCounter = new AtomicLong(100000);

    public SimulatorOrderRoutes(OrderBook orderBook, DemoOrderTracker tracker,
                                FixResponseSender responseSender,
                                Supplier<FixEngine> fixEngineSupplier) {
        this.orderBook = orderBook;
        this.tracker = tracker;
        this.responseSender = responseSender;
        this.fixEngineSupplier = fixEngineSupplier;
    }

    @Override
    public String getBasePath() {
        return "/orders";
    }

    @Override
    public void registerRoutes(Javalin app, String contextPath) {
        String base = contextPath + getBasePath();

        app.get(base, this::listOrders);
        app.get(base + "/stats", this::getStats);
        app.get(base + "/capabilities", this::getCapabilities);
        app.get(base + "/{clOrdId}", this::getOrder);
        app.post(base + "/{clOrdId}/reject", this::rejectOrder);
        app.post(base + "/{clOrdId}/fill", this::fillOrder);
        app.post(base + "/{clOrdId}/partial-fill", this::partialFillOrder);
        app.post(base + "/{clOrdId}/cancel", this::cancelOrder);
    }

    private void listOrders(Context ctx) {
        List<TrackedOrder> orders = tracker.getAllOrders();

        // Apply filters
        String session = ctx.queryParam("session");
        String state = ctx.queryParam("state");
        String symbol = ctx.queryParam("symbol");

        if (session != null) {
            orders = orders.stream().filter(o -> session.equals(o.getSessionId())).collect(Collectors.toList());
        }
        if (state != null) {
            orders = orders.stream().filter(o -> state.equalsIgnoreCase(o.getState())).collect(Collectors.toList());
        }
        if (symbol != null) {
            orders = orders.stream().filter(o -> symbol.equalsIgnoreCase(o.getSymbol())).collect(Collectors.toList());
        }

        ctx.json(orders);
    }

    private void getStats(Context ctx) {
        ctx.json(tracker.getStats());
    }

    private void getCapabilities(Context ctx) {
        ctx.json(Map.of(
                "canSendOrders", false,
                "canManageOrders", true
        ));
    }

    private void getOrder(Context ctx) {
        String clOrdId = ctx.pathParam("clOrdId");
        TrackedOrder order = tracker.getOrder(clOrdId);
        if (order == null) {
            ctx.status(404).json(Map.of("error", "Order not found: " + clOrdId));
            return;
        }
        ctx.json(order);
    }

    private void rejectOrder(Context ctx) {
        String clOrdId = ctx.pathParam("clOrdId");
        Map<String, Object> body = parseBody(ctx);
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null) reason = "Manual rejection";

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clOrdId);
        if (orderOpt.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Order not found in order book: " + clOrdId));
            return;
        }

        Order order = orderOpt.get();
        FixSession session = findSession(order.getSessionId());
        if (session == null) {
            ctx.status(500).json(Map.of("error", "Session not found: " + order.getSessionId()));
            return;
        }

        if (!order.reject(reason)) {
            ctx.status(409).json(Map.of("error", "Cannot reject order in state: " + order.getState()));
            return;
        }

        responseSender.sendOrderRejected(session, order, reason);

        // Update tracker
        tracker.updateOrder(clOrdId, order.getState().name(), order.getFilledQty(),
                order.getLeavesQty(), order.getAvgFillPrice());
        tracker.addMessage(clOrdId, new MessageEvent(
                System.currentTimeMillis(), "SENT", FixTags.MsgTypes.ExecutionReport,
                "ExecutionReport (Rejected)", Map.of("ExecType", "REJECTED", "Text", reason),
                ""));

        ctx.json(tracker.getOrder(clOrdId));
        log.info("Manually rejected order: {}", clOrdId);
    }

    private void fillOrder(Context ctx) {
        String clOrdId = ctx.pathParam("clOrdId");
        Map<String, Object> body = parseBody(ctx);

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clOrdId);
        if (orderOpt.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Order not found in order book: " + clOrdId));
            return;
        }

        Order order = orderOpt.get();
        double fillPrice = body != null && body.containsKey("price")
                ? ((Number) body.get("price")).doubleValue() : order.getPrice();
        double fillQty = order.getLeavesQty();

        FixSession session = findSession(order.getSessionId());
        if (session == null) {
            ctx.status(500).json(Map.of("error", "Session not found: " + order.getSessionId()));
            return;
        }

        FillDecision decision = FillDecision.fullFill(fillQty, fillPrice);
        long execId = execIdCounter.getAndIncrement();

        if (!order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ctx.status(409).json(Map.of("error", "Cannot fill order in state: " + order.getState()));
            return;
        }

        responseSender.sendFill(session, order, decision, execId);

        tracker.updateOrder(clOrdId, order.getState().name(), order.getFilledQty(),
                order.getLeavesQty(), order.getAvgFillPrice());
        tracker.addMessage(clOrdId, new MessageEvent(
                System.currentTimeMillis(), "SENT", FixTags.MsgTypes.ExecutionReport,
                "ExecutionReport (Fill)",
                Map.of("ExecType", "FILL", "LastQty", String.valueOf(fillQty),
                        "LastPx", String.valueOf(fillPrice)),
                ""));

        ctx.json(tracker.getOrder(clOrdId));
        log.info("Manually filled order: {} qty={} price={}", clOrdId, fillQty, fillPrice);
    }

    private void partialFillOrder(Context ctx) {
        String clOrdId = ctx.pathParam("clOrdId");
        Map<String, Object> body = parseBody(ctx);

        if (body == null || !body.containsKey("qty")) {
            ctx.status(400).json(Map.of("error", "Missing required field: qty"));
            return;
        }

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clOrdId);
        if (orderOpt.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Order not found in order book: " + clOrdId));
            return;
        }

        Order order = orderOpt.get();
        double fillQty = ((Number) body.get("qty")).doubleValue();
        double fillPrice = body.containsKey("price")
                ? ((Number) body.get("price")).doubleValue() : order.getPrice();

        if (fillQty > order.getLeavesQty()) {
            ctx.status(400).json(Map.of("error",
                    "Fill qty " + fillQty + " exceeds leaves qty " + order.getLeavesQty()));
            return;
        }

        FixSession session = findSession(order.getSessionId());
        if (session == null) {
            ctx.status(500).json(Map.of("error", "Session not found: " + order.getSessionId()));
            return;
        }

        FillDecision decision = FillDecision.partialFill(fillQty, fillPrice);
        long execId = execIdCounter.getAndIncrement();

        if (!order.fill(decision.getFillQty(), decision.getFillPrice())) {
            ctx.status(409).json(Map.of("error", "Cannot fill order in state: " + order.getState()));
            return;
        }

        responseSender.sendFill(session, order, decision, execId);

        tracker.updateOrder(clOrdId, order.getState().name(), order.getFilledQty(),
                order.getLeavesQty(), order.getAvgFillPrice());
        tracker.addMessage(clOrdId, new MessageEvent(
                System.currentTimeMillis(), "SENT", FixTags.MsgTypes.ExecutionReport,
                "ExecutionReport (Partial Fill)",
                Map.of("ExecType", "PARTIAL_FILL", "LastQty", String.valueOf(fillQty),
                        "LastPx", String.valueOf(fillPrice)),
                ""));

        ctx.json(tracker.getOrder(clOrdId));
        log.info("Manually partial filled order: {} qty={} price={}", clOrdId, fillQty, fillPrice);
    }

    private void cancelOrder(Context ctx) {
        String clOrdId = ctx.pathParam("clOrdId");

        Optional<Order> orderOpt = orderBook.getOrderByClientId(clOrdId);
        if (orderOpt.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Order not found in order book: " + clOrdId));
            return;
        }

        Order order = orderOpt.get();
        FixSession session = findSession(order.getSessionId());
        if (session == null) {
            ctx.status(500).json(Map.of("error", "Session not found: " + order.getSessionId()));
            return;
        }

        if (!order.cancel()) {
            ctx.status(409).json(Map.of("error", "Cannot cancel order in state: " + order.getState()));
            return;
        }

        responseSender.sendOrderCanceled(session, order, clOrdId);

        tracker.updateOrder(clOrdId, order.getState().name(), order.getFilledQty(),
                order.getLeavesQty(), order.getAvgFillPrice());
        tracker.addMessage(clOrdId, new MessageEvent(
                System.currentTimeMillis(), "SENT", FixTags.MsgTypes.ExecutionReport,
                "ExecutionReport (Canceled)",
                Map.of("ExecType", "CANCELED"),
                ""));

        ctx.json(tracker.getOrder(clOrdId));
        log.info("Manually canceled order: {}", clOrdId);
    }

    private FixSession findSession(String sessionId) {
        try {
            FixEngine engine = fixEngineSupplier.get();
            return engine.getSession(sessionId);
        } catch (Exception e) {
            log.error("Failed to find session: {}", sessionId, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Context ctx) {
        try {
            String bodyStr = ctx.body();
            if (bodyStr == null || bodyStr.isBlank()) return null;
            return ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getDescription() {
        return "Exchange Simulator Order Management API";
    }
}
