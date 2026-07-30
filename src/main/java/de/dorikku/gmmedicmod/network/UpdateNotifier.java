package de.dorikku.gmmedicmod.network;

import de.dorikku.gmmedicmod.GMMedic;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Holds back the "new version available" chat line until {@value #JOIN_DELAY_MS} ms after the
 * player joined, so it doesn't land in the middle of the server's own join spam where nobody
 * would read it.
 *
 * <p>The notice arrives from the API server right after AUTH (which happens on join), so it is
 * parked here and posted by the client tick once the delay has passed. Waiting on a tick also
 * means the player entity is guaranteed to exist by then — no need to guess whether AUTH beat
 * the join.</p>
 *
 * <p>Announced once per version per game session: the server repeats the notice on every
 * re-AUTH, and the reconnect backoff would otherwise turn that into chat spam.</p>
 */
public final class UpdateNotifier {

    private static final long JOIN_DELAY_MS = 20_000L;

    private static long joinedAtMs = 0L;
    private static long queuedAtMs = 0L;
    private static String pendingVersion = null;
    private static Component pendingLine = null;
    private static String announcedVersion = null;

    private UpdateNotifier() {}

    /** Starts the delay window. Called from the client's join event. */
    public static void onJoin() {
        joinedAtMs = System.currentTimeMillis();
    }

    /**
     * Clears anything still waiting when the player leaves, so a notice can't surface after a
     * disconnect. {@code announcedVersion} deliberately survives: rejoining in the same game
     * session should not repeat a notice the medic has already seen.
     */
    public static void onDisconnect() {
        joinedAtMs = 0L;
        queuedAtMs = 0L;
        pendingVersion = null;
        pendingLine = null;
    }

    /** Parks a notice for display. Ignored if this version was already announced. */
    public static void queue(String version, Component line) {
        if (version == null || version.equals(announcedVersion)) return;
        pendingVersion = version;
        pendingLine = line;
        queuedAtMs = System.currentTimeMillis();
    }

    public static void tick(Minecraft client) {
        if (pendingLine == null || client.player == null) return;
        // Checked here rather than on arrival, so a notice that came in while the setting was
        // off is still delivered if the medic turns it back on during the session.
        if (!ApiConfig.getInstance().isUpdateNoticeEnabled()) return;

        // Anchor on the join; fall back to arrival time if no join was ever recorded, so a
        // notice can't be stranded forever.
        long anchor = joinedAtMs > 0L ? joinedAtMs : queuedAtMs;
        if (System.currentTimeMillis() - anchor < JOIN_DELAY_MS) return;

        client.player.sendSystemMessage(pendingLine);
        announcedVersion = pendingVersion;
        GMMedic.LOGGER.info("[ApiConnection] Announced update {} ({}s after join)",
                pendingVersion, JOIN_DELAY_MS / 1000L);
        pendingVersion = null;
        pendingLine = null;
        queuedAtMs = 0L;
    }
}
