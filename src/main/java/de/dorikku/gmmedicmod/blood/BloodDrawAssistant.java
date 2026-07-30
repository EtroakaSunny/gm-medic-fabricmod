package de.dorikku.gmmedicmod.blood;

import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.network.OutboundMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * While an on-duty medic holds a {@code Leere Spritze} and looks at another player, asks the
 * API server whether that player may donate blood again and reports the answer above the
 * hotbar. {@link de.dorikku.gmmedicmod.render.BloodTargetHighlightRenderer} draws the same
 * answer onto the player in the world.
 *
 * <p>The player is picked by casting the look vector against the other players' (slightly
 * expanded) bounding boxes, so it follows the crosshair rather than mere proximity. Blocks
 * in between are not checked — at {@value #MAX_TARGET_DISTANCE} blocks a medic looking
 * through a wall at a patient is not worth the extra raycast.</p>
 */
public final class BloodDrawAssistant {

    private static final String SYRINGE_ITEM_NAME = "Leere Spritze";
    /** How far ahead a player is still recognised as the one being looked at. */
    private static final double MAX_TARGET_DISTANCE = 8.0;
    /** Bounding boxes are grown by this much so aiming does not have to be pixel-exact. */
    private static final double TARGET_TOLERANCE = 0.3;
    /** The overlay message fades after a few seconds, so refresh it while aiming. */
    private static final long ACTION_BAR_REFRESH_MS = 1_000L;

    /** Account name of the player currently looked at, or {@code null} — read by the renderer. */
    private static volatile String lookTargetName = null;
    private static String lastActionBarText = null;
    private static long lastActionBarMs = 0L;

    private BloodDrawAssistant() {}

    /** Account name of the player the medic is aiming at with a syringe, or {@code null}. */
    public static String getLookTargetName() {
        return lookTargetName;
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null
                || !ApiConnection.getInstance().isFeatureUnlocked()
                || !EmergencyCallManager.getInstance().isInDuty()
                // Display off: no target detection, no status requests, no message. Donations
                // are still tracked — that runs off the chat line, not this tick.
                || !HudConfig.getInstance().isBloodDisplayEnabled()
                || !isHoldingSyringe(player)) {
            reset();
            return;
        }

        AbstractClientPlayer target = findLookedAtPlayer(client, player);
        if (target == null) {
            reset();
            return;
        }

        // The account name is what the game server names in its donation message, so it is
        // the key both sides agree on.
        String name = target.getGameProfile().name();
        lookTargetName = name;

        BloodDonationManager manager = BloodDonationManager.getInstance();
        if (manager.claimStatusRequest(name)) {
            ApiConnection.getInstance().send(OutboundMessages.bloodStatusRequest(name));
        }
        showStatus(client, name, manager.statusOf(name));
    }

    private static void reset() {
        lookTargetName = null;
        lastActionBarText = null;
        lastActionBarMs = 0L;
    }

    private static boolean isHoldingSyringe(LocalPlayer player) {
        ItemStack held = player.getInventory().getSelectedItem();
        if (held.isEmpty()) return false;
        return stripColorCodes(held.getHoverName().getString()).equalsIgnoreCase(SYRINGE_ITEM_NAME);
    }

    private static AbstractClientPlayer findLookedAtPlayer(Minecraft client, LocalPlayer self) {
        Vec3 start = self.getEyePosition();
        Vec3 end = start.add(self.getViewVector(1.0f).scale(MAX_TARGET_DISTANCE));

        AbstractClientPlayer best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (AbstractClientPlayer other : client.level.players()) {
            if (other == self || other.isSpectator()) continue;
            AABB box = other.getBoundingBox().inflate(TARGET_TOLERANCE);
            Optional<Vec3> hit = box.clip(start, end);
            if (hit.isEmpty()) continue;
            double distanceSq = start.distanceToSqr(hit.get());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = other;
            }
        }
        return best;
    }

    private static void showStatus(Minecraft client, String name, BloodDonationManager.Result result) {
        // Wording follows the game's own term ("gespendet"): the cooldown is a donation
        // interval, so "Spende" reads consistently with the message that starts it.
        String text = switch (result.status()) {
            case READY    -> "✔ " + name + " — Blutspende möglich";
            case COOLDOWN -> "✖ " + name + " — Nächste Spende in " + formatRemaining(result.remainingSeconds());
            case UNKNOWN  -> "… " + name + " — Status wird abgefragt";
        };
        ChatFormatting color = switch (result.status()) {
            case READY    -> ChatFormatting.GREEN;
            case COOLDOWN -> ChatFormatting.RED;
            case UNKNOWN  -> ChatFormatting.GRAY;
        };

        long now = System.currentTimeMillis();
        if (text.equals(lastActionBarText) && now - lastActionBarMs < ACTION_BAR_REFRESH_MS) return;
        lastActionBarText = text;
        lastActionBarMs = now;
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.literal(text).withStyle(color));
        }
    }

    /** {@code m:ss} for a cooldown under an hour, {@code h:mm:ss} otherwise. */
    public static String formatRemaining(int seconds) {
        if (seconds < 0) seconds = 0;
        int hours = seconds / 3600;
        int minutes = seconds / 60 % 60;
        int secs = seconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%d:%02d", minutes, secs);
    }

    /** Removes legacy {@code §x} formatting codes; display names may carry a colour. */
    private static String stripColorCodes(String name) {
        return name == null ? "" : name.replaceAll("§.", "").trim();
    }
}
