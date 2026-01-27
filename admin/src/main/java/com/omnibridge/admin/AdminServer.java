package com.omnibridge.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.omnibridge.admin.config.AdminServerConfig;
import com.omnibridge.admin.routes.RouteProvider;
import com.omnibridge.admin.websocket.WebSocketHandler;
import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight admin server using Javalin.
 *
 * <p>Provides REST API and WebSocket endpoints for administration.
 * Implements the {@link Component} lifecycle for integration with
 * the application framework.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Extensible route registration via {@link RouteProvider}</li>
 *   <li>WebSocket support via {@link WebSocketHandler}</li>
 *   <li>CORS configuration</li>
 *   <li>JSON serialization with Jackson</li>
 *   <li>Health check endpoint</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * AdminServerConfig config = AdminServerConfig.builder()
 *     .port(8080)
 *     .contextPath("/api")
 *     .build();
 *
 * AdminServer server = new AdminServer("admin-server", config);
 * server.addRouteProvider(new SessionRoutes(sessionService));
 * server.addWebSocketHandler(new SessionStateWebSocket(sessionService));
 *
 * server.initialize();
 * server.startActive();
 * }</pre>
 */
public class AdminServer implements Component {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private final String name;
    private final AdminServerConfig config;
    private final AtomicReference<ComponentState> state;
    private final List<RouteProvider> routeProviders;
    private final List<WebSocketHandler> webSocketHandlers;
    private final ObjectMapper objectMapper;

    private Javalin app;

    /**
     * Create a new AdminServer with the given configuration.
     *
     * @param name the component name
     * @param config the server configuration
     */
    public AdminServer(String name, AdminServerConfig config) {
        this.name = name;
        this.config = config;
        this.state = new AtomicReference<>(ComponentState.UNINITIALIZED);
        this.routeProviders = new CopyOnWriteArrayList<>();
        this.webSocketHandlers = new CopyOnWriteArrayList<>();
        this.objectMapper = createObjectMapper();
    }

    /**
     * Create with default name.
     */
    public AdminServer(AdminServerConfig config) {
        this("admin-server", config);
    }

    /**
     * Add a route provider for REST endpoints.
     *
     * @param provider the route provider
     * @return this server for chaining
     */
    public AdminServer addRouteProvider(RouteProvider provider) {
        routeProviders.add(provider);
        log.debug("[{}] Added route provider: {} -> {}",
                name, provider.getBasePath(), provider.getDescription());
        return this;
    }

    /**
     * Add a WebSocket handler.
     *
     * @param handler the WebSocket handler
     * @return this server for chaining
     */
    public AdminServer addWebSocketHandler(WebSocketHandler handler) {
        webSocketHandlers.add(handler);
        log.debug("[{}] Added WebSocket handler: {} -> {}",
                name, handler.getPath(), handler.getDescription());
        return this;
    }

    /**
     * Get the Javalin application instance.
     * Only available after initialization.
     *
     * @return the Javalin app, or null if not initialized
     */
    public Javalin getApp() {
        return app;
    }

    /**
     * Get the server configuration.
     */
    public AdminServerConfig getConfig() {
        return config;
    }

    // ========== Component Lifecycle ==========

    @Override
    public void initialize() throws Exception {
        if (!state.compareAndSet(ComponentState.UNINITIALIZED, ComponentState.INITIALIZED)) {
            throw new IllegalStateException("Cannot initialize from state: " + state.get());
        }

        if (!config.isEnabled()) {
            log.info("[{}] Admin server is disabled", name);
            return;
        }

        log.info("[{}] Initializing admin server on {}:{}", name, config.getHost(), config.getPort());

        // Create Javalin app with configuration
        app = Javalin.create(cfg -> {
            // JSON serialization
            cfg.jsonMapper(new JavalinJackson(objectMapper, true));

            // CORS
            if (config.isCorsEnabled()) {
                cfg.bundledPlugins.enableCors(cors -> {
                    cors.addRule(rule -> {
                        for (String origin : config.getCorsAllowedOrigins()) {
                            if ("*".equals(origin)) {
                                rule.anyHost();
                            } else {
                                rule.allowHost(origin);
                            }
                        }
                    });
                });
            }

            // Request logging
            cfg.requestLogger.http((ctx, ms) -> {
                log.debug("[{}] {} {} -> {} ({}ms)",
                        name, ctx.method(), ctx.path(), ctx.status(), ms);
            });
        });

        // Register error handlers
        registerErrorHandlers();

        // Register health check
        registerHealthCheck();

        // Register route providers
        registerRoutes();

        // Register WebSocket handlers
        registerWebSockets();

        log.info("[{}] Initialized with {} route providers, {} WebSocket handlers",
                name, routeProviders.size(), webSocketHandlers.size());
    }

