package com.omnibridge.apps.fix.initiator.routes;

import com.omnibridge.admin.routes.RouteProvider;
import com.omnibridge.apps.common.demo.DemoOrderTracker;
import com.omnibridge.apps.common.demo.MessageEvent;
import com.omnibridge.apps.common.demo.TrackedOrder;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * REST API routes for FIX initiator order management.
 * Provides order listing, inspection, and order submission.
 */
public class InitiatorOrderRoutes implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(InitiatorOrderRoutes.class);

    private final DemoOrderTracker tracker;
    private final Supplier<FixSession> sessionSupplier;
    private final AtomicLong clOrdIdCounter = new AtomicLong(1);

    public InitiatorOrderRoutes(DemoOrderTracker tracker, Supplier<FixSession> sessionSupplier) {
        this.tracker = tracker;
        this.sessionSupplier = sessionSupplier;
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
        app.post(base, this::submitOrder);
    }

    private void listOrders(Context ctx) {
        List<TrackedOrder> orders = tracker.getAllOrders();

        String session = ctx.queryParam("session");
        String state = ctx.queryParam("state");

        if (session != null) {
            orders = orders.stream().filter(o -> session.equals(o.getSessionId())).collect(Collectors.toList());
        }
        if (state != null) {
            orders = orders.stream().filter(o -> state.equalsIgnoreCase(o.getState())).collect(Collectors.toList());
        }

        ctx.json(orders);
    }

    private void getStats(Context ctx) {
        ctx.json(tracker.getStats());
    }

    private void getCapabilities(Context ctx) {
        ctx.json(Map.of(
                "canSendOrders", true,
                "canManageOrders", false
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

    @SuppressWarnings("unchecked")
    private void submitOrder(Context ctx) {
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }

        String symbol = (String) body.get("symbol");
        String sideStr = (String) body.get("side");
        Number qtyNum = (Number) body.get("qty");
        Number priceNum = (Number) body.get("price");
        String orderTypeStr = (String) body.getOrDefault("orderType", "LIMIT");

        if (symbol == null || sideStr == null || qtyNum == null) {
            ctx.status(400).json(Map.of("error", "Required fields: symbol, side, qty"));
            return;
        }

        char side;
        if ("BUY".equalsIgnoreCase(sideStr)) {
            side = FixTags.SIDE_BUY;
        } else if ("SELL".equalsIgnoreCase(sideStr)) {
            side = FixTags.SIDE_SELL;
        } else {
            ctx.status(400).json(Map.of("error", "Invalid side: " + sideStr + " (expected BUY or SELL)"));
            return;
        }

        char ordType;
        if ("MARKET".equalsIgnoreCase(orderTypeStr)) {
            ordType = FixTags.ORD_TYPE_MARKET;
        } else {
            ordType = FixTags.ORD_TYPE_LIMIT;
        }

        int qty = qtyNum.intValue();
        double price = priceNum != null ? priceNum.doubleValue() : 0;
        String clOrdId = "DEMO" + clOrdIdCounter.getAndIncrement();

        FixSession session = sessionSupplier.get();
        if (session == null) {
            ctx.status(503).json(Map.of("error", "No FIX session available"));
            return;
        }

        try {
            RingBufferOutgoingMessage msg = session.tryClaimMessage(FixTags.MsgTypes.NewOrderSingle);
            if (msg == null) {
                ctx.status(503).json(Map.of("error", "Ring buffer full, try again"));
                return;
            }

            msg.setField(FixTags.ClOrdID, clOrdId);
            msg.setField(FixTags.Symbol, symbol);
            msg.setField(FixTags.Side, side);
            msg.setField(FixTags.OrderQty, qty);
            msg.setField(FixTags.OrdType, ordType);
            if (price > 0) {
                msg.setField(FixTags.Price, price, 2);
            }
            msg.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
            msg.setField(FixTags.TransactTime, Instant.now().toString());

            session.commitMessage(msg);

            // Track the order
            TrackedOrder tracked = new TrackedOrder(
                    clOrdId, 0, symbol, sideStr.toUpperCase(),
                    orderTypeStr.toUpperCase(), qty, price, "PENDING_NEW",
                    session.getConfig().getSessionId());
            tracker.trackOrder(tracked);

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ClOrdID", clOrdId);
            fields.put("Symbol", symbol);
            fields.put("Side", sideStr.toUpperCase());
            fields.put("OrderQty", String.valueOf(qty));
            fields.put("Price", String.valueOf(price));
            fields.put("OrdType", orderTypeStr.toUpperCase());

            tracker.addMessage(clOrdId, new MessageEvent(
                    System.currentTimeMillis(), "SENT",
                    FixTags.MsgTypes.NewOrderSingle, "NewOrderSingle",
                    fields, ""));

            ctx.json(Map.of("clOrdId", clOrdId, "status", "sent"));
            log.info("Demo order submitted: {} {} {} {} @ {}", clOrdId, sideStr, qty, symbol, price);
        } catch (Exception e) {
            log.error("Failed to submit order", e);
            ctx.status(500).json(Map.of("error", "Failed to submit order: " + e.getMessage()));
        }
    }

    @Override
    public String getDescription() {
        return "FIX Initiator Order Management API";
    }
}
