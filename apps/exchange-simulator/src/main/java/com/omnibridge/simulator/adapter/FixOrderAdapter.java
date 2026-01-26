package com.omnibridge.simulator.adapter;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;

/**
 * Adapter for converting FIX messages to Order objects.
 */
public class FixOrderAdapter implements OrderAdapter<IncomingFixMessage, FixSession> {

    @Override
    public Order adapt(IncomingFixMessage message, FixSession session, long orderId) {
        CharSequence clOrdId = message.getCharSequence(FixTags.ClOrdID);
        CharSequence symbol = message.getCharSequence(FixTags.Symbol);
        char side = message.getChar(FixTags.Side);
        int orderQty = message.getInt(FixTags.OrderQty);
        char ordType = message.getChar(FixTags.OrdType);
        double price = message.getDouble(FixTags.Price);

        return Order.builder()
                .orderId(orderId)
                .clientOrderId(clOrdId != null ? clOrdId.toString() : null)
                .symbol(symbol != null ? symbol.toString() : null)
                .side(OrderSide.fromFixValue(side))
                .orderType(OrderType.fromFixValue(ordType))
                .qty(orderQty)
                .price(price)
                .sessionId(session.getConfig().getSessionId())
                .protocol(getProtocol())
                .build();
    }

    /**
     * Extract original client order ID from cancel/replace request.
     */
    public String getOrigClientOrderId(IncomingFixMessage message) {
        CharSequence origClOrdId = message.getCharSequence(41); // OrigClOrdID
        return origClOrdId != null ? origClOrdId.toString() : null;
    }

    /**
     * Extract client order ID.
     */
    public String getClientOrderId(IncomingFixMessage message) {
        CharSequence clOrdId = message.getCharSequence(FixTags.ClOrdID);
        return clOrdId != null ? clOrdId.toString() : null;
    }

    @Override
    public String getProtocol() {
        return "FIX";
    }
}
