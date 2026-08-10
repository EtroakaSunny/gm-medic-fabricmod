package de.dorikku.gmmedicmod.blood;

import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.BloodDonationManager.Result;
import de.dorikku.gmmedicmod.manager.BloodDonationManager.Status;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.network.OutboundMessages;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Posts a small local-only chat line confirming whether a player may donate blood again, right
 * after {@link de.dorikku.gmmedicmod.handler.ChatMessageHandler} spots them mentioning "Blut" in
 * chat/funk, or right after an emergency call's caller becomes known.
 *
 * <p>The status is usually not cached yet, so a request goes out to the API server and the
 * announcement waits — ticked rather than event-driven, the same polling style
 * {@link BloodDrawAssistant} uses — for the answer to arrive. A request already answered (e.g.
 * because a medic recently aimed a syringe at that player) announces immediately.</p>
 */
public final class BloodChatNoteHandler {

    /** How long a request may wait for an answer before it's dropped silently. */
    private static final long PENDING_TIMEOUT_MS = 8_000L;

    private record Pending(String displayName, long requestedAtMs) {}

    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private BloodChatNoteHandler() {}

    /** Called once a "Blut" mention or a finalized call's caller has been recognised. */
    public static void request(String displayName) {
        BloodDonationManager manager = BloodDonationManager.getInstance();
        Result result = manager.statusOf(displayName);
        if (result.status() != Status.UNKNOWN) {
            announce(displayName, result);
            return;
        }
        pending.put(key(displayName), new Pending(displayName, System.currentTimeMillis()));
        if (manager.claimStatusRequest(displayName)) {
            ApiConnection.getInstance().send(OutboundMessages.bloodStatusRequest(displayName));
        }
    }

    public static void tick(Minecraft client) {
        if (pending.isEmpty()) return;
        BloodDonationManager manager = BloodDonationManager.getInstance();
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            Pending p = entry.getValue();
            Result result = manager.statusOf(p.displayName());
            if (result.status() != Status.UNKNOWN) {
                announce(p.displayName(), result);
                return true;
            }
            return now - p.requestedAtMs() > PENDING_TIMEOUT_MS;
        });
    }

    /** Wipes pending requests — used on disconnect, where every one of them loses its target. */
    public static void clear() {
        pending.clear();
    }

    private static void announce(String name, Result result) {
        HudConfig cfg = HudConfig.getInstance();
        String text;
        ChatFormatting color;
        if (result.status() == Status.READY) {
            if (!cfg.isBloodChatNoteCanDonateEnabled()) return;
            text = "✔ " + name + " kann jetzt wieder Blut spenden.";
            color = ChatFormatting.GREEN;
        } else {
            if (!cfg.isBloodChatNoteCannotDonateEnabled()) return;
            text = "✖ " + name + " kann noch nicht wieder Blut spenden (noch "
                    + BloodDrawAssistant.formatRemaining(result.remainingSeconds()) + ").";
            color = ChatFormatting.RED;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
