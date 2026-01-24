package com.omnibridge.admin.websocket;

import io.javalin.websocket.WsConfig;

import java.util.function.Consumer;

/**
 * Interface for registering WebSocket endpoints with the admin server.
 *
 * <p>Implement this interface to add new WebSocket endpoints to the admin API.
 * WebSocket handlers are registered during server startup.</p>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class MyWebSocket implements WebSocketHandler {
 *     private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
 *
 *     @Override
 *     public String getPath() {
 *         return "/my-updates";
 *     }
 *
 *     @Override
 *     public Consumer<WsConfig> getHandler() {
 *         return ws -> {
 *             ws.onConnect(ctx -> clients.add(ctx));
 *             ws.onClose(ctx -> clients.remove(ctx));
 *             ws.onMessage(ctx -> handleMessage(ctx));
 *         };
 *     }
 *
 *     public void broadcast(String message) {
 *         clients.forEach(ctx -> ctx.send(message));
 *     }
 * }
 * }</pre>
 */
public interface WebSocketHandler {

    /**
     * Get the WebSocket path.
     *
     * <p>This path is appended to the WebSocket base path configured in AdminServerConfig.
     * For example, if WebSocket path is "/ws" and this returns "/sessions",
     * the endpoint will be available at "/ws/sessions".</p>
     *
     * @return the WebSocket path (should start with "/")
     */
    String getPath();

    /**
     * Get the WebSocket configuration handler.
     *
     * @return consumer that configures the WebSocket endpoint
     */
    Consumer<WsConfig> getHandler();

    /**
     * Get a description of this WebSocket handler for logging/documentation.
     *
     * @return description of the WebSocket endpoint
     */
    default String getDescription() {
        return getClass().getSimpleName();
    }

    /**
     * Called when the server is starting.
     * Override to perform any initialization.
     */
    default void onServerStart() {
        // No-op by default
    }

    /**
     * Called when the server is stopping.
     * Override to perform any cleanup.
     */
    default void onServerStop() {
        // No-op by default
    }
}
