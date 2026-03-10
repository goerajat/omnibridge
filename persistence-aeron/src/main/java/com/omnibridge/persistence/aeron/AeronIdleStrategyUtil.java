package com.omnibridge.persistence.aeron;

import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * Shared factory for creating Aeron {@link IdleStrategy} instances from
 * configuration strings.
 */
final class AeronIdleStrategyUtil {

    private AeronIdleStrategyUtil() {
    }

    static IdleStrategy create(String strategy) {
        return switch (strategy) {
            case "busy-spin" -> new BusySpinIdleStrategy();
            case "yielding" -> new YieldingIdleStrategy();
            default -> new SleepingIdleStrategy(1_000_000); // 1ms
        };
    }
}
