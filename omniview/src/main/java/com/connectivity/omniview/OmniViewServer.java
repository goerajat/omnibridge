package com.connectivity.omniview;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public OmniViewServer(int port) {
        this.port = port;
        this.server = new Server(port);
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

        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(spaHandler);

        server.setHandler(contextHandler);

        // Write PID file for process management
        writePidFile();

        server.start();
        LOG.info("OmniView started on http://localhost:{}", port);
        LOG.info("Press Ctrl+C to stop");

        // Add shutdown hook to clean up PID file
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));

        server.join();
    }

    public void stop() throws Exception {
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
}
