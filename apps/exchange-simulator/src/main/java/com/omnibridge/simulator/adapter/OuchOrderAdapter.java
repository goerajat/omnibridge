package com.omnibridge.simulator.adapter;

import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.message.EnterOrderMessage;
import com.omnibridge.ouch.message.Side;
import com.omnibridge.ouch.message.v50.V50EnterOrderMessage;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;

/**
 * Adapter for converting OUCH messages to Order objects.
 * Handles both OUCH 4.2 and 5.0 protocols.
 */
public class OuchOrderAdapter implements OrderAdapter<EnterOrderMessage, OuchSession> {

    @Override
    public Order adapt(EnterOrderMessage message, OuchSession session, long orderId) {
        String token = message.getOrderToken();
        String symbol = message.getSymbol();
        Side side = message.getSide();
        int shares = message.getShares();
        double price = message.getPriceAsDouble();

        return Order.builder()
                .orderId(orderId)
                .clientOrderId(token)
                .symbol(symbol)
                .side(side == Side.BUY ? OrderSide.BUY : OrderSide.SELL)
                .orderType(OrderType.LIMIT)
                .qty(shares)
                .price(price)
                .sessionId(session.getSessionId())
                .protocol(getProtocol())
                .build();
    }

    /**
     * Adapt OUCH 5.0 enter order message.
     */
    public Order adaptV50(V50EnterOrderMessage message, OuchSession session, long orderId) {
        long userRefNum = message.getUserRefNum();
        String token = String.valueOf(userRefNum);
        String symbol = message.getSymbol();
        Side side = message.getSide();
        int qty = message.getQuantity();
        double price = message.getPriceAsDouble();

        return Order.builder()
                .orderId(orderId)
                .clientOrderId(token)
                .symbol(symbol)
                .side(side == Side.BUY ? OrderSide.BUY : OrderSide.SELL)
                .orderType(OrderType.LIMIT)
                .qty(qty)
                .price(price)
                .sessionId(session.getSessionId())
                .protocol(getProtocol() + "50")
                .build();
    }

    @Override
    public String getProtocol() {
        return "OUCH";
    }
}
