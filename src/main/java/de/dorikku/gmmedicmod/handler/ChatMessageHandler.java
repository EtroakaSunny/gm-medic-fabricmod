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

public class ChatMessageHandler {

    private static final EmergencyCallManager manager = EmergencyCallManager.getInstance();

    private static final int DEDUP_SIZE = 10;
    private static final long[] recentHashes = new long[DEDUP_SIZE];
    private static int recentIndex = 0;

    // Strip ALL §X formatting codes
    private static final Pattern FORMAT_CODE_PATTERN = Pattern.compile("\u00a7.");

    // ---- FUNK patterns ----
    // Real GermanMiner server uses:  Ⓛ [Rank] Player » message  (Ⓛ = U+24C1, circled L for Lokal/Funk)
    // Simulation/old format uses:    [FUNK] (Rank) Player » message
    // We detect FUNK by checking for EITHER prefix.

    // Accept pattern: matches both formats
    //   "[FUNK] (Rank) Name » Ich nehme den Notruf von X entgegen!"
    //   "Ⓛ [Rank] Name » Ich nehme den Notruf von X entgegen!"
    //   Also handles cases where the » is some Unicode arrow/separator
    private static final Pattern ACCEPT_PATTERN =
            Pattern.compile("(?:\\[FUNK\\]\\s*\\([^)]*\\)|[\u24B6-\u24E9]\\s*\\[[^\\]]*\\])\\s+(.{1,16})\\s*.\\s*Ich nehme den Notruf von (.+?)\\s*entgegen!");

    private static final Pattern LOCATION_PATTERN =
            Pattern.compile("X:\\s*(-?[\\d.]+)[,\\s]+Y:\\s*(-?[\\d.]+)[,\\s]+Z:\\s*(-?[\\d.]+)\\)?(?:\\s+\\(([^)]+)\\))?");

    private static final Pattern REMAINING_TIME_PATTERN =
            Pattern.compile("(?:(\\d+)\\s*Minuten?[,\\s]+)?(\\d+)\\s*Sekunden?");

    private static final Pattern WITHDRAW_PATTERN = Pattern.compile("Spieler (.+?) hat");
    private static final Pattern REVIVE_PATTERN = Pattern.compile("habe (.+?) wieder");
    private static final Pattern LOGOUT_PATTERN = Pattern.compile("Spieler (.+?) hat sich ausgeloggt");
    private static final Pattern REACHED_PATTERN = Pattern.compile("Ich habe.*\\((.*)\\) erreicht");
    private static final Pattern REJECTED_PATTERN = Pattern.compile("von (.+?) mit");

