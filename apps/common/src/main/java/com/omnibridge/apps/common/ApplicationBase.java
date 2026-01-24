package com.omnibridge.apps.common;

import com.omnibridge.admin.AdminServer;
import com.omnibridge.admin.config.AdminServerConfig;
import com.omnibridge.admin.routes.SessionRoutes;
import com.omnibridge.admin.websocket.SessionStateWebSocket;
import com.omnibridge.config.ClockProvider;
import com.omnibridge.config.provider.DefaultComponentProvider;
import com.omnibridge.config.schedule.SchedulerConfig;
import com.omnibridge.config.schedule.SessionScheduler;
import com.omnibridge.config.session.DefaultSessionManagementService;
import com.omnibridge.config.session.SessionManagementService;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.config.FixEngineConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.network.NetworkEventLoop;
import com.omnibridge.network.config.NetworkConfig;
import com.omnibridge.ouch.engine.OuchEngine;
import com.omnibridge.ouch.engine.config.OuchEngineConfig;
import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import com.omnibridge.persistence.memory.MemoryMappedLogStore;
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

        // Register SessionManagementService and AdminServer if admin is enabled
        boolean adminEnabled = config.hasPath("admin.enabled") && config.getBoolean("admin.enabled");
        if (adminEnabled) {
            // Register SessionManagementService factory
            provider.register(SessionManagementService.class, (name, cfg, p) -> {
                return new DefaultSessionManagementService();
            });

            // Register AdminServer factory
            provider.register(AdminServer.class, (name, cfg, p) -> {
                AdminServerConfig adminConfig = AdminServerConfig.fromConfig(cfg);
                SessionManagementService sessionService = p.getComponent(SessionManagementService.class);

                AdminServer server = new AdminServer(adminConfig);
                server.addRouteProvider(new SessionRoutes(sessionService));
                server.addWebSocketHandler(new SessionStateWebSocket(sessionService));

                return server;
            });

            log.info("Admin API enabled on port {}",
                    config.hasPath("admin.port") ? config.getInt("admin.port") : 8080);
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

        // Create engine (triggers factory, but doesn't initialize yet)
        FixEngine engine = provider.getComponent(FixEngine.class);

        // Initialize components (this calls engine.initialize() which creates sessions)
        provider.initialize();

        // Get sessions (created by engine.initialize(), with schedules automatically associated)
        List<FixSession> sessions = engine.getAllSessions();

        if (sessions.isEmpty()) {
            log.error("No sessions configured");
            return 1;
        }


        // Let subclass configure listeners
        configureFix(engine, sessions);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            running = false;
            shutdown();
        }));

        // Start components
        provider.start();

        // Run application-specific logic
        return runFix(engine, sessions);
    }

    /**
     * Initialize and run an OUCH engine application.
     */
    private int initializeAndRunOuch(Config config) throws Exception {
        // Register OuchEngine factory
        provider.register(OuchEngine.class, (name, cfg, p) -> {
            Config ouchConfig = cfg.hasPath("ouch-engine") ? cfg.getConfig("ouch-engine") : cfg;
            OuchEngineConfig engineConfig = OuchEngineConfig.fromConfig(ouchConfig);
            return new OuchEngine(engineConfig, p);
        });

        // Create engine (triggers factory, but doesn't initialize yet)
        OuchEngine engine = provider.getComponent(OuchEngine.class);

        // Initialize components (this calls engine.initialize() which creates sessions)
        provider.initialize();

        // Get sessions (created by engine.initialize())
        List<OuchSession> sessions = new java.util.ArrayList<>(engine.getSessions());

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
            shutdown();
        }));

        // Start components
        provider.start();

        // Run application-specific logic
        return runOuch(engine, sessions);
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
