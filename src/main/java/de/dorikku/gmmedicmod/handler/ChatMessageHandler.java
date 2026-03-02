package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager.ParsingState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central handler for all chat messages. Detects duty status, emergency call
 * transmissions, call acceptance, rejection, withdrawal, and other FUNK events.
 *
 * GermanMiner FUNK format: [FUNK] (Rank) Player » message
 */
public class ChatMessageHandler {

    private static final EmergencyCallManager manager = EmergencyCallManager.getInstance();


    // --- Patterns ---

    private static final Pattern FORMAT_CODE = Pattern.compile("\u00a7.");

    private static final Pattern LOCATION =
            Pattern.compile("X:\\s*(-?[\\d.]+)[,\\s]+Y:\\s*(-?[\\d.]+)[,\\s]+Z:\\s*(-?[\\d.]+)\\)?(?:\\s+\\(([^)]+)\\))?");

    private static final Pattern REMAINING_TIME =
            Pattern.compile("(?:(\\d+)\\s*Minuten?[,\\s]+)?(\\d+)\\s*Sekunden?");

    // Extracts caller from "Ich bin nun auf dem Weg zu dem Notruf von PlayerName."
    private static final Pattern ACCEPT_ECALL_CALLER =
            Pattern.compile("Ich bin nun auf dem Weg zu dem Notruf von (.+?)\\.");

    // Extracts caller from "Ich bin nun auf dem Weg zu der Todesmeldung von PlayerName."
    private static final Pattern ACCEPT_DEATH_CALLER =
            Pattern.compile("Ich bin nun auf dem Weg zu der Todesmeldung von (.+?)\\.");

    private static final Pattern REJECT_CALLER =
            Pattern.compile("Ich habe den Notruf von (.+?)\\s+zur\u00fcckgewiesen");

    private static final Pattern WITHDRAW_CALLER = Pattern.compile("Spieler (.+?) hat");
    private static final Pattern REVIVE_CALLER   = Pattern.compile("habe (.+?) wieder");
    private static final Pattern LOGOUT_CALLER   = Pattern.compile("Spieler (.+?) hat sich ausgeloggt");
    private static final Pattern REACHED_CALLER  = Pattern.compile("Ich habe den Notruf von (.+?)\\s+erreicht");

    // Extracts caller from "Ich kann die Todesmeldung von PlayerName nicht mehr erledigen."
    private static final Pattern CANCEL_DEATH_CALLER =
            Pattern.compile("Ich kann die Todesmeldung von (.+?)\\s+nicht mehr erledigen");

    // Extracts "PlayerName" from FUNK message before the » separator
    private static final Pattern FUNK_SENDER_SIM  = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(.+?)\\s*\u00bb");
    private static final Pattern FUNK_SENDER_ANY  = Pattern.compile("[\\])]\\s+(.+?)\\s*\u00bb");


    // --- Public entry point ---

    /** Called by ClientReceiveMessageEvents.GAME — the sole intercept for all messages. */
    public static void onGameMessage(Text message, boolean overlay) {
        if (overlay) return;
        try {
            String raw = message.getString();

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
            extractAndResolve(WITHDRAW_CALLER, msg, "withdrawn", "Zur\u00fcckgezogen");
            return;
        }

        // Revived
        if (isFunk && msg.contains("Ich habe") && msg.contains("wiederbelebt")) {
            extractAndResolve(REVIVE_CALLER, msg, "revived", "Wiederbelebt");
            return;
        }

        // Logged out
        if (msg.contains("ZENTRALE") && msg.contains("hat sich ausgeloggt")) {
            extractAndResolve(LOGOUT_CALLER, msg, "logout", "Ausgeloggt");
            return;
        }

        // Reached
        if (isFunk && msg.contains("Ich habe den Notruf von") && msg.contains("erreicht")) {
            extractAndResolve(REACHED_CALLER, msg, "reached", "Erreicht");
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

        // Accepted E-Call: "Ich bin nun auf dem Weg zu dem Notruf von PlayerName."
        if (isFunk && msg.contains("Ich bin nun auf dem Weg zu dem Notruf von")) {
            Matcher m = ACCEPT_ECALL_CALLER.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                String medic = extractFunkSenderOrDefault(msg);
                manager.assignMedic(caller, medic);
                GMMedic.LOGGER.info("[GM-Medic] E-Call accepted: {} by {}", caller, medic);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not parse E-Call accept: {}", msg);
            }
            return;
        }

        // Accepted Death: "Ich bin nun auf dem Weg zu der Todesmeldung von PlayerName."
        if (isFunk && msg.contains("Ich bin nun auf dem Weg zu der Todesmeldung von")) {
            Matcher m = ACCEPT_DEATH_CALLER.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                String medic = extractFunkSenderOrDefault(msg);
                manager.assignMedic(caller, medic);
                GMMedic.LOGGER.info("[GM-Medic] Death accepted: {} by {}", caller, medic);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not parse death accept: {}", msg);
            }
            return;
        }

