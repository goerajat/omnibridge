package com.omnibridge.apps.ouch.acceptor;

import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.message.*;
import com.omnibridge.ouch.message.v50.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Message handler for the OUCH acceptor (exchange simulator).
 *
 * <p>Handles incoming orders using the high-level OuchSession API.
 * Works with both OUCH 4.2 and 5.0 protocols - the version is determined
 * by the session configuration.</p>
 *
 * <p>This handler uses the type-safe OuchMessageListener interface to receive
 * messages without needing to understand the byte-level message layout.</p>
 */
public class AcceptorMessageHandler implements OuchMessageListener<OuchSession> {

    private static final Logger log = LoggerFactory.getLogger(AcceptorMessageHandler.class);

    private final double fillRate;
    private final boolean latencyMode;

    // Order tracking
    private final Map<String, OrderState> orders = new ConcurrentHashMap<>();
    private final AtomicLong orderRefNum = new AtomicLong(1);
    private final AtomicLong matchNumber = new AtomicLong(1);

    // Statistics
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersCanceled = new AtomicLong(0);
    private final AtomicLong ordersReplaced = new AtomicLong(0);

    public AcceptorMessageHandler(double fillRate, boolean latencyMode) {
        this.fillRate = fillRate;
        this.latencyMode = latencyMode;
    }

    @Override
    public void onEnterOrder(OuchSession session, EnterOrderMessage msg) {
        ordersReceived.incrementAndGet();

        String token = msg.getOrderToken();
        Side side = msg.getSide();
        String symbol = msg.getSymbol();
        int shares = msg.getShares();
        double price = msg.getPriceAsDouble();

        if (!latencyMode) {
            log.info("[{}] Enter Order: token={}, side={}, symbol={}, qty={}, price={}",
                    session.getSessionId(), token, side, symbol, shares, price);
        }

        // Track the order
        long orderRef = orderRefNum.getAndIncrement();
        OrderState orderState = new OrderState(token, symbol, side, shares, price, orderRef);
        orders.put(token, orderState);

        // Send Order Accepted acknowledgment
        boolean accepted = session.sendOrderAccepted(token, side, symbol, shares, price, orderRef);
        if (accepted) {
            ordersAccepted.incrementAndGet();
            if (!latencyMode) {
                log.info("[{}] Order Accepted: token={}, orderRef={}",
                        session.getSessionId(), token, orderRef);
            }
        } else {
            log.warn("[{}] Failed to send Order Accepted for token={}", session.getSessionId(), token);
        }

        // Simulate fill if fill rate > 0
        if (fillRate > 0 && Math.random() < fillRate) {
            long match = matchNumber.getAndIncrement();

            // Send Order Executed message
            boolean executed = session.sendOrderExecuted(token, shares, price, match);
            if (executed) {
                ordersFilled.incrementAndGet();
                orders.remove(token);
                if (!latencyMode) {
                    log.info("[{}] Order Filled: token={}, qty={}, price={}, match={}",
                            session.getSessionId(), token, shares, price, match);
                }
            } else {
                log.warn("[{}] Failed to send Order Executed for token={}", session.getSessionId(), token);
            }
        }
    }

    @Override
    public void onCancelOrder(OuchSession session, CancelOrderMessage msg) {
        String token = msg.getOrderToken();

        OrderState order = orders.remove(token);
        if (order != null) {
            ordersCanceled.incrementAndGet();

            if (!latencyMode) {
                log.info("[{}] Cancel Order: token={}", session.getSessionId(), token);
            }
        } else {
            log.warn("[{}] Cancel for unknown order: {}", session.getSessionId(), token);
        }
    }

    @Override
    public void onReplaceOrder(OuchSession session, ReplaceOrderMessage msg) {
        String existingToken = msg.getExistingOrderToken();
        String newToken = msg.getReplacementOrderToken();
        int newShares = msg.getShares();
        double newPrice = msg.getPriceAsDouble();

        OrderState oldOrder = orders.remove(existingToken);
        if (oldOrder != null) {
            ordersReplaced.incrementAndGet();

            long orderRef = orderRefNum.getAndIncrement();
            OrderState newOrder = new OrderState(newToken, oldOrder.symbol, oldOrder.side,
                    newShares, newPrice, orderRef);
            orders.put(newToken, newOrder);

            if (!latencyMode) {
                log.info("[{}] Replace Order: {} -> {}, qty={}, price={}",
                        session.getSessionId(), existingToken, newToken, newShares, newPrice);
            }
        } else {
            log.warn("[{}] Replace for unknown order: {}", session.getSessionId(), existingToken);
        }
    }

    @Override
    public void onMessage(OuchSession session, OuchMessage message) {
        // Generic handler for any message type not specifically handled above
        if (!latencyMode) {
            log.debug("[{}] Received message: {}", session.getSessionId(), message.getMessageType());
        }
    }

