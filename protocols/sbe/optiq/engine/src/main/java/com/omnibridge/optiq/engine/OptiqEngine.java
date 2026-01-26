package com.omnibridge.optiq.engine;

import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.optiq.engine.config.OptiqEngineConfig;
import com.omnibridge.optiq.engine.config.OptiqSessionConfig;
import com.omnibridge.sbe.engine.SbeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Euronext Optiq protocol engine.
 * <p>
 * Manages multiple Optiq sessions for order entry on Euronext markets.
 * <p>
 * Example usage:
 * <pre>
 * OptiqEngineConfig config = OptiqEngineConfig.fromConfig(hoconConfig);
 * OptiqEngine engine = new OptiqEngine(config, provider);
 * engine.initialize();
 * engine.start();
 *
 * // Connect a session
 * engine.connect("session1");
 *
 * // Stop the engine
 * engine.stop();
 * </pre>
 */
public class OptiqEngine extends SbeEngine<OptiqSession, OptiqSessionConfig, OptiqEngineConfig> {

    private static final Logger log = LoggerFactory.getLogger(OptiqEngine.class);

    private static final String ENGINE_NAME = "Optiq";

    /**
     * Creates an Optiq engine with configuration.
     *
     * @param config the engine configuration
     */
    public OptiqEngine(OptiqEngineConfig config) {
        super(config);
    }

    /**
     * Creates an Optiq engine using the provider pattern.
     *
     * @param config the engine configuration
     * @param provider the component provider
     */
    public OptiqEngine(OptiqEngineConfig config, ComponentProvider provider) {
        super(config, provider);
    }

    @Override
    protected String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    protected OptiqSession createSession(OptiqSessionConfig sessionConfig) {
        return new OptiqSession(sessionConfig, logStore);
    }

    /**
     * Gets a session by ID and casts to OptiqSession.
     *
     * @param sessionId the session ID
     * @return the session, or null if not found
     */
    public OptiqSession getOptiqSession(String sessionId) {
        return getSession(sessionId);
    }

    /**
     * Sends a heartbeat on all established sessions.
     * Call this periodically to maintain connections.
     */
    public void sendHeartbeats() {
        for (OptiqSession session : getSessions()) {
            if (session.getSessionState().isEstablished() && session.isHeartbeatDue()) {
                session.sendHeartbeat();
            }
        }
    }
}
