package com.omnibridge.simulator.core.fill;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration holder for fill engine rules.
 */
public class FillConfig {

    private final List<SymbolFillRule> rules;

    public FillConfig(List<SymbolFillRule> rules) {
        this.rules = rules;
    }

    public List<SymbolFillRule> getRules() {
        return rules;
    }

    /**
     * Parse fill configuration from Typesafe Config.
     */
    public static FillConfig fromConfig(Config config) {
        List<SymbolFillRule> rules = new ArrayList<>();

        if (config.hasPath("rules")) {
            ConfigList ruleList = config.getList("rules");
            int priority = ruleList.size(); // Higher priority for earlier rules

            for (ConfigValue value : ruleList) {
                Config ruleConfig = ((com.typesafe.config.ConfigObject) value).toConfig();

                SymbolFillRule.Builder builder = SymbolFillRule.builder()
                        .priority(priority--);

                if (ruleConfig.hasPath("symbol")) {
                    builder.symbolPattern(ruleConfig.getString("symbol"));
                }

                if (ruleConfig.hasPath("fill-probability")) {
                    builder.fillProbability(ruleConfig.getDouble("fill-probability"));
                }

                if (ruleConfig.hasPath("partial-fill-probability")) {
                    builder.partialFillProbability(ruleConfig.getDouble("partial-fill-probability"));
                }

                if (ruleConfig.hasPath("min-partial-fill-ratio")) {
                    builder.minPartialFillRatio(ruleConfig.getDouble("min-partial-fill-ratio"));
                }

                if (ruleConfig.hasPath("max-partial-fill-ratio")) {
                    builder.maxPartialFillRatio(ruleConfig.getDouble("max-partial-fill-ratio"));
                }

                rules.add(builder.build());
            }
        }

        return new FillConfig(rules);
    }

    /**
     * Create a default fill configuration with 100% fills.
     */
    public static FillConfig defaultConfig() {
        List<SymbolFillRule> rules = new ArrayList<>();
        rules.add(SymbolFillRule.builder()
                .symbolPattern("*")
                .fillProbability(1.0)
                .partialFillProbability(0.0)
                .priority(0)
                .build());
        return new FillConfig(rules);
    }
}
