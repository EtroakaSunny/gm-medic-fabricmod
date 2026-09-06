package de.dorikku.gmmedicmod.diagnosis;

import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The nudge a medic gets once a diagnosis has been dragging on.
 *
 * <p>The slide carries every dye on the Leitfaden except at most one, and that one names the
 * illness. So the mod compares two things: which dyes it has actually read out of the menu, and
 * which ones the medic has left unticked. When those agree the medic has it right and nothing
 * is said — including the case where nothing is missing at all and every box is ticked.</p>
 *
 * <p>Because at most one dye can be absent, the mod can tell whether it has seen the whole
 * slide: two or more dyes unaccounted for means pages are still unopened, and the honest hint
 * there is to say so rather than to invent a diagnosis from half a sample.</p>
 *
 * <p>Hints start after {@value #FIRST_HINT_MS} ms and get one step more specific every
 * {@value #HINT_STEP_MS} ms: something is off → how far off → roughly which colour range → the
 * dye and its illness. Even the last stage only names it; the medic still ticks the box and
 * makes the call.</p>
 */
public final class MicroscopeHints {

    /** How long a medic gets before the first nudge. */
    public static final long FIRST_HINT_MS = 60_000L;
    /** How long each further stage takes. */
    public static final long HINT_STEP_MS = 30_000L;
    private static final int MAX_STAGE = 4;

    private static final int COLOR_INFO = 0xFFAAAAAA;

    /** @param text what to show on the panel, {@code color} an opaque ARGB value */
    public record Hint(int stage, String text, int color) {}

    private MicroscopeHints() {}

    /**
     * The hint for a run right now, or {@code null} while it is still too early or the
     * checklist already matches the slide.
     */
    public static Hint forSession(MicroscopeSession session) {
        int stage = stageFor(session);
        if (stage <= 0) return null;

        List<DyeColor> absent = missing(session.getObserved());
        if (absent.size() > 1) {
            // More than one dye unaccounted for is impossible on a whole slide, so the medic
            // has not been through every page — and neither has the mod.
            return new Hint(stage, "Du hast noch nicht das ganze Präparat gesehen.", COLOR_INFO);
        }

        List<DyeColor> open = missing(session.getChecked());
        if (open.equals(absent)) return null;

        return new Hint(stage, textFor(stage, absent, open), colorFor(stage));
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

    private static String textFor(int stage, List<DyeColor> absent, List<DyeColor> open) {
        return switch (stage) {
            case 1 -> "Da stimmt noch etwas nicht.";
            case 2 -> howFarOff(absent, open);
            case 3 -> family(absent, open);
            default -> exact(absent);
        };
    }

    /** Says how wrong the checklist is without saying which dye is involved. */
    private static String howFarOff(List<DyeColor> absent, List<DyeColor> open) {
        if (open.size() > 1) {
            return "Es kann höchstens ein Farbstoff fehlen — du hast noch " + open.size() + " offen.";
        }
        if (open.isEmpty()) {
            return "Du hast alle markiert, aber ein Farbstoff fehlt auf dem Präparat.";
        }
        return "Der Farbstoff, den du offen gelassen hast, ist auf dem Präparat.";
    }

    /** Names a colour family — always one of several dyes, never a giveaway on its own. */
    private static String family(List<DyeColor> absent, List<DyeColor> open) {
        if (!absent.isEmpty()) {
            return "Der fehlende Farbstoff liegt im Bereich " + MicroscopeColors.familyOf(absent.getFirst()) + ".";
        }
        // Nothing is missing, so the medic has wrongly left something open — point at that.
        return "Sieh dir den Bereich " + MicroscopeColors.familyOf(open.getFirst()) + " noch einmal an.";
    }

    private static String exact(List<DyeColor> absent) {
        if (absent.isEmpty()) {
            return "Es fehlt kein Farbstoff — kein Befund.";
        }
        DyeColor color = absent.getFirst();
        return "Es fehlt: " + MicroscopeColors.nameOf(color) + " → " + MicroscopeColors.illnessOf(color);
    }

    private static int colorFor(int stage) {
        return switch (stage) {
            case 1 -> 0xFFFFFF55;
            case 2, 3 -> 0xFFFFAA00;
            default -> 0xFFFF5555;
        };
    }

    /** The Leitfaden's dyes that {@code present} does not hold, in guide order. */
    static List<DyeColor> missing(Set<DyeColor> present) {
        List<DyeColor> result = new ArrayList<>();
        for (DyeColor color : MicroscopeColors.ORDER) {
            if (!present.contains(color)) result.add(color);
        }
        return result;
    }
}
