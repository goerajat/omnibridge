package com.connectivity.omniview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Embedded Jetty server for OmniView web application.
 * Serves static files from the bundled React application.
 */
public class OmniViewServer {

    private static final Logger LOG = LoggerFactory.getLogger(OmniViewServer.class);
    private static final String DEFAULT_PORT = "3000";
    private static final String APP_NAME = "omniview";

    private final Server server;
    private final int port;
    private final AppConfigService appConfigService;
    private final ObjectMapper objectMapper;
    private final WebSocketClient wsClient;
    private final HttpClient httpClient;

    public OmniViewServer(int port) {
        this.port = port;
        this.server = new Server(port);
        this.appConfigService = new AppConfigService();
        this.objectMapper = new ObjectMapper();
        this.wsClient = new WebSocketClient();
        this.wsClient.setIdleTimeout(Duration.ofMinutes(5));
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public void start() throws Exception {
        // Try to load resources from classpath first (when running from JAR)
        URL staticUrl = getClass().getClassLoader().getResource("static");
        Resource baseResource;

        ResourceHandler resourceHandler = new ResourceHandler();

        if (staticUrl != null) {
            LOG.info("Serving static files from classpath: {}", staticUrl);
            baseResource = ResourceFactory.of(resourceHandler).newResource(staticUrl.toURI());
        } else {
            // Fall back to dist directory (development mode)
            Path distPath = Path.of("dist");
            if (Files.exists(distPath)) {
                LOG.info("Serving static files from: {}", distPath.toAbsolutePath());
                baseResource = ResourceFactory.of(resourceHandler).newResource(distPath);
            } else {
                throw new IllegalStateException(
                    "No static resources found. Run 'npm run build' first or ensure JAR is properly packaged.");
            }
        }

        // Configure resource handler
        resourceHandler.setBaseResource(baseResource);
        resourceHandler.setDirAllowed(false);
        resourceHandler.setWelcomeFiles(new String[]{"index.html"});

        // Create SPA handler that wraps ResourceHandler properly
        SpaHandler spaHandler = new SpaHandler(resourceHandler, baseResource);

        // Create API handler that wraps SPA handler (handles /api/apps and /api/proxy before static files)
        ApiHandler apiHandler = new ApiHandler(spaHandler, appConfigService, objectMapper, httpClient);

        // Create context handler
        ContextHandler contextHandler = new ContextHandler("/");

        // Create WebSocket upgrade handler and explicitly build the handler chain.
        // WebSocketUpgradeHandler.from() may not reliably insert itself into the chain
        // in all Jetty 12.0.x versions, so we wire it up manually.
        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, contextHandler, container -> {
            container.setIdleTimeout(Duration.ofMinutes(5));
            container.addMapping("/ws/proxy/*", (upgradeRequest, upgradeResponse, callback) -> {
                String requestPath = upgradeRequest.getHttpURI().getPath();
                String appId = requestPath.substring("/ws/proxy/".length());

                if (appId.isEmpty()) {
                    LOG.warn("WebSocket proxy: missing appId");
                    callback.failed(new IllegalArgumentException("Missing appId"));
                    return null;
                }

                Optional<AppConfigService.AppConfig> appOpt = appConfigService.getApp(appId);
                if (appOpt.isEmpty()) {
                    LOG.warn("WebSocket proxy: app not found: {}", appId);
                    callback.failed(new IllegalArgumentException("App not found: " + appId));
                    return null;
                }

                AppConfigService.AppConfig app = appOpt.get();
                WebSocketProxySession proxySession = new WebSocketProxySession(appId);
                WebSocketProxySession.BackendListener backendListener = proxySession.new BackendListener();

                String backendUrl = "ws://" + app.host() + ":" + app.port() + "/ws/sessions";
                LOG.info("WebSocket proxy: connecting to backend {} for app {}", backendUrl, appId);

                try {
                    wsClient.connect(backendListener, URI.create(backendUrl));
                } catch (Exception e) {
                    LOG.error("WebSocket proxy: failed to connect to backend for app {}", appId, e);
                    callback.failed(e);
                    return null;
                }

                // Do NOT call callback.succeeded() here — Jetty 12 auto-completes
                // the callback when a non-null listener is returned. Calling it twice
                // causes the browser session to close immediately with code 1006.
                return proxySession.new BrowserListener();
            });
        });

        // Chain: ContextHandler → WebSocketUpgradeHandler → ApiHandler → SpaHandler → ResourceHandler
        wsHandler.setHandler(apiHandler);
        contextHandler.setHandler(wsHandler);

        server.setHandler(contextHandler);

        // Write PID file for process management
        writePidFile();

        // Start WebSocket client before server
        wsClient.start();

        server.start();
        LOG.info("OmniView started on http://localhost:{}", port);
        LOG.info("Press Ctrl+C to stop");

        // Add shutdown hook to clean up PID file
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));

        server.join();
    }

    public void stop() throws Exception {
        wsClient.stop();
        server.stop();
        cleanup();
    }

    private void writePidFile() {
        try {
            Path pidDir = Path.of(System.getProperty("java.io.tmpdir"));
            Path pidFile = pidDir.resolve(APP_NAME + ".pid");
            long pid = ProcessHandle.current().pid();
            Files.writeString(pidFile, String.valueOf(pid));
            LOG.debug("PID file written: {} (PID: {})", pidFile, pid);
        } catch (IOException e) {
            LOG.warn("Failed to write PID file: {}", e.getMessage());
        }
    }

    private void cleanup() {
        try {
            Path pidFile = Path.of(System.getProperty("java.io.tmpdir"), APP_NAME + ".pid");
            Files.deleteIfExists(pidFile);
        } catch (IOException e) {
            LOG.warn("Failed to delete PID file: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("port", DEFAULT_PORT));

        // Allow port override via command line
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid port argument '{}', using default: {}", args[0], port);
            }
        }

        try {
            OmniViewServer server = new OmniViewServer(port);
            server.start();
        } catch (Exception e) {
            LOG.error("Failed to start OmniView server", e);
            System.exit(1);
        }
    }

    /**
     * Custom handler that wraps ResourceHandler and serves index.html for SPA routes.
     * Extends Handler.Wrapper to properly include ResourceHandler in the handler chain.
     */
    private static class SpaHandler extends Handler.Wrapper {

        private final Resource baseResource;

        public SpaHandler(Handler handler, Resource baseResource) {
            super(handler);
            this.baseResource = baseResource;
        }

        @Override
        public boolean handle(org.eclipse.jetty.server.Request request,
                             org.eclipse.jetty.server.Response response,
                             org.eclipse.jetty.util.Callback callback) throws Exception {

            String path = request.getHttpURI().getPath();

            // For SPA routes (no file extension, not API/WS), serve index.html
            if (!path.startsWith("/api") && !path.startsWith("/ws") && !path.contains(".")) {
                // Check if a real resource exists at this path
                String resourcePath = path.length() > 1 ? path.substring(1) : "";
                Resource resource = resourcePath.isEmpty() ? null : baseResource.resolve(resourcePath);

                // If no resource exists, serve index.html for SPA routing
                if (resource == null || !resource.exists()) {
                    Resource indexResource = baseResource.resolve("index.html");
                    if (indexResource != null && indexResource.exists()) {
                        response.setStatus(200);
                        response.getHeaders().put("Content-Type", "text/html; charset=utf-8");

                        // Read content from the resource
                        try (InputStream is = indexResource.newInputStream()) {
                            byte[] content = is.readAllBytes();
                            response.write(true, ByteBuffer.wrap(content), callback);
                            return true;
                        }
                    }
                }
            }

            // Delegate to ResourceHandler for actual files
            return super.handle(request, response, callback);
        }
    }

    /**
     * API handler for REST endpoints.
     * Handles /api/apps/* requests for app configuration management
     * and /api/proxy/{appId}/* for proxying requests to backend admin servers.
     */
    private static class ApiHandler extends Handler.Wrapper {

        private final AppConfigService appConfigService;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;

        public ApiHandler(Handler handler, AppConfigService appConfigService, ObjectMapper objectMapper,
                         HttpClient httpClient) {
            super(handler);
            this.appConfigService = appConfigService;
            this.objectMapper = objectMapper;
            this.httpClient = httpClient;
        }

        @Override
        public boolean handle(org.eclipse.jetty.server.Request request,
                             org.eclipse.jetty.server.Response response,
                             org.eclipse.jetty.util.Callback callback) throws Exception {

            String path = request.getHttpURI().getPath();
            String method = request.getMethod();

            // Handle /api/proxy/{appId}/* endpoints
            if (path.startsWith("/api/proxy/")) {
                return handleProxyApi(request, response, callback, path, method);
            }

            // Handle /api/apps endpoints
            if (path.equals("/api/apps") || path.startsWith("/api/apps/")) {
                return handleAppsApi(request, response, callback, path, method);
            }

            // Delegate to WebSocket/SPA handler for other requests
            return super.handle(request, response, callback);
        }

        private boolean handleProxyApi(org.eclipse.jetty.server.Request request,
                                       org.eclipse.jetty.server.Response response,
                                       org.eclipse.jetty.util.Callback callback,
                                       String path,
                                       String method) throws Exception {
            // Path format: /api/proxy/{appId}/remaining/path
            String afterProxy = path.substring("/api/proxy/".length());
            int slashIndex = afterProxy.indexOf('/');

            String appId;
            String remainingPath;
            if (slashIndex >= 0) {
                appId = afterProxy.substring(0, slashIndex);
                remainingPath = afterProxy.substring(slashIndex); // includes leading /
            } else {
                appId = afterProxy;
                remainingPath = "/";
            }

            Optional<AppConfigService.AppConfig> appOpt = appConfigService.getApp(appId);
            if (appOpt.isEmpty()) {
                sendJson(response, callback, 404, new ErrorResponse("App not found: " + appId));
                return true;
            }

            AppConfigService.AppConfig app = appOpt.get();
            String targetUrl = "http://" + app.host() + ":" + app.port() + remainingPath;

            // Build query string if present
            String query = request.getHttpURI().getQuery();
            if (query != null && !query.isEmpty()) {
                targetUrl += "?" + query;
            }

            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(10));

                // Forward the request method and body
                if ("POST".equals(method) || "PUT".equals(method)) {
                    byte[] body;
                    try (InputStream is = org.eclipse.jetty.server.Request.asInputStream(request)) {
                        body = is.readAllBytes();
                    }
                    String contentType = request.getHeaders().get("Content-Type");
                    if (contentType == null) contentType = "application/json";
                    reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                              .header("Content-Type", contentType);
                } else if ("DELETE".equals(method)) {
                    reqBuilder.DELETE();
                } else {
                    reqBuilder.GET();
                }

                HttpResponse<byte[]> backendResponse = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

                response.setStatus(backendResponse.statusCode());

                // Forward content-type from backend
                backendResponse.headers().firstValue("Content-Type")
                    .ifPresent(ct -> response.getHeaders().put("Content-Type", ct));

                response.write(true, ByteBuffer.wrap(backendResponse.body()), callback);
                return true;

            } catch (Exception e) {
                LOG.error("Proxy error for app {} -> {}: {}", appId, targetUrl, e.getMessage());
                sendJson(response, callback, 502,
                    new ErrorResponse("Failed to reach backend: " + e.getMessage()));
                return true;
            }
        }

        private boolean handleAppsApi(org.eclipse.jetty.server.Request request,
                                      org.eclipse.jetty.server.Response response,
                                      org.eclipse.jetty.util.Callback callback,
                                      String path,
                                      String method) throws Exception {

            // Set CORS headers for API requests
            response.getHeaders().put("Access-Control-Allow-Origin", "*");
            response.getHeaders().put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.getHeaders().put("Access-Control-Allow-Headers", "Content-Type");

            // Handle preflight
            if ("OPTIONS".equals(method)) {
                response.setStatus(204);
                callback.succeeded();
                return true;
            }

            try {
                // GET /api/apps - List all apps
                if (path.equals("/api/apps") && "GET".equals(method)) {
                    var apps = appConfigService.getAllApps();
                    sendJson(response, callback, 200, apps);
                    return true;
                }

                // POST /api/apps - Add new app
                if (path.equals("/api/apps") && "POST".equals(method)) {
                    String body = readRequestBody(request);
                    AppConfigService.AppConfig app = objectMapper.readValue(body, AppConfigService.AppConfig.class);
                    AppConfigService.AppConfig created = appConfigService.addApp(app);
                    sendJson(response, callback, 201, created);
                    return true;
                }

                // Extract app ID from path: /api/apps/{id}
                String appId = null;
                if (path.startsWith("/api/apps/") && path.length() > "/api/apps/".length()) {
                    String remaining = path.substring("/api/apps/".length());
                    int slashIndex = remaining.indexOf('/');
                    if (slashIndex >= 0) {
                        appId = remaining.substring(0, slashIndex);
                        String action = remaining.substring(slashIndex + 1);

                        // POST /api/apps/{id}/toggle - Toggle app enabled state
                        if ("toggle".equals(action) && "POST".equals(method)) {
                            var result = appConfigService.toggleApp(appId);
                            if (result.isPresent()) {
                                sendJson(response, callback, 200, result.get());
                            } else {
                                sendJson(response, callback, 404, new ErrorResponse("App not found: " + appId));
                            }
                            return true;
                        }
                    } else {
                        appId = remaining;
                    }
                }

                if (appId != null) {
                    // GET /api/apps/{id} - Get app by ID
                    if ("GET".equals(method)) {
                        var app = appConfigService.getApp(appId);
                        if (app.isPresent()) {
                            sendJson(response, callback, 200, app.get());
                        } else {
                            sendJson(response, callback, 404, new ErrorResponse("App not found: " + appId));
                        }
                        return true;
                    }

                    // PUT /api/apps/{id} - Update app
                    if ("PUT".equals(method)) {
                        String body = readRequestBody(request);
                        AppConfigService.AppConfig updates = objectMapper.readValue(body, AppConfigService.AppConfig.class);
                        var result = appConfigService.updateApp(appId, updates);
                        if (result.isPresent()) {
                            sendJson(response, callback, 200, result.get());
                        } else {
                            sendJson(response, callback, 404, new ErrorResponse("App not found: " + appId));
                        }
                        return true;
                    }

                    // DELETE /api/apps/{id} - Remove app
                    if ("DELETE".equals(method)) {
                        boolean removed = appConfigService.removeApp(appId);
                        if (removed) {
                            response.setStatus(204);
                            callback.succeeded();
                        } else {
                            sendJson(response, callback, 404, new ErrorResponse("App not found: " + appId));
                        }
                        return true;
                    }
                }

                // Method not allowed
                sendJson(response, callback, 405, new ErrorResponse("Method not allowed"));
                return true;

            } catch (Exception e) {
                LOG.error("Error handling API request: {} {}", method, path, e);
                sendJson(response, callback, 500, new ErrorResponse("Internal server error: " + e.getMessage()));
                return true;
            }
        }

        private String readRequestBody(org.eclipse.jetty.server.Request request) throws IOException {
            try (InputStream is = org.eclipse.jetty.server.Request.asInputStream(request)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private void sendJson(org.eclipse.jetty.server.Response response,
                             org.eclipse.jetty.util.Callback callback,
                             int status,
                             Object data) throws Exception {
            response.setStatus(status);
            response.getHeaders().put("Content-Type", "application/json");
            String json = objectMapper.writeValueAsString(data);
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
        }

        private record ErrorResponse(String error) {}
    }
}
