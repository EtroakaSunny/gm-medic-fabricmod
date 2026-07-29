package de.dorikku.gmmedicmod.handler;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Purely visual actionbar countdown shown for a few seconds right after a call's full
 * transmission — including its ANNEHMEN/ANRUFEN/MELDEN/ZURÜCKWEISEN buttons — has arrived
 * in chat. Never touches that chat line, so the buttons stay clickable the whole time.
 */
public final class CallArrivalCountdown {

    public static final int DEFAULT_SECONDS = 5;

    private static long endMs = 0L;
    private static int lastShownSecond = -1;

    private CallArrivalCountdown() {}

    public static void start() {
        start(DEFAULT_SECONDS);
    }

    public static void start(int seconds) {
        endMs = System.currentTimeMillis() + seconds * 1000L;
        lastShownSecond = -1;
    }

    public static void tick(MinecraftClient client) {
        if (endMs == 0L || client.player == null) return;

        long remainingMs = endMs - System.currentTimeMillis();
        if (remainingMs <= 0) {
            endMs = 0L;
            return;
        }

        int secondsLeft = (int) Math.ceil(remainingMs / 1000.0);
        if (secondsLeft == lastShownSecond) return;
        lastShownSecond = secondsLeft;

        client.player.sendMessage(Text.literal("⏱ " + secondsLeft).formatted(Formatting.YELLOW), true);
    }
}