    // =====================================================
    // V50 callbacks
    // =====================================================

    @Override
    public void onEnterOrderV50(OuchSession session, V50EnterOrderMessage msg) {
        ordersReceived.incrementAndGet();

        long userRefNum = msg.getUserRefNum();
        String token = String.valueOf(userRefNum);  // Use UserRefNum as token string
        Side side = msg.getSide();
        String symbol = msg.getSymbol();
        int shares = msg.getQuantity();
        double price = msg.getPriceAsDouble();

        if (!latencyMode) {
            log.info("[{}] Enter Order (V50): userRef={}, side={}, symbol={}, qty={}, price={}",
                    session.getSessionId(), userRefNum, side, symbol, shares, price);
        }

        // Track the order
        long orderRef = orderRefNum.getAndIncrement();
        OrderState orderState = new OrderState(token, symbol, side, shares, price, orderRef);
        orders.put(token, orderState);

        // Send Order Accepted acknowledgment
        boolean accepted = session.sendOrderAccepted(token, side, symbol, shares, price, orderRef);
        if (accepted) {
            ordersAccepted.incrementAndGet();
            if (!latencyMode) {
                log.info("[{}] Order Accepted (V50): userRef={}, orderRef={}",
                        session.getSessionId(), userRefNum, orderRef);
            }
        } else {
            log.warn("[{}] Failed to send Order Accepted for userRef={}", session.getSessionId(), userRefNum);
        }

        // Simulate fill if fill rate > 0
        if (fillRate > 0 && Math.random() < fillRate) {
            long match = matchNumber.getAndIncrement();

            // Send Order Executed message
            boolean executed = session.sendOrderExecuted(token, shares, price, match);
            if (executed) {
                ordersFilled.incrementAndGet();
                orders.remove(token);
                if (!latencyMode) {
                    log.info("[{}] Order Filled (V50): userRef={}, qty={}, price={}, match={}",
                            session.getSessionId(), userRefNum, shares, price, match);
                }
            } else {
                log.warn("[{}] Failed to send Order Executed for userRef={}", session.getSessionId(), userRefNum);
            }
        }
    }

    @Override
    public void onCancelOrderV50(OuchSession session, V50CancelOrderMessage msg) {
        long userRefNum = msg.getUserRefNum();
        String token = String.valueOf(userRefNum);

        OrderState order = orders.remove(token);
        if (order != null) {
            ordersCanceled.incrementAndGet();

            if (!latencyMode) {
                log.info("[{}] Cancel Order (V50): userRef={}", session.getSessionId(), userRefNum);
            }
        } else {
            log.warn("[{}] Cancel for unknown order (V50): userRef={}", session.getSessionId(), userRefNum);
        }
    }

    @Override
    public void onReplaceOrderV50(OuchSession session, V50ReplaceOrderMessage msg) {
        long existingUserRef = msg.getExistingUserRefNum();
        String existingToken = String.valueOf(existingUserRef);
        long newUserRef = msg.getUserRefNum();
        String newToken = String.valueOf(newUserRef);
        int newShares = msg.getQuantity();
        double newPrice = msg.getPriceAsDouble();

        OrderState oldOrder = orders.remove(existingToken);
        if (oldOrder != null) {
            ordersReplaced.incrementAndGet();

            long orderRef = orderRefNum.getAndIncrement();
            OrderState newOrder = new OrderState(newToken, oldOrder.symbol, oldOrder.side,
                    newShares, newPrice, orderRef);
            orders.put(newToken, newOrder);

            if (!latencyMode) {
                log.info("[{}] Replace Order (V50): {} -> {}, qty={}, price={}",
                        session.getSessionId(), existingUserRef, newUserRef, newShares, newPrice);
            }
        } else {
            log.warn("[{}] Replace for unknown order (V50): userRef={}", session.getSessionId(), existingUserRef);
        }
    }

    // Statistics getters

    public long getOrdersReceived() {
        return ordersReceived.get();
    }

    public long getOrdersAccepted() {
        return ordersAccepted.get();
    }

    public long getOrdersFilled() {
        return ordersFilled.get();
    }

    public long getOrdersCanceled() {
        return ordersCanceled.get();
    }

    public long getOrdersReplaced() {
        return ordersReplaced.get();
    }

    /**
     * Internal order state tracking.
     */
    private static class OrderState {
        final String token;
        final String symbol;
        final Side side;
        final int shares;
        final double price;
        final long orderRef;

        OrderState(String token, String symbol, Side side, int shares, double price, long orderRef) {
            this.token = token;
            this.symbol = symbol;
            this.side = side;
            this.shares = shares;
            this.price = price;
            this.orderRef = orderRef;
        }
    }
}
