package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager.ParsingState;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Keeps the transmission blocks out of the chat while the Notrufe panel is clickable (see
 * {@link HudConfig#isHideCallChatEnabled()}). Hiding only affects what is displayed: every line
 * still goes through the parser first, so the call reaches the panel and the API server as before.
 *
 * <p>Hidden lines are kept until their transmission is complete. If it collided with another one
 * — a call ends up entangled ("⚠ Fehler") or the parser timed out — they are put back into the
 * chat in their original order, because the panel's data can't be trusted then.</p>
 */
public final class CallChatFilter {

    /** Timestamp prefix some chat setups put in front of every line ("┃ 12:34:56 »", "12:34:56 |"). */
    private static final Pattern TIME_PREFIX = Pattern.compile("^\\s*(?:┃\\s*)?\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[»|]\\s?");
    private static final Pattern FIELD_LINE = Pattern.compile(
            "^\\s*-\\s+(?:Notruf von|Betroffener|Von|Erstellt|Grund|Todesursache|Verbleibende Zeit|Distanz|Ortung|Ort|Auf dem Weg):");
    /** Hidden lines older than this are dropped; a transmission never takes nearly that long. */
    private static final long KEEP_MS = 30_000L;
    /** A transmission that is no longer being parsed after this long is judged in {@link #tick}. */
    private static final long SETTLE_MS = 1_000L;

    private record HiddenLine(Component message, long atMs) {}

    private static final List<HiddenLine> hidden = new ArrayList<>();
    /** Entangled calls already judged, so an old collision doesn't reveal every later block too. */
    private static final Set<EmergencyCall> judgedEntangled = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean revealing = false;

    private CallChatFilter() {}

    public static boolean isActive() {
        HudConfig cfg = HudConfig.getInstance();
        return cfg.isClickableCallsEnabled() && cfg.isHideCallChatEnabled()
                && EmergencyCallManager.getInstance().isInDuty();
    }

    /**
     * True when the line belongs to a transmission: the ZENTRALE announcement, the block header,
     * or — while a block is being parsed — one of its field lines, the empty line or the buttons.
     * Must be asked before the parser sees the line, since the parser changes the state.
     */
    public static boolean isTransmissionLine(String sanitized, boolean parsingBefore) {
        String line = TIME_PREFIX.matcher(sanitized).replaceFirst("");
        if (line.contains("ZENTRALE") && line.contains("ich schicke euch die Daten")) return true;
        if (line.contains("BERMITTLUNG VON ZENTRALE")) return true;
        if (!parsingBefore) return false;
        return line.isBlank() || FIELD_LINE.matcher(line).find() || CallActions.isButtonLine(line);
    }

    /**
     * Called after the parser has handled the line. Returns whether the chat should show it.
     */
    public static boolean afterParse(Component message, boolean transmissionLine) {
        if (!isActive()) {
            if (!hidden.isEmpty()) reveal("filter turned off");
            return true;
        }
        boolean show = true;
        if (transmissionLine) {
            hidden.add(new HiddenLine(message, System.currentTimeMillis()));
            show = false;
        }
        judgeIfComplete();
        return show;
    }

    /** Catches transmissions that ended without a button line (timeout) and drops stale lines. */
    public static void tick(Minecraft client) {
        if (hidden.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!isActive()) {
            reveal("filter turned off");
            return;
        }
        hidden.removeIf(line -> now - line.atMs() > KEEP_MS);
        if (!hidden.isEmpty() && now - hidden.get(hidden.size() - 1).atMs() > SETTLE_MS) {
            judgeIfComplete();
        }
    }

    public static void clear() {
        hidden.clear();
        judgedEntangled.clear();
    }

    private static void judgeIfComplete() {
        EmergencyCallManager mgr = EmergencyCallManager.getInstance();
        if (hidden.isEmpty() || mgr.getState() != ParsingState.IDLE) return;

        long since = hidden.get(0).atMs();
        boolean collided = false;
        for (EmergencyCall call : mgr.getActiveCalls()) {
            if (call.isEntangled() && judgedEntangled.add(call)) collided = true;
            if (call.isResolved() && call.getResolvedAtMs() >= since
                    && ("Zeitüberschreitung".equals(call.getResolvedReason()) || "Fehler".equals(call.getResolvedReason()))) {
                collided = true;
            }
        }
        Set<EmergencyCall> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(mgr.getActiveCalls());
        judgedEntangled.retainAll(live);

        if (collided) {
            reveal("transmissions collided");
        } else {
            hidden.clear();
        }
    }

    /**
     * True while {@link #reveal} puts lines back. {@code sendSystemMessage} runs them through the
     * chat events again, so {@link ChatMessageHandler} must let them pass untouched.
     */
    public static boolean isRevealing() {
        return revealing;
    }

    /** Puts the hidden lines back into the chat. Local display only — nothing is re-parsed or sent. */
    private static void reveal(String why) {
        // Copied and cleared first: re-entering the chat events while iterating {@code hidden}
        // used to add to it mid-loop and crash with a ConcurrentModificationException.
        List<HiddenLine> lines = new ArrayList<>(hidden);
        hidden.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            revealing = true;
            try {
                for (HiddenLine line : lines) client.player.sendSystemMessage(line.message());
            } finally {
                revealing = false;
            }
        }
        GMMedic.LOGGER.info("[GM-Medic] Showing {} hidden transmission line(s): {}", lines.size(), why);
    }
}
