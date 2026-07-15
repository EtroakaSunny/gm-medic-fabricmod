package de.dorikku.gmmedicmod.render;

import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws an outline box around every player that currently has an open emergency
 * call, so the on-duty medic can spot them in the world. The box is depth-tested
 * (visible only in line of sight) and only rendered for players within the
 * configured range.
 */
public final class CallTargetHighlightRenderer {

    // RGB triples matching the HUD palette (DEATH = red, ECALL = orange, HEAL keyword = green).
    private static final float[] DEATH_RGB = {1.0f, 0.33f, 0.33f};
    private static final float[] ECALL_RGB = {1.0f, 0.66f, 0.0f};
    private static final float[] HEAL_RGB  = {0.33f, 1.0f, 0.33f};
    private static final float BOX_ALPHA = 0.85f;
    private static final float LINE_WIDTH = 2.5f;

    private CallTargetHighlightRenderer() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;
        if (!ApiConnection.getInstance().isFeatureUnlocked()) return;
        if (!EmergencyCallManager.getInstance().isInDuty()) return;
        if (!HudConfig.getInstance().isHighlightEnabled()) return;

        Map<String, EmergencyCall.CallType> targets = collectOpenCallTargets();
        Set<String> healTargets = EmergencyCallManager.getInstance().getKeywordHighlightTargets();
        if (targets.isEmpty() && healTargets.isEmpty()) return;

        double range = HudConfig.getInstance().getHighlightRange();
        double rangeSq = range * range;
        float tickDelta = client.getRenderTickCounter().getTickProgress(true);
        Vec3d cam = client.gameRenderer.getCamera().getCameraPos();

        MatrixStack ms = ctx.matrices();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getLines());

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            if (client.player.squaredDistanceTo(player) > rangeSq) continue;

            // An open call takes colour priority; otherwise a keyword highlight ("heal"/"low") shows green.
            EmergencyCall.CallType type = matchTarget(targets, player);
            float[] rgb;
            if (type != null) {
                rgb = type == EmergencyCall.CallType.DEATH ? DEATH_RGB : ECALL_RGB;
            } else if (playerMatches(healTargets, player)) {
                rgb = HEAL_RGB;
            } else {
                continue;
            }
            Box box = lerpedBox(player, tickDelta);

            // The matrix stack is anchored at the camera, so feed the box in camera-relative space.
            VoxelShape shape = VoxelShapes.cuboidUnchecked(
                    box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
                    box.maxX - cam.x, box.maxY - cam.y, box.maxZ - cam.z);
            int color = ColorHelper.fromFloats(BOX_ALPHA, rgb[0], rgb[1], rgb[2]);
            // MC 1.21.10's VertexRendering.drawOutline has no line-width parameter (the WIP was
            // written against a newer API); the LINE_WIDTH constant is kept for a future upgrade.
            VertexRendering.drawOutline(ms, vc, shape, 0.0, 0.0, 0.0, color);
        }
    }

    /**
     * Caller names (normalized, lowercase) of all open calls mapped to their type.
     * "Open" = not pending, not resolved, not rejected. Pending calls carry the
     * placeholder caller "..." so they never match a real player anyway.
     */
    private static Map<String, EmergencyCall.CallType> collectOpenCallTargets() {
        Map<String, EmergencyCall.CallType> targets = new HashMap<>();
        for (EmergencyCall call : EmergencyCallManager.getInstance().getActiveCalls()) {
            if (call.isPending() || call.isResolved() || call.isRejected()) continue;
            String key = normalizeKey(call.getCallerName());
            if (key != null) targets.put(key, call.getType());
        }
        return targets;
    }

    private static EmergencyCall.CallType matchTarget(Map<String, EmergencyCall.CallType> targets,
                                                       AbstractClientPlayerEntity player) {
        String account = normalizeKey(player.getGameProfile().name());
        if (account != null && targets.containsKey(account)) return targets.get(account);
        String name = normalizeKey(player.getName().getString());
        if (name != null && targets.containsKey(name)) return targets.get(name);
        if (player.getDisplayName() != null) {
            String display = normalizeKey(player.getDisplayName().getString());
            if (display != null && targets.containsKey(display)) return targets.get(display);
        }
        return null;
    }

    /** Like {@link #matchTarget} but for a plain set of normalized name keys (keyword highlights). */
    private static boolean playerMatches(Set<String> keys, AbstractClientPlayerEntity player) {
        if (keys.isEmpty()) return false;
        String account = normalizeKey(player.getGameProfile().name());
        if (account != null && keys.contains(account)) return true;
        String name = normalizeKey(player.getName().getString());
        if (name != null && keys.contains(name)) return true;
        if (player.getDisplayName() != null) {
            String display = normalizeKey(player.getDisplayName().getString());
            if (display != null && keys.contains(display)) return true;
        }
        return false;
    }

    private static String normalizeKey(String name) {
        String normalized = EmergencyCallManager.normalizeCallerName(name);
        return normalized != null ? normalized.toLowerCase() : null;
    }

    private static Box lerpedBox(AbstractClientPlayerEntity player, float tickDelta) {
        Vec3d pos = player.getLerpedPos(tickDelta);
        double halfWidth = player.getWidth() / 2.0;
        double height = player.getHeight();
        return new Box(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, pos.y + height, pos.z + halfWidth
        );
    }
}
