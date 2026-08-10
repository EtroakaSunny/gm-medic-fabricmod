package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.HudConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Lets a medic drag the Notrufe panel and the bank-alarm banner to wherever they want on
 * screen. Both preview boxes are approximations of the real HUD elements (same width/roughly
 * the same height as {@link de.dorikku.gmmedicmod.hud.EmergencyCallHud}/{@link
 * de.dorikku.gmmedicmod.hud.AlarmHud} draw) — close enough to place them by, without coupling
 * this screen to their exact per-call layout math.
 *
 * <p>{@link HudConfig}'s setters already persist to disk, so releasing the mouse on a box is
 * all "saving" a new position takes; there is no separate save/cancel step.</p>
 */
public class HudPositionScreen extends AbstractGMMedicScreen {

    private static final int NOTRUFE_WIDTH_NORMAL = 200;
    private static final int NOTRUFE_WIDTH_COMPACT = 130;
    private static final int NOTRUFE_HEIGHT_NORMAL = 70;
    private static final int NOTRUFE_HEIGHT_COMPACT = 50;
    private static final int ALARM_HEIGHT = 44;

    private static final int BOX_BG = 0xA0000000;
    private static final int BOX_BORDER = 0xFFFFAA00;
    private static final int BOX_BORDER_DRAG = 0xFFFFFF55;
    private static final int ALARM_BG = 0x80AA0000;
    private static final int ALARM_BG_DRAG = 0xA0CC0000;

    private int notrufeW;
    private int notrufeH;
    private int notrufeX;
    private int notrufeY;
    private int alarmY;

    private boolean draggingNotrufe = false;
    private boolean draggingAlarm = false;
    private double dragOffsetX;
    private double dragOffsetY;

    public HudPositionScreen(Screen parent) {
        super(Text.literal("HUD-Position"), parent);
    }

    @Override
    protected void init() {
        HudConfig cfg = HudConfig.getInstance();
        boolean compact = cfg.isCompactMode();
        notrufeW = compact ? NOTRUFE_WIDTH_COMPACT : NOTRUFE_WIDTH_NORMAL;
        notrufeH = compact ? NOTRUFE_HEIGHT_COMPACT : NOTRUFE_HEIGHT_NORMAL;

        notrufeX = cfg.isNotrufePositionCustomized()
                ? (int) Math.round(cfg.getNotrufePosX() * this.width)
                : this.width - notrufeW - 10;
        notrufeY = cfg.isNotrufePositionCustomized()
                ? (int) Math.round(cfg.getNotrufePosY() * this.height)
                : 10;
        clampNotrufe();

        alarmY = cfg.isAlarmPositionCustomized()
                ? (int) Math.round(cfg.getAlarmPosY() * this.height)
                : 12;
        clampAlarm();

        int x = centeredX(BUTTON_WIDTH);
        int y = this.height - 90;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Notrufe-Position zurücksetzen"), b -> {
                    HudConfig.getInstance().resetNotrufePosition();
                    notrufeX = this.width - notrufeW - 10;
                    notrufeY = 10;
                })
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Alarm-Position zurücksetzen"), b -> {
                    HudConfig.getInstance().resetAlarmPosition();
                    alarmY = 12;
                })
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y).setMessage(Text.literal("Fertig"));
    }

    private void clampNotrufe() {
        notrufeX = Math.max(0, Math.min(this.width - notrufeW, notrufeX));
        notrufeY = Math.max(0, Math.min(this.height - notrufeH, notrufeY));
    }

    private void clampAlarm() {
        alarmY = Math.max(0, Math.min(this.height - ALARM_HEIGHT, alarmY));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        if (click.button() != 0) return false;

        double mouseX = click.x();
        double mouseY = click.y();
        if (mouseX >= notrufeX && mouseX <= notrufeX + notrufeW && mouseY >= notrufeY && mouseY <= notrufeY + notrufeH) {
            draggingNotrufe = true;
            dragOffsetX = mouseX - notrufeX;
            dragOffsetY = mouseY - notrufeY;
            return true;
        }
        if (mouseY >= alarmY && mouseY <= alarmY + ALARM_HEIGHT) {
            draggingAlarm = true;
            dragOffsetY = mouseY - alarmY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double dragX, double dragY) {
        if (draggingNotrufe) {
            notrufeX = (int) Math.round(click.x() - dragOffsetX);
            notrufeY = (int) Math.round(click.y() - dragOffsetY);
            clampNotrufe();
            return true;
        }
        if (draggingAlarm) {
            alarmY = (int) Math.round(click.y() - dragOffsetY);
            clampAlarm();
            return true;
        }
        return super.mouseDragged(click, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingNotrufe) {
            draggingNotrufe = false;
            HudConfig.getInstance().setNotrufePosition((double) notrufeX / this.width, (double) notrufeY / this.height);
            return true;
        }
        if (draggingAlarm) {
            draggingAlarm = false;
            HudConfig.getInstance().setAlarmPositionY((double) alarmY / this.height);
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int alarmColor = draggingAlarm ? ALARM_BG_DRAG : ALARM_BG;
        int alarmBorder = draggingAlarm ? BOX_BORDER_DRAG : BOX_BORDER;
        context.fill(0, alarmY, this.width, alarmY + ALARM_HEIGHT, alarmColor);
        context.fill(0, alarmY, this.width, alarmY + 2, alarmBorder);
        context.fill(0, alarmY + ALARM_HEIGHT - 2, this.width, alarmY + ALARM_HEIGHT, alarmBorder);
        String alarmLabel = "⚠ Alarm-Banner";
        context.drawText(this.textRenderer, alarmLabel, this.width / 2 - this.textRenderer.getWidth(alarmLabel) / 2, alarmY + ALARM_HEIGHT / 2 - 4, 0xFFFFFFFF, true);

        int notrufeBorder = draggingNotrufe ? BOX_BORDER_DRAG : BOX_BORDER;
        context.fill(notrufeX, notrufeY, notrufeX + notrufeW, notrufeY + notrufeH, BOX_BG);
        context.fill(notrufeX, notrufeY, notrufeX + notrufeW, notrufeY + 1, notrufeBorder);
        context.fill(notrufeX, notrufeY + notrufeH - 1, notrufeX + notrufeW, notrufeY + notrufeH, notrufeBorder);
        context.fill(notrufeX, notrufeY, notrufeX + 1, notrufeY + notrufeH, notrufeBorder);
        context.fill(notrufeX + notrufeW - 1, notrufeY, notrufeX + notrufeW, notrufeY + notrufeH, notrufeBorder);
        context.drawText(this.textRenderer, "🚑 Notrufe", notrufeX + 6, notrufeY + 6, 0xFFFFAA00, true);
        context.drawText(this.textRenderer, "(ziehen zum Verschieben)", notrufeX + 6, notrufeY + notrufeH - 14, 0xFFAAAAAA, true);

        String hint = "Ziehe die Kästen an die gewünschte Position.";
        context.drawText(this.textRenderer, hint, centeredX(this.textRenderer.getWidth(hint)), this.height - 110, 0xFFFFFFFF, true);
    }
}
