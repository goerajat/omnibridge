package com.omnibridge.simulator.core.fill;

import com.omnibridge.simulator.core.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fill engine that evaluates orders against a prioritized list of rules.
 */
public class RuleBasedFillEngine implements FillEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedFillEngine.class);

    private final List<FillRule> rules = new ArrayList<>();
    private FillDecision defaultDecision = FillDecision.noFill("No matching rule");

    @Override
    public FillDecision evaluate(Order order) {
        // Evaluate rules in priority order (highest first)
        for (FillRule rule : rules) {
            if (rule.matches(order)) {
                FillDecision decision = rule.evaluate(order);
                log.debug("Order {} matched rule {}: {}", order.getClientOrderId(), rule, decision);
                return decision;
            }
        }

        log.debug("Order {} no matching rule, using default: {}", order.getClientOrderId(), defaultDecision);
        return defaultDecision;
    }

    @Override
    public void addRule(FillRule rule) {
        rules.add(rule);
        // Re-sort by priority (descending)
        rules.sort(Comparator.comparingInt(FillRule::getPriority).reversed());
        log.info("Added fill rule: {} (total rules: {})", rule, rules.size());
    }

    @Override
    public void clearRules() {
        rules.clear();
        log.info("Cleared all fill rules");
    }

    /**
     * Set the default decision when no rule matches.
     */
    public void setDefaultDecision(FillDecision decision) {
        this.defaultDecision = decision;
    }

    /**
     * Get the number of configured rules.
     */
    public int getRuleCount() {
        return rules.size();
    }
}
