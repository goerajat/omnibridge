package com.omnibridge.admin.routes;

import io.javalin.Javalin;

/**
 * Interface for registering REST API routes with the admin server.
 *
 * <p>Implement this interface to add new REST endpoints to the admin API.
 * Routes are registered during server startup.</p>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class MyRoutes implements RouteProvider {
 *     @Override
 *     public String getBasePath() {
 *         return "/my-feature";
 *     }
 *
 *     @Override
 *     public void registerRoutes(Javalin app, String contextPath) {
 *         String base = contextPath + getBasePath();
 *         app.get(base, ctx -> ctx.json(Map.of("status", "ok")));
 *         app.post(base + "/action", ctx -> { ... });
 *     }
 * }
 * }</pre>
 */
public interface RouteProvider {

    /**
     * Get the base path for routes provided by this provider.
     *
     * <p>This path is appended to the server's context path.
     * For example, if context path is "/api" and base path is "/sessions",
     * routes will be available at "/api/sessions".</p>
     *
     * @return the base path (should start with "/")
     */
    String getBasePath();

    /**
     * Register routes with the Javalin application.
     *
     * @param app the Javalin application
     * @param contextPath the server's context path (e.g., "/api")
     */
    void registerRoutes(Javalin app, String contextPath);

    /**
     * Get a description of this route provider for logging/documentation.
     *
     * @return description of the routes provided
     */
    default String getDescription() {
        return getClass().getSimpleName();
    }

    /**
     * Get the priority for route registration order.
     * Lower values are registered first.
     *
     * @return priority (default 100)
     */
    default int getPriority() {
        return 100;
    }
}
