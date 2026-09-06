package de.dorikku.gmmedicmod.diagnosis;

import net.minecraft.world.item.DyeColor;

import java.util.EnumSet;
import java.util.Set;

/**
 * One patient's run through the microscope: which colours the medic has ticked off, which ones
 * the mod has actually seen in the menu, and when the run started.
 *
 * <p>A run deliberately outlives the screen — the menu is paged, so it closes and reopens as a
 * fresh screen every time the medic flips a page, and the ticks have to survive that. It does
 * not outlive {@link MicroscopeDiagnosisManager#SESSION_TTL_MS} though: a sample gets re-taken
 * after a while, and a stale checklist for the same patient would then be wrong.</p>
 */
public final class MicroscopeSession {

    private final long startedAtMs = System.currentTimeMillis();
    /** What the medic has ticked. The whole point of the panel — never written to by the mod. */
    private final EnumSet<DyeColor> checked = EnumSet.noneOf(DyeColor.class);
    /**
     * What the mod has read out of the menu itself, accumulated over every page the medic has
     * opened during this run. Only used to work out the hints; the medic still has to tick
     * every box by hand.
     */
    private final EnumSet<DyeColor> observed = EnumSet.noneOf(DyeColor.class);
    /** Which hint stage has been reached, 0 = none yet. See {@link MicroscopeHints}. */
    private int hintStage = 0;

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public long getAgeMs() {
        return System.currentTimeMillis() - startedAtMs;
    }

    public boolean isChecked(DyeColor color) {
        return checked.contains(color);
    }

    public void toggle(DyeColor color) {
        if (!checked.add(color)) {
            checked.remove(color);
        }
    }

    /**
     * Ticks a colour without ever un-ticking it. Used by the click-the-sample shortcut, where
     * clicking the same dye on a second page must not undo the tick the first one made.
     */
    public void check(DyeColor color) {
        checked.add(color);
    }

    public int checkedCount() {
        return checked.size();
    }

    public Set<DyeColor> getChecked() {
        return checked;
    }

    public Set<DyeColor> getObserved() {
        return observed;
    }

    public void observe(DyeColor color) {
        observed.add(color);
    }

    public int getHintStage() {
        return hintStage;
    }

    public void setHintStage(int stage) {
        hintStage = stage;
    }
}
