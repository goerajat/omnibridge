package com.omnibridge.apps.fix.acceptor;

import com.omnibridge.apps.common.ApplicationBase;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.session.FixSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Sample FIX acceptor (exchange simulator).
 * Accepts incoming FIX connections and simulates order handling.
 */
@Command(name = "sample-acceptor", description = "Sample FIX acceptor (exchange simulator)")
public class SampleAcceptor extends ApplicationBase {

    private static final Logger log = LoggerFactory.getLogger(SampleAcceptor.class);

    @Option(names = {"-c", "--config"}, description = "Configuration file(s)", split = ",")
    private List<String> configFiles = new ArrayList<>();

    @Option(names = {"--fill-rate"}, description = "Probability of immediate fill (0.0-1.0)", defaultValue = "0.8")
    private double fillRate;

    @Option(names = {"--latency"}, description = "Enable latency mode (reduced logging)", defaultValue = "false")
    private boolean latencyMode;

    @Override
    protected List<String> getConfigFiles() {
        if (configFiles.isEmpty()) {
            configFiles.add("acceptor.conf");
        }
        return configFiles;
    }

    @Override
    protected EngineType getEngineType() {
        return EngineType.FIX;
    }

    @Override
    protected void configureFix(FixEngine engine, List<FixSession> sessions) {
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency mode enabled - log level set to ERROR");
        }

        AcceptorMessageListener messageListener = new AcceptorMessageListener(fillRate, latencyMode);
        engine.addStateListener(new AcceptorStateListener(messageListener));
        engine.addMessageListener(messageListener);

        log.info("Acceptor configured with fill rate: {}", fillRate);
    }

    @Override
    protected int runFix(FixEngine engine, List<FixSession> sessions) throws Exception {
        log.info("Acceptor started. Press Ctrl+C to stop.");

        // Run as daemon - wait indefinitely
        while (isRunning()) {
            Thread.sleep(1000);
        }

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleAcceptor()).execute(args);
        System.exit(exitCode);
    }
}
