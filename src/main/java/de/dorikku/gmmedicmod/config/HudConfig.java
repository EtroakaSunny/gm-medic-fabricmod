package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for HUD display settings.
 * Supports compact mode for players using large GUI scales.
 */
public class HudConfig {
    private static HudConfig INSTANCE;

    private boolean compactMode = false;
    private boolean loaded = false;

    private HudConfig() {
    }

    public static HudConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new HudConfig();
        }
        if (!INSTANCE.loaded) {
            INSTANCE.load();
        }
        return INSTANCE;
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("gm-medic-hud.cfg");
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
                    if (line.startsWith("compactMode=")) {
                        compactMode = Boolean.parseBoolean(line.substring("compactMode=".length()).trim());
                    }
                }
                GMMedic.LOGGER.info("[HudConfig] Loaded config: compactMode={}", compactMode);
            } catch (Exception e) {
                GMMedic.LOGGER.warn("[HudConfig] Failed to load config, using defaults", e);
            }
        } else {
            GMMedic.LOGGER.info("[HudConfig] No config file found, using defaults");
        }
    }

    public void save() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            String content = "# GM-Medic HUD Configuration\n" +
                           "# Set compactMode to true for a more compact HUD display (useful for large GUI scales)\n" +
                           "compactMode=" + compactMode + "\n";
            Files.write(configPath, content.getBytes());
            GMMedic.LOGGER.info("[HudConfig] Saved config: compactMode={}", compactMode);
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[HudConfig] Failed to save config", e);
        }
    }

    public boolean isCompactMode() {
        return compactMode;
    }

    public void setCompactMode(boolean enabled) {
        this.compactMode = enabled;
        save();
    }

    public void toggleCompactMode() {
        setCompactMode(!compactMode);
    }
}

