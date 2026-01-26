package com.omnibridge.simulator;

import com.omnibridge.apps.common.ApplicationBase;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.ilink3.engine.ILink3Engine;
import com.omnibridge.ouch.engine.OuchEngine;
import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.optiq.engine.OptiqEngine;
import com.omnibridge.pillar.engine.PillarEngine;
import com.omnibridge.pillar.engine.PillarSession;
import com.omnibridge.simulator.core.fill.FillEngine;
import com.omnibridge.simulator.core.fill.RuleBasedFillEngine;
import com.omnibridge.simulator.core.fill.SymbolFillRule;
import com.omnibridge.simulator.core.order.DefaultOrderBook;
import com.omnibridge.simulator.core.order.OrderBook;
import com.omnibridge.simulator.core.order.OrderIdGenerator;
import com.omnibridge.simulator.handler.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * Multi-protocol exchange simulator.
 * <p>
 * Accepts orders from FIX, OUCH (4.2/5.0), iLink3, Optiq, and Pillar protocols.
 * Uses centralized order state management and config-driven fill rules.
 */
@Command(name = "exchange-simulator", mixinStandardHelpOptions = true,
         description = "Multi-protocol exchange simulator")
public class ExchangeSimulator extends ApplicationBase {

    private static final Logger log = LoggerFactory.getLogger(ExchangeSimulator.class);

    // Core components shared across all protocols
    private OrderBook orderBook;
    private FillEngine fillEngine;
    private OrderIdGenerator orderIdGenerator;

    // Protocol handlers
    private FixProtocolHandler fixHandler;
    private OuchProtocolHandler ouchHandler;
    private ILink3ProtocolHandler ilink3Handler;
    private OptiqProtocolHandler optiqHandler;
    private PillarProtocolHandler pillarHandler;

    @Override
    protected List<String> getConfigFiles() {
        return List.of("exchange-simulator.conf", "simulator-components.conf");
    }

    @Override
    protected void preInitialize() throws Exception {
        log.info("Initializing Exchange Simulator...");

        // Create shared order management components
        orderBook = new DefaultOrderBook();
        orderIdGenerator = new OrderIdGenerator(1000000);
        fillEngine = createFillEngine(provider.getConfig());

        // Create protocol handlers
        fixHandler = new FixProtocolHandler(orderBook, fillEngine, orderIdGenerator);
        ouchHandler = new OuchProtocolHandler(orderBook, fillEngine, orderIdGenerator);
        ilink3Handler = new ILink3ProtocolHandler(orderBook, fillEngine, orderIdGenerator);
        optiqHandler = new OptiqProtocolHandler(orderBook, fillEngine, orderIdGenerator);
        pillarHandler = new PillarProtocolHandler(orderBook, fillEngine, orderIdGenerator);

        log.info("Created order book and fill engine");
    }

    @Override
    protected void postStart() throws Exception {
        // Wire FIX engine
        try {
            FixEngine fixEngine = provider.getComponent(FixEngine.class);
            for (FixSession session : fixEngine.getAllSessions()) {
                session.addMessageListener(fixHandler);
                log.info("Registered FIX handler for session: {}", session.getConfig().getSessionId());
            }
        } catch (IllegalArgumentException e) {
            log.debug("FIX engine not configured");
        }

        // Wire OUCH engine
        try {
            OuchEngine ouchEngine = provider.getComponent(OuchEngine.class);
            for (OuchSession session : ouchEngine.getSessions()) {
                session.addMessageListener(ouchHandler);
                log.info("Registered OUCH handler for session: {}", session.getSessionId());
            }
        } catch (IllegalArgumentException e) {
            log.debug("OUCH engine not configured");
        }

        // Wire iLink3 engine
        try {
            ILink3Engine ilink3Engine = provider.getComponent(ILink3Engine.class);
            ilink3Engine.addMessageListener((session, msg) -> ilink3Handler.onMessage(session, msg));
            log.info("Registered iLink3 handler");
        } catch (IllegalArgumentException e) {
            log.debug("iLink3 engine not configured");
        }

        // Wire Optiq engine
        try {
            OptiqEngine optiqEngine = provider.getComponent(OptiqEngine.class);
            optiqEngine.addMessageListener((session, msg) -> optiqHandler.onMessage(session, msg));
            log.info("Registered Optiq handler");
        } catch (IllegalArgumentException e) {
            log.debug("Optiq engine not configured");
        }

        // Wire Pillar engine
        try {
            PillarEngine pillarEngine = provider.getComponent(PillarEngine.class);
            pillarEngine.addMessageListener((session, msg) -> pillarHandler.onMessage((PillarSession) session, msg));
            log.info("Registered Pillar handler");
        } catch (IllegalArgumentException e) {
            log.debug("Pillar engine not configured");
        }

        logStartupInfo();
    }

