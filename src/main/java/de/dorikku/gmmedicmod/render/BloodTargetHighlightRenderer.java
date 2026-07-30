package de.dorikku.gmmedicmod.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.dorikku.gmmedicmod.blood.BloodDrawAssistant;
import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Marks the player an on-duty medic is aiming at with a {@code Leere Spritze} (see
 * {@link BloodDrawAssistant}) with their blood-donation status: an outline box in green
 * (may donate), red (still on cooldown) or grey (answer pending), plus a floating label
 * above the head carrying the remaining time.
 *
 * <p>Deliberately separate from {@link CallTargetHighlightRenderer}: that one marks every
 * player with an open call at once and only ever draws a box, this one marks the single
 * player being aimed at and adds world text. Both honour the same
 * {@link HudConfig#isHighlightEnabled()} toggle.</p>
 */
public final class BloodTargetHighlightRenderer {

    // Box colours mirror the HUD palette (green = ok, red = blocked, grey = unknown).
    private static final float[] READY_RGB    = {0.33f, 1.0f, 0.33f};
    private static final float[] COOLDOWN_RGB = {1.0f, 0.33f, 0.33f};
    private static final float[] UNKNOWN_RGB  = {0.67f, 0.67f, 0.67f};

    private static final float BOX_ALPHA = 0.9f;
    private static final float LINE_WIDTH = 3.0f;
    /** How far above the head the label floats. */
    private static final double LABEL_OFFSET_Y = 0.7;
    /** {@code submitNameTag} lifts the label by this much on its own; keep the total at the offset above. */
    private static final double NAME_TAG_BUILTIN_LIFT = 0.5;

    private BloodTargetHighlightRenderer() {}

    public static void render(LevelRenderContext ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) return;
        if (!ApiConnection.getInstance().isFeatureUnlocked()) return;
        if (!EmergencyCallManager.getInstance().isInDuty()) return;
        if (!HudConfig.getInstance().isBloodDisplayEnabled()) return;
        if (!HudConfig.getInstance().isHighlightEnabled()) return;

        String targetName = BloodDrawAssistant.getLookTargetName();
        if (targetName == null) return;
        AbstractClientPlayer target = findPlayer(client, targetName);
        if (target == null) return;

        BloodDonationManager.Result result = BloodDonationManager.getInstance().statusOf(targetName);
        float[] rgb = switch (result.status()) {
            case READY    -> READY_RGB;
            case COOLDOWN -> COOLDOWN_RGB;
            case UNKNOWN  -> UNKNOWN_RGB;
        };
        // Kept short — this floats in the world — but same "Spende" wording as the actionbar.
        String label = switch (result.status()) {
            case READY    -> "Spende möglich";
            case COOLDOWN -> "Noch " + BloodDrawAssistant.formatRemaining(result.remainingSeconds());
            case UNKNOWN  -> "Status …";
        };
        ChatFormatting labelColor = switch (result.status()) {
            case READY    -> ChatFormatting.GREEN;
            case COOLDOWN -> ChatFormatting.RED;
            case UNKNOWN  -> ChatFormatting.GRAY;
        };

        float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 cam = client.gameRenderer.getMainCamera().position();
        Vec3 pos = target.getPosition(tickDelta);

        drawBox(ctx, target, pos, cam, rgb);
        drawLabel(ctx, target, pos, cam, Component.literal(label).withStyle(labelColor));
    }

    /** Camera-relative, since the pose passed to the geometry callback is anchored at the camera. */
    private static void drawBox(LevelRenderContext ctx, AbstractClientPlayer target, Vec3 pos, Vec3 cam, float[] rgb) {
        double halfWidth = target.getBbWidth() / 2.0;
        double height = target.getBbHeight();
        AABB box = new AABB(
                pos.x - cam.x - halfWidth, pos.y - cam.y, pos.z - cam.z - halfWidth,
                pos.x - cam.x + halfWidth, pos.y - cam.y + height, pos.z - cam.z + halfWidth
        );
        int color = ARGB.colorFromFloat(BOX_ALPHA, rgb[0], rgb[1], rgb[2]);

        ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.lines(),
                (pose, vc) -> renderBoxOutline(vc, pose, box, color));
    }

    /**
     * The label rides on the vanilla name-tag submission: it billboards towards the camera,
     * carries the same backdrop as a player name and stays readable through walls — which is
     * the point here, the medic is aiming at the player anyway.
     */
    private static void drawLabel(LevelRenderContext ctx, AbstractClientPlayer target, Vec3 pos, Vec3 cam, Component label) {
        Vec3 attachment = new Vec3(
                pos.x - cam.x,
                pos.y - cam.y + target.getBbHeight() + LABEL_OFFSET_Y - NAME_TAG_BUILTIN_LIFT,
                pos.z - cam.z
        );
        ctx.submitNodeCollector().submitNameTag(
                ctx.poseStack(),
                attachment,
                0,
                label,
                true,
                LightCoordsUtil.FULL_BRIGHT,
                attachment.lengthSqr(),   // camera distance², used to order the name-tag submits
                ctx.levelState().cameraRenderState
        );
    }

    private static AbstractClientPlayer findPlayer(Minecraft client, String accountName) {
        for (AbstractClientPlayer player : client.level.players()) {
            if (accountName.equalsIgnoreCase(player.getGameProfile().name())) return player;
        }
        return null;
    }

    /** Draws the 12 edges of an axis-aligned box (26.1 dropped the vanilla ShapeRenderer debug helper). */
    private static void renderBoxOutline(VertexConsumer vc, PoseStack.Pose pose, AABB box, int color) {
        double minX = box.minX, minY = box.minY, minZ = box.minZ;
        double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;
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
