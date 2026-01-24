package com.fixengine.apps.common;

import com.fixengine.config.ClockProvider;
import com.fixengine.config.provider.DefaultComponentProvider;
import com.fixengine.config.schedule.SchedulerConfig;
import com.fixengine.config.schedule.SessionScheduler;
import com.fixengine.engine.FixEngine;
import com.fixengine.engine.config.FixEngineConfig;
import com.fixengine.engine.session.FixSession;
import com.fixengine.network.NetworkEventLoop;
import com.fixengine.network.config.NetworkConfig;
import com.fixengine.ouch.engine.OuchEngine;
import com.fixengine.ouch.engine.config.OuchEngineConfig;
import com.fixengine.ouch.engine.config.OuchSessionConfig;
import com.fixengine.ouch.engine.session.OuchSession;
import com.fixengine.persistence.LogStore;
import com.fixengine.persistence.config.PersistenceConfig;
import com.fixengine.persistence.memory.MemoryMappedLogStore;
import ch.qos.logback.classic.Level;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Base class for applications providing common initialization and lifecycle management.
 *
 * <p>Supports both FIX and OUCH protocol engines through a unified interface.
 * Subclasses should:</p>
 * <ul>
 *   <li>Define command-line options using picocli annotations</li>
 *   <li>Implement {@link #getConfigFiles()} to return config file paths</li>
 *   <li>Implement {@link #getEngineType()} to specify FIX or OUCH</li>
 *   <li>Override the appropriate configure and run methods for their engine type</li>
 * </ul>
 *
 * <p>For FIX applications, override:</p>
 * <ul>
 *   <li>{@link #configureFix(FixEngine, List)}</li>
 *   <li>{@link #runFix(FixEngine, List)}</li>
 * </ul>
 *
 * <p>For OUCH applications, override:</p>
 * <ul>
 *   <li>{@link #configureOuch(OuchEngine, List)}</li>
 *   <li>{@link #runOuch(OuchEngine, List)}</li>
 * </ul>
 */
public abstract class ApplicationBase implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ApplicationBase.class);

    /**
     * Engine types supported by the application base.
     */
    public enum EngineType {
        FIX,
        OUCH
    }

    protected DefaultComponentProvider provider;
    protected volatile boolean running = true;

    @Override
    public Integer call() throws Exception {
        try {
            // Load configuration
            List<String> configFiles = getConfigFiles();
            log.info("Loading configuration from: {}", configFiles);

            // Create provider
            provider = DefaultComponentProvider.create(configFiles);
            Config config = provider.getConfig();

            // Register common factories
            registerCommonFactories(config);

            // Register engine-specific factories and run
            EngineType engineType = getEngineType();
            int result;

            switch (engineType) {
                case FIX -> result = initializeAndRunFix(config);
                case OUCH -> result = initializeAndRunOuch(config);
                default -> throw new IllegalStateException("Unknown engine type: " + engineType);
            }

            return result;

        } catch (Exception e) {
            log.error("Error in application", e);
            shutdown();
            return 1;
        }
    }

    /**
     * Register factories common to both FIX and OUCH engines.
     */
    private void registerCommonFactories(Config config) {
        // Register ClockProvider factory (system clock by default)
        provider.register(ClockProvider.class, (name, cfg, p) -> ClockProvider.system());

        // Register NetworkEventLoop factory
        provider.register(NetworkEventLoop.class, (name, cfg, p) -> {
            NetworkConfig networkConfig = NetworkConfig.fromConfig(cfg.getConfig(name == null ? "network" : "network." + name));
            return new NetworkEventLoop(networkConfig, p);
        });

        // Register LogStore factory if persistence is enabled
        if (config.hasPath("persistence") && config.getBoolean("persistence.enabled")) {
            provider.register(LogStore.class, (name, cfg, p) -> {
                PersistenceConfig persistenceConfig = PersistenceConfig.fromConfig(cfg.getConfig(name == null ? "persistence" : "persistence." + name));
                return new MemoryMappedLogStore(persistenceConfig, p);
            });
        }

        // Register SessionScheduler factory if schedulers are configured
        if (config.hasPath("schedulers")) {
            provider.register(SessionScheduler.class, (name, cfg, p) -> {
                ClockProvider clock = p.getComponent(ClockProvider.class);
                SessionScheduler scheduler = new SessionScheduler(clock);
                SchedulerConfig schedulerConfig = SchedulerConfig.fromConfig(cfg);
                schedulerConfig.applyTo(scheduler);
                return scheduler;
            });
        }
    }

    /**
     * Initialize and run a FIX engine application.
     */
    private int initializeAndRunFix(Config config) throws Exception {
        // Register FixEngine factory
        provider.register(FixEngine.class, (name, cfg, p) -> {
            FixEngineConfig engineConfig = FixEngineConfig.fromConfig(cfg);
            return new FixEngine(engineConfig, p);
        });

        // Get engine (this triggers creation of all dependencies)
        FixEngine engine = provider.getComponent(FixEngine.class);
        List<FixSession> sessions = engine.createSessionsFromConfig();

        if (sessions.isEmpty()) {
            log.error("No sessions configured");
            return 1;
        }

        // Associate sessions with schedules if SessionScheduler is configured
        if (engine.getSessionScheduler() != null) {
            var associations = SchedulerConfig.getSessionScheduleAssociations(config);
            for (var entry : associations.entrySet()) {
                String sessionName = entry.getKey();
                String scheduleName = entry.getValue();
                for (FixSession session : sessions) {
                    if (sessionName.equals(session.getConfig().getSessionName())) {
                        engine.associateSessionWithSchedule(session.getConfig().getSessionId(), scheduleName);
                        log.info("Associated session '{}' with schedule '{}'", sessionName, scheduleName);
                        break;
                    }
                }
            }
        }

        // Let subclass configure listeners
        configureFix(engine, sessions);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            running = false;
            shutdown();
        }));

        // Initialize and start components
        provider.initialize();
        provider.start();

        // Run application-specific logic
        return runFix(engine, sessions);
    }

    /**
     * Initialize and run an OUCH engine application.
     */
    private int initializeAndRunOuch(Config config) throws Exception {
        // Parse OUCH engine config
        Config ouchConfig = config.hasPath("ouch-engine") ? config.getConfig("ouch-engine") : config;
        OuchEngineConfig engineConfig = OuchEngineConfig.fromConfig(ouchConfig);

        // Create engine
        OuchEngine engine = new OuchEngine(engineConfig);

        // Set up dependencies from provider
        try {
            NetworkEventLoop eventLoop = provider.getComponent(NetworkEventLoop.class);
            engine.setNetworkEventLoop(eventLoop);
        } catch (Exception e) {
            // Create default network event loop
            NetworkEventLoop eventLoop = new NetworkEventLoop("ouch-io");
            engine.setNetworkEventLoop(eventLoop);
        }

        try {
            LogStore logStore = provider.getComponent(LogStore.class);
            engine.setLogStore(logStore);
        } catch (Exception e) {
            // LogStore is optional
        }

        try {
            SessionScheduler scheduler = provider.getComponent(SessionScheduler.class);
            engine.setScheduler(scheduler);
        } catch (Exception e) {
            // Scheduler is optional
        }

        try {
            ClockProvider clock = provider.getComponent(ClockProvider.class);
            engine.setClockProvider(clock);
        } catch (Exception e) {
            // ClockProvider is optional
        }

        // Create sessions from config
        List<OuchSession> sessions = new java.util.ArrayList<>();
        for (OuchSessionConfig sessionConfig : engineConfig.getSessions()) {
            OuchSession session = engine.createSession(sessionConfig);
            sessions.add(session);
        }

        if (sessions.isEmpty()) {
            log.error("No OUCH sessions configured");
            return 1;
        }

        // Let subclass configure listeners
        configureOuch(engine, sessions);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            running = false;
            shutdownOuch(engine);
        }));

        // Initialize and start engine
        engine.initialize();
        engine.start();

        // Run application-specific logic
        return runOuch(engine, sessions);
    }

    /**
     * Shutdown OUCH engine specifically.
     */
    private void shutdownOuch(OuchEngine engine) {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception e) {
                log.warn("Error stopping OUCH engine: {}", e.getMessage());
            }
        }
        shutdown();
    }

    /**
     * Get the list of configuration files to load.
     *
     * @return list of config file paths
     */
    protected abstract List<String> getConfigFiles();

    /**
     * Get the engine type for this application (FIX or OUCH).
     *
     * @return the engine type
     */
    protected abstract EngineType getEngineType();

    /**
     * Configure the FIX engine with listeners before starting.
     * Override this method for FIX applications.
     *
     * @param engine the FIX engine
     * @param sessions the created sessions
     */
    protected void configureFix(FixEngine engine, List<FixSession> sessions) {
        // Override in FIX subclasses
    }

    /**
     * Run the FIX application logic after engine is started.
     * Override this method for FIX applications.
     *
     * @param engine the FIX engine
     * @param sessions the created sessions
     * @return exit code (0 for success)
     * @throws Exception if an error occurs
     */
    protected int runFix(FixEngine engine, List<FixSession> sessions) throws Exception {
        // Override in FIX subclasses
        return 0;
    }

    /**
     * Configure the OUCH engine with listeners before starting.
     * Override this method for OUCH applications.
     *
     * @param engine the OUCH engine
     * @param sessions the created sessions
     */
    protected void configureOuch(OuchEngine engine, List<OuchSession> sessions) {
        // Override in OUCH subclasses
    }

    /**
     * Run the OUCH application logic after engine is started.
     * Override this method for OUCH applications.
     *
     * @param engine the OUCH engine
     * @param sessions the created sessions
     * @return exit code (0 for success)
     * @throws Exception if an error occurs
     */
    protected int runOuch(OuchEngine engine, List<OuchSession> sessions) throws Exception {
        // Override in OUCH subclasses
        return 0;
    }

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
     * Set the log level for engine packages.
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

    /**
     * Get the component provider for accessing registered components.
     */
    protected DefaultComponentProvider getProvider() {
        return provider;
    }
}
