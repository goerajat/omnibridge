package com.omnibridge.simulator;

import com.omnibridge.config.ConfigLoader;
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
}
