package de.dorikku.gmmedicmod.handler;

import de.dorikku.gmmedicmod.blood.BloodDrawAssistant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

    public static void tick(Minecraft client) {
        if (endMs == 0L || client.player == null) return;
        // A selected syringe means BloodDrawAssistant owns the actionbar right now — its
        // donation status is more important than this countdown, so stay out of its way.
        if (BloodDrawAssistant.isSyringeSelected(client)) return;

        long remainingMs = endMs - System.currentTimeMillis();
        if (remainingMs <= 0) {
            endMs = 0L;
            return;
        }

        int secondsLeft = (int) Math.ceil(remainingMs / 1000.0);
        if (secondsLeft == lastShownSecond) return;
        lastShownSecond = secondsLeft;

        client.player.sendOverlayMessage(Component.literal("⏱ " + secondsLeft).withStyle(ChatFormatting.YELLOW));
    }
}
