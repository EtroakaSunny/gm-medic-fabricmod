package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.blood.BloodChatNoteHandler;
import de.dorikku.gmmedicmod.config.ReviveReplyConfig;
import de.dorikku.gmmedicmod.manager.AlarmManager;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager.ParsingState;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.network.OutboundMessages;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
    // Direct system confirmation to the reviver, e.g. "... ℹ Du hast thespecial erfolgreich wiederbelebt."
    // Unlike the [FUNK] broadcast above, this only ever appears for your own revive — no
    // reviver-name/isFunk matching needed to know the auto-reply target.
    private static final Pattern SELF_REVIVE_TARGET  = Pattern.compile("Du hast\\s+(.+?)\\s+erfolgreich wiederbelebt");
    private static final Pattern LOGOUT_CALLER       = Pattern.compile("Spieler (.+?) hat sich ausgeloggt");
    private static final Pattern REACHED_CALLER      = Pattern.compile("Ich habe den Notruf von (.+?)\\s+erreicht");
    private static final Pattern CANCEL_DEATH_CALLER = Pattern.compile("Ich kann die Todesmeldung von (.+?)\\s+nicht mehr erledigen");
    private static final Pattern FUNK_SENDER_SIM     = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(.+?)\\s*»");
    private static final Pattern FUNK_SENDER_ANY     = Pattern.compile("[\\])]\\s+(.+?)\\s*»");
    private static final Pattern FUNK_SHIFT_JOIN     = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(\\S+)\\s+Trete\\s+Meine\\s+Schicht\\s+an", Pattern.CASE_INSENSITIVE);
    // Public chat line: "...<PlayerName> » <message>". The player name is the word right before the ».
    private static final Pattern CHAT_SENDER_BODY    = Pattern.compile("(\\w{1,16})\\s*»\\s*(.+)$");
    // Whole-word match so "low" does not fire on "below"/"yellow", "heal" not on "health" and
    // "leben" not on "überleben"/"erleben".
    private static final Pattern HIGHLIGHT_KEYWORD   = Pattern.compile("(?i)\\b(?:heal|heilung|leben|low)\\b");
    // "... legt <player> einen Verband an" — a medic treated the player, so the highlight can go.
    private static final Pattern BANDAGE_TARGET      = Pattern.compile("legt\\s+(.+?)\\s+einen Verband an");
    // Direct system confirmation to the medic who drew the blood: "Du hast das Blut von
    // <player> erfolgreich gespendet." Nobody else sees it, so reporting it to the API
    // server is the only way the other medics learn about the player's 60 min cooldown.
    private static final Pattern BLOOD_DONATED_TARGET = Pattern.compile("Du hast das Blut von\\s+(.+?)\\s+erfolgreich gespendet");
    // Any word containing "blut" (Blutspende, verblutet, Blutgruppe, ...), "spende" (spenden,
    // Spende, gespendet, ...), the community slang "spendi", or "abpumpen"/"abgepumpt" (the
    // separable verb used for having blood drawn). The "spende" branch excludes the bare word
    // "Spender" via a negative lookahead — that alone is a very common, unrelated German word
    // for "dispenser" (Seifenspender, Klopapierspender), unlike its declined/compound forms
    // ("Spenderliste", "Spendern", ...), which stay ambiguous enough to still count.
    private static final Pattern BLOOD_MENTION = Pattern.compile("(?i)\\b\\p{L}*(?:blut|spende(?!r\\b)|spendi|ab(?:ge)?pump)\\p{L}*\\b");
    // Bank alarm, only trusted from the D-Funk: "Der Alarm der <Bank> wurde ausgelöst"
    private static final Pattern DFUNK_ALARM_START   = Pattern.compile("Der Alarm der (.+?) wurde ausgelöst");
    private static final String  DFUNK_ALARM_END     = "Der Bankraub wurde beendet";

    /**
     * Duty state observed while the feature gate was still closed, applied as soon as the API
     * server verifies this client. Only touched from the client thread (chat events and the
     * inbound dispatcher's {@code client.execute} both run there).
     */
    private static Boolean pendingDutyState = null;

    public static void onGameMessage(Component message, boolean overlay) {
        if (overlay) return;
        // Unverified players get nothing from the mod at all — not even duty detection,
        // so isInDuty() can never flip true and everything gated on it stays off too.
        if (!ApiConnection.getInstance().isFeatureUnlocked()) {
            rememberDutyStateWhileLocked(message);
            return;
        }
        try {
            String raw = message.getString();
            GMMedic.LOGGER.debug("[GM-Medic] Raw message: {}", raw);
            processMessage(raw);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error processing message", e);
        }
    }

    /**
     * The game server announces a rejoining medic's duty state ("Ich bin wieder auf dem Server")
     * about a second after the join — normally before the API handshake started in
     * {@code ClientPlayConnectionEvents.JOIN} finishes, so {@link #onGameMessage} would drop that
     * one line and the medic would stay marked off duty for the rest of the session. Remember the
     * last duty signal instead and let {@link #applyPendingDutyState()} replay it after AUTH_OK.
     *
     * <p>Nothing else is buffered: everything else the mod reacts to is either re-synced by the
     * API server on duty start (open calls) or would be stale by the time verification lands.</p>
     */
    private static void rememberDutyStateWhileLocked(Component message) {
        try {
            String msg = sanitize(message.getString());
            Boolean state = detectDutyState(msg, isFunkMessage(msg));
            if (state == null) return;
            pendingDutyState = state;
            GMMedic.LOGGER.info("[GM-Medic] Duty signal ({}) seen before verification — applying after AUTH_OK",
                    state ? "on" : "off");
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error checking message for duty state", e);
        }
    }

    /** Applies the duty state seen before AUTH_OK. Called once the API server has verified us. */
    public static void applyPendingDutyState() {
        Boolean state = pendingDutyState;
        pendingDutyState = null;
        if (state == null || manager.isInDuty() == state) return;
        manager.setInDuty(state);
        GMMedic.LOGGER.info("[GM-Medic] {} duty (pre-verification signal)", state ? "On" : "Off");
    }

    /** Drops a remembered duty signal — on AUTH_FAIL and on leaving the server. */
    public static void clearPendingDutyState() {
        pendingDutyState = null;
    }

    private static void processMessage(String msg) {
        msg = sanitize(msg);

        boolean isFunk = isFunkMessage(msg);

        checkKeywordHighlight(msg, isFunk);
        checkBandageApplied(msg);
        checkBloodDonated(msg);

        Boolean dutyState = detectDutyState(msg, isFunk);
        if (dutyState != null) {
            manager.setInDuty(dutyState);
            GMMedic.LOGGER.info("[GM-Medic] {} duty", dutyState ? "On" : "Off");
            return;
        }

        // Bank alarm — evaluated before the duty gate so the state also updates for
        // off-duty players in case the D-Funk is visible to them.
        if (isDFunkMessage(msg)) {
            Matcher alarm = DFUNK_ALARM_START.matcher(msg);
            if (alarm.find()) {
                String alarmName = alarm.group(1).trim();
                AlarmManager.getInstance().triggerFromChat(alarmName);
                GMMedic.LOGGER.info("[GM-Medic] D-Funk alarm triggered: {}", alarmName);
                return;
            }
            if (msg.contains(DFUNK_ALARM_END)) {
                AlarmManager.getInstance().endFromChat();
                GMMedic.LOGGER.info("[GM-Medic] D-Funk alarm ended (Bankraub beendet)");
                return;
            }
        }

        if (!manager.isInDuty()) return;

        checkBloodMention(msg);

        if (msg.contains("ZENTRALE") && msg.contains("hat seinen Notruf zurückgezogen")) {
            extractAndResolve(WITHDRAW_CALLER, msg, "withdrawn", "Zurückgezogen");
            return;
        }

        if (isFunk && msg.contains("Ich habe") && msg.contains("wiederbelebt")) {
            extractAndResolve(REVIVE_CALLER, msg, "revived", "Wiederbelebt");
            return;
        }

        if (msg.contains("Du hast") && msg.contains("erfolgreich wiederbelebt")) {
            sendReviveReplyIfConfigured(msg);
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
                CallArrivalCountdown.start();
                requestBloodStatusForCaller(call);
            }
        }
    }

    /**
     * Highlights a player for 30s when they write "heal"/"heilung"/"leben"/"low" in normal chat.
     * Emergency-call traffic (FUNK radio, ZENTRALE broadcasts and the data-transmission lines) can
     * contain these words too, so it is explicitly excluded — only genuine "&lt;name&gt; » message"
     * player chat counts.
     */
    private static void checkKeywordHighlight(String msg, boolean isFunk) {
        if (isFunk) return;
        if (msg.contains("ZENTRALE")) return;
        if (msg.contains("BERMITTLUNG")) return;                 // DATENÜBERMITTLUNG header/blocks
        if (manager.getState() == ParsingState.PARSING) return;  // mid-transmission lines

        Matcher m = CHAT_SENDER_BODY.matcher(msg);
        if (!m.find()) return;                                   // not a player chat line
        String body = m.group(2);
        if (!HIGHLIGHT_KEYWORD.matcher(body).find()) return;

        String sender = EmergencyCallManager.normalizeCallerName(m.group(1));
        if (sender == null) return;
        manager.addKeywordHighlight(sender);
        GMMedic.LOGGER.info("[GM-Medic] Heal/low keyword from {} — highlighting 30s", sender);
    }

    /**
     * A medic applying a bandage ("… legt &lt;player&gt; einen Verband an") means the player has
     * been treated — their keyword highlight is removed immediately.
     */
    private static void checkBandageApplied(String msg) {
        Matcher m = BANDAGE_TARGET.matcher(msg);
        if (!m.find()) return;
        String target = EmergencyCallManager.normalizeCallerName(m.group(1));
        if (target == null) return;
        manager.removeKeywordHighlight(target);
        GMMedic.LOGGER.info("[GM-Medic] Bandage applied to {} — keyword highlight removed", target);
    }

    /**
     * Reports the blood donation the game server has just confirmed to this medic
     * ("Du hast das Blut von &lt;player&gt; erfolgreich gespendet."). Only the acting medic
     * sees that line, so this client is the API server's only source for the player's
     * cooldown — from there it reaches every other medic as a {@code BLOOD_SYNC}.
     *
     * <p>Runs before the duty gate on purpose: the single report of this event must not be
     * lost because duty detection missed a state change. The cooldown is also applied
     * locally right away so the feature still works with the API connection down; the
     * server's own reply supersedes that estimate.</p>
     */
    private static void checkBloodDonated(String msg) {
        Matcher m = BLOOD_DONATED_TARGET.matcher(msg);
        if (!m.find()) return;
        String player = EmergencyCallManager.normalizeCallerName(m.group(1));
        if (player == null) return;

        BloodDonationManager.getInstance().applyLocalDraw(player);
        ApiConnection.getInstance().send(OutboundMessages.bloodDrawn(getPlayerName(), player));
        GMMedic.LOGGER.info("[GM-Medic] Blood donated for {} — reported to API", player);
    }

    /**
     * A player mentions "Blut" in their own normal chat or funk line — asks
     * {@link BloodChatNoteHandler} to look up their donation status and post a small
     * local-only confirmation line right after their message. Works for both plain chat
     * ("Name » message") and funk ("[FUNK] (Rang) Name » message" / "Ⓛ [Rang] Name »
     * message"): either way the sender's name is exactly the run of word characters right
     * before the "»", so one pattern covers both without needing to branch on {@code isFunk}.
     *
     * <p>Skipped for the local player's own messages — a medic talking about blood/donations
     * in general (e.g. asking who needs one) shouldn't get a status note about themselves.</p>
     */
    private static void checkBloodMention(String msg) {
        if (msg.contains("ZENTRALE")) return;                    // e.g. "[FUNK] ZENTRALE » ..."
        if (msg.contains("BERMITTLUNG")) return;                 // DATENÜBERMITTLUNG header/blocks
        if (manager.getState() == ParsingState.PARSING) return;  // mid-transmission lines

        Matcher m = CHAT_SENDER_BODY.matcher(msg);
        if (!m.find()) return;
        if (!BLOOD_MENTION.matcher(m.group(2)).find()) return;

        String sender = EmergencyCallManager.normalizeCallerName(m.group(1));
        if (sender == null) return;
        String self = getPlayerName();
        if (self != null && sender.equalsIgnoreCase(self)) return;
        BloodChatNoteHandler.request(sender);
        GMMedic.LOGGER.info("[GM-Medic] {} mentioned Blut — requesting donation status", sender);
    }

    /**
     * Warms the donation-status cache for a freshly finalized call's caller, so a medic sees
     * whether they may donate again as soon as the transmission arrives — before anyone has
     * even aimed a syringe at them. Reuses {@link BloodChatNoteHandler} so the same green/red
     * note (and the same two config toggles) applies here as for a chat mention.
     *
     * <p>Only for a normal Notruf (ECALL) whose reason mentions "Blut" — a Todesmeldung (DEATH)
     * or an ecall for an unrelated reason (e.g. a plain heal call) never triggers this.</p>
     */
    private static void requestBloodStatusForCaller(EmergencyCall call) {
        if (call.getType() != EmergencyCall.CallType.ECALL) return;
        String reason = call.getReason();
        if (reason == null || !BLOOD_MENTION.matcher(reason).find()) return;

        String name = EmergencyCallManager.normalizeCallerName(call.getCallerName());
        if (name == null || name.equals("Unbekannt") || name.equals("...")) return;
        BloodChatNoteHandler.request(name);
    }

    private static String sanitize(String msg) {
        msg = FORMAT_CODE.matcher(msg).replaceAll("");
        return msg.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    /**
     * Recognises the lines that flip the duty state, either as a direct system message or as this
     * player's own FUNK broadcast. Returns {@code null} when the message says nothing about duty.
     */
    private static Boolean detectDutyState(String msg, boolean isFunk) {
        if (msg.contains("Du bist nicht mehr im Dienst") || msg.contains("Du hast den Dienst verlassen")) return false;

        if (!isFunk) return null;

        Matcher shiftJoin = FUNK_SHIFT_JOIN.matcher(msg);
        if (shiftJoin.find()) {
            String sender = shiftJoin.group(1).trim();
            String playerName = getPlayerName();
            if (playerName == null || sender.equalsIgnoreCase(playerName)) return true;
        }

        if (!isOwnMessage(getPlayerName(), extractFunkSender(msg), msg)) return null;

        if (msg.contains("Ich bin wieder auf dem Server")) return true;
        if (msg.contains("Ich bin nicht mehr im Dienst") || msg.contains("Ich bin nun offline")) return false;
        return null;
    }

    private static boolean isFunkMessage(String msg) {
        return msg.contains("[FUNK]");
    }

    /**
     * D-Funk lines carry the Ⓓ channel marker (the real server prefixes funk lines
     * with a circled channel letter, e.g. Ⓛ for the medic funk). "[D-FUNK]" is
     * accepted as legacy/simulation format.
     */
    private static boolean isDFunkMessage(String msg) {
        return msg.contains("Ⓓ") || msg.contains("[D-FUNK]");
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

    /**
     * Sends an automatic public chat reply right after the server's own confirmation to you
     * ("... Du hast X erfolgreich wiederbelebt."). Unlike the old [FUNK]-broadcast heuristic,
     * this message only ever appears for your own revive, so no reviver-name matching is needed.
     */
    private static void sendReviveReplyIfConfigured(String msg) {
        if (!ReviveReplyConfig.getInstance().isEnabled()) return;

        Matcher m = SELF_REVIVE_TARGET.matcher(msg);
        if (!m.find()) return;
        String target = EmergencyCallManager.normalizeCallerName(m.group(1));
        if (target == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) return;
        client.player.connection.sendChat(ReviveReplyConfig.getInstance().buildReply(target));
        GMMedic.LOGGER.info("[GM-Medic] Sent revive auto-reply to {}", target);
    }

    private static String extractValue(String msg, String key) {
        int idx = msg.indexOf(key);
        if (idx < 0) return "";
        return msg.substring(idx + key.length()).trim();
    }

    private static String getPlayerName() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return null;
        if (client.player != null) return client.player.getScoreboardName();
        if (client.getUser() != null) return client.getUser().getName();
        return null;
    }
}
