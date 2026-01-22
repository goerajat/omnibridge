package com.fixengine.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigLoader.
 *
 * Note: Tests for typed config classes (NetworkConfig, PersistenceConfig, etc.)
 * have been moved to their respective modules.
 */
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
    void loadConfig_parsesAllPaths() throws IOException {
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

        // Verify all paths are accessible
        assertTrue(config.hasPath("network"));
        assertTrue(config.hasPath("persistence"));
        assertTrue(config.hasPath("fix-engine.sessions"));

        // Verify raw config values
        assertEquals("test-loop", config.getString("network.name"));
        assertEquals(2, config.getInt("network.cpu-affinity"));
        assertTrue(config.getBoolean("persistence.enabled"));

        // Verify session list
        assertEquals(1, config.getConfigList("fix-engine.sessions").size());
        Config sessionConfig = config.getConfigList("fix-engine.sessions").get(0);
        assertEquals("TEST_SESSION", sessionConfig.getString("session-name"));
        assertEquals("initiator", sessionConfig.getString("role"));
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

    @Test
    void loadMissingConfigFile_returnsNull() {
        Config config = ConfigLoader.load("non-existent.conf");
        // Should still have default config from reference.conf
        assertTrue(config.hasPath("network"));
    }

    @Test
    void builder_withSystemProperties() throws IOException {
        Path configFile = tempDir.resolve("sys-props.conf");
        Files.writeString(configFile, """
            network {
              name = ${?TEST_NETWORK_NAME}
              cpu-affinity = -1
            }
            persistence {
              enabled = false
            }
            fix-engine {
              sessions = []
            }
            """);

        // Set system property and invalidate config caches
        System.setProperty("TEST_NETWORK_NAME", "from-sys-prop");
        ConfigFactory.invalidateCaches();

        try {
            Config config = ConfigLoader.builder()
                    .withReferenceConf(false)
                    .withApplicationConf(false)
                    .addFile(configFile.toString())
                    .withSystemProperties(true)
                    .build();

            assertEquals("from-sys-prop", config.getString("network.name"));
        } finally {
            System.clearProperty("TEST_NETWORK_NAME");
            ConfigFactory.invalidateCaches();
        }
    }
}
