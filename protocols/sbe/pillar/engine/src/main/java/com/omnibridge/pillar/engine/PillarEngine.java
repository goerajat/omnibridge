package com.omnibridge.pillar.engine;

import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.pillar.engine.config.PillarEngineConfig;
import com.omnibridge.pillar.engine.config.PillarSessionConfig;
import com.omnibridge.sbe.engine.SbeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NYSE Pillar protocol engine.
 * <p>
 * Manages multiple Pillar sessions for order entry on NYSE markets.
 * <p>
 * Example usage:
 * <pre>
 * PillarEngineConfig config = PillarEngineConfig.fromConfig(hoconConfig);
 * PillarEngine engine = new PillarEngine(config, provider);
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
public class PillarEngine extends SbeEngine<PillarSession, PillarSessionConfig, PillarEngineConfig> {

    private static final Logger log = LoggerFactory.getLogger(PillarEngine.class);

    private static final String ENGINE_NAME = "Pillar";

    /**
     * Creates a Pillar engine with configuration.
     *
     * @param config the engine configuration
     */
    public PillarEngine(PillarEngineConfig config) {
        super(config);
    }

    /**
     * Creates a Pillar engine using the provider pattern.
     *
     * @param config the engine configuration
     * @param provider the component provider
     */
    public PillarEngine(PillarEngineConfig config, ComponentProvider provider) {
        super(config, provider);
    }

    @Override
    protected String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    protected PillarSession createSession(PillarSessionConfig sessionConfig) {
        return new PillarSession(sessionConfig, logStore);
    }

    /**
     * Gets a session by ID and casts to PillarSession.
     *
     * @param sessionId the session ID
     * @return the session, or null if not found
     */
    public PillarSession getPillarSession(String sessionId) {
        return getSession(sessionId);
    }

    /**
     * Sends a heartbeat on all established sessions.
     * Call this periodically to maintain connections.
     */
    public void sendHeartbeats() {
        for (PillarSession session : getSessions()) {
            if (session.getSessionState().isEstablished() && session.isHeartbeatDue()) {
                session.sendHeartbeat();
            }
        }
    }
}
