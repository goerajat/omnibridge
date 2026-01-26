package com.omnibridge.simulator.core.order;

/**
 * Order state enum representing the lifecycle of an order.
 */
public enum OrderState {
    /** Order has been received but not yet acknowledged */
    PENDING,
    /** Order has been accepted and is active */
    NEW,
    /** Order has been partially filled */
    PARTIALLY_FILLED,
    /** Order has been completely filled */
    FILLED,
    /** Order has been canceled */
    CANCELED,
    /** Order has been rejected */
    REJECTED,
    /** Order has been replaced (new order created) */
    REPLACED,
    /** Order has expired */
    EXPIRED
}
