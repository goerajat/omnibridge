package com.omnibridge.simulator.adapter;

import com.omnibridge.optiq.engine.OptiqSession;
import com.omnibridge.optiq.message.order.NewOrderMessage;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import com.omnibridge.simulator.core.order.OrderType;

/**
 * Adapter for converting Optiq messages to Order objects.
 */
public class OptiqOrderAdapter implements OrderAdapter<NewOrderMessage, OptiqSession> {

    @Override
    public Order adapt(NewOrderMessage message, OptiqSession session, long orderId) {
        long clientOrderId = message.getClientOrderId();
        double price = message.getOrderPx();
        long qty = message.getOrderQty();
        byte side = message.getOrderSide();

        return Order.builder()
                .orderId(orderId)
                .clientOrderId(String.valueOf(clientOrderId))
                .symbol(String.valueOf(message.getSymbolIndex()))
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
        return "OPTIQ";
    }
}
