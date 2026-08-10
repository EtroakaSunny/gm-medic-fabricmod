package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.HudConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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
        super(Component.literal("HUD-Position"), parent);
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

        this.addRenderableWidget(
                Button.builder(Component.literal("Notrufe-Position zurücksetzen"), b -> {
                    HudConfig.getInstance().resetNotrufePosition();
                    notrufeX = this.width - notrufeW - 10;
                    notrufeY = 10;
                })
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                Button.builder(Component.literal("Alarm-Position zurücksetzen"), b -> {
                    HudConfig.getInstance().resetAlarmPosition();
                    alarmY = 12;
                })
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y).setMessage(Component.literal("Fertig"));
    }

    private void clampNotrufe() {
        notrufeX = Math.max(0, Math.min(this.width - notrufeW, notrufeX));
        notrufeY = Math.max(0, Math.min(this.height - notrufeH, notrufeY));
    }

    private void clampAlarm() {
        alarmY = Math.max(0, Math.min(this.height - ALARM_HEIGHT, alarmY));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;

        double mouseX = event.x();
        double mouseY = event.y();
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
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingNotrufe) {
            notrufeX = (int) Math.round(event.x() - dragOffsetX);
            notrufeY = (int) Math.round(event.y() - dragOffsetY);
            clampNotrufe();
            return true;
        }
        if (draggingAlarm) {
            alarmY = (int) Math.round(event.y() - dragOffsetY);
            clampAlarm();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
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
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        int alarmColor = draggingAlarm ? ALARM_BG_DRAG : ALARM_BG;
        int alarmBorder = draggingAlarm ? BOX_BORDER_DRAG : BOX_BORDER;
        guiGraphics.fill(0, alarmY, this.width, alarmY + ALARM_HEIGHT, alarmColor);
        guiGraphics.fill(0, alarmY, this.width, alarmY + 2, alarmBorder);
        guiGraphics.fill(0, alarmY + ALARM_HEIGHT - 2, this.width, alarmY + ALARM_HEIGHT, alarmBorder);
        String alarmLabel = "⚠ Alarm-Banner";
        guiGraphics.text(this.font, alarmLabel, this.width / 2 - this.font.width(alarmLabel) / 2, alarmY + ALARM_HEIGHT / 2 - 4, 0xFFFFFFFF, true);

        int notrufeBorder = draggingNotrufe ? BOX_BORDER_DRAG : BOX_BORDER;
        guiGraphics.fill(notrufeX, notrufeY, notrufeX + notrufeW, notrufeY + notrufeH, BOX_BG);
        guiGraphics.fill(notrufeX, notrufeY, notrufeX + notrufeW, notrufeY + 1, notrufeBorder);
        guiGraphics.fill(notrufeX, notrufeY + notrufeH - 1, notrufeX + notrufeW, notrufeY + notrufeH, notrufeBorder);
        guiGraphics.fill(notrufeX, notrufeY, notrufeX + 1, notrufeY + notrufeH, notrufeBorder);
        guiGraphics.fill(notrufeX + notrufeW - 1, notrufeY, notrufeX + notrufeW, notrufeY + notrufeH, notrufeBorder);
        guiGraphics.text(this.font, "🚑 Notrufe", notrufeX + 6, notrufeY + 6, 0xFFFFAA00, true);
        guiGraphics.text(this.font, "(ziehen zum Verschieben)", notrufeX + 6, notrufeY + notrufeH - 14, 0xFFAAAAAA, true);

        String hint = "Ziehe die Kästen an die gewünschte Position.";
        guiGraphics.text(this.font, hint, centeredX(this.font.width(hint)), this.height - 110, 0xFFFFFFFF, true);
    }
}
