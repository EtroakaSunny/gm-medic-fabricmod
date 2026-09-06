package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the microscope diagnosis checklist
 * ({@link de.dorikku.gmmedicmod.diagnosis.MicroscopeOverlay}). Purely local — nothing here is
 * ever sent to the API server, the checklist only exists on the medic's own client.
 */
public class MicroscopeConfig {

    private static MicroscopeConfig INSTANCE;

    /** Whether the checklist panel is drawn next to a "Mikroskop" menu at all. */
    private boolean enabled = true;
    /**
     * Whether the small layout is used. Meant for the very large GUI scales some medics play
     * on, where the full 16-row list does not fit beside the menu. The overlay falls back to
     * the compact layout on its own whenever the normal one would not fit, so this only
     * forces the small variant on a screen that could show either.
     */
    private boolean compactMode = false;
    /**
     * {@code false} — every entry is its colour's German name, written in that colour.
     * {@code true} — every entry is Minecraft's own dye icon for that colour instead.
     */
    private boolean iconLabels = false;
    private boolean loaded = false;

    private MicroscopeConfig() {}

    public static MicroscopeConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MicroscopeConfig();
        }
        if (!INSTANCE.loaded) {
            INSTANCE.load();
        }
        return INSTANCE;
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("gm-medic-microscope.cfg");
    }

    public void load() {
        loaded = true;
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            GMMedic.LOGGER.info("[MicroscopeConfig] No config file found, using defaults");
            return;
        }
        try {
            for (String line : Files.readString(configPath).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("enabled=")) {
                    enabled = Boolean.parseBoolean(line.substring("enabled=".length()).trim());
                } else if (line.startsWith("compactMode=")) {
                    compactMode = Boolean.parseBoolean(line.substring("compactMode=".length()).trim());
                } else if (line.startsWith("iconLabels=")) {
                    iconLabels = Boolean.parseBoolean(line.substring("iconLabels=".length()).trim());
                }
            }
            GMMedic.LOGGER.info("[MicroscopeConfig] Loaded config: enabled={}, compactMode={}, iconLabels={}",
                    enabled, compactMode, iconLabels);
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[MicroscopeConfig] Failed to load config, using defaults", e);
        }
    }

    public void save() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            String content = "# GM-Medic microscope diagnosis checklist\n" +
                    "# Show the colour checklist next to a \"Mikroskop\" menu\n" +
                    "enabled=" + enabled + "\n" +
                    "# Force the small layout. The overlay already falls back to it whenever the\n" +
                    "# normal one would not fit on screen, so this is only needed to pick it early\n" +
                    "compactMode=" + compactMode + "\n" +
                    "# false = colour names written in their own colour, true = Minecraft's dye icons\n" +
                    "iconLabels=" + iconLabels + "\n";
            Files.writeString(configPath, content);
            GMMedic.LOGGER.info("[MicroscopeConfig] Saved config: enabled={}, compactMode={}, iconLabels={}",
                    enabled, compactMode, iconLabels);
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[MicroscopeConfig] Failed to save config", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public boolean isCompactMode() {
        return compactMode;
    }

    public void setCompactMode(boolean value) {
        compactMode = value;
        save();
    }

    public boolean isIconLabels() {
        return iconLabels;
    }

    public void setIconLabels(boolean value) {
        iconLabels = value;
        save();
    }
}
