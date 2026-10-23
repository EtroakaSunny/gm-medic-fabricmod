package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * The chat buttons under each transmission block, kept per call so the Notrufe panel can offer
 * them too. The server attaches a click event to every button label of the last block line
 * ({@code ANNEHMEN  ANRUFEN  MELDEN  ZURÜCKWEISEN} for a Notruf, {@code ANNEHMEN} for a
 * Todesmeldung, {@code ANNEHMEN  FREIGEBEN} once one has been accepted and the block is sent
 * again). Those click events are stored unchanged and later replayed through the same vanilla
 * handler a chat click uses, so a click on the panel does exactly what a click in chat does.
 *
 * <p>Keyed by call type and caller rather than by {@link EmergencyCall} instance: a call synced
 * from the API server may be a different object than the one this client parsed, and a block
 * sent again (with FREIGEBEN) is discarded as a duplicate before it ever becomes a call.</p>
 */
public final class CallActions {

    public static final String ACCEPT  = "ANNEHMEN";
    public static final String CALL    = "ANRUFEN";
    public static final String REPORT  = "MELDEN";
    public static final String REJECT  = "ZURÜCKWEISEN";
    public static final String RELEASE = "FREIGEBEN";

    /** Display order on the action screen; anything else the server adds is appended after these. */
    public static final List<String> ORDER = List.of(ACCEPT, RELEASE, CALL, REPORT, REJECT);

    private static final Map<String, Map<String, ClickEvent>> BY_CALL = new HashMap<>();

    private CallActions() {}

    /** True for the transmission's button line, judged by its plain text. */
    public static boolean isButtonLine(String sanitized) {
        return sanitized.contains(ACCEPT);
    }

    /** Every label of the message that carries a click event, upper-cased and stripped of colour codes. */
    public static Map<String, ClickEvent> extract(Component message) {
        Map<String, ClickEvent> found = new LinkedHashMap<>();
        message.visit((Style style, String text) -> {
            ClickEvent event = style.getClickEvent();
            if (event != null) {
                String label = text.replaceAll("§.", "").trim().toUpperCase(Locale.ROOT);
                if (!label.isEmpty()) found.putIfAbsent(label, event);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found;
    }

    /**
     * Stores the buttons for the call the parser is filling right now. Must run before the
     * button line is handed to the parser, which finalizes (and forgets) that call.
     */
    public static void capture(Component message, EmergencyCall pending) {
        if (pending == null) return;
        Map<String, ClickEvent> buttons = extract(message);
        String key = key(pending.getType(), pending.getCallerName());
        if (buttons.isEmpty() || key == null) return;
        BY_CALL.put(key, buttons);
        GMMedic.LOGGER.info("[GM-Medic] Stored chat buttons {} for {}", buttons.keySet(), pending.getCallerName());
    }

    /** The buttons last seen for this call, in the order the server sent them; empty if none. */
    public static Map<String, ClickEvent> get(EmergencyCall call) {
        String key = key(call.getType(), call.getCallerName());
        Map<String, ClickEvent> buttons = key != null ? BY_CALL.get(key) : null;
        return buttons != null ? Collections.unmodifiableMap(buttons) : Map.of();
    }

    /** Forgets buttons of calls that are no longer on the panel. */
    public static void prune() {
        if (BY_CALL.isEmpty()) return;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (EmergencyCall call : EmergencyCallManager.getInstance().getActiveCalls()) {
            String key = key(call.getType(), call.getCallerName());
            if (key != null) live.add(key);
        }
        BY_CALL.keySet().retainAll(live);
    }

    public static void clear() {
        BY_CALL.clear();
    }

    private static String key(EmergencyCall.CallType type, String caller) {
        String name = EmergencyCallManager.normalizeCallerName(caller);
        if (name == null || name.equals("...") || name.equals("Unbekannt")) return null;
        return type + ":" + name.toLowerCase(Locale.ROOT);
    }
}
