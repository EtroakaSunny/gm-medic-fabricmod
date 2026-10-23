package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.GMMedic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Answers the server's reason prompt after ZURÜCKWEISEN was chosen on the Notrufe panel. The
 * server asks "Gib einen Grund an, weshalb du den Notruf zurückweisen willst." and takes the next
 * chat line as the reason, so the reason entered on the panel is only sent once that very prompt
 * has arrived — never blindly, where it could end up in the public chat instead.
 *
 * <p>Without a reason entered on the panel, the chat is opened instead so the medic can type one
 * (or {@code stop}), exactly as after a click on the chat button.</p>
 */
public final class CallRejectPrompt {

    private static final String PROMPT = "weshalb du den Notruf zurückweisen willst";
    /** How long a chosen rejection waits for the server's prompt. */
    private static final long WAIT_MS = 10_000L;

    private static String reason = null;
    private static long armedAtMs = 0L;

    private CallRejectPrompt() {}

    /** Arms the answer for the next prompt; a blank reason means "let me type it". */
    public static void arm(String text) {
        reason = text == null ? "" : text.trim();
        armedAtMs = System.currentTimeMillis();
    }

    /** Called for every chat line; answers the prompt if a rejection from the panel is waiting for it. */
    public static void onMessage(String sanitized) {
        if (reason == null || !sanitized.contains(PROMPT)) return;
        String answer = reason;
        boolean fresh = System.currentTimeMillis() - armedAtMs <= WAIT_MS;
        reason = null;
        if (!fresh) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) return;
        // The prompt arrives while the chat line is still being handled; act on the next tick.
        client.execute(() -> {
            if (client.player == null || client.player.connection == null) return;
            if (answer.isEmpty()) {
                client.gui.setScreen(new ChatScreen("", false));
            } else {
                client.player.connection.sendChat(answer);
                GMMedic.LOGGER.info("[GM-Medic] Answered the rejection prompt with the panel's reason");
            }
        });
    }

    public static void clear() {
        reason = null;
    }
}
