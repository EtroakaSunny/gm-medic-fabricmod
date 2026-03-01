package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager.ParsingState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import com.mojang.authlib.GameProfile;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central handler for all chat messages. Detects duty status, emergency call
 * transmissions, call acceptance, rejection, withdrawal, and other FUNK events.
 *
 * GermanMiner uses two message formats for FUNK:
 *   - Real server:  Ⓛ [Rank] Player » message   (Ⓛ = U+24C1)
 *   - Simulation:   [FUNK] (Rank) Player » message
 */
public class ChatMessageHandler {

    private static final EmergencyCallManager manager = EmergencyCallManager.getInstance();

    // Deduplication ring buffer — prevents the same message being processed multiple times
    // across the three mixin intercept points (PacketMixin, MessageHandler, ChatHud).
    private static final int DEDUP_SIZE = 10;
    private static final long[] recentHashes = new long[DEDUP_SIZE];
    private static int recentIndex = 0;

    // --- Patterns ---

    private static final Pattern FORMAT_CODE = Pattern.compile("\u00a7.");

    private static final Pattern LOCATION =
            Pattern.compile("X:\\s*(-?[\\d.]+)[,\\s]+Y:\\s*(-?[\\d.]+)[,\\s]+Z:\\s*(-?[\\d.]+)\\)?(?:\\s+\\(([^)]+)\\))?");

    private static final Pattern REMAINING_TIME =
            Pattern.compile("(?:(\\d+)\\s*Minuten?[,\\s]+)?(\\d+)\\s*Sekunden?");

    // Accepts both FUNK formats: "[FUNK] (Rank) Name »" and "Ⓛ [Rank] Name »"
    private static final Pattern ACCEPT =
            Pattern.compile("(?:\\[FUNK]\\s*\\([^)]*\\)|[\u24B6-\u24E9]\\s*\\[[^]]*])\\s+(.{1,16})\\s*.\\s*Ich nehme den Notruf von (.+?)\\s*entgegen!");

    private static final Pattern REJECT_CALLER =
            Pattern.compile("Ich habe den Notruf von (.+?)(?:\\s+mit folgendem Grund)?\\s+zur\u00fcckgewiesen");

    private static final Pattern WITHDRAW_CALLER = Pattern.compile("Spieler (.+?) hat");
    private static final Pattern REVIVE_CALLER   = Pattern.compile("habe (.+?) wieder");
    private static final Pattern LOGOUT_CALLER   = Pattern.compile("Spieler (.+?) hat sich ausgeloggt");
    private static final Pattern REACHED_CALLER  = Pattern.compile("Ich habe.*\\((.*)\\) erreicht");

    // Extracts "PlayerName" from FUNK message before the » separator
    private static final Pattern FUNK_SENDER_SIM  = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(.+?)\\s*\u00bb");
    private static final Pattern FUNK_SENDER_REAL = Pattern.compile("[\u24B6-\u24E9]\\s*\\[[^]]*]\\s+(.+?)\\s*\u00bb");
    private static final Pattern FUNK_SENDER_ANY  = Pattern.compile("[\\])]\\s+(.+?)\\s*\u00bb");

    // Fallback accept patterns (used when the primary ACCEPT pattern misses)
    private static final Pattern ACCEPT_FALLBACK =
            Pattern.compile("(?:^|]\\s*|\\)\\s*)([A-Za-z0-9_]{1,16})\\s*.\\s*Ich nehme den Notruf von (.+?)\\s*entgegen!");
    private static final Pattern ACCEPT_ULTRA_FALLBACK =
            Pattern.compile("Ich nehme den Notruf von (.+?)\\s*entgegen!");

    // --- Public entry points (called by Fabric events and mixins) ---

    public static void onGameMessage(Text message, boolean overlay) {
        if (!overlay) processDeduped(message);
    }

    @SuppressWarnings("unused") // Parameters required by Fabric event signature
    public static void onChatMessage(Text message, @Nullable SignedMessage signedMessage,
                                     @Nullable GameProfile sender, MessageType.Parameters params,
                                     Instant receptionTimestamp) {
        processDeduped(message);
    }

    public static void processFromMixin(Text message) {
        processDeduped(message);
    }

    // --- Deduplication ---