        // Cancel medic on route (death): "Ich kann die Todesmeldung von PlayerName nicht mehr erledigen. Bitte übernehmen!"
        if (isFunk && msg.contains("Ich kann die Todesmeldung von") && msg.contains("nicht mehr erledigen")) {
            Matcher m = CANCEL_DEATH_CALLER.matcher(msg);
            if (m.find()) {
                String caller = m.group(1).trim();
                manager.unassignMedic(caller);
                GMMedic.LOGGER.info("[GM-Medic] Medic cancelled on death: {}", caller);
            } else {
                GMMedic.LOGGER.warn("[GM-Medic] Could not parse cancel-on-route: {}", msg);
            }
            return;
        }

        // Transmission pre-message: "Wir haben einen neuen Notruf erhalten" / "Wir haben eine neue Todesmeldung erhalten"
        // This arrives ~2 seconds before the actual data block, creating the pending placeholder early.
        if (msg.contains("ZENTRALE") && msg.contains("ich schicke euch die Daten")) {
            boolean isDeath = msg.contains("Todesmeldung");
            manager.startParsing(isDeath);
            GMMedic.LOGGER.info("[GM-Medic] Transmission pre-message — pending {} created", isDeath ? "death" : "ecall");
            return;
        }

        // Transmission header (DATENÜBERMITTLUNG VON ZENTRALE) — arrives with the data lines,
        // just skip it since startParsing() was already called on the pre-message.
        if (msg.contains("DATEN") && msg.contains("BERMITTLUNG VON ZENTRALE")) {
            // If state is not PARSING (e.g. pre-message was missed), start parsing now as fallback.
            // But NOT if a call was just finalized — the header can arrive after ANNEHMEN in the
            // same message batch due to processing order, which would create a duplicate.
            if (manager.getState() != ParsingState.PARSING && !manager.wasRecentlyFinalized(1000)) {
                manager.startParsing(false);  // Type unknown from header alone — data lines will set it
                GMMedic.LOGGER.info("[GM-Medic] Transmission started (fallback from header)");
            }
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

    /** Returns true if the message contains a [FUNK] prefix. */
    private static boolean isFunkMessage(String msg) {
        return msg.contains("[FUNK]");
    }

    /** Extracts the FUNK sender player name, or null if not found. */
    private static String extractFunkSender(String msg) {
        for (Pattern p : new Pattern[]{FUNK_SENDER_SIM, FUNK_SENDER_ANY}) {
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

    /** Extracts a caller from msg using the given pattern and resolves the call (gray-out before removal). */
    private static void extractAndResolve(Pattern pattern, String msg, String reason, String displayLabel) {
        Matcher m = pattern.matcher(msg);
        if (m.find()) {
            String caller = m.group(1).trim();
            manager.resolveCall(caller, displayLabel);
            GMMedic.LOGGER.info("[GM-Medic] Call resolved ({}): {}", reason, caller);
        } else {
            GMMedic.LOGGER.warn("[GM-Medic] Could not parse caller for {}: {}", reason, msg);
        }
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
