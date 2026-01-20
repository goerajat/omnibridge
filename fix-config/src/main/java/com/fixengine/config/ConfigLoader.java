package com.fixengine.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads and merges configuration from multiple sources.
 *
 * <p>Configuration is loaded in the following order (later sources override earlier):
 * <ol>
 *   <li>reference.conf (from classpath - defaults)</li>
 *   <li>application.conf (from classpath)</li>
 *   <li>Config files specified via {@link #load(String...)} or {@link #load(List)}</li>
 *   <li>System properties</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Load with custom config files
 * Config config = ConfigLoader.load("base.conf", "override.conf");
 * FixEngineConfig engineConfig = FixEngineConfig.fromConfig(config);
 *
 * // Or use the builder
 * Config config = ConfigLoader.builder()
 *     .addFile("base.conf")
 *     .addFile("environment/prod.conf")
 *     .withSystemProperties(true)
 *     .build();
 * }</pre>
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    /**
     * Load configuration using default loading order:
     * reference.conf -> application.conf -> system properties.
     */
    public static Config load() {
        return ConfigFactory.load();
    }

    /**
     * Load configuration with additional config files.
     * Files are loaded in order, with later files overriding earlier ones.
     *
     * @param configFiles paths to config files
     * @return merged configuration
     */
    public static Config load(String... configFiles) {
        return load(Arrays.asList(configFiles));
    }

    /**
     * Load configuration with additional config files.
     * Files are loaded in order, with later files overriding earlier ones.
     *
     * @param configFiles list of paths to config files
     * @return merged configuration
     */
    public static Config load(List<String> configFiles) {
        return builder().addFiles(configFiles).build();
    }

    /**
     * Load FixEngineConfig directly from config files.
     *
     * @param configFiles paths to config files
     * @return parsed FixEngineConfig
     */
    public static FixEngineConfig loadEngineConfig(String... configFiles) {
        return FixEngineConfig.fromConfig(load(configFiles));
    }

    /**
     * Load FixEngineConfig directly from config files.
     *
     * @param configFiles list of paths to config files
     * @return parsed FixEngineConfig
     */
    public static FixEngineConfig loadEngineConfig(List<String> configFiles) {
        return FixEngineConfig.fromConfig(load(configFiles));
    }

    /**
     * Load NetworkConfig from Typesafe Config.
     *
     * @param config the root config
     * @return parsed NetworkConfig
     */
    public static NetworkConfig loadNetworkConfig(Config config) {
        return NetworkConfig.fromConfig(config.getConfig("network"));
    }

    /**
     * Load NetworkConfig directly from config files.
     *
     * @param configFiles paths to config files
     * @return parsed NetworkConfig
     */
    public static NetworkConfig loadNetworkConfig(String... configFiles) {
        return loadNetworkConfig(load(configFiles));
    }

    /**
     * Load NetworkConfig directly from config files.
     *
     * @param configFiles list of paths to config files
     * @return parsed NetworkConfig
     */
    public static NetworkConfig loadNetworkConfig(List<String> configFiles) {
        return loadNetworkConfig(load(configFiles));
    }

    /**
     * Load PersistenceConfig from Typesafe Config.
     *
     * @param config the root config
     * @return parsed PersistenceConfig
     */
    public static PersistenceConfig loadPersistenceConfig(Config config) {
        return PersistenceConfig.fromConfig(config.getConfig("persistence"));
    }

    /**
     * Load PersistenceConfig directly from config files.
     *
     * @param configFiles paths to config files
     * @return parsed PersistenceConfig
     */
    public static PersistenceConfig loadPersistenceConfig(String... configFiles) {
        return loadPersistenceConfig(load(configFiles));
    }

    /**
     * Load PersistenceConfig directly from config files.
     *
     * @param configFiles list of paths to config files
     * @return parsed PersistenceConfig
     */
    public static PersistenceConfig loadPersistenceConfig(List<String> configFiles) {
        return loadPersistenceConfig(load(configFiles));
    }

    /**
     * Create a builder for more control over configuration loading.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ConfigLoader with fine-grained control over loading.
     */
    public static final class Builder {
        private final List<String> configFiles = new ArrayList<>();
        private boolean includeSystemProperties = true;
        private boolean includeEnvironmentVariables = true;
        private boolean includeApplicationConf = true;
        private boolean includeReferenceConf = true;
        private boolean allowUnresolved = false;

        private Builder() {}

        /**
         * Add a config file to load.
         */
        public Builder addFile(String path) {
            this.configFiles.add(path);
            return this;
        }

        /**
         * Add multiple config files to load.
         */
        public Builder addFiles(List<String> paths) {
            this.configFiles.addAll(paths);
            return this;
        }

        /**
         * Add multiple config files to load.
         */
        public Builder addFiles(String... paths) {
            this.configFiles.addAll(Arrays.asList(paths));
            return this;
        }

        /**
         * Whether to include system properties in resolution.
         * Default: true
         */
        public Builder withSystemProperties(boolean include) {
            this.includeSystemProperties = include;
            return this;
        }

        /**
         * Whether to include environment variables in resolution.
         * Default: true
         */
        public Builder withEnvironmentVariables(boolean include) {
            this.includeEnvironmentVariables = include;
            return this;
        }

        /**
         * Whether to load application.conf from classpath.
         * Default: true
         */
        public Builder withApplicationConf(boolean include) {
            this.includeApplicationConf = include;
            return this;
        }

        /**
         * Whether to load reference.conf from classpath.
         * Default: true
         */
        public Builder withReferenceConf(boolean include) {
            this.includeReferenceConf = include;
            return this;
        }

        /**
         * Whether to allow unresolved substitutions.
         * Default: false
         */
        public Builder allowUnresolved(boolean allow) {
            this.allowUnresolved = allow;
            return this;
        }

        /**
         * Build the merged configuration.
         */
        public Config build() {
            Config config = ConfigFactory.empty();

            // Load reference.conf (defaults)
            if (includeReferenceConf) {
                config = config.withFallback(ConfigFactory.defaultReference());
                log.debug("Loaded reference.conf");
            }

            // Load application.conf
            if (includeApplicationConf) {
                config = ConfigFactory.defaultApplication().withFallback(config);
                log.debug("Loaded application.conf");
            }

            // Load custom config files in order (later files override earlier)
            for (String filePath : configFiles) {
                Config fileConfig = loadConfigFile(filePath);
                if (fileConfig != null) {
                    config = fileConfig.withFallback(config);
                    log.info("Loaded config file: {}", filePath);
                }
            }

            // Apply system properties and environment variables on top
            if (includeSystemProperties) {
                config = ConfigFactory.systemProperties().withFallback(config);
            }
            if (includeEnvironmentVariables) {
                config = ConfigFactory.systemEnvironment().withFallback(config);
            }

            // Resolve substitutions
            ConfigResolveOptions resolveOptions = ConfigResolveOptions.defaults()
                    .setAllowUnresolved(allowUnresolved);
            config = config.resolve(resolveOptions);

            return config;
        }

        private Config loadConfigFile(String path) {
            File file = new File(path);
            if (file.exists()) {
                try {
                    return ConfigFactory.parseFile(file, ConfigParseOptions.defaults());
                } catch (Exception e) {
                    log.error("Failed to parse config file: {}", path, e);
                    throw new ConfigurationException("Failed to parse config file: " + path, e);
                }
            } else {
                // Try loading from classpath
                try {
                    Config classpathConfig = ConfigFactory.parseResources(path, ConfigParseOptions.defaults());
                    if (!classpathConfig.isEmpty()) {
                        return classpathConfig;
                    }
                } catch (Exception e) {
                    log.debug("Config file not found on classpath: {}", path);
                }
                log.warn("Config file not found: {}", path);
                return null;
            }
        }
    }

    /**
     * Exception thrown when configuration loading fails.
     */
    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