    /**
     * Check if a stripped message is a FUNK-type message.
     * Real server uses Ⓛ (U+24C1) or other circled letters, simulation uses [FUNK].
     */
    private static boolean isFunkMessage(String msg) {
        if (msg.contains("[FUNK]")) return true;
        // Check for circled letter prefixes used by real GermanMiner server
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if ((c >= '\u24B6' && c <= '\u24CF') || (c >= '\u24D0' && c <= '\u24E9')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract player name from a FUNK message.
     * Format 1: "[FUNK] (Rank) PlayerName » message"
     * Format 2: "Ⓛ [Rank] PlayerName » message"
     * Returns null if cannot extract.
     */
    private static String extractFunkSender(String msg) {
        // Try format 1: [FUNK] (Rank) PlayerName »
        Pattern p1 = Pattern.compile("\\[FUNK\\]\\s*\\([^)]*\\)\\s+(.+?)\\s*\u00bb");
        Matcher m1 = p1.matcher(msg);
        if (m1.find()) return m1.group(1).trim();

        // Try format 2: Ⓛ [Rank] PlayerName »  (circled letter followed by [Rank])
        Pattern p2 = Pattern.compile("[\u24B6-\u24E9]\\s*\\[[^\\]]*\\]\\s+(.+?)\\s*\u00bb");
        Matcher m2 = p2.matcher(msg);
        if (m2.find()) return m2.group(1).trim();

        // Fallback: try to find "PlayerName »" after any bracket-enclosed prefix
        Pattern p3 = Pattern.compile("[\\])]\\s+(.+?)\\s*\u00bb");
        Matcher m3 = p3.matcher(msg);
        if (m3.find()) return m3.group(1).trim();

        return null;
    }

    public static void onGameMessage(Text message, boolean overlay) {
        if (overlay) return;
        processDeduped(message, "GAME");
    }

    public static void onChatMessage(Text message, @Nullable SignedMessage signedMessage, @Nullable GameProfile sender, MessageType.Parameters params, Instant receptionTimestamp) {
        processDeduped(message, "CHAT");
    }

    public static void processFromMixin(Text message) {
        processDeduped(message, "MIXIN");
    }

    private static void processDeduped(Text message, String source) {
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

            GMMedic.LOGGER.info("[GM-Medic] [{}] message: {}", source, raw);

            // Log Unicode codepoints for debugging unknown server formats
            if (raw.length() > 0 && raw.length() < 300) {
                StringBuilder cp = new StringBuilder();
                for (int i = 0; i < Math.min(raw.length(), 50); i++) {
                    char c = raw.charAt(i);
                    if (c > 127) {
                        cp.append(String.format("U+%04X ", (int) c));
                    } else {
                        cp.append(c);
                    }
                }
                GMMedic.LOGGER.info("[GM-Medic] [{}] first50chars(unicode): {}", source, cp.toString().trim());
            }

            processMessage(raw);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error processing {} message", source, e);
        }
    }

    private static void processMessage(String msg) {
        // Strip formatting codes
        msg = FORMAT_CODE_PATTERN.matcher(msg).replaceAll("");
        msg = msg.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        GMMedic.LOGGER.info("[GM-Medic] stripped: {}", msg);

        boolean isFunk = isFunkMessage(msg);
        GMMedic.LOGGER.info("[GM-Medic] isFunk={}, isZentrale={}, parsingState={}, inDuty={}",
                isFunk, msg.contains("ZENTRALE"), manager.getState(), manager.isInDuty());

        // ---- DUTY detection ----
        if (msg.contains("Du bist nun im Dienst") || msg.contains("Du bist jetzt im Dienst")) {
            manager.setInDuty(true);
            GMMedic.LOGGER.info("[GM-Medic] >>> Player is now ON DUTY (direct message)");
            return;
        }

        // Manual debug trigger: any message containing "TestDuty" forces duty on (works on any server)
        if (msg.contains("TestDuty")) {
            manager.setInDuty(true);
            GMMedic.LOGGER.info("[GM-Medic] >>> Player is now ON DUTY (TestDuty trigger)");
            return;
        }

        if (msg.contains("Du bist nicht mehr im Dienst") || msg.contains("Du hast den Dienst verlassen")) {
            manager.setInDuty(false);
            GMMedic.LOGGER.info("[GM-Medic] >>> Player is now OFF DUTY (direct message)");
            return;
        }

        // ---- FUNK-based duty detection ----
        if (isFunk) {
            String playerName = getPlayerName();
            String funkSender = extractFunkSender(msg);
            GMMedic.LOGGER.info("[GM-Medic] FUNK detected. playerName={}, funkSender={}, msg={}", playerName, funkSender, msg);

            boolean isOwnMessage = false;
            if (playerName != null && funkSender != null) {
                isOwnMessage = funkSender.equalsIgnoreCase(playerName);
                GMMedic.LOGGER.info("[GM-Medic] FUNK sender match check: funkSender='{}' == playerName='{}' -> {}", funkSender, playerName, isOwnMessage);
            } else if (playerName != null) {
                // Fallback: check if player name appears anywhere in the message
                isOwnMessage = msg.contains(playerName);
                GMMedic.LOGGER.info("[GM-Medic] FUNK fallback contains check: msg.contains('{}') -> {}", playerName, isOwnMessage);
            }

            if (isOwnMessage || playerName == null) {
                if (msg.contains("Ich bin wieder auf dem Server")) {
                    manager.setInDuty(true);
                    GMMedic.LOGGER.info("[GM-Medic] >>> Player is now ON DUTY (FUNK join message, playerName={})", playerName);
                    return;
                }
                if (msg.contains("Ich bin nicht mehr im Dienst") || msg.contains("Ich bin nun offline")) {
                    manager.setInDuty(false);
                    GMMedic.LOGGER.info("[GM-Medic] >>> Player is now OFF DUTY (FUNK leave message)");
                    return;
                }
            }
        }

        // ---- Everything below requires being on duty ----
        if (!manager.isInDuty()) {
            GMMedic.LOGGER.info("[GM-Medic] Not in duty, skipping further processing for: {}", msg.length() > 80 ? msg.substring(0, 80) + "..." : msg);
            return;
        }

        // ---- Call withdrawn by ZENTRALE ----
        if (msg.contains("ZENTRALE") && msg.contains("hat seinen Notruf zur\u00fcckgezogen")) {
            Matcher m = WITHDRAW_PATTERN.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                manager.removeCallByCallerName(caller);
                GMMedic.LOGGER.info("[GM-Medic] >>> Call WITHDRAWN: {}", caller);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not extract caller from withdraw message: {}", msg);
            }
            return;
        }

        // ---- Revive message ----
        if (isFunk && msg.contains("Ich habe") && msg.contains("wiederbelebt")) {
            Matcher m = REVIVE_PATTERN.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                manager.removeCallByCallerName(caller);
                GMMedic.LOGGER.info("[GM-Medic] >>> Call REVIVED: {}", caller);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not extract caller from revive message: {}", msg);
            }
            return;
        }

