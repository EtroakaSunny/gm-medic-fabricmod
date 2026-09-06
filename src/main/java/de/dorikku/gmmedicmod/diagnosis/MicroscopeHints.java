package de.dorikku.gmmedicmod.diagnosis;

import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The nudge a medic gets once a diagnosis has been dragging on, worked out by comparing the
 * ticks on the checklist against the colours the mod has actually read out of the menu.
 *
 * <p>Hints start after {@value #FIRST_HINT_MS} ms and get one step more specific every
 * {@value #HINT_STEP_MS} ms: something is off → how much is off → roughly where → which
 * colour exactly. The last stage names the colours but still does not tick them, so the
 * medic always makes the call.</p>
 *
 * <p>When the ticks already match what the mod has seen there is nothing wrong and no hint is
 * shown at all — a sample where nothing is missing simply stays quiet.</p>
 *
 * <p>The mod only knows the pages the medic has actually opened, which is exactly the ground
 * the medic could have ticked from, so an unopened page can never produce a "you missed one".
 * A colour ticked ahead of opening the page it sits on does read as one too many until that
 * page is opened — by the time hints start, a medic has normally been through the menu.</p>
 */
public final class MicroscopeHints {

    /** How long a medic gets before the first nudge. */
    public static final long FIRST_HINT_MS = 60_000L;
    /** How long each further stage takes. */
    public static final long HINT_STEP_MS = 30_000L;
    private static final int MAX_STAGE = 4;

    /** @param text what to show on the panel, {@code color} an opaque ARGB value */
    public record Hint(int stage, String text, int color) {}

    private MicroscopeHints() {}

    /**
     * The hint for a run right now, or {@code null} while it is too early, the checklist
     * matches the sample, or the mod has not read a single colour yet.
     */
    public static Hint forSession(MicroscopeSession session) {
        int stage = stageFor(session);
        if (stage <= 0) return null;

        Set<DyeColor> observed = session.getObserved();
        if (observed.isEmpty()) return null;

        List<DyeColor> missed = difference(observed, session.getChecked());
        List<DyeColor> extra = difference(session.getChecked(), observed);
        if (missed.isEmpty() && extra.isEmpty()) return null;

        return new Hint(stage, textFor(stage, missed, extra), colorFor(stage));
    }

    /**
     * The stage a run has reached, {@code 0} while it is still too early. Kept on the session
     * so a stage that has been reached is never taken back.
     */
    private static int stageFor(MicroscopeSession session) {
        long age = session.getAgeMs();
        int stage = age < FIRST_HINT_MS
                ? 0
                : (int) Math.min(MAX_STAGE, 1 + (age - FIRST_HINT_MS) / HINT_STEP_MS);
        if (stage > session.getHintStage()) {
            session.setHintStage(stage);
        }
        return session.getHintStage();
    }

    private static String textFor(int stage, List<DyeColor> missed, List<DyeColor> extra) {
        return switch (stage) {
            case 1 -> "Da stimmt noch etwas nicht.";
            case 2 -> counts(missed, extra);
            case 3 -> family(missed, extra);
            default -> exact(missed, extra);
        };
    }

    private static String counts(List<DyeColor> missed, List<DyeColor> extra) {
        List<String> parts = new ArrayList<>(2);
        if (!missed.isEmpty()) {
            parts.add(missed.size() == 1 ? "Dir fehlt noch 1 Farbe." : "Dir fehlen noch " + missed.size() + " Farben.");
        }
        if (!extra.isEmpty()) {
            parts.add(extra.size() == 1
                    ? "1 Markierung passt nicht zum Präparat."
                    : extra.size() + " Markierungen passen nicht zum Präparat.");
        }
        return String.join(" ", parts);
    }

    /** Names the colour family of one offender — always a family of several, never a giveaway. */
    private static String family(List<DyeColor> missed, List<DyeColor> extra) {
        if (!missed.isEmpty()) {
            return "Im Bereich " + MicroscopeColors.familyOf(missed.getFirst()) + " fehlt dir noch etwas.";
        }
        return "Im Bereich " + MicroscopeColors.familyOf(extra.getFirst()) + " hast du zu viel markiert.";
    }

    private static String exact(List<DyeColor> missed, List<DyeColor> extra) {
        List<String> parts = new ArrayList<>(2);
        if (!missed.isEmpty()) parts.add("Es fehlt: " + names(missed));
        if (!extra.isEmpty()) parts.add("Zu viel markiert: " + names(extra));
        return String.join(" ", parts);
    }

    private static String names(List<DyeColor> colors) {
        List<String> names = new ArrayList<>(colors.size());
        for (DyeColor color : colors) names.add(MicroscopeColors.nameOf(color));
        return String.join(", ", names);
    }

    private static int colorFor(int stage) {
        return switch (stage) {
            case 1 -> 0xFFFFFF55;
            case 2, 3 -> 0xFFFFAA00;
            default -> 0xFFFF5555;
        };
    }

    /** {@code a \ b}, in {@link MicroscopeColors#ORDER} so hints read in spectrum order. */
    private static List<DyeColor> difference(Set<DyeColor> a, Set<DyeColor> b) {
        EnumSet<DyeColor> rest = EnumSet.copyOf(a);
        rest.removeAll(b);
        List<DyeColor> result = new ArrayList<>(rest.size());
        for (DyeColor color : MicroscopeColors.ORDER) {
            if (rest.contains(color)) result.add(color);
        }
        return result;
    }
}
