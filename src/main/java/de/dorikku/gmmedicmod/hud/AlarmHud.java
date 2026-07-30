package de.dorikku.gmmedicmod.hud;

import de.dorikku.gmmedicmod.manager.AlarmManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.joml.Matrix3x2fStack;

/**
 * Full-width flashing banner shown while a bank alarm is active. Only rendered for
 * players that are NOT on duty — on-duty medics read the D-Funk directly.
 */
public class AlarmHud {

    private static final long FLASH_MS = 600L;

    private static final int BG_BRIGHT     = 0xD0990000;
    private static final int BG_DARK       = 0xD0550000;
    private static final int BORDER_BRIGHT = 0xFFFFFF55;
    private static final int BORDER_DARK   = 0xFFFFAA00;

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        AlarmManager alarm = AlarmManager.getInstance();
        if (!alarm.isActive()) return;
        if (EmergencyCallManager.getInstance().isInDuty()) return;

        String name = alarm.getAlarmName();
        if (name == null) return;
        boolean flash = System.currentTimeMillis() / FLASH_MS % 2 == 0;
        TextRenderer text = client.textRenderer;
        int width = client.getWindow().getScaledWidth();

        int top = 12;
        int height = 44;
        ctx.fill(0, top, width, top + height, flash ? BG_BRIGHT : BG_DARK);
        ctx.fill(0, top, width, top + 2, flash ? BORDER_BRIGHT : BORDER_DARK);
        ctx.fill(0, top + height - 2, width, top + height, flash ? BORDER_BRIGHT : BORDER_DARK);

        String headline = "⚠ ALARM AUSGELÖST ⚠";
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        // 2x scale: the scaled text is 2*getWidth wide, so start at center - getWidth.
        matrices.translate(width / 2f - text.getWidth(headline), top + 6);
        matrices.scale(2f);
        ctx.drawText(text, headline, 0, 0, flash ? 0xFFFFFFFF : 0xFFFFFF55, true);
        matrices.popMatrix();

        long elapsed = Math.max(0L, System.currentTimeMillis() - alarm.getTriggeredAtMs()) / 1000L;
        String info = "Der Alarm der " + name + " wurde ausgelöst — seit "
                + String.format("%d:%02d", elapsed / 60, elapsed % 60);
        ctx.drawText(text, info, (width - text.getWidth(info)) / 2, top + 30, 0xFFFFFFFF, true);
    }
}
