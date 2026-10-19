package com.omnibridge.fix.engine;

import com.omnibridge.fix.engine.config.EngineSessionConfig;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.message.ApplVerID;
import com.omnibridge.fix.message.FixVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sessions defined in the engine config must keep their FIX version, or a FIX 5.0
 * session logs on without DefaultApplVerID (1137) and FIXT counterparties reject it.
 */
class SessionConfigConversionTest {

    private static EngineSessionConfig.Builder acceptor() {
        return EngineSessionConfig.builder()
                .sessionName("S")
                .senderCompId("EXCHANGE")
                .targetCompId("CLIENT")
                .role(EngineSessionConfig.Role.ACCEPTOR)
                .port(9876);
    }

    @Test
    void fix50KeepsVersionAndDefaultApplVerId() {
        SessionConfig converted = FixEngine.convertToSessionConfig(
                acceptor().fixVersion(FixVersion.FIX50SP2).build());

        assertEquals(FixVersion.FIX50SP2, converted.getFixVersion());
        assertEquals("FIXT.1.1", converted.getBeginString());
        assertEquals(ApplVerID.FIX50SP2, converted.getDefaultApplVerID());
    }

    @Test
    void beginStringAloneStillSelectsFix42() {
        SessionConfig converted = FixEngine.convertToSessionConfig(
                acceptor().beginString("FIX.4.2").build());

        assertEquals("FIX.4.2", converted.getBeginString());
        assertNull(converted.getDefaultApplVerID());
    }

    @Test
    void defaultIsFix44() {
        SessionConfig converted = FixEngine.convertToSessionConfig(acceptor().build());

        assertEquals("FIX.4.4", converted.getBeginString());
        assertEquals(FixVersion.FIX44, converted.getFixVersion());
    }
}
