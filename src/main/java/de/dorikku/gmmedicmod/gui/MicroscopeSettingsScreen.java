package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.MicroscopeConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings for the microscope diagnosis checklist. All three are local display choices —
 * nothing here is synced, the checklist never leaves the client.
 */
public class MicroscopeSettingsScreen extends AbstractGMMedicScreen {

    public MicroscopeSettingsScreen(Screen parent) {
        super(Component.literal("Mikroskop-Diagnose"), parent);
    }

    @Override
    protected void initWidgets() {
        MicroscopeConfig cfg = MicroscopeConfig.getInstance();
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Farb-Checkliste"),
                                (btn, value) -> MicroscopeConfig.getInstance().setEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isCompactMode())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Kompakter Modus"),
                                (btn, value) -> MicroscopeConfig.getInstance().setCompactMode(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.booleanBuilder(Component.literal("Farb-Icon"), Component.literal("Farbname"),
                                cfg.isIconLabels())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Anzeige"),
                                (btn, value) -> MicroscopeConfig.getInstance().setIconLabels(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isClickToCheck())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Klick im Mikroskop hakt ab"),
                                (btn, value) -> MicroscopeConfig.getInstance().setClickToCheck(value))
        );
        y += ROW_SPACING + 10;

        addBackButton(y);
    }
}
