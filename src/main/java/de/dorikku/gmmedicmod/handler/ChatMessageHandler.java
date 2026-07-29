package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.ReviveReplyConfig;
import de.dorikku.gmmedicmod.manager.AlarmManager;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager.ParsingState;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.network.OutboundMessages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    // Direct system confirmation to the reviver, e.g. "... ℹ Du hast thespecial erfolgreich wiederbelebt."
    // Unlike the [FUNK] broadcast above, this only ever appears for your own revive — no
    // reviver-name/isFunk matching needed to know the auto-reply target.
    private static final Pattern SELF_REVIVE_TARGET  = Pattern.compile("Du hast\\s+(.+?)\\s+erfolgreich wiederbelebt");
    private static final Pattern LOGOUT_CALLER       = Pattern.compile("Spieler (.+?) hat sich ausgeloggt");
    private static final Pattern REACHED_CALLER      = Pattern.compile("Ich habe den Notruf von (.+?)\\s+erreicht");
    private static final Pattern CANCEL_DEATH_CALLER = Pattern.compile("Ich kann die Todesmeldung von (.+?)\\s+nicht mehr erledigen");
    private static final Pattern FUNK_SENDER_SIM     = Pattern.compile("\\[FUNK]\\s*\\([^)]*\\)\\s+(.+?)\\s*»");
    private static final Pattern FUNK_SENDER_ANY     = Pattern.compile("[\\])]\\s+(.+?)\\s*»");
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
    // Bank alarm, only trusted from the D-Funk: "Der Alarm der <Bank> wurde ausgelöst"
    private static final Pattern DFUNK_ALARM_START   = Pattern.compile("Der Alarm der (.+?) wurde ausgelöst");
    private static final String  DFUNK_ALARM_END     = "Der Bankraub wurde beendet";
    // "Ⓓ (Polizei) NAME » message" — the police department channel, distinct from the medics'
    // own [FUNK]. The real server marks D-Funk lines with the circled "Ⓓ" letter; "[D-FUNK]"
    // is accepted too as a legacy/simulation format (see isDFunkMessage below).
    // Never sent to the server: see acceptVerbalCall.
    private static final Pattern POLICE_DFUNK        = Pattern.compile("(?:Ⓓ|\\[D-FUNK])\\s*\\(Polizei\\)\\s+(.+?)\\s*»\\s*(.+)$");

    /** Officer (normalized, lowercase) -> last time the "entgegennehmen" hint was posted, to avoid re-spamming it. */
    private static final Map<String, Long> lastVerbalOfferMs = new ConcurrentHashMap<>();
    private static final long VERBAL_OFFER_COOLDOWN_MS = 60_000L;

    public static void onGameMessage(Text message, boolean overlay) {
        if (overlay) return;
        // Unverified players get nothing from the mod at all — not even duty detection,
        // so isInDuty() can never flip true and everything gated on it stays off too.
        if (!ApiConnection.getInstance().isFeatureUnlocked()) return;
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

        checkKeywordHighlight(msg, isFunk);
        checkBandageApplied(msg);
        checkBloodDonated(msg);

        if (msg.contains("Du bist nun im Dienst") || msg.contains("Du bist jetzt im Dienst")) {
            manager.setInDuty(true);
            GMMedic.LOGGER.info("[GM-Medic] On duty (direct)");
            return;
        }

        if (msg.contains("Du bist nicht mehr im Dienst") || msg.contains("Du hast den Dienst verlassen")) {
            manager.setInDuty(false);
            GMMedic.LOGGER.info("[GM-Medic] Off duty (direct)");
            return;
        }

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

        checkPoliceVerbalCall(msg);

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
        resolveVerbalCallIfPresent(target);
        GMMedic.LOGGER.info("[GM-Medic] Bandage applied to {} — keyword highlight removed", target);
    }

    /**
     * A local-only verbal call (see {@link #acceptVerbalCall}) stops the moment its cop is
     * bandaged — resolved directly on the instance, never through {@link EmergencyCallManager#resolveCall},
     * so no CALL_RESOLVED is ever sent. The HUD sweeps resolved calls a few seconds later on its own.
     */
    private static void resolveVerbalCallIfPresent(String target) {
        EmergencyCall call = manager.findActiveLocalOnlyCall(target);
        if (call == null) return;
        call.setResolved("Geheilt");
        GMMedic.LOGGER.info("[GM-Medic] Local verbal call for {} resolved (bandaged) — not synced", target);
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
     * Detects a police officer sounding like they need medical help on their own [D-FUNK]
     * channel and offers a clickable chat hint to accept it as a verbal (mündlich) call —
     * entirely client-side, see {@link #acceptVerbalCall}.
     */
    private static void checkPoliceVerbalCall(String msg) {
        Matcher m = POLICE_DFUNK.matcher(msg);
        if (!m.find()) return;
        String officer = EmergencyCallManager.normalizeCallerName(m.group(1));
        String body = m.group(2);
        if (officer == null || !HIGHLIGHT_KEYWORD.matcher(body).find()) return;
        if (manager.findActiveLocalOnlyCall(officer) != null) return;

        String key = officer.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = lastVerbalOfferMs.get(key);
        if (last != null && now - last < VERBAL_OFFER_COOLDOWN_MS) return;
        lastVerbalOfferMs.put(key, now);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        MutableText hint = Text.literal("[Mündlicher Notruf entgegennehmen]")
                .formatted(Formatting.GREEN, Formatting.BOLD)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/gmverbal " + officer))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(
                                "Sendet \"/d Unterwegs!\" und legt einen lokalen Notruf an (kein Server-Sync)"))));

        Text line = Text.literal("[GM-Medic] ").formatted(Formatting.GRAY)
                .append(Text.literal(officer + " klingt im D-Funk nach Heilbedarf. ").formatted(Formatting.WHITE))
                .append(hint);
        client.player.sendMessage(line, false);
        GMMedic.LOGGER.info("[GM-Medic] Offered verbal call accept for {}", officer);
    }

    /**
     * Runs when the "[Mündlicher Notruf entgegennehmen]" hint is clicked (via the {@code /gmverbal}
     * client command). Sends the "/d Unterwegs!" department reply and adds a local-only
     * {@link EmergencyCall} — added via {@link EmergencyCallManager#addCall}, assigned directly on
     * the instance, never through {@code finalizeCall}/{@code assignMedic}, so nothing here ever
     * fires a {@code CallEventListener} event or reaches the API server.
     */
    public static void acceptVerbalCall(String officerRaw) {
        String officer = EmergencyCallManager.normalizeCallerName(officerRaw);
        if (officer == null || manager.findActiveLocalOnlyCall(officer) != null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.networkHandler == null) return;

        client.player.networkHandler.sendChatCommand("d Unterwegs!");

        EmergencyCall call = new EmergencyCall(officer, "Mündlicher Notruf (Polizei)", 0, 0, 0, null, EmergencyCall.CallType.ECALL);
        call.clearLocation();
        call.setLocalOnly(true);
        call.setAssignedMedic(getPlayerName());
        manager.addCall(call);
        GMMedic.LOGGER.info("[GM-Medic] Accepted verbal Notruf from {} (local only, not synced)", officer);
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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.networkHandler == null) return;
        client.player.networkHandler.sendChatMessage(ReviveReplyConfig.getInstance().buildReply(target));
        GMMedic.LOGGER.info("[GM-Medic] Sent revive auto-reply to {}", target);
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
