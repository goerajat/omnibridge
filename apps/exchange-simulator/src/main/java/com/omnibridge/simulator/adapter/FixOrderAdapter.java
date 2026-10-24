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

        return new Order(orderId, clOrdId != null ? clOrdId.toString() : null, symbolOf(symbol),
                OrderSide.fromFixValue(side), OrderType.fromFixValue(ordType), orderQty, price,
                session.getConfig().getSessionId(), getProtocol());
    }

    /** Symbols seen so far by their characters: a repeated symbol costs no String. */
    private final String[] symbolCache = new String[1024];

    private String symbolOf(CharSequence symbol) {
        if (symbol == null) {
            return null;
        }
        int hash = 0;
        for (int i = 0, n = symbol.length(); i < n; i++) {
            hash = 31 * hash + symbol.charAt(i);
        }
        int slot = hash & (symbolCache.length - 1);
        String cached = symbolCache[slot];
        if (cached != null && cached.contentEquals(symbol)) {
            return cached;
        }
        String fresh = symbol.toString();
        symbolCache[slot] = fresh;
        return fresh;
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
