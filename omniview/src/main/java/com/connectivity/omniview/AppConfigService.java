package com.connectivity.omniview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing application configurations with file-based persistence.
 * Stores configurations in a JSON file that survives application redeployments.
 */
public class AppConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigService.class);
    private static final String DEFAULT_DATA_DIR = "/opt/omniview/data";
    private static final String CONFIG_FILE_NAME = "apps.json";

    private final Path configFilePath;
    private final ObjectMapper objectMapper;
    private final Map<String, AppConfig> apps;

    public AppConfigService() {
        this(Path.of(System.getProperty("omniview.data.dir", DEFAULT_DATA_DIR)));
    }

    public AppConfigService(Path dataDir) {
        this.configFilePath = dataDir.resolve(CONFIG_FILE_NAME);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.apps = new ConcurrentHashMap<>();

        // Ensure data directory exists
        try {
            Files.createDirectories(dataDir);
            LOG.info("Using data directory: {}", dataDir.toAbsolutePath());
        } catch (IOException e) {
            LOG.error("Failed to create data directory: {}", dataDir, e);
        }

        // Load existing configurations
        loadConfigs();
    }

    /**
     * Get all app configurations.
     */
    public List<AppConfig> getAllApps() {
        return new ArrayList<>(apps.values());
    }

    /**
     * Get an app configuration by ID.
     */
    public Optional<AppConfig> getApp(String id) {
        return Optional.ofNullable(apps.get(id));
    }

    /**
     * Add a new app configuration.
     *
     * @param app the app config (id will be generated if null/empty)
     * @return the app with generated id
     */
    public AppConfig addApp(AppConfig app) {
        String id = app.id();
        if (id == null || id.isEmpty()) {
            id = generateId();
        }
        AppConfig newApp = new AppConfig(id, app.name(), app.host(), app.port(), app.enabled());
        apps.put(id, newApp);
        saveConfigs();
        LOG.info("Added app: {} ({}:{})", newApp.name(), newApp.host(), newApp.port());
        return newApp;
    }

    /**
     * Update an existing app configuration.
     *
     * @param id the app id
     * @param updates the updates to apply
     * @return the updated app, or empty if not found
     */
    public Optional<AppConfig> updateApp(String id, AppConfig updates) {
        AppConfig existing = apps.get(id);
        if (existing == null) {
            return Optional.empty();
        }

        AppConfig updated = new AppConfig(
            id,
            updates.name() != null ? updates.name() : existing.name(),
            updates.host() != null ? updates.host() : existing.host(),
            updates.port() > 0 ? updates.port() : existing.port(),
            updates.enabled()
        );

        apps.put(id, updated);
        saveConfigs();
        LOG.info("Updated app: {} ({}:{})", updated.name(), updated.host(), updated.port());
        return Optional.of(updated);
    }

    /**
     * Remove an app configuration.
     *
     * @param id the app id
     * @return true if removed, false if not found
     */
    public boolean removeApp(String id) {
        AppConfig removed = apps.remove(id);
        if (removed != null) {
            saveConfigs();
            LOG.info("Removed app: {} ({})", removed.name(), id);
            return true;
        }
        return false;
    }

    /**
     * Toggle the enabled state of an app.
     *
     * @param id the app id
     * @return the updated app, or empty if not found
     */
    public Optional<AppConfig> toggleApp(String id) {
        AppConfig existing = apps.get(id);
        if (existing == null) {
            return Optional.empty();
        }

        AppConfig updated = new AppConfig(
            id,
            existing.name(),
            existing.host(),
            existing.port(),
            !existing.enabled()
        );

        apps.put(id, updated);
        saveConfigs();
        LOG.info("Toggled app: {} enabled={}", updated.name(), updated.enabled());
        return Optional.of(updated);
    }

    private void loadConfigs() {
        if (!Files.exists(configFilePath)) {
            LOG.info("No existing config file found at {}", configFilePath);
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            List<AppConfig> loadedApps = objectMapper.readValue(json,
                new TypeReference<List<AppConfig>>() {});

            apps.clear();
            for (AppConfig app : loadedApps) {
                apps.put(app.id(), app);
            }
            LOG.info("Loaded {} app configurations from {}", apps.size(), configFilePath);
        } catch (IOException e) {
            LOG.error("Failed to load app configurations from {}", configFilePath, e);
        }
    }

    private void saveConfigs() {
        try {
            String json = objectMapper.writeValueAsString(new ArrayList<>(apps.values()));
            Files.writeString(configFilePath, json);
            LOG.debug("Saved {} app configurations to {}", apps.size(), configFilePath);
        } catch (IOException e) {
            LOG.error("Failed to save app configurations to {}", configFilePath, e);
        }
    }

    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * App configuration record.
     */
    public record AppConfig(
        String id,
        String name,
        String host,
        int port,
        boolean enabled
    ) {}
}
