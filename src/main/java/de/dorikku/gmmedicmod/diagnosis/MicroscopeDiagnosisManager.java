package de.dorikku.gmmedicmod.diagnosis;

import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import net.minecraft.world.item.DyeColor;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one {@link MicroscopeSession} per patient, so a medic's ticks survive the menu being
 * closed and reopened while paging through the sample — but no longer than they should.
 *
 * <p>A session is dropped again</p>
 * <ul>
 *   <li>{@value #SESSION_TTL_MS} ms after it started, because a sample that old has usually
 *       been re-taken and the old checklist would then describe the wrong slide;</li>
 *   <li>when the medic goes off duty, since the next shift starts over;</li>
 *   <li>on disconnect, for the same reason.</li>
 * </ul>
 *
 * <p>Purely client-side state — nothing here is sent to or read from the API server.</p>
 */
public final class MicroscopeDiagnosisManager {

    /** How long a run is kept before it is treated as a stale slide. */
    public static final long SESSION_TTL_MS = 5L * 60L * 1000L;

    private static final MicroscopeDiagnosisManager INSTANCE = new MicroscopeDiagnosisManager();

    public static MicroscopeDiagnosisManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, MicroscopeSession> sessions = new ConcurrentHashMap<>();
    /** Last duty state seen by {@link #tick()}, so the off-duty edge can be detected here. */
    private boolean wasInDuty = false;

    private MicroscopeDiagnosisManager() {}

    /**
     * The running session for a patient, started on first use. Returns a fresh one once the
     * previous run has aged past {@link #SESSION_TTL_MS}.
     */
    public MicroscopeSession session(String patient) {
        String key = key(patient);
        MicroscopeSession session = sessions.get(key);
        if (session != null && session.getAgeMs() > SESSION_TTL_MS) {
            sessions.remove(key, session);
            session = null;
        }
        return session != null ? session : sessions.computeIfAbsent(key, k -> new MicroscopeSession());
    }

    /** Throws away a patient's run so the next look starts from a blank checklist and timer. */
    public void reset(String patient) {
        sessions.remove(key(patient));
    }

    public void clear() {
        sessions.clear();
    }

    /**
     * Drops expired runs and, on the falling edge of duty, everything. Watching the duty state
     * here rather than hooking the chat handler keeps this feature self-contained; the panel is
     * only ever drawn from a screen, so noticing a shift end one tick late changes nothing.
     */
    public void tick() {
        boolean inDuty = EmergencyCallManager.getInstance().isInDuty();
        if (wasInDuty && !inDuty) {
            clear();
        }
        wasInDuty = inDuty;

        if (sessions.isEmpty()) return;
        Iterator<Map.Entry<String, MicroscopeSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().getAgeMs() > SESSION_TTL_MS) {
                it.remove();
            }
        }
    }

    /** Records a colour the mod has read out of the open menu for this patient. */
    public void observe(String patient, DyeColor color) {
        session(patient).observe(color);
    }

    private static String key(String patient) {
        return patient == null ? "" : patient.toLowerCase(Locale.ROOT).trim();
    }
}
