package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.HudConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

/** Replaces the old {@code /gmhud} command tree. */
public class HudSettingsScreen extends AbstractGMMedicScreen {

    private static final int RANGE_STEP = 8;

    public HudSettingsScreen(Screen parent) {
        super(Text.literal("HUD-Einstellungen"), parent);
    }

    @Override
    protected void init() {
        HudConfig cfg = HudConfig.getInstance();
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(cfg.isCompactMode())
                        .build(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Text.literal("Kompakter Modus"),
                                (btn, value) -> HudConfig.getInstance().setCompactMode(value))
        );
        y += ROW_SPACING;

        this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(cfg.isHighlightEnabled())
                        .build(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Text.literal("Spieler-Highlight"),
                                (btn, value) -> HudConfig.getInstance().setHighlightEnabled(value))
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
