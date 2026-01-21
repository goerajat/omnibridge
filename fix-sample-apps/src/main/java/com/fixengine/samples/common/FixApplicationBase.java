package com.fixengine.samples.common;

import com.fixengine.config.provider.DefaultComponentProvider;
import com.fixengine.engine.FixEngine;
import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.engine.session.SessionStateListener;
import com.fixengine.network.NetworkEventLoop;
import com.fixengine.persistence.FixLogStore;
import com.fixengine.persistence.memory.MemoryMappedFixLogStore;
import ch.qos.logback.classic.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Base class for FIX applications providing common initialization and lifecycle management.
 *
 * <p>Subclasses should:</p>
 * <ul>
 *   <li>Define command-line options using picocli annotations</li>
 *   <li>Implement {@link #getConfigFiles()} to return config file paths</li>
 *   <li>Implement {@link #configure(FixEngine, List)} to register listeners</li>
 *   <li>Implement {@link #run(FixEngine, List)} to define application behavior</li>
 * </ul>
 */
public abstract class FixApplicationBase implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(FixApplicationBase.class);

    protected DefaultComponentProvider provider;
    protected volatile boolean running = true;

    @Override
    public Integer call() throws Exception {
        try {
            // Load configuration
            List<String> configFiles = getConfigFiles();
            log.info("Loading configuration from: {}", configFiles);

            // Create and initialize provider
            provider = DefaultComponentProvider.create(configFiles);

            // Register standard component factories
            provider.withNetworkFactory(config -> new NetworkEventLoop(config, provider))
                    .withPersistenceFactory(config -> new MemoryMappedFixLogStore(config, provider))
                    .withEngineFactory((config, networkLoop, persistence) ->
                            new FixEngine(config, (NetworkEventLoop) networkLoop, (FixLogStore) persistence));

            // Initialize components
            provider.initialize();

            // Get engine and create sessions
            FixEngine engine = (FixEngine) provider.getEngineProvider().get();
            List<FixSession> sessions = engine.createSessionsFromConfig();

            if (sessions.isEmpty()) {
                log.error("No sessions configured");
                return 1;
            }

            // Let subclass configure listeners
            configure(engine, sessions);

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown signal received");
                running = false;
                shutdown();
            }));

            // Start provider (starts network loop and engine)
            provider.start();

            // Run application-specific logic
            return run(engine, sessions);

        } catch (Exception e) {
            log.error("Error in application", e);
            shutdown();
            return 1;
        }
    }

    /**
     * Get the list of configuration files to load.
     *
     * @return list of config file paths
     */
    protected abstract List<String> getConfigFiles();

    /**
     * Configure the engine with listeners before starting.
     *
     * @param engine the FIX engine
     * @param sessions the created sessions
     */
    protected abstract void configure(FixEngine engine, List<FixSession> sessions);

    /**
     * Run the application logic after engine is started.
     *
     * @param engine the FIX engine
     * @param sessions the created sessions
     * @return exit code (0 for success)
     * @throws Exception if an error occurs
     */
    protected abstract int run(FixEngine engine, List<FixSession> sessions) throws Exception;

    /**
     * Shutdown the application and release resources.
     */
    protected void shutdown() {
        if (provider != null) {
            try {
                provider.stop();
            } catch (Exception e) {
                log.warn("Error during shutdown: {}", e.getMessage());
            }
        }
    }

    /**
     * Add a state listener to the engine.
     */
    protected void addStateListener(FixEngine engine, SessionStateListener listener) {
        engine.addStateListener(listener);
    }

    /**
     * Add a message listener to the engine.
     */
    protected void addMessageListener(FixEngine engine, MessageListener listener) {
        engine.addMessageListener(listener);
    }

    /**
     * Set the log level for the FIX engine packages.
     *
     * @param level the log level (e.g., "ERROR", "WARN", "INFO", "DEBUG")
     */
    protected void setLogLevel(String level) {
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.fixengine");
        rootLogger.setLevel(Level.toLevel(level));
    }

    /**
     * Check if the application is still running.
     */
    protected boolean isRunning() {
        return running;
    }
}
