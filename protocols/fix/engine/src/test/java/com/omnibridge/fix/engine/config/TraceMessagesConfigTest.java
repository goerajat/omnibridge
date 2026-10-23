package com.omnibridge.fix.engine.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * trace-messages (outgoing messages in the application log) is separate from
 * log-messages (messages persisted to the LogStore) and defaults to it.
 */
class TraceMessagesConfigTest {

    private static EngineSessionConfig session(String extra) {
        Config config = ConfigFactory.parseString(
                "session-id = S1, sender-comp-id = A, target-comp-id = B, port = 9876, initiator = false\n" + extra);
        return EngineSessionConfig.fromConfig(config);
    }

    private static SessionConfig.Builder sessionBuilder() {
        return SessionConfig.builder().sessionName("S1").acceptor().senderCompId("A").targetCompId("B").port(9876);
    }

    @Test
    void traceFollowsLogMessagesByDefault() {
        assertTrue(session("").isTraceMessages());
        EngineSessionConfig off = session("log-messages = false");
        assertFalse(off.isLogMessages());
        assertFalse(off.isTraceMessages());
    }

    @Test
    void persistWithoutTracing() {
        EngineSessionConfig config = session("log-messages = true\ntrace-messages = false");
        assertTrue(config.isLogMessages());
        assertFalse(config.isTraceMessages());
    }

    @Test
    void sessionConfigDefaultsTraceToLogMessages() {
        assertFalse(sessionBuilder().logMessages(false).build().isTraceMessages());
        SessionConfig persisted = sessionBuilder().logMessages(true).traceMessages(false).build();
        assertTrue(persisted.isLogMessages());
        assertFalse(persisted.isTraceMessages());
    }
}
