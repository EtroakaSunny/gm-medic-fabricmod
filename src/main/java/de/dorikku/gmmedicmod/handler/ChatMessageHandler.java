package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager.ParsingState;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatMessageHandler {

    private static final EmergencyCallManager manager = EmergencyCallManager.getInstance();

    private static final Pattern FORMAT_CODE         = Pattern.compile("§.");
    private static final Pattern LOCATION            = Pattern.compile("X:\\s*(-?[\\d.]+)[,\\s]+Y:\\s*(-?[\\d.]+)[,\\s]+Z:\\s*(-?[\\d.]+)\\)?(?:\\s+\\(([^)]+)\\))?");
    private static final Pattern REMAINING_TIME      = Pattern.compile("(?:(\\d+)\\s*Minuten?[,\\s]+)?(\\d+)\\s*Sekunden?");
    private static final Pattern ACCEPT_ECALL_CALLER  = Pattern.compile("Ich bin nun auf dem Weg zu dem Notruf von (.+?)\\.");
    private static final Pattern ACCEPT_DEATH_CALLER  = Pattern.compile("Ich bin nun auf dem Weg zu der Todesmeldung von (.+?)\\.");
    private static final Pattern REJECT_CALLER       = Pattern.compile("Ich habe den Notruf von (.+?)\\s+zurückgewiesen");
    private static final Pattern WITHDRAW_CALLER     = Pattern.compile("Spieler (.+?) hat");
    private static final Pattern REVIVE_CALLER       = Pattern.compile("Ich habe\\s+(.+?)\\s+wiederbelebt(?:[.!?]|$)");
    private static final Pattern LOGOUT_CALLER       = Pattern.compile("Spieler (.+?) hat sich ausgeloggt");
    private static final Pattern REACHED_CALLER      = Pattern.compile("Ich habe den Notruf von (.+?)\\s+erreicht");
    private static final Pattern CANCEL_DEATH_CALLER = Pattern.compile("Ich kann die Todesmeldung von (.+?)\\s+nicht mehr erledigen");
    private static final Pattern FUNK_SENDER_SIM     = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(.+?)\\s*»");
    private static final Pattern FUNK_SENDER_ANY     = Pattern.compile("[\\])]\\s+(.+?)\\s*»");
    private static final Pattern FUNK_SHIFT_JOIN     = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(\\S+)\\s+Trete\\s+Meine\\s+Schicht\\s+an", Pattern.CASE_INSENSITIVE);

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

    private static void processMessage(String msg) {
        msg = FORMAT_CODE.matcher(msg).replaceAll("");
        msg = msg.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        boolean isFunk = isFunkMessage(msg);

        if (msg.contains("Du bist nicht mehr im Dienst") || msg.contains("Du hast den Dienst verlassen")) {
            manager.setInDuty(false);
            GMMedic.LOGGER.info("[GM-Medic] Off duty (direct)");
            return;
        }

        if (isFunk) {
            String playerName = getPlayerName();

            Matcher shiftJoinMatcher = FUNK_SHIFT_JOIN.matcher(msg);
            if (shiftJoinMatcher.find()) {
                String sender = shiftJoinMatcher.group(1).trim();
                if (playerName == null || sender.equalsIgnoreCase(playerName)) {
                    manager.setInDuty(true);
                    GMMedic.LOGGER.info("[GM-Medic] On duty (FUNK shift join)");
                    return;
                }
            }

            String funkSender = extractFunkSender(msg);
            boolean isOwn = isOwnMessage(playerName, funkSender, msg);
            if (isOwn) {
                if (msg.contains("Ich bin wieder auf dem Server")) {
                    manager.setInDuty(true);
                    GMMedic.LOGGER.info("[GM-Medic] On duty (FUNK rejoin)");
                    return;
                }
                if (msg.contains("Ich bin nicht mehr im Dienst") || msg.contains("Ich bin nun offline")) {
                    manager.setInDuty(false);
                    GMMedic.LOGGER.info("[GM-Medic] Off duty (FUNK leave)");
                    return;
                }
            }
        }

        if (!manager.isInDuty()) return;

        if (msg.contains("ZENTRALE") && msg.contains("hat seinen Notruf zurückgezogen")) {
            extractAndResolve(WITHDRAW_CALLER, msg, "withdrawn", "Zurückgezogen");
            return;
        }

        if (isFunk && msg.contains("Ich habe") && msg.contains("wiederbelebt")) {
            extractAndResolve(REVIVE_CALLER, msg, "revived", "Wiederbelebt");
            return;
        }

        if (msg.contains("ZENTRALE") && msg.contains("hat sich ausgeloggt")) {
            extractAndResolve(LOGOUT_CALLER, msg, "logout", "Ausgeloggt");
            return;
        }

        if (isFunk && msg.contains("Ich habe den Notruf von") && msg.contains("erreicht")) {
            extractAndResolve(REACHED_CALLER, msg, "reached", "Erreicht");
            return;
        }

        if (isFunk && msg.contains("Ich habe den Notruf von") && msg.contains("zurückgewiesen")) {
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

        if (msg.contains("ZENTRALE") && msg.contains("ich schicke euch die Daten")) {
            boolean isDeath = msg.contains("Todesmeldung");
            manager.startParsing(isDeath);
            GMMedic.LOGGER.info("[GM-Medic] Transmission pre-message — pending {} created", isDeath ? "death" : "ecall");
            return;
        }

        if (msg.contains("DATENÜBERMITTLUNG VON ZENTRALE")
                || (msg.contains("DATEN") && msg.contains("BERMITTLUNG VON ZENTRALE"))) {
            if (manager.getState() == ParsingState.PARSING && manager.hasPendingCallerName()) {
                EmergencyCall prev = manager.finalizeCall();
                if (prev != null) {
                    GMMedic.LOGGER.info("[GM-Medic] Force-finalized pending call before new data block: {} — {}", prev.getCallerName(), prev.getReason());
                }
            }
            if (manager.getState() == ParsingState.IDLE) {
                manager.startOrphanParsing();
                GMMedic.LOGGER.info("[GM-Medic] Orphaned transmission header detected — starting orphan parsing");
            }
            return;
        }

        if (manager.getState() == ParsingState.PARSING) {
            parseTransmissionLine(msg);
        }
    }

    private static void parseTransmissionLine(String msg) {
        if (msg.contains("Betroffener:")) {
            manager.setCaller(extractValue(msg, "Betroffener:"), true);
        } else if (msg.contains("Notruf von:")) {
            manager.setCaller(extractValue(msg, "Notruf von:"), false);
        } else if (msg.contains("Von:")) {
            manager.setCaller(extractValue(msg, "Von:"), false);
        } else if (msg.contains("Verbleibende Zeit:")) {
            Matcher m = REMAINING_TIME.matcher(msg);
            if (m.find()) {
                int minutes = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
                int seconds = Integer.parseInt(m.group(2));
                manager.setRemainingTime(minutes * 60 + seconds);
            }
        } else if (msg.contains("Todesursache:")) {
            manager.setReason(extractValue(msg, "Todesursache:"));
        } else if (msg.contains("Grund:")) {
            manager.setReason(extractValue(msg, "Grund:"));
        } else if (msg.contains("Ortung:") || msg.contains("Ort:")) {
            Matcher m = LOCATION.matcher(msg);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1));
                double y = Double.parseDouble(m.group(2));
                double z = Double.parseDouble(m.group(3));
                String locName = m.group(4) != null ? m.group(4).trim() : "";
                manager.setLocation(x, y, z, locName);
            }
        } else if (msg.contains("HQ: Kannst du") || msg.contains("ANNEHMEN")) {
            EmergencyCall call = manager.finalizeCall();
            if (call != null) {
                GMMedic.LOGGER.info("[GM-Medic] Call finalized: {} — {}", call.getCallerName(), call.getReason());
            }
        }
    }

    private static boolean isFunkMessage(String msg) {
        return msg.contains("[FUNK]");
    }

    private static String extractFunkSender(String msg) {
        for (Pattern pattern : new Pattern[]{FUNK_SENDER_SIM, FUNK_SENDER_ANY}) {
            Matcher m = pattern.matcher(msg);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }

    private static String extractFunkSenderOrDefault(String msg) {
        String sender = extractFunkSender(msg);
        return sender != null ? sender : "Unbekannt";
    }

    private static boolean isOwnMessage(String playerName, String funkSender, String msg) {
        if (playerName == null) return true;
        return funkSender != null ? funkSender.equalsIgnoreCase(playerName) : msg.contains(playerName);
    }

    private static void extractAndResolve(Pattern pattern, String msg, String reason, String displayLabel) {
        Matcher m = pattern.matcher(msg);
        if (m.find()) {
            String caller = EmergencyCallManager.normalizeCallerName(m.group(1));
            if (caller == null) {
                GMMedic.LOGGER.warn("[GM-Medic] Parsed empty caller for {}: {}", reason, msg);
                return;
            }
            manager.resolveCall(caller, displayLabel);
            GMMedic.LOGGER.info("[GM-Medic] Call resolved ({}): {}", reason, caller);
        } else {
            GMMedic.LOGGER.warn("[GM-Medic] Could not parse caller for {}: {}", reason, msg);
        }
    }

    private static String extractValue(String msg, String key) {
        int idx = msg.indexOf(key);
        if (idx < 0) return "";
        return msg.substring(idx + key.length()).trim();
    }

    private static String getPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return null;
        if (client.player != null) return client.player.getNameForScoreboard();
        if (client.getSession() != null) return client.getSession().getUsername();
        return null;
    }
}
