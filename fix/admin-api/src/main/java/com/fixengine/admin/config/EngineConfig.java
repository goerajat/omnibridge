package com.fixengine.admin.config;

import com.fixengine.admin.service.SessionService;
import com.fixengine.admin.websocket.SessionWebSocketHandler;
import com.fixengine.engine.FixEngine;
import com.fixengine.engine.config.SessionConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalTime;

/**
 * Configuration for initializing the FIX engine with sample sessions.
 * In a production environment, this would load configuration from external sources.
 */
@Configuration
public class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);

    private final SessionService sessionService;
    private final SessionWebSocketHandler webSocketHandler;

    @Value("${fix.engine.enabled:true}")
    private boolean engineEnabled;

    @Value("${fix.engine.persistence-path:./fix-logs}")
    private String persistencePath;

    private FixEngine engine;

    public EngineConfig(SessionService sessionService, SessionWebSocketHandler webSocketHandler) {
        this.sessionService = sessionService;
        this.webSocketHandler = webSocketHandler;
    }

    @PostConstruct
    public void initialize() {
        if (!engineEnabled) {
            log.info("FIX Engine is disabled");
            return;
        }

        try {
            log.info("Initializing FIX Engine...");

            // Create engine configuration with sample sessions
            com.fixengine.engine.config.EngineConfig config = com.fixengine.engine.config.EngineConfig.builder()
                    .persistencePath(persistencePath)
                    .build();

            engine = new FixEngine(config);
            engine.start();

            // Create sample sessions for demonstration
            createSampleSessions();

            // Register services with the engine
            sessionService.setEngine(engine);
            webSocketHandler.registerWithEngine();

            log.info("FIX Engine initialized successfully");

        } catch (IOException e) {
            log.error("Failed to initialize FIX Engine", e);
        }
    }

    private void createSampleSessions() {
        // Sample Initiator Session
        SessionConfig initiatorConfig = SessionConfig.builder()
                .sessionName("SampleInitiator")
                .senderCompId("CLIENT")
                .targetCompId("EXCHANGE")
                .host("localhost")
                .port(9880)
                .initiator()
                .heartbeatInterval(30)
                .resetOnLogon(true)
                .eodTime(LocalTime.of(17, 0))  // EOD at 5pm
                .resetOnEod(true)
                .build();

        engine.createSession(initiatorConfig);

        // Sample Acceptor Session
        SessionConfig acceptorConfig = SessionConfig.builder()
                .sessionName("SampleAcceptor")
                .senderCompId("EXCHANGE")
                .targetCompId("CLIENT")
                .port(9881)
                .acceptor()
                .heartbeatInterval(30)
                .resetOnLogon(true)
                .build();

        engine.createSession(acceptorConfig);

        log.info("Sample sessions created");
    }

    @PreDestroy
    public void shutdown() {
        if (engine != null) {
            log.info("Shutting down FIX Engine...");
            webSocketHandler.unregisterFromEngine();
            engine.stop();
            log.info("FIX Engine shut down");
        }
    }
}
