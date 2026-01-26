package com.omnibridge.simulator.adapter;

import com.omnibridge.pillar.engine.PillarSession;
import com.omnibridge.pillar.message.order.NewOrderMessage;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;

/**
 * Adapter for converting Pillar messages to Order objects.
 */
public class PillarOrderAdapter implements OrderAdapter<NewOrderMessage, PillarSession> {

    @Override
    public Order adapt(NewOrderMessage message, PillarSession session, long orderId) {
        long clOrdId = message.getClOrdId();
        double price = message.getPrice();
        long qty = message.getOrderQty();
        byte side = message.getSide();
        String symbol = message.getSymbol();

        return Order.builder()
                .orderId(orderId)
                .clientOrderId(String.valueOf(clOrdId))
                .symbol(symbol)
                .side(OrderSide.fromByte(side))
                .orderType(OrderType.LIMIT)
                .qty(qty)
                .price(price)
                .sessionId(session.getSessionId())
                .protocol(getProtocol())
                .build();
    }

    @Override
    public String getProtocol() {
        return "PILLAR";
    }
}
