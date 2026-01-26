package com.omnibridge.simulator.core.factory;

import com.omnibridge.simulator.core.fill.*;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating FillEngine instances.
 */
public class FillEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(FillEngineFactory.class);

    /**
     * Create a FillEngine from configuration.
     */
    public FillEngine create(Config config) {
        RuleBasedFillEngine engine = new RuleBasedFillEngine();

        if (config != null && config.hasPath("fill-engine")) {
            FillConfig fillConfig = FillConfig.fromConfig(config.getConfig("fill-engine"));
            for (SymbolFillRule rule : fillConfig.getRules()) {
                engine.addRule(rule);
            }
            log.info("Created FillEngine with {} rules from config", fillConfig.getRules().size());
        } else {
            // Default: 100% fill all orders
            engine.addRule(SymbolFillRule.builder()
                    .symbolPattern("*")
                    .fillProbability(1.0)
                    .build());
            log.info("Created FillEngine with default 100% fill rule");
        }

        return engine;
    }

    /**
     * Create a FillEngine with default 100% fill.
     */
    public FillEngine createDefault() {
        RuleBasedFillEngine engine = new RuleBasedFillEngine();
        engine.addRule(SymbolFillRule.builder()
                .symbolPattern("*")
                .fillProbability(1.0)
                .build());
        return engine;
    }

    /**
     * Singleton instance for convenience.
     */
    private static final FillEngineFactory INSTANCE = new FillEngineFactory();

    public static FillEngineFactory getInstance() {
        return INSTANCE;
    }
}
