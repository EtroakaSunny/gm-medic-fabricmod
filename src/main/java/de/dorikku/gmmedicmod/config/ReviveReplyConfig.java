package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Auto-reply sent in normal public chat when the local player is the one who revived or
 * healed another player (see ChatMessageHandler's "Ich habe X wiederbelebt!" handling).
 * Other on-duty medics who merely see that broadcast never trigger it.
 */
public class ReviveReplyConfig {
    public static final String DEFAULT_MESSAGE = "Kein Problem, {player}! Pass beim nächsten Mal besser auf.";

    private static ReviveReplyConfig INSTANCE;
    private boolean loaded = false;
    private boolean enabled = false;
    private String message = DEFAULT_MESSAGE;

    private ReviveReplyConfig() {}

    public static ReviveReplyConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ReviveReplyConfig();
        }
        if (!INSTANCE.loaded) {
            INSTANCE.load();
        }
        return INSTANCE;
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("gm-medic-revive-reply.cfg");
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
                    if (line.startsWith("enabled=")) {
                        enabled = Boolean.parseBoolean(line.substring("enabled=".length()).trim());
                    } else if (line.startsWith("message=")) {
                        String value = line.substring("message=".length());
                        message = value.isBlank() ? DEFAULT_MESSAGE : unescape(value);
                    }
                }
                GMMedic.LOGGER.info("[ReviveReplyConfig] Loaded: enabled={}, message={}", enabled, message);
            } catch (Exception e) {
                GMMedic.LOGGER.warn("[ReviveReplyConfig] Failed to load config, using defaults", e);
            }
        } else {
            GMMedic.LOGGER.info("[ReviveReplyConfig] No config file found, using defaults");
        }
    }

    public void save() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            String content = "# GM-Medic Revive Auto-Reply Configuration\n"
                    + "# Sends a normal public chat reply when YOU revive/heal a player - never for\n"
                    + "# other on-duty medics who just see the same FUNK broadcast.\n"
                    + "enabled=" + enabled + "\n"
                    + "# {player} is replaced with the revived player's name.\n"
                    + "message=" + escape(message) + "\n";
            Files.writeString(configPath, content);
            GMMedic.LOGGER.info("[ReviveReplyConfig] Saved config");
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[ReviveReplyConfig] Failed to save config", e);
        }
    }

    private static String escape(String value) {
        return value.replace("\n", "\\n");
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        save();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String v) {
        message = (v == null || v.isBlank()) ? DEFAULT_MESSAGE : v.trim();
        save();
    }

    public String buildReply(String playerName) {
        return message.replace("{player}", playerName);
    }
}
