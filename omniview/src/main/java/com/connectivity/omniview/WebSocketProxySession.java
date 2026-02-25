package com.connectivity.omniview;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages a bidirectional WebSocket proxy pair between a browser client
 * and a backend admin server.
 */
public class WebSocketProxySession {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProxySession.class);

    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(5);

    private final String appId;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Session browserSession;
    private volatile Session backendSession;

    public WebSocketProxySession(String appId) {
        this.appId = appId;
    }

    /**
     * Listener for the browser-side WebSocket connection.
     * Receives messages from the browser and forwards them to the backend.
     */
    public class BrowserListener implements Session.Listener.AutoDemanding {

        @Override
        public void onWebSocketOpen(Session session) {
            browserSession = session;
            session.setIdleTimeout(SESSION_IDLE_TIMEOUT);
            LOG.info("Browser WebSocket opened for app: {} (idle timeout: {})", appId, SESSION_IDLE_TIMEOUT);

            // If the backend already failed before this session opened,
            // close immediately so the browser can reconnect.
            if (closed.get()) {
                LOG.info("Browser WebSocket opened but proxy already closed for app: {} — closing", appId);
                closeQuietly(session);
            }
        }

        @Override
        public void onWebSocketText(String message) {
            if (closed.get()) return;
            Session backend = backendSession;
            if (backend != null && backend.isOpen()) {
                backend.sendText(message, Callback.NOOP);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            LOG.info("Browser WebSocket closed for app: {} (code={}, reason={})", appId, statusCode, reason);
            closeAll();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            LOG.warn("Browser WebSocket error for app: {}: {}", appId, cause.getMessage());
            closeAll();
        }
    }

    /**
     * Listener for the backend-side WebSocket connection.
     * Receives messages from the backend admin server and forwards them to the browser.
     */
    public class BackendListener implements Session.Listener.AutoDemanding {

        @Override
        public void onWebSocketOpen(Session session) {
            backendSession = session;
            session.setIdleTimeout(SESSION_IDLE_TIMEOUT);
            LOG.info("Backend WebSocket opened for app: {} (idle timeout: {})", appId, SESSION_IDLE_TIMEOUT);
        }

        @Override
        public void onWebSocketText(String message) {
            if (closed.get()) return;
            Session browser = browserSession;
            if (browser != null && browser.isOpen()) {
                browser.sendText(message, Callback.NOOP);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            LOG.info("Backend WebSocket closed for app: {} (code={}, reason={})", appId, statusCode, reason);
            closeAll();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            LOG.warn("Backend WebSocket error for app: {}: {}", appId, cause.getMessage());
            closeAll();
        }
    }

    private void closeAll() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(browserSession);
            closeQuietly(backendSession);
        }
    }

    private void closeQuietly(Session session) {
        if (session != null && session.isOpen()) {
            try {
                session.close(org.eclipse.jetty.websocket.api.StatusCode.NORMAL, "proxy closed", Callback.NOOP);
            } catch (Exception e) {
                LOG.debug("Error closing session: {}", e.getMessage());
            }
        }
    }
}
