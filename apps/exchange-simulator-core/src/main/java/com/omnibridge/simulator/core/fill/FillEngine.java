package com.omnibridge.simulator.core.fill;

import com.omnibridge.simulator.core.order.Order;

/**
 * Interface for fill decision engines.
 */
public interface FillEngine {

    /**
     * Evaluate an order and determine if/how it should be filled.
     *
     * @param order the order to evaluate
     * @return the fill decision
     */
    FillDecision evaluate(Order order);

    /**
     * Add a fill rule to the engine.
     *
     * @param rule the rule to add
     */
    void addRule(FillRule rule);

    /**
     * Remove all rules from the engine.
     */
    void clearRules();
}
