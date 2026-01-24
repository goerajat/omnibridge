package com.fixengine.apps.ouch.acceptor;

import com.fixengine.apps.common.ApplicationBase;
import com.fixengine.ouch.engine.OuchEngine;
import com.fixengine.ouch.engine.session.OuchSession;
import com.fixengine.ouch.engine.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Sample OUCH acceptor (exchange simulator).
 *
 * <p>Accepts incoming OUCH connections and simulates order handling.
 * Uses the high-level OuchSession API and supports both OUCH 4.2 and 5.0 protocols.</p>
 *
 * <p>The protocol version is configured in the session configuration file.</p>
 */
@Command(name = "ouch-acceptor", description = "Sample OUCH acceptor (exchange simulator)")
public class SampleOuchAcceptor extends ApplicationBase {

    private static final Logger log = LoggerFactory.getLogger(SampleOuchAcceptor.class);

    @Option(names = {"-c", "--config"}, description = "Configuration file(s)", split = ",")
    private List<String> configFiles = new ArrayList<>();

    @Option(names = {"--fill-rate"}, description = "Probability of immediate fill (0.0-1.0)", defaultValue = "1.0")
    private double fillRate;

    @Option(names = {"--latency"}, description = "Enable latency mode (reduced logging)", defaultValue = "false")
    private boolean latencyMode;

    private AcceptorMessageHandler messageHandler;

    @Override
    protected List<String> getConfigFiles() {
        if (configFiles.isEmpty()) {
            configFiles.add("ouch-acceptor.conf");
        }
        return configFiles;
    }

    @Override
    protected EngineType getEngineType() {
        return EngineType.OUCH;
    }

    @Override
    protected void configureOuch(OuchEngine engine, List<OuchSession> sessions) {
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency mode enabled - log level set to ERROR");
        }

        // Create message handler
        messageHandler = new AcceptorMessageHandler(fillRate, latencyMode);

        // Add listeners to all sessions
        for (OuchSession session : sessions) {
            log.info("Configuring session: {} (protocol: {})",
                    session.getSessionId(), session.getProtocolVersion());

            // Add state listener
            session.addStateListener((sess, oldState, newState) -> {
                log.info("Session {} state: {} -> {}", sess.getSessionId(), oldState, newState);
            });

            // Add typed message listener for incoming orders
            session.addMessageListener(messageHandler);
        }

        log.info("Acceptor configured with {} session(s), fill rate: {}",
                sessions.size(), fillRate);
    }

    @Override
    protected int runOuch(OuchEngine engine, List<OuchSession> sessions) throws Exception {
        log.info("OUCH Acceptor started. Press Ctrl+C to stop.");
        log.info("Listening for connections...");

        // Display session info
        for (OuchSession session : sessions) {
            log.info("Session {} ready (protocol: {}, port: {})",
                    session.getSessionId(),
                    session.getProtocolVersion(),
                    session.getPort());
        }

        // Run as daemon - wait indefinitely
        while (isRunning()) {
            Thread.sleep(1000);
        }

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleOuchAcceptor()).execute(args);
        System.exit(exitCode);
    }
}
