package com.omnibridge.simulator.adapter;

import com.omnibridge.ilink3.engine.ILink3Session;
import com.omnibridge.ilink3.message.order.NewOrderSingleMessage;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;

/**
 * Adapter for converting iLink3 messages to Order objects.
 */
public class ILink3OrderAdapter implements OrderAdapter<NewOrderSingleMessage, ILink3Session> {

    @Override
    public Order adapt(NewOrderSingleMessage message, ILink3Session session, long orderId) {
        String clOrdId = message.getClOrdId();
        double price = message.getPrice();
        int qty = message.getOrderQty();
        byte side = message.getSide();

        return Order.builder()
                .orderId(orderId)
                .clientOrderId(clOrdId)
                .symbol("") // iLink3 uses security ID
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
        return "ILINK3";
    }
}