    private static void processDeduped(Text message) {
        try {
            String raw = message.getString();
            long hash = raw.hashCode() * 31L + (System.currentTimeMillis() / 50);

            synchronized (recentHashes) {
                for (long h : recentHashes) {
                    if (h == hash) return;
                }
                recentHashes[recentIndex] = hash;
                recentIndex = (recentIndex + 1) % DEDUP_SIZE;
            }

            GMMedic.LOGGER.debug("[GM-Medic] Raw message: {}", raw);
            processMessage(raw);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error processing message", e);
        }
    }

    // --- Message processing ---

    private static void processMessage(String msg) {
        msg = FORMAT_CODE.matcher(msg).replaceAll("");
        msg = msg.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        boolean isFunk = isFunkMessage(msg);

        // Duty on
        if (msg.contains("Du bist nun im Dienst") || msg.contains("Du bist jetzt im Dienst")) {
            manager.setInDuty(true);
            GMMedic.LOGGER.info("[GM-Medic] On duty (direct)");
            return;
        }

        // Debug trigger — type "TestDuty" in any chat to force duty on
        if (msg.contains("TestDuty")) {
            manager.setInDuty(true);
            GMMedic.LOGGER.info("[GM-Medic] On duty (TestDuty)");
            return;
        }

        // Duty off
        if (msg.contains("Du bist nicht mehr im Dienst") || msg.contains("Du hast den Dienst verlassen")) {
            manager.setInDuty(false);
            GMMedic.LOGGER.info("[GM-Medic] Off duty (direct)");
            return;
        }

        // FUNK-based duty detection (own messages only)
        if (isFunk) {
            String playerName = getPlayerName();
            String funkSender = extractFunkSender(msg);
            boolean isOwn = isOwnMessage(playerName, funkSender, msg);

            if (isOwn) {
                if (msg.contains("Ich bin wieder auf dem Server")) {
                    manager.setInDuty(true);
                    GMMedic.LOGGER.info("[GM-Medic] On duty (FUNK join)");
                    return;
                }
                if (msg.contains("Ich bin nicht mehr im Dienst") || msg.contains("Ich bin nun offline")) {
                    manager.setInDuty(false);
                    GMMedic.LOGGER.info("[GM-Medic] Off duty (FUNK leave)");
                    return;
                }
            }
        }

        // Everything below requires being on duty
        if (!manager.isInDuty()) return;

        // Call withdrawn
        if (msg.contains("ZENTRALE") && msg.contains("hat seinen Notruf zur\u00fcckgezogen")) {
            extractAndRemove(WITHDRAW_CALLER, msg, "withdrawn");
            return;
        }

        // Revived
        if (isFunk && msg.contains("Ich habe") && msg.contains("wiederbelebt")) {
            extractAndRemove(REVIVE_CALLER, msg, "revived");
            return;
        }

        // Logged out
        if (msg.contains("ZENTRALE") && msg.contains("hat sich ausgeloggt")) {
            extractAndRemove(LOGOUT_CALLER, msg, "logout");
            return;
        }

        // Reached
        if (isFunk && msg.contains("Ich habe den Notruf in") && msg.contains("erreicht")) {
            extractAndRemove(REACHED_CALLER, msg, "reached");
            return;
        }

        // Rejected
        if (isFunk && msg.contains("Ich habe den Notruf von") && msg.contains("zur\u00fcckgewiesen")) {
            Matcher m = REJECT_CALLER.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                String medic = extractFunkSenderOrDefault(msg);
                manager.rejectCall(caller, medic);
                GMMedic.LOGGER.info("[GM-Medic] Call rejected: {} by {}", caller, medic);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not parse rejection: {}", msg);
            }
            return;
        }

        // Accepted
        if (isFunk && msg.contains("Ich nehme den Notruf von") && msg.contains("entgegen")) {
            if (!tryAccept(ACCEPT, msg) && !tryAccept(ACCEPT_FALLBACK, msg)) {
                Matcher mu = ACCEPT_ULTRA_FALLBACK.matcher(msg);
                if (mu.find()) {
                    String caller = mu.group(1).trim();
                    String medic = extractFunkSenderOrDefault(msg);
                    manager.assignMedic(caller, medic);
                    GMMedic.LOGGER.info("[GM-Medic] Call accepted: {} by {}", caller, medic);
                } else {
                    GMMedic.LOGGER.warn("[GM-Medic] Could not parse accept: {}", msg);
                }
            }
            return;
        }

        // Transmission header
        if (msg.contains("DATEN") && msg.contains("BERMITTLUNG VON ZENTRALE")) {
            manager.startParsing();
            GMMedic.LOGGER.info("[GM-Medic] Transmission started");
            return;
        }

