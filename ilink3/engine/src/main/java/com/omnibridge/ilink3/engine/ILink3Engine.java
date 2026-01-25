package com.omnibridge.ilink3.engine;

import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.ilink3.engine.config.ILink3EngineConfig;
import com.omnibridge.ilink3.engine.config.ILink3SessionConfig;
import com.omnibridge.sbe.engine.SbeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CME iLink 3 protocol engine.
 * <p>
 * Manages multiple iLink 3 sessions for order entry on CME Globex markets.
 * <p>
 * Example usage:
 * <pre>
 * ILink3EngineConfig config = ILink3EngineConfig.fromConfig(hoconConfig);
 * ILink3Engine engine = new ILink3Engine(config, provider);
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
public class ILink3Engine extends SbeEngine<ILink3Session, ILink3SessionConfig, ILink3EngineConfig> {

    private static final Logger log = LoggerFactory.getLogger(ILink3Engine.class);

    private static final String ENGINE_NAME = "iLink 3";

    /**
     * Creates an iLink 3 engine with configuration.
     *
     * @param config the engine configuration
     */
    public ILink3Engine(ILink3EngineConfig config) {
        super(config);
    }

    /**
     * Creates an iLink 3 engine using the provider pattern.
     *
     * @param config the engine configuration
     * @param provider the component provider
     */
    public ILink3Engine(ILink3EngineConfig config, ComponentProvider provider) {
        super(config, provider);
    }

    @Override
    protected String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    protected ILink3Session createSession(ILink3SessionConfig sessionConfig) {
        return new ILink3Session(sessionConfig, logStore);
    }

    /**
     * Gets a session by ID and casts to ILink3Session.
     *
     * @param sessionId the session ID
     * @return the session, or null if not found
     */
    public ILink3Session getILink3Session(String sessionId) {
        return getSession(sessionId);
    }

    /**
     * Sends a heartbeat on all established sessions.
     * Call this periodically to maintain connections.
     */
    public void sendHeartbeats() {
        for (ILink3Session session : getSessions()) {
            if (session.getSessionState().isEstablished() && session.isHeartbeatDue()) {
                session.sendSequence();
            }
        }
    }
}
