package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.HudConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Replaces the old {@code /gmhud} command tree. */
public class HudSettingsScreen extends AbstractGMMedicScreen {

    private static final int RANGE_STEP = 8;

    public HudSettingsScreen(Screen parent) {
        super(Component.literal("HUD-Einstellungen"), parent);
    }

    @Override
    protected void init() {
        HudConfig cfg = HudConfig.getInstance();
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isCompactMode())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Kompakter Modus"),
                                (btn, value) -> HudConfig.getInstance().setCompactMode(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isHighlightEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Spieler-Highlight"),
                                (btn, value) -> HudConfig.getInstance().setHighlightEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isBloodDisplayEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Blutspende-Anzeige"),
                                (btn, value) -> HudConfig.getInstance().setBloodDisplayEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isBloodDrawMessageEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Blutspende-Meldung"),
                                (btn, value) -> HudConfig.getInstance().setBloodDrawMessageEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isCallTimerEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Notruf-Timer"),
                                (btn, value) -> HudConfig.getInstance().setCallTimerEnabled(value))
        );
        y += ROW_SPACING;

        addStepper(x, y, "Highlight-Reichweite",
                () -> (int) HudConfig.getInstance().getHighlightRange(),
                value -> HudConfig.getInstance().setHighlightRange(value),
                RANGE_STEP, (int) HudConfig.MIN_HIGHLIGHT_RANGE, (int) HudConfig.MAX_HIGHLIGHT_RANGE, " Blöcke");
        y += ROW_SPACING + 10;

        addBackButton(y);
    }
}