        // Parse transmission lines
        if (manager.getState() != ParsingState.PARSING) return;
        parseTransmissionLine(msg);
    }

    // --- Transmission line parsing ---

    private static void parseTransmissionLine(String msg) {
        if (msg.contains("Betroffener:")) {
            manager.setCaller(extractValue(msg, "Betroffener:"), true);
            return;
        }
        if (msg.contains("Notruf von:")) {
            manager.setCaller(extractValue(msg, "Notruf von:"), false);
            return;
        }
        if (msg.contains("Von:")) {
            manager.setCaller(extractValue(msg, "Von:"), false);
            return;
        }
        if (msg.contains("Verbleibende Zeit:")) {
            Matcher m = REMAINING_TIME.matcher(msg);
            if (m.find()) {
                int minutes = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
                int seconds = Integer.parseInt(m.group(2));
                manager.setRemainingTime(minutes * 60 + seconds);
            }
            return;
        }
        if (msg.contains("Todesursache:")) {
            manager.setReason(extractValue(msg, "Todesursache:"));
            return;
        }
        if (msg.contains("Grund:")) {
            manager.setReason(extractValue(msg, "Grund:"));
            return;
        }
        if (msg.contains("Ortung:") || msg.contains("Ort:")) {
            Matcher m = LOCATION.matcher(msg);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1));
                double y = Double.parseDouble(m.group(2));
                double z = Double.parseDouble(m.group(3).replace(")", ""));
                String locName = m.group(4) != null ? m.group(4).trim() : "";
                manager.setLocation(x, y, z, locName);
            }
            return;
        }
        if (msg.contains("HQ: Kannst du") || msg.contains("ANNEHMEN")) {
            var call = manager.finalizeCall();
            if (call != null) {
                GMMedic.LOGGER.info("[GM-Medic] Call finalized: {} — {}", call.getCallerName(), call.getReason());
            }
        }
    }

    // --- Helpers ---

    /** Returns true if the message uses a FUNK prefix ([FUNK] or circled Unicode letter). */
    private static boolean isFunkMessage(String msg) {
        if (msg.contains("[FUNK]")) return true;
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if ((c >= '\u24B6' && c <= '\u24CF') || (c >= '\u24D0' && c <= '\u24E9')) return true;
        }
        return false;
    }

    /** Extracts the FUNK sender player name, or null if not found. */
    private static String extractFunkSender(String msg) {
        for (Pattern p : new Pattern[]{FUNK_SENDER_SIM, FUNK_SENDER_REAL, FUNK_SENDER_ANY}) {
            Matcher m = p.matcher(msg);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }

    /** Extracts the FUNK sender, defaulting to "Unbekannt". */
    private static String extractFunkSenderOrDefault(String msg) {
        String sender = extractFunkSender(msg);
        return sender != null ? sender : "Unbekannt";
    }

    /** Checks if a FUNK message was sent by the local player. */
    private static boolean isOwnMessage(String playerName, String funkSender, String msg) {
        if (playerName == null) return true; // Can't verify — assume own
        if (funkSender != null) return funkSender.equalsIgnoreCase(playerName);
        return msg.contains(playerName);
    }

    /** Extracts a caller from msg using the given pattern and removes the call. */
    private static void extractAndRemove(Pattern pattern, String msg, String reason) {
        Matcher m = pattern.matcher(msg);
        if (m.find()) {
            String caller = m.group(1).trim();
            manager.removeCallByCallerName(caller);
            GMMedic.LOGGER.info("[GM-Medic] Call removed ({}): {}", reason, caller);
        } else {
            GMMedic.LOGGER.warn("[GM-Medic] Could not parse caller for {}: {}", reason, msg);
        }
    }

    /** Tries to match an accept pattern and assign the medic. Returns true on success. */
    private static boolean tryAccept(Pattern pattern, String msg) {
        Matcher m = pattern.matcher(msg);
        if (m.find()) {
            String medic = m.group(1).trim();
            String caller = m.group(2).trim();
            manager.assignMedic(caller, medic);
            GMMedic.LOGGER.info("[GM-Medic] Call accepted: {} by {}", caller, medic);
            return true;
        }
        return false;
    }

    /** Extracts the value after "Key:" from a transmission line. */
    private static String extractValue(String msg, String key) {
        return msg.replaceAll(".*" + Pattern.quote(key) + "\\s*", "").trim();
    }

    private static String getPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return null;
        if (client.player != null) return client.player.getNameForScoreboard();
        if (client.getSession() != null) return client.getSession().getUsername();
        return null;
    }
}
