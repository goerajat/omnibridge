package com.fixengine.samples.acceptor;

import com.fixengine.engine.FixEngine;
import com.fixengine.engine.session.FixSession;
import com.fixengine.samples.common.FixApplicationBase;
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
public class SampleAcceptor extends FixApplicationBase {

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
    protected void configure(FixEngine engine, List<FixSession> sessions) {
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency mode enabled - log level set to ERROR");
        }

        AcceptorMessageListener messageListener = new AcceptorMessageListener(fillRate, latencyMode);
        addStateListener(engine, new AcceptorStateListener(messageListener));
        addMessageListener(engine, messageListener);

        log.info("Acceptor configured with fill rate: {}", fillRate);
    }

    @Override
    protected int run(FixEngine engine, List<FixSession> sessions) throws Exception {
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
