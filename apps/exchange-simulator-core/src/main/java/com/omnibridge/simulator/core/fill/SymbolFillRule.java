package com.omnibridge.simulator.core.fill;

import com.omnibridge.simulator.core.order.Order;

import java.util.Random;
import java.util.regex.Pattern;

/**
 * Fill rule based on symbol pattern matching with configurable fill probabilities.
 */
public class SymbolFillRule implements FillRule {

    private final String symbolPattern;
    private final Pattern regex;
    private final double fillProbability;
    private final double partialFillProbability;
    private final double minPartialFillRatio;
    private final double maxPartialFillRatio;
    private final int priority;
    private final Random random;

    private SymbolFillRule(Builder builder) {
        this.symbolPattern = builder.symbolPattern;
        this.regex = patternToRegex(builder.symbolPattern);
        this.fillProbability = builder.fillProbability;
        this.partialFillProbability = builder.partialFillProbability;
        this.minPartialFillRatio = builder.minPartialFillRatio;
        this.maxPartialFillRatio = builder.maxPartialFillRatio;
        this.priority = builder.priority;
        this.random = new Random();
    }

    @Override
    public boolean matches(Order order) {
        return regex.matcher(order.getSymbol()).matches();
    }

    @Override
    public FillDecision evaluate(Order order) {
        // Check if we should fill at all
        if (random.nextDouble() >= fillProbability) {
            return FillDecision.noFill("Below fill probability threshold");
        }

        double fillPrice = order.getPrice() > 0 ? order.getPrice() : 100.0;
        double orderQty = order.getLeavesQty();

        // Check if this should be a partial fill
        if (partialFillProbability > 0 && random.nextDouble() < partialFillProbability) {
            // Calculate partial fill quantity
            double ratio = minPartialFillRatio + random.nextDouble() * (maxPartialFillRatio - minPartialFillRatio);
            double fillQty = Math.max(1, Math.floor(orderQty * ratio));
            return FillDecision.partialFill(fillQty, fillPrice);
        }

        // Full fill
        return FillDecision.fullFill(orderQty, fillPrice);
    }

    @Override
    public int getPriority() {
        return priority;
    }

    public String getSymbolPattern() {
        return symbolPattern;
    }

    public double getFillProbability() {
        return fillProbability;
    }

    public double getPartialFillProbability() {
        return partialFillProbability;
    }

    /**
     * Convert a glob-like pattern to a regex.
     * Supports * (match any characters) and ? (match single character).
     */
    private static Pattern patternToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '\\' -> regex.append("\\\\");
                default -> regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String symbolPattern = "*";
        private double fillProbability = 1.0;
        private double partialFillProbability = 0.0;
        private double minPartialFillRatio = 0.1;
        private double maxPartialFillRatio = 0.9;
        private int priority = 0;

        public Builder symbolPattern(String pattern) {
            this.symbolPattern = pattern;
            return this;
        }

        public Builder fillProbability(double probability) {
            this.fillProbability = Math.max(0, Math.min(1, probability));
            return this;
        }

        public Builder partialFillProbability(double probability) {
            this.partialFillProbability = Math.max(0, Math.min(1, probability));
            return this;
        }

        public Builder minPartialFillRatio(double ratio) {
            this.minPartialFillRatio = Math.max(0, Math.min(1, ratio));
            return this;
        }

        public Builder maxPartialFillRatio(double ratio) {
            this.maxPartialFillRatio = Math.max(0, Math.min(1, ratio));
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public SymbolFillRule build() {
            return new SymbolFillRule(this);
        }
    }

    @Override
    public String toString() {
        return String.format("SymbolFillRule[pattern=%s, fillProb=%.2f, partialProb=%.2f, priority=%d]",
                symbolPattern, fillProbability, partialFillProbability, priority);
    }
}
