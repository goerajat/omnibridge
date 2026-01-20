package com.fixengine.config;

import com.typesafe.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadDefaultConfig() {
        Config config = ConfigLoader.load();

        assertTrue(config.hasPath("network"));
        assertTrue(config.hasPath("persistence"));
        assertTrue(config.hasPath("fix-engine.sessions"));

        // Check default values
        assertEquals("fix-event-loop", config.getString("network.name"));
        assertEquals(-1, config.getInt("network.cpu-affinity"));
        assertEquals(65536, config.getInt("network.read-buffer-size"));

        assertTrue(config.getBoolean("persistence.enabled"));
        assertEquals("memory-mapped", config.getString("persistence.store-type"));
    }

    @Test
    void loadCustomConfigFile() throws IOException {
        Path configFile = tempDir.resolve("custom.conf");
        Files.writeString(configFile, """
            network {
              name = "custom-loop"
              cpu-affinity = 5
            }
            persistence {
              enabled = false
            }
            fix-engine {
              sessions = []
            }
            """);

        Config config = ConfigLoader.load(configFile.toString());

        assertEquals("custom-loop", config.getString("network.name"));
        assertEquals(5, config.getInt("network.cpu-affinity"));
        assertFalse(config.getBoolean("persistence.enabled"));
    }

    @Test
    void loadMultipleConfigFiles_laterOverridesEarlier() throws IOException {
        Path baseConfig = tempDir.resolve("base.conf");
        Files.writeString(baseConfig, """
            network {
              name = "base-loop"
              cpu-affinity = 1
            }
            persistence {
              enabled = true
              base-path = "/base/path"
            }
            fix-engine {
              sessions = []
            }
            """);

        Path overrideConfig = tempDir.resolve("override.conf");
        Files.writeString(overrideConfig, """
            network {
              cpu-affinity = 3
            }
            persistence {
              base-path = "/override/path"
            }
            """);

        Config config = ConfigLoader.load(baseConfig.toString(), overrideConfig.toString());

        // Override should take precedence
        assertEquals("base-loop", config.getString("network.name"));
        assertEquals(3, config.getInt("network.cpu-affinity"));
        assertEquals("/override/path", config.getString("persistence.base-path"));
    }

    @Test
    void loadEngineConfig_parsesAllFields() throws IOException {
        Path configFile = tempDir.resolve("engine.conf");
        Files.writeString(configFile, """
            network {
              name = "test-loop"
              cpu-affinity = 2
              read-buffer-size = 131072
              write-buffer-size = 131072
              select-timeout-ms = 50
              busy-spin-mode = false
            }
            persistence {
              enabled = true
              store-type = "memory-mapped"
              base-path = "/var/fix-logs"
              max-file-size = 512MB
              sync-on-write = true
              max-streams = 50
            }
            fix-engine {
              sessions = [
                {
                  session-name = "TEST_SESSION"
                  role = "initiator"
                  sender-comp-id = "CLIENT"
                  target-comp-id = "SERVER"
                  begin-string = "FIX.4.4"
                  host = "localhost"
                  port = 9876
                  heartbeat-interval = 30
                  reset-on-logon = true
                  reset-on-logout = false
                  reset-on-disconnect = false
                  reconnect-interval = 5
                  max-reconnect-attempts = 10
                  time-zone = "UTC"
                  reset-on-eod = false
                  log-messages = true
                  message-pool-size = 128
                  max-message-length = 1024
                  max-tag-number = 500
                  start-time = ""
                  end-time = ""
                  eod-time = ""
                }
              ]
            }
            """);

        Config config = ConfigLoader.load(configFile.toString());
        NetworkConfig network = ConfigLoader.loadNetworkConfig(config);
        PersistenceConfig persistence = ConfigLoader.loadPersistenceConfig(config);
        FixEngineConfig engineConfig = FixEngineConfig.fromConfig(config);

        // Verify network config
        assertEquals("test-loop", network.getName());
        assertEquals(2, network.getCpuAffinity());
        assertEquals(131072, network.getReadBufferSize());
        assertEquals(131072, network.getWriteBufferSize());
        assertEquals(50, network.getSelectTimeoutMs());

        // Verify persistence config
        assertTrue(persistence.isEnabled());
        assertEquals(PersistenceConfig.StoreType.MEMORY_MAPPED, persistence.getStoreType());
        assertEquals("/var/fix-logs", persistence.getBasePath());
        // Typesafe Config uses SI units (512MB = 512 * 1000 * 1000)
        assertEquals(512_000_000L, persistence.getMaxFileSize());
        assertTrue(persistence.isSyncOnWrite());
        assertEquals(50, persistence.getMaxStreams());

        // Verify session config
        assertEquals(1, engineConfig.getSessions().size());
        EngineSessionConfig session = engineConfig.getSessions().get(0);
        assertEquals("TEST_SESSION", session.getSessionName());
        assertEquals(EngineSessionConfig.Role.INITIATOR, session.getRole());
        assertEquals("CLIENT", session.getSenderCompId());
        assertEquals("SERVER", session.getTargetCompId());
        assertEquals("localhost", session.getHost());
        assertEquals(9876, session.getPort());
        assertEquals(30, session.getHeartbeatInterval());
        assertTrue(session.isResetOnLogon());
        assertEquals(128, session.getMessagePoolSize());
    }

    @Test
    void loadEngineConfig_withMultipleSessions() throws IOException {
        Path configFile = tempDir.resolve("multi-session.conf");
        Files.writeString(configFile, """
            network {
              name = "multi-session-loop"
              cpu-affinity = -1
              read-buffer-size = 65536
              write-buffer-size = 65536
              select-timeout-ms = 100
              busy-spin-mode = false
            }
            persistence {
              enabled = true
              store-type = "memory-mapped"
              base-path = "./logs"
              max-file-size = 256MB
              sync-on-write = false
              max-streams = 100
            }
            fix-engine {
              sessions = [
                {
                  session-name = "SESSION1"
                  role = "acceptor"
                  sender-comp-id = "SERVER"
                  target-comp-id = "CLIENT1"
                  begin-string = "FIX.4.4"
                  port = 9876
                  heartbeat-interval = 30
                  reset-on-logon = true
                  reset-on-logout = false
                  reset-on-disconnect = false
                  reconnect-interval = 5
                  max-reconnect-attempts = -1
                  time-zone = "UTC"
                  reset-on-eod = false
                  log-messages = true
                  message-pool-size = 64
                  max-message-length = 4096
                  max-tag-number = 1000
                },
                {
                  session-name = "SESSION2"
                  role = "acceptor"
                  sender-comp-id = "SERVER"
                  target-comp-id = "CLIENT2"
                  begin-string = "FIX.4.4"
                  port = 9877
                  heartbeat-interval = 30
                  reset-on-logon = true
                  reset-on-logout = false
                  reset-on-disconnect = false
                  reconnect-interval = 5
                  max-reconnect-attempts = -1
                  time-zone = "UTC"
                  reset-on-eod = false
                  log-messages = true
                  message-pool-size = 64
                  max-message-length = 4096
                  max-tag-number = 1000
                }
              ]
            }
            """);

        FixEngineConfig engineConfig = ConfigLoader.loadEngineConfig(configFile.toString());

        assertEquals(2, engineConfig.getSessions().size());

        EngineSessionConfig session1 = engineConfig.getSession("SESSION1");
        assertNotNull(session1);
        assertEquals("CLIENT1", session1.getTargetCompId());
        assertEquals(9876, session1.getPort());

        EngineSessionConfig session2 = engineConfig.getSession("SESSION2");
        assertNotNull(session2);
        assertEquals("CLIENT2", session2.getTargetCompId());
        assertEquals(9877, session2.getPort());

        // Test getSessionById
        EngineSessionConfig byId = engineConfig.getSessionById("SERVER->CLIENT1");
        assertNotNull(byId);
        assertEquals("SESSION1", byId.getSessionName());
    }

    @Test
    void builderPattern_customLoading() throws IOException {
        Path configFile = tempDir.resolve("builder-test.conf");
        Files.writeString(configFile, """
            network {
              name = "builder-test"
              cpu-affinity = -1
              read-buffer-size = 65536
              write-buffer-size = 65536
              select-timeout-ms = 100
              busy-spin-mode = false
            }
            persistence {
              enabled = false
              store-type = "none"
              base-path = "."
              max-file-size = 256MB
              sync-on-write = false
              max-streams = 100
            }
            fix-engine {
              sessions = []
            }
            """);

        Config config = ConfigLoader.builder()
                .withReferenceConf(false)
                .withApplicationConf(false)
                .addFile(configFile.toString())
                .build();

        assertEquals("builder-test", config.getString("network.name"));
        assertFalse(config.getBoolean("persistence.enabled"));
    }
}