    @Override
    public void startActive() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + currentState);
        }

        if (!config.isEnabled()) {
            log.info("[{}] Admin server is disabled, not starting", name);
            state.set(ComponentState.ACTIVE);
            return;
        }

        log.info("[{}] Starting admin server on {}:{}", name, config.getHost(), config.getPort());

        // Notify WebSocket handlers
        for (WebSocketHandler handler : webSocketHandlers) {
            handler.onServerStart();
        }

        // Start the server
        app.start(config.getHost(), config.getPort());

        state.set(ComponentState.ACTIVE);
        log.info("[{}] Admin server started at http://{}:{}{}",
                name, config.getHost(), config.getPort(), config.getContextPath());
    }

    @Override
    public void startStandby() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + currentState);
        }

        // In standby mode, we don't start the server
        log.info("[{}] Starting in STANDBY mode (server not started)", name);
        state.set(ComponentState.STANDBY);
    }

    @Override
    public void becomeActive() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + currentState);
        }

        if (config.isEnabled() && app != null) {
            log.info("[{}] Transitioning to ACTIVE, starting server", name);

            for (WebSocketHandler handler : webSocketHandlers) {
                handler.onServerStart();
            }

            app.start(config.getHost(), config.getPort());
        }

        state.set(ComponentState.ACTIVE);
    }

    @Override
    public void becomeStandby() throws Exception {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + currentState);
        }

        if (config.isEnabled() && app != null) {
            log.info("[{}] Transitioning to STANDBY, stopping server", name);

            for (WebSocketHandler handler : webSocketHandlers) {
                handler.onServerStop();
            }

            app.stop();
        }

        state.set(ComponentState.STANDBY);
    }

    @Override
    public void stop() {
        ComponentState currentState = state.get();
        if (currentState == ComponentState.STOPPED) {
            log.debug("[{}] Already stopped", name);
            return;
        }

        log.info("[{}] Stopping admin server", name);

        if (app != null) {
            for (WebSocketHandler handler : webSocketHandlers) {
                try {
                    handler.onServerStop();
                } catch (Exception e) {
                    log.warn("[{}] Error stopping WebSocket handler: {}", name, handler.getPath(), e);
                }
            }

            try {
                app.stop();
            } catch (Exception e) {
                log.warn("[{}] Error stopping Javalin", name, e);
            }
        }

        state.set(ComponentState.STOPPED);
        log.info("[{}] Admin server stopped", name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ComponentState getState() {
        return state.get();
    }

    // ========== Private Methods ==========

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    private void registerErrorHandlers() {
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("error", e.getMessage()));
        });

        app.exception(IllegalStateException.class, (e, ctx) -> {
            ctx.status(HttpStatus.CONFLICT);
            ctx.json(Map.of("error", e.getMessage()));
        });

        app.exception(Exception.class, (e, ctx) -> {
            log.error("[{}] Unhandled exception", name, e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("error", "Internal server error"));
        });

        app.error(HttpStatus.NOT_FOUND, ctx -> {
            ctx.json(Map.of("error", "Not found: " + ctx.path()));
        });
    }

    private void registerHealthCheck() {
        String healthPath = config.getContextPath() + "/health";
        app.get(healthPath, ctx -> {
            ctx.json(Map.of(
                    "status", "UP",
                    "component", name,
                    "state", state.get().toString()
            ));
        });
        log.debug("[{}] Registered health check at {}", name, healthPath);
    }

    private void registerRoutes() {
        // Sort by priority
        List<RouteProvider> sorted = new ArrayList<>(routeProviders);
        sorted.sort(Comparator.comparingInt(RouteProvider::getPriority));

        for (RouteProvider provider : sorted) {
            try {
                provider.registerRoutes(app, config.getContextPath());
                log.info("[{}] Registered routes: {} -> {}",
                        name, config.getContextPath() + provider.getBasePath(), provider.getDescription());
            } catch (Exception e) {
                log.error("[{}] Failed to register routes for {}", name, provider.getDescription(), e);
                throw new RuntimeException("Failed to register routes", e);
            }
        }
    }

    private void registerWebSockets() {
        if (!config.isWebsocketEnabled()) {
            log.debug("[{}] WebSocket is disabled", name);
            return;
        }

        for (WebSocketHandler handler : webSocketHandlers) {
            String fullPath = config.getWebsocketPath() + handler.getPath();
            try {
                // Pass idle timeout to handler if it supports it
                if (handler instanceof ConfigurableWebSocketHandler) {
                    ((ConfigurableWebSocketHandler) handler)
                            .setIdleTimeoutMs(config.getWebsocketIdleTimeoutMs());
                }
                app.ws(fullPath, handler.getHandler());
                log.info("[{}] Registered WebSocket: {} -> {}",
                        name, fullPath, handler.getDescription());
            } catch (Exception e) {
                log.error("[{}] Failed to register WebSocket for {}", name, handler.getDescription(), e);
                throw new RuntimeException("Failed to register WebSocket", e);
            }
        }
    }

    /**
     * Interface for WebSocket handlers that can be configured with idle timeout.
     */
    public interface ConfigurableWebSocketHandler extends WebSocketHandler {
        void setIdleTimeoutMs(long timeoutMs);
    }
}