        // ---- Logout message ----
        if (msg.contains("ZENTRALE") && msg.contains("hat sich ausgeloggt")) {
            Matcher m = LOGOUT_PATTERN.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                manager.removeCallByCallerName(caller);
                GMMedic.LOGGER.info("[GM-Medic] >>> Call LOGOUT: {}", caller);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not extract caller from logout message: {}", msg);
            }
            return;
        }

        // ---- Reached message ----
        if (isFunk && msg.contains("Ich habe den Notruf in") && msg.contains("erreicht")) {
            Matcher m = REACHED_PATTERN.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                manager.removeCallByCallerName(caller);
                GMMedic.LOGGER.info("[GM-Medic] >>> Call REACHED: {}", caller);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not extract caller from reached message: {}", msg);
            }
            return;
        }

        // ---- Rejected message ----
        // Real format: "Ⓛ [Azubi] JustinXII » Ich habe den Notruf von Dorikku zurückgewiesen"
        // Sim format:  "[FUNK] (Azubi) JustinXII » Ich habe den Notruf von Dorikku mit folgendem Grund zurückgewiesen: ..."
        if (isFunk && msg.contains("Ich habe den Notruf von") && msg.contains("zur\u00fcckgewiesen")) {
            // Extract caller name: "Ich habe den Notruf von <NAME> zurückgewiesen" or "von <NAME> mit folgendem Grund zurückgewiesen"
            Pattern rejectCallerPattern = Pattern.compile("Ich habe den Notruf von (.+?)(?:\\s+mit folgendem Grund)?\\s+zur\u00fcckgewiesen");
            Matcher m = rejectCallerPattern.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                String funkSender = extractFunkSender(msg);
                String medic = funkSender != null ? funkSender : "Unbekannt";
                manager.rejectCall(caller, medic);
                GMMedic.LOGGER.info("[GM-Medic] >>> Call REJECTED by {} for caller {}", medic, caller);
            } else {
                // Fallback with old pattern
                Matcher mOld = REJECTED_PATTERN.matcher(msg);
                if (mOld.find()) {
                    String caller = mOld.group(1).trim();
                    String funkSender = extractFunkSender(msg);
                    String medic = funkSender != null ? funkSender : "Unbekannt";
                    manager.rejectCall(caller, medic);
                    GMMedic.LOGGER.info("[GM-Medic] >>> Call REJECTED (fallback) by {} for caller {}", medic, caller);
                } else {
                    GMMedic.LOGGER.warn("[GM-Medic] Could not extract caller from rejected message: {}", msg);
                }
            }
            return;
        }

        // ---- Accept message (another medic takes a call) ----
        if (isFunk && msg.contains("Ich nehme den Notruf von") && msg.contains("entgegen")) {
            GMMedic.LOGGER.info("[GM-Medic] Trying to match ACCEPT_PATTERN on: {}", msg);
            Matcher m = ACCEPT_PATTERN.matcher(msg);
            if (m.find()) {
                String medic = m.group(1).trim();
                String caller = m.group(2).trim();
                manager.assignMedic(caller, medic);
                GMMedic.LOGGER.info("[GM-Medic] >>> Call ACCEPTED by {} for {}", medic, caller);
            } else {
                // Fallback: try a simpler extraction
                GMMedic.LOGGER.warn("[GM-Medic] ACCEPT_PATTERN did not match, trying fallback extraction for: {}", msg);
                Pattern fallbackAccept = Pattern.compile("(?:^|\\]\\s*|\\)\\s*)([A-Za-z0-9_]{1,16})\\s*.\\s*Ich nehme den Notruf von (.+?)\\s*entgegen!");
                Matcher mf = fallbackAccept.matcher(msg);
                if (mf.find()) {
                    String medic = mf.group(1).trim();
                    String caller = mf.group(2).trim();
                    manager.assignMedic(caller, medic);
                    GMMedic.LOGGER.info("[GM-Medic] >>> Call ACCEPTED (fallback) by {} for {}", medic, caller);
                } else {
                    // Ultra-fallback: just extract caller name
                    Pattern ultraFallback = Pattern.compile("Ich nehme den Notruf von (.+?)\\s*entgegen!");
                    Matcher mu = ultraFallback.matcher(msg);
                    if (mu.find()) {
                        String caller = mu.group(1).trim();
                        String funkSender = extractFunkSender(msg);
                        String medic = funkSender != null ? funkSender : "Unbekannt";
                        manager.assignMedic(caller, medic);
                        GMMedic.LOGGER.info("[GM-Medic] >>> Call ACCEPTED (ultra-fallback) by {} for {}", medic, caller);
                    } else {
                        GMMedic.LOGGER.error("[GM-Medic] Could not parse accept message at all: {}", msg);
                    }
                }
            }
            return;
        }

        // ---- DATENÜBERMITTLUNG header ----
        if (msg.contains("DATEN") && msg.contains("BERMITTLUNG VON ZENTRALE")) {
            manager.startParsing();
            GMMedic.LOGGER.info("[GM-Medic] >>> TRANSMISSION STARTED (pending call created)");
            return;
        }

        // ---- Parsing individual lines of a transmission block ----
        if (manager.getState() != ParsingState.PARSING) {
            return;
        }

        GMMedic.LOGGER.info("[GM-Medic] [PARSING] line: {}", msg);

        if (msg.contains("Betroffener:")) {
            String caller = msg.replaceAll(".*Betroffener:\\s*", "").trim();
            manager.setCaller(caller, true);
            GMMedic.LOGGER.info("[GM-Medic] >>> DEATH victim: {}", caller);
            return;
        }

        if (msg.contains("Notruf von:")) {
            String caller = msg.replaceAll(".*Notruf von:\\s*", "").trim();
            manager.setCaller(caller, false);
            GMMedic.LOGGER.info("[GM-Medic] >>> ECALL caller: {}", caller);
            return;
        }

        if (msg.contains("Von:")) {
            String caller = msg.replaceAll(".*Von:\\s*", "").trim();
            manager.setCaller(caller, false);
            GMMedic.LOGGER.info("[GM-Medic] >>> ECALL caller (Von): {}", caller);
            return;
        }

        if (msg.contains("Verbleibende Zeit:")) {
            Matcher m = REMAINING_TIME_PATTERN.matcher(msg);
            if (m.find()) {
                int minutes = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
                int seconds = Integer.parseInt(m.group(2));
                int totalSeconds = minutes * 60 + seconds;
                manager.setRemainingTime(totalSeconds);
                GMMedic.LOGGER.info("[GM-Medic] >>> Remaining time: {}m {}s = {}s total", minutes, seconds, totalSeconds);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not parse remaining time from: {}", msg);
            }
            return;
        }

        if (msg.contains("Todesursache:")) {
            String reason = msg.replaceAll(".*Todesursache:\\s*", "").trim();
            manager.setReason(reason);
            GMMedic.LOGGER.info("[GM-Medic] >>> Death reason: {}", reason);
            return;
        }

        if (msg.contains("Grund:")) {
            String reason = msg.replaceAll(".*Grund:\\s*", "").trim();
            manager.setReason(reason);
            GMMedic.LOGGER.info("[GM-Medic] >>> Call reason: {}", reason);
            return;
        }

        if (msg.contains("Ortung:") || msg.contains("Ort:")) {
            Matcher m = LOCATION_PATTERN.matcher(msg);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1));
                double y = Double.parseDouble(m.group(2));
                String zStr = m.group(3).replace(")", "");
                double z = Double.parseDouble(zStr);
                String locName = m.group(4) != null ? m.group(4).trim() : "";
                manager.setLocation(x, y, z, locName);
                GMMedic.LOGGER.info("[GM-Medic] >>> Location: X={}, Y={}, Z={} ({})", x, y, z, locName);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not parse location from: {}", msg);
            }
            return;
        }

        if (msg.contains("HQ: Kannst du") || msg.contains("ANNEHMEN")) {
            var call = manager.finalizeCall();
            if (call != null) {
                GMMedic.LOGGER.info("[GM-Medic] >>> CALL FINALIZED: {} - {} (type={})", call.getCallerName(), call.getReason(), call.getType());
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Tried to finalize call but no pending call found");
            }
            return;
        }
    }

    private static String getPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return null;
        if (client.player != null) {
            return client.player.getNameForScoreboard();
        }
        if (client.getSession() != null && client.getSession().getUsername() != null) {
            return client.getSession().getUsername();
        }
        return null;
    }
}
