package de.dorikku.gmmedicmod.manager;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of who may donate blood again and when.
 *
 * <p>The API server is authoritative: entries only ever come from it, either as the
 * answer to a {@code BLOOD_STATUS_REQUEST} for one player, as a {@code BLOOD_SYNC}
 * when any medic reports a donation, or as the {@code BLOOD_LIST} bulk sync on
 * connect/duty start. Nothing is derived locally, so every medic sees the same
 * cooldowns even though the game server tells only the acting medic about a donation.</p>
 *
 * <p>Pure state — sending is done by the callers ({@code ChatMessageHandler} reports a
 * donation, {@code BloodDrawAssistant} asks for a status).</p>
 */
public final class BloodDonationManager {

    public enum Status {
        /** Never asked, or the cached answer went stale — worth a fresh request. */
        UNKNOWN,
        /** May donate right now. */
        READY,
        /** Donated recently; see {@link Result#remainingSeconds()}. */
        COOLDOWN
    }

    public record Result(Status status, int remainingSeconds) {}

    private static final Result UNKNOWN_RESULT = new Result(Status.UNKNOWN, 0);

    /**
     * The game server's donation interval, used only to estimate a cooldown locally when the
     * API server can't be reached (see {@link #applyLocalDraw}); its answers always win.
     */
    private static final long DEFAULT_COOLDOWN_MS = 60L * 60L * 1000L;

    /**
     * A ready-state nobody has looked at for this long is forgotten entirely, so the cache
     * doesn't accumulate an entry for every player ever aimed at. Safely longer than
     * {@link #READY_REFRESH_MS}: a player still being watched gets refreshed — and their
     * entry renewed — well before this runs out, so this never causes a visible reset.
     */
    private static final long READY_RETENTION_MS = 5L * 60L * 1000L;

    /** Don't re-ask about the same player more often than this while looking at them. */
    private static final long REQUEST_RETRY_MS = 5_000L;
    /**
     * A "may donate" answer is quietly re-checked once it is this old. Donations are
     * broadcast, so a cached ready-state stays correct on its own — this only guards against
     * a missed broadcast, at the cost of one small request per minute per player looked at.
     * The cached answer keeps being displayed meanwhile, so the refresh never shows up as a
     * flicker back to {@link Status#UNKNOWN}.
     */
    private static final long READY_REFRESH_MS = 60_000L;

    /**
     * @param readyAtMs epoch ms at which the player may donate again; {@code 0} when the
     *                  server reported them as ready without a running cooldown
     * @param knownAtMs when this client learned the above
     */
    private record Entry(long readyAtMs, long knownAtMs) {}

    private static final BloodDonationManager INSTANCE = new BloodDonationManager();

    public static BloodDonationManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** Player key -> when a status request last went out, so ticks don't spam the socket. */
    private final Map<String, Long> requestedAtMs = new ConcurrentHashMap<>();

    private BloodDonationManager() {}

    /** Applies a {@code BLOOD_STATUS} answer. {@code readyAtMs <= 0} means "may donate now". */
    public void applyStatus(String playerName, boolean canDonate, long readyAtMs) {
        String key = key(playerName);
        if (key == null) return;
        entries.put(key, new Entry(canDonate ? 0L : readyAtMs, System.currentTimeMillis()));
        requestedAtMs.remove(key);
    }

    /** Applies a reported donation ({@code BLOOD_SYNC} / {@code BLOOD_LIST}). */
    public void applyDraw(String playerName, long readyAtMs) {
        String key = key(playerName);
        if (key == null) return;
        entries.put(key, new Entry(readyAtMs, System.currentTimeMillis()));
        requestedAtMs.remove(key);
    }

    /**
     * Applies the cooldown for a donation this client just witnessed in chat, estimated with
     * {@link #DEFAULT_COOLDOWN_MS}, so the status is right even with the API connection down.
     * The server's own {@code BLOOD_SYNC} for the same donation replaces it with the
     * authoritative time as soon as it arrives.
     */
    public void applyLocalDraw(String playerName) {
        applyDraw(playerName, System.currentTimeMillis() + DEFAULT_COOLDOWN_MS);
    }

    /** Wipes the cache — used on disconnect, where every cached answer loses its source. */
    public void clear() {
        entries.clear();
        requestedAtMs.clear();
    }

    public Result statusOf(String playerName) {
        String key = key(playerName);
        if (key == null) return UNKNOWN_RESULT;
        Entry entry = entries.get(key);
        if (entry == null) return UNKNOWN_RESULT;

        long now = System.currentTimeMillis();
        if (entry.readyAtMs() > now) {
            long remainingMs = entry.readyAtMs() - now;
            return new Result(Status.COOLDOWN, (int) ((remainingMs + 999L) / 1000L));
        }
        // Ready — either reported so, or the cooldown has since run out. Once it has been
        // sitting unwatched long enough, drop it rather than cache it for the whole session.
        if (now - Math.max(entry.knownAtMs(), entry.readyAtMs()) > READY_RETENTION_MS) {
            entries.remove(key, entry);
            return UNKNOWN_RESULT;
        }
        return new Result(Status.READY, 0);
    }

    /**
     * True when the caller should send a {@code BLOOD_STATUS_REQUEST} for this player now,
     * claiming the throttle slot in the same step so repeated ticks send only one request.
     *
     * <p>Fires for a player nothing is known about, and to quietly refresh a ready-state
     * older than {@link #READY_REFRESH_MS} — a running cooldown needs no refresh, its end is
     * already known.</p>
     */
    public boolean claimStatusRequest(String playerName) {
        String key = key(playerName);
        if (key == null) return false;

        long now = System.currentTimeMillis();
        Entry entry = entries.get(key);
        if (entry != null) {
            if (entry.readyAtMs() > now) return false;  // on cooldown, nothing to ask
            if (now - Math.max(entry.knownAtMs(), entry.readyAtMs()) <= READY_REFRESH_MS) return false;
        }

        Long last = requestedAtMs.get(key);
        if (last != null && now - last < REQUEST_RETRY_MS) return false;
        requestedAtMs.put(key, now);
        return true;
    }

    private static String key(String playerName) {
        String normalized = EmergencyCallManager.normalizeCallerName(playerName);
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : null;
    }
}
