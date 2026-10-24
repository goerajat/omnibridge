package com.omnibridge.fix.engine;

import com.omnibridge.fix.engine.config.FixEngineConfig;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fix-engine.read-cap-bytes sets every session's read cap (default 2048); a session's own
 * read-cap-bytes wins.
 */
class ReadCapConfigTest {

    private static final String SESSIONS = "sessions = ["
            + "{ session-id = A, sender-comp-id = X, target-comp-id = Y, port = 9876, role = acceptor },"
            + "{ session-id = B, sender-comp-id = X, target-comp-id = Z, port = 9877, role = acceptor,"
            + "  read-cap-bytes = 8192 } ]";

    private static SessionConfig session(FixEngineConfig engine, String name) {
        return FixEngine.convertToSessionConfig(engine.getSession(name), 0, engine.getReadCapBytes());
    }

    @Test
    void defaultsTo2048() {
        FixEngineConfig engine = FixEngineConfig.fromConfig(ConfigFactory.parseString("fix-engine { " + SESSIONS + " }"));
        assertEquals(SessionConfig.DEFAULT_READ_CAP_BYTES, engine.getReadCapBytes());
        assertEquals(2048, session(engine, "A").getReadCapBytes());
        assertEquals(8192, session(engine, "B").getReadCapBytes());
    }

    @Test
    void engineSettingAppliesToSessionsWithoutTheirOwn() {
        FixEngineConfig engine = FixEngineConfig.fromConfig(ConfigFactory.parseString(
                "fix-engine { read-cap-bytes = 4096, " + SESSIONS + " }"));
        assertEquals(4096, session(engine, "A").getReadCapBytes());
        assertEquals(8192, session(engine, "B").getReadCapBytes());
    }
}
