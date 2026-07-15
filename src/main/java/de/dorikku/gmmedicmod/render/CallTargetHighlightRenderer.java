package de.dorikku.gmmedicmod.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
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

    public static void render(LevelRenderContext ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) return;
        if (!ApiConnection.getInstance().isFeatureUnlocked()) return;
        if (!EmergencyCallManager.getInstance().isInDuty()) return;
        if (!HudConfig.getInstance().isHighlightEnabled()) return;

        Map<String, EmergencyCall.CallType> targets = collectOpenCallTargets();
        Set<String> healTargets = EmergencyCallManager.getInstance().getKeywordHighlightTargets();
        if (targets.isEmpty() && healTargets.isEmpty()) return;

        double range = HudConfig.getInstance().getHighlightRange();
        double rangeSq = range * range;
        float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 cam = client.gameRenderer.mainCamera().position();

        List<BoxSpec> boxes = new ArrayList<>();
        for (AbstractClientPlayer player : client.level.players()) {
            if (player == client.player) continue;
            if (client.player.distanceToSqr(player) > rangeSq) continue;

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
            AABB box = lerpedBox(player, tickDelta);
            int color = ARGB.colorFromFloat(BOX_ALPHA, rgb[0], rgb[1], rgb[2]);

            // Camera-relative, since the pose passed to the geometry callback is anchored at the camera.
            boxes.add(new BoxSpec(
                    box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
                    box.maxX - cam.x, box.maxY - cam.y, box.maxZ - cam.z,
                    color));
        }
        if (boxes.isEmpty()) return;

        ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.lines(), (pose, vc) -> {
            for (BoxSpec b : boxes) {
                renderBoxOutline(vc, pose, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, b.color);
            }
        });
    }

    private record BoxSpec(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int color) {}

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
                                                       AbstractClientPlayer player) {
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
    private static boolean playerMatches(Set<String> keys, AbstractClientPlayer player) {
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

    private static AABB lerpedBox(AbstractClientPlayer player, float tickDelta) {
        Vec3 pos = player.getPosition(tickDelta);
        double halfWidth = player.getBbWidth() / 2.0;
        double height = player.getBbHeight();
        return new AABB(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, pos.y + height, pos.z + halfWidth
        );
    }

    /** Draws the 12 edges of an axis-aligned box (26.2 dropped the vanilla ShapeRenderer debug helper). */
    private static void renderBoxOutline(VertexConsumer vc, PoseStack.Pose pose,
                                          double minX, double minY, double minZ,
                                          double maxX, double maxY, double maxZ,
                                          int color) {
        // bottom
        line(vc, pose, minX, minY, minZ, maxX, minY, minZ, color);
        line(vc, pose, maxX, minY, minZ, maxX, minY, maxZ, color);
        line(vc, pose, maxX, minY, maxZ, minX, minY, maxZ, color);
        line(vc, pose, minX, minY, maxZ, minX, minY, minZ, color);
        // top
        line(vc, pose, minX, maxY, minZ, maxX, maxY, minZ, color);
        line(vc, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color);
        line(vc, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        line(vc, pose, minX, maxY, maxZ, minX, maxY, minZ, color);
        // verticals
        line(vc, pose, minX, minY, minZ, minX, maxY, minZ, color);
        line(vc, pose, maxX, minY, minZ, maxX, maxY, minZ, color);
        line(vc, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color);
        line(vc, pose, minX, minY, maxZ, minX, maxY, maxZ, color);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                              double x1, double y1, double z1,
                              double x2, double y2, double z2,
                              int color) {
        float nx = (float) (x2 - x1), ny = (float) (y2 - y1), nz = (float) (z2 - z1);
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
    }
}
