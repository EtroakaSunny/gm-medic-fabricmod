package de.dorikku.gmmedicmod.render;

import de.dorikku.gmmedicmod.blood.BloodDrawAssistant;
import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

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
    private static final int READY_TEXT_COLOR    = 0xFF55FF55;
    private static final int COOLDOWN_TEXT_COLOR = 0xFFFF5555;
    private static final int UNKNOWN_TEXT_COLOR  = 0xFFAAAAAA;
    private static final int LABEL_BACKGROUND    = 0x66000000;

    private static final float BOX_ALPHA = 0.9f;
    private static final float LINE_WIDTH = 3.0f;
    /** How far above the head the label floats. */
    private static final double LABEL_OFFSET_Y = 0.7;
    /** Text is drawn in block space, so it has to be scaled down and flipped upright. */
    private static final float LABEL_SCALE = 0.025f;

    private BloodTargetHighlightRenderer() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;
        if (!ApiConnection.getInstance().isFeatureUnlocked()) return;
        if (!EmergencyCallManager.getInstance().isInDuty()) return;
        if (!HudConfig.getInstance().isBloodDisplayEnabled()) return;
        if (!HudConfig.getInstance().isHighlightEnabled()) return;

        String targetName = BloodDrawAssistant.getLookTargetName();
        if (targetName == null) return;
        AbstractClientPlayerEntity target = findPlayer(client, targetName);
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
        int labelColor = switch (result.status()) {
            case READY    -> READY_TEXT_COLOR;
            case COOLDOWN -> COOLDOWN_TEXT_COLOR;
            case UNKNOWN  -> UNKNOWN_TEXT_COLOR;
        };

        float tickDelta = client.getRenderTickCounter().getTickProgress(true);
        Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
        Vec3d pos = target.getLerpedPos(tickDelta);
        MatrixStack ms = ctx.matrices();

        drawBox(ctx, ms, target, pos, cam, rgb);
        drawLabel(client, ctx, ms, target, pos, cam, label, labelColor);
    }

    private static void drawBox(WorldRenderContext ctx, MatrixStack ms, AbstractClientPlayerEntity target,
                                Vec3d pos, Vec3d cam, float[] rgb) {
        double halfWidth = target.getWidth() / 2.0;
        double height = target.getHeight();
        Box box = new Box(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, pos.y + height, pos.z + halfWidth
        );

        // The matrix stack is anchored at the camera, so feed the box in camera-relative space.
        VoxelShape shape = VoxelShapes.cuboidUnchecked(
                box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
                box.maxX - cam.x, box.maxY - cam.y, box.maxZ - cam.z);
        VertexConsumer vc = ctx.consumers().getBuffer(RenderLayers.lines());
        int color = ColorHelper.fromFloats(BOX_ALPHA, rgb[0], rgb[1], rgb[2]);
        VertexRendering.drawOutline(ms, vc, shape, 0.0, 0.0, 0.0, color, LINE_WIDTH);
    }

    /** Billboard text above the head: face the camera, flip upright, shrink to block scale. */
    private static void drawLabel(MinecraftClient client, WorldRenderContext ctx, MatrixStack ms,
                                  AbstractClientPlayerEntity target, Vec3d pos, Vec3d cam,
                                  String label, int color) {
        TextRenderer textRenderer = client.textRenderer;
        ms.push();
        ms.translate(
                pos.x - cam.x,
                pos.y + target.getHeight() + LABEL_OFFSET_Y - cam.y,
                pos.z - cam.z
        );
        ms.multiply(client.gameRenderer.getCamera().getRotation());
        ms.scale(-LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        textRenderer.draw(
                label,
                -textRenderer.getWidth(label) / 2.0f,
                0.0f,
                color,
                false,
                ms.peek().getPositionMatrix(),
                ctx.consumers(),
                TextRenderer.TextLayerType.SEE_THROUGH,
                LABEL_BACKGROUND,
                LightmapTextureManager.MAX_LIGHT_COORDINATE
        );
        ms.pop();
    }

    private static AbstractClientPlayerEntity findPlayer(MinecraftClient client, String accountName) {
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (accountName.equalsIgnoreCase(player.getGameProfile().name())) return player;
        }
        return null;
    }
}
