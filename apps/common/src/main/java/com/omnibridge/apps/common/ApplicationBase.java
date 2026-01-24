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
 * <p>Supports two modes of operation:</p>
 * <ul>
 *   <li><b>Config-driven mode</b>: When a "components" section exists in the configuration,
 *       components are automatically registered and created based on the config.
 *       Use lifecycle hooks: {@link #preInitialize()}, {@link #postInitialize()},
 *       {@link #preStart()}, {@link #postStart()}.</li>
 *   <li><b>Legacy mode</b>: When no "components" section exists, uses the engine-type
 *       based approach with {@link #configureFix}/{@link #configureOuch} and
 *       {@link #runFix}/{@link #runOuch} methods.</li>
 * </ul>
 *
 * <p>For config-driven applications (recommended):</p>
 * <pre>{@code
 * public class MyApp extends ApplicationBase {
 *     protected void postStart() throws Exception {
 *         FixEngine engine = provider.getComponent(FixEngine.class);
 *         // Add listeners, run app logic
 *     }
 *
 *     protected List<String> getConfigFiles() {
 *         return List.of("my-app.conf");
 *     }
 * }
 * }</pre>
 *
 * <p>For legacy FIX applications, override:</p>
 * <ul>
 *   <li>{@link #getEngineType()} to return {@link EngineType#FIX}</li>
 *   <li>{@link #configureFix(FixEngine, List)}</li>
 *   <li>{@link #runFix(FixEngine, List)}</li>
 * </ul>
 *
 * <p>For legacy OUCH applications, override:</p>
 * <ul>
 *   <li>{@link #getEngineType()} to return {@link EngineType#OUCH}</li>
 *   <li>{@link #configureOuch(OuchEngine, List)}</li>
 *   <li>{@link #runOuch(OuchEngine, List)}</li>
 * </ul>
 */
public abstract class ApplicationBase implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ApplicationBase.class);

    /**
     * Engine types supported by the application base (for legacy mode).
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

            // Check if config-driven mode is enabled
            if (config.hasPath("components")) {
                return runConfigDriven();
            } else {
                return runLegacy(config);
            }

        } catch (Exception e) {
            log.error("Error in application", e);
            shutdown();
            return 1;
        }
    }

    // ==================== Config-Driven Mode ====================

    /**
     * Run using config-driven component registration.
     */
    private int runConfigDriven() throws Exception {
        try {
            // 1. Load components from config (auto-registers factories and creates components)
            provider.loadComponentsFromConfig();

            // 2. Allow subclass to register additional components or customize
            preInitialize();

            // 3. Initialize all components
            provider.initialize();
            postInitialize();

            // 4. Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown signal received");
                running = false;
            }));

            // 5. Start all components
            preStart();
            provider.start();
            postStart();

            // 6. Wait for shutdown signal
            awaitShutdown();

            return 0;
        } finally {
            shutdown();
        }
    }

    /**
     * Called before components are initialized.
     * Subclass can register additional factories or customize configuration.
     *
     * @throws Exception if an error occurs
     */
    protected void preInitialize() throws Exception {
        // Override in subclasses
    }

    /**
     * Called after all components are initialized but before starting.
     * Components are created but not yet active.
     *
     * @throws Exception if an error occurs
     */
    protected void postInitialize() throws Exception {
        // Override in subclasses
    }

    /**
     * Called before components are started.
     *
     * @throws Exception if an error occurs
     */
    protected void preStart() throws Exception {
        // Override in subclasses
    }

    /**
     * Called after all components are started and active.
     * This is where application-specific logic should run.
     *
     * <p>Example:</p>
     * <pre>{@code
     * protected void postStart() throws Exception {
     *     FixEngine engine = provider.getComponent(FixEngine.class);
     *     engine.addMessageListener(new MyMessageListener());
     *     log.info("Application ready");
     * }
     * }</pre>
     *
     * @throws Exception if an error occurs
     */
    protected void postStart() throws Exception {
        // Override in subclasses
    }

    /**
     * Wait for shutdown signal.
     * Default implementation waits for the running flag to become false.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    protected void awaitShutdown() throws InterruptedException {
        while (running) {
            Thread.sleep(1000);
        }
    }

    // ==================== Legacy Mode ====================

    /**
     * Run using legacy engine-type based approach.
     */
    private int runLegacy(Config config) throws Exception {
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
    }

    /**
     * Register factories common to both FIX and OUCH engines (legacy mode).
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
     * Initialize and run a FIX engine application (legacy mode).
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
     * Initialize and run an OUCH engine application (legacy mode).
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

    // ==================== Abstract/Hook Methods ====================

    /**
     * Get the list of configuration files to load.
     *
     * @return list of config file paths
     */
    protected abstract List<String> getConfigFiles();

    /**
     * Get the engine type for this application (FIX or OUCH).
     * Only used in legacy mode (when no "components" section in config).
     *
     * @return the engine type
     */
    protected EngineType getEngineType() {
        // Default to FIX for backward compatibility
        return EngineType.FIX;
    }

    /**
     * Configure the FIX engine with listeners before starting (legacy mode).
     * Override this method for FIX applications.
     *
     * @param engine the FIX engine
     * @param sessions the created sessions
     */
    protected void configureFix(FixEngine engine, List<FixSession> sessions) {
        // Override in FIX subclasses
    }

    /**
     * Run the FIX application logic after engine is started (legacy mode).
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
     * Configure the OUCH engine with listeners before starting (legacy mode).
     * Override this method for OUCH applications.
     *
     * @param engine the OUCH engine
     * @param sessions the created sessions
     */
    protected void configureOuch(OuchEngine engine, List<OuchSession> sessions) {
        // Override in OUCH subclasses
    }

    /**
     * Run the OUCH application logic after engine is started (legacy mode).
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

    // ==================== Utility Methods ====================

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

        // Also set for omnibridge packages
        ch.qos.logback.classic.Logger omnibridgeLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.omnibridge");
        omnibridgeLogger.setLevel(Level.toLevel(level));
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
