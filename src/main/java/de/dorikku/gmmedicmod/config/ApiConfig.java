package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Configuration for the API synchronization connection.
 * Stores API URL, API key, polling interval, and enabled flag.
 */
public class ApiConfig {
    private static ApiConfig INSTANCE;

    private boolean enabled = false;
    private String apiUrl = "http://localhost:8000";
    private String apiKey = "";
    private int pollIntervalSeconds = 3;
    private String clientId = UUID.randomUUID().toString();
    private boolean loaded = false;

    private ApiConfig() {
    }

    public static ApiConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ApiConfig();
        }
        if (!INSTANCE.loaded) {
            INSTANCE.load();
        }
        return INSTANCE;
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("gm-medic-api.cfg");
    }

    public void load() {
        loaded = true;
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                String content = new String(Files.readAllBytes(configPath));
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("=", 2);
                    if (parts.length != 2) continue;
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    switch (key) {
                        case "enabled" -> enabled = Boolean.parseBoolean(value);
                        case "apiUrl" -> apiUrl = value;
                        case "apiKey" -> apiKey = value;
                        case "pollIntervalSeconds" -> {
                            try {
                                pollIntervalSeconds = Math.max(1, Integer.parseInt(value));
                            } catch (NumberFormatException ignored) {}
                        }
                        case "clientId" -> clientId = value;
                    }
                }
                GMMedic.LOGGER.info("[ApiConfig] Loaded: enabled={}, url={}, pollInterval={}s",
                        enabled, apiUrl, pollIntervalSeconds);
            } catch (Exception e) {
                GMMedic.LOGGER.warn("[ApiConfig] Failed to load config, using defaults", e);
            }
        } else {
            GMMedic.LOGGER.info("[ApiConfig] No config file found, creating defaults");
            save();
        }
    }

    public void save() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            String content = """
                    # GM-Medic API Sync Configuration
                    # Enable or disable the API sync feature
                    enabled=%s
                    # The base URL of the sync API (no trailing slash)
                    apiUrl=%s
                    # Your secret API key for authentication
                    apiKey=%s
                    # How often to poll for updates from other clients (in seconds, minimum 1)
                    pollIntervalSeconds=%d
                    # Unique client identifier (auto-generated, do not change unless necessary)
                    clientId=%s
                    """.formatted(enabled, apiUrl, apiKey, pollIntervalSeconds, clientId);
            Files.write(configPath, content.getBytes());
            GMMedic.LOGGER.info("[ApiConfig] Saved config");
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[ApiConfig] Failed to save config", e);
        }
    }

    // --- Getters & Setters ---

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        save();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        save();
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int seconds) {
        this.pollIntervalSeconds = Math.max(1, seconds);
        save();
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * Returns true if the API is fully configured and ready to use.
     */
    public boolean isReady() {
        return enabled && apiKey != null && !apiKey.isEmpty()
                && apiUrl != null && !apiUrl.isEmpty();
    }
}