    private FillEngine createFillEngine(Config config) {
        RuleBasedFillEngine engine = new RuleBasedFillEngine();

        if (config.hasPath("fill-engine.rules")) {
            ConfigList ruleList = config.getList("fill-engine.rules");
            int priority = ruleList.size();

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

                SymbolFillRule rule = builder.build();
                engine.addRule(rule);
                log.info("Added fill rule: symbol={}, fillProb={}, partialProb={}",
                        rule.getSymbolPattern(), rule.getFillProbability(), rule.getPartialFillProbability());
            }
        } else {
            // Default: 100% fill for all symbols
            engine.addRule(SymbolFillRule.builder()
                    .symbolPattern("*")
                    .fillProbability(1.0)
                    .build());
            log.info("Using default 100% fill rate for all symbols");
        }

        return engine;
    }

    private void logStartupInfo() {
        log.info("======================================================");
        log.info("  Exchange Simulator Started");
        log.info("======================================================");

        Config config = provider.getConfig();

        // FIX sessions (grouped by port to show multi-session sharing)
        if (config.hasPath("fix-engine.sessions")) {
            ConfigList sessions = config.getList("fix-engine.sessions");
            java.util.Map<Integer, java.util.List<String>> portToSessions = new java.util.LinkedHashMap<>();

            for (ConfigValue value : sessions) {
                Config sessionConfig = ((com.typesafe.config.ConfigObject) value).toConfig();
                int port = sessionConfig.getInt("port");
                String sender = sessionConfig.getString("sender-comp-id");
                String target = sessionConfig.getString("target-comp-id");
                portToSessions.computeIfAbsent(port, k -> new java.util.ArrayList<>())
                        .add(sender + " -> " + target);
            }

            int uniquePorts = portToSessions.size();
            log.info("FIX Sessions: {} sessions on {} port(s)", sessions.size(), uniquePorts);
            for (var entry : portToSessions.entrySet()) {
                int port = entry.getKey();
                java.util.List<String> sessionList = entry.getValue();
                if (sessionList.size() > 1) {
                    log.info("  Port {} (multi-session):", port);
                    for (String session : sessionList) {
                        log.info("    - {}", session);
                    }
                } else {
                    log.info("  - Port {}: {}", port, sessionList.get(0));
                }
            }
        }

        // OUCH sessions
        if (config.hasPath("ouch-engine.sessions")) {
            ConfigList sessions = config.getList("ouch-engine.sessions");
            log.info("OUCH Sessions: {}", sessions.size());
            for (ConfigValue value : sessions) {
                Config sessionConfig = ((com.typesafe.config.ConfigObject) value).toConfig();
                int port = sessionConfig.getInt("port");
                String sessionId = sessionConfig.getString("session-id");
                String version = sessionConfig.hasPath("protocol-version") ?
                        sessionConfig.getString("protocol-version") : "4.2";
                log.info("  - Port {}: {} (v{})", port, sessionId, version);
            }
        }

        // iLink3 sessions
        if (config.hasPath("ilink3-engine.sessions")) {
            ConfigList sessions = config.getList("ilink3-engine.sessions");
            log.info("iLink3 Sessions: {}", sessions.size());
        }

        // Optiq sessions
        if (config.hasPath("optiq-engine.sessions")) {
            ConfigList sessions = config.getList("optiq-engine.sessions");
            log.info("Optiq Sessions: {}", sessions.size());
        }

        // Pillar sessions
        if (config.hasPath("pillar-engine.sessions")) {
            ConfigList sessions = config.getList("pillar-engine.sessions");
            log.info("Pillar Sessions: {}", sessions.size());
        }

        // Admin API
        if (config.hasPath("admin.port")) {
            log.info("Admin API: http://localhost:{}", config.getInt("admin.port"));
        }

        log.info("======================================================");
    }

    @Override
    protected void awaitShutdown() throws InterruptedException {
        while (running) {
            Thread.sleep(5000);

            // Log periodic statistics
            log.debug("Orders: received={}, filled={}, active={}",
                    fixHandler.getOrdersReceived() + ouchHandler.getOrdersReceived(),
                    fixHandler.getOrdersFilled() + ouchHandler.getOrdersFilled(),
                    orderBook.getActiveOrderCount());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ExchangeSimulator()).execute(args);
        System.exit(exitCode);
    }
}
