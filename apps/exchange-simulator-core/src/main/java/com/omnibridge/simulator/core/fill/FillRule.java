package com.omnibridge.simulator.core.fill;

import com.omnibridge.simulator.core.order.Order;

/**
 * Interface for fill rules that determine whether and how an order should be filled.
 */
public interface FillRule {

    /**
     * Check if this rule matches the given order.
     *
     * @param order the order to evaluate
     * @return true if this rule applies to the order
     */
    boolean matches(Order order);

    /**
     * Evaluate the order and return a fill decision.
     * Only called if matches() returns true.
     *
     * @param order the order to evaluate
     * @return the fill decision
     */
    FillDecision evaluate(Order order);

    /**
     * Get the priority of this rule (higher = evaluated first).
     *
     * @return the priority
     */
    int getPriority();
}
