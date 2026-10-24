package com.omnibridge.simulator;

import com.omnibridge.config.ConfigLoader;
import com.omnibridge.simulator.core.fill.FillConfig;
import com.omnibridge.simulator.core.fill.RuleBasedFillEngine;
import com.omnibridge.simulator.core.order.Order;
import com.typesafe.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulator's own settings must win over the shared defaults in components.conf.
 */
class ExchangeSimulatorConfigTest {

    private final Config config = ConfigLoader.load(new ExchangeSimulator().getConfigFiles());

    @Test
    void networkSectionComesFromSimulatorConfig() {
        assertEquals("simulator-event-loop", config.getString("network.name"));
        assertEquals("exchange-simulator.conf",
                config.getValue("network.busy-spin-mode").origin().resource());
    }

    @Test
    void persistenceSectionComesFromSimulatorConfig() {
        assertEquals("./data/simulator-logs", config.getString("persistence.base-path"));
        assertEquals("exchange-simulator.conf",
                config.getValue("persistence.store-type").origin().resource());
        // Keys only components.conf defines are still inherited
        assertEquals(100, config.getInt("persistence.max-streams"));
    }

    @Test
    void sharedDefaultsAndComponentsStillLoad() {
        assertTrue(config.getBoolean("admin.enabled"));
        assertTrue(config.getBoolean("metrics.enabled"));
        assertEquals("com.omnibridge.network.factory.NetworkEventLoopFactory",
                config.getString("components.network.factory"));
        assertTrue(config.getBoolean("components.pillar-engine.enabled"));
        assertEquals(5, config.getList("fix-engine.sessions").size());
    }

    /** The latency test sends NOFILL orders: acked, never filled. TEST orders still always fill. */
    @Test
    void nofillSymbolsAreNeverFilled() {
        RuleBasedFillEngine engine = new RuleBasedFillEngine();
        FillConfig.fromConfig(config.getConfig("fill-engine")).getRules().forEach(engine::addRule);
        for (int i = 0; i < 1000; i++) {
            assertFalse(engine.evaluate(order("NOFILL")).shouldFill(), "NOFILL filled");
            assertTrue(engine.evaluate(order("TEST")).isFullFill(), "TEST not fully filled");
        }
    }

    private static Order order(String symbol) {
        return Order.builder().orderId(1).clientOrderId("C1").symbol(symbol).qty(100).price(100.0).build();
    }
}
