package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class HudConfig {
    public static final double MIN_HIGHLIGHT_RANGE = 8.0;
    public static final double MAX_HIGHLIGHT_RANGE = 256.0;

    private static HudConfig INSTANCE;
    private boolean compactMode = false;
    private boolean highlightEnabled = true;
    private double highlightRange = 100.0;
    /**
     * Whether the blood-donation status is shown at all (syringe actionbar message, the
     * green/red box and its floating label, and the note when another medic draws blood).
     * Donations are still tracked and reported when this is off — only the display stops.
     * {@link #bloodDrawMessageEnabled} silences just the note while the rest stays visible.
     */
    private boolean bloodDisplayEnabled = true;
    /**
     * Whether the chat note about a donation another medic performed is shown. The cooldown
     * itself is applied either way — turning this off only silences the message.
     */
    private boolean bloodDrawMessageEnabled = true;
    /**
     * Whether the short actionbar countdown after a call's transmission is shown. Purely
     * visual: the call itself is tracked and displayed in the HUD either way.
     */
    private boolean callTimerEnabled = true;
    private boolean loaded = false;

    private HudConfig() {}

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
                String content = Files.readString(configPath);
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("compactMode=")) {
                        compactMode = Boolean.parseBoolean(line.substring("compactMode=".length()).trim());
                    } else if (line.startsWith("highlightEnabled=")) {
                        highlightEnabled = Boolean.parseBoolean(line.substring("highlightEnabled=".length()).trim());
                    } else if (line.startsWith("bloodDisplayEnabled=")) {
                        bloodDisplayEnabled = Boolean.parseBoolean(line.substring("bloodDisplayEnabled=".length()).trim());
                    } else if (line.startsWith("bloodDrawMessageEnabled=")) {
                        bloodDrawMessageEnabled = Boolean.parseBoolean(line.substring("bloodDrawMessageEnabled=".length()).trim());
                    } else if (line.startsWith("callTimerEnabled=")) {
                        callTimerEnabled = Boolean.parseBoolean(line.substring("callTimerEnabled=".length()).trim());
                    } else if (line.startsWith("highlightRange=")) {
                        try {
                            highlightRange = clampRange(Double.parseDouble(line.substring("highlightRange=".length()).trim()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                GMMedic.LOGGER.info("[HudConfig] Loaded config: compactMode={}, highlightEnabled={}, highlightRange={}",
                        compactMode, highlightEnabled, highlightRange);
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
                    "compactMode=" + compactMode + "\n" +
                    "# Highlight players that have an open emergency call with an outline box\n" +
                    "highlightEnabled=" + highlightEnabled + "\n" +
                    "# Maximum distance (in blocks) at which players are highlighted\n" +
                    "highlightRange=" + highlightRange + "\n" +
                    "# Show blood-donation status (syringe message, box, label, other medics' draws).\n" +
                    "# Donations are still tracked and reported when this is off\n" +
                    "bloodDisplayEnabled=" + bloodDisplayEnabled + "\n" +
                    "# Show a chat note when another medic donated a player's blood.\n" +
                    "# The cooldown is still applied when this is off\n" +
                    "bloodDrawMessageEnabled=" + bloodDrawMessageEnabled + "\n" +
                    "# Show the short countdown above the hotbar when a call's transmission arrives.\n" +
                    "# The call is tracked and shown in the HUD either way\n" +
                    "callTimerEnabled=" + callTimerEnabled + "\n";
            Files.writeString(configPath, content);
            GMMedic.LOGGER.info("[HudConfig] Saved config: compactMode={}, highlightEnabled={}, highlightRange={}",
                    compactMode, highlightEnabled, highlightRange);
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[HudConfig] Failed to save config", e);
        }
    }

    public boolean isCompactMode() {
        return compactMode;
    }

    public void setCompactMode(boolean enabled) {
        compactMode = enabled;
        save();
    }

    public void toggleCompactMode() {
        setCompactMode(!compactMode);
    }

    public boolean isHighlightEnabled() {
        return highlightEnabled;
    }

    public void setHighlightEnabled(boolean enabled) {
        highlightEnabled = enabled;
        save();
    }

    public void toggleHighlight() {
        setHighlightEnabled(!highlightEnabled);
    }

    public boolean isBloodDisplayEnabled() {
        return bloodDisplayEnabled;
    }

    public void setBloodDisplayEnabled(boolean enabled) {
        bloodDisplayEnabled = enabled;
        save();
    }

    public boolean isBloodDrawMessageEnabled() {
        return bloodDrawMessageEnabled;
    }

    public void setBloodDrawMessageEnabled(boolean enabled) {
        bloodDrawMessageEnabled = enabled;
        save();
    }

    public boolean isCallTimerEnabled() {
        return callTimerEnabled;
    }

    public void setCallTimerEnabled(boolean enabled) {
        callTimerEnabled = enabled;
        save();
    }

    public double getHighlightRange() {
        return highlightRange;
    }

    public void setHighlightRange(double range) {
        highlightRange = clampRange(range);
        save();
    }

    private static double clampRange(double range) {
        return Math.max(MIN_HIGHLIGHT_RANGE, Math.min(MAX_HIGHLIGHT_RANGE, range));
    }
}
