package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.ReviveReplyConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

/**
 * Hub screen for every GM-Medic setting that used to be a chat command (HUD, vehicle
 * automation, API management) plus the revive auto-reply toggle. Only {@code /gmapi url}
 * stays a command — everything else lives here now.
 */
public class GMMedicMenuScreen extends AbstractGMMedicScreen {

    public GMMedicMenuScreen(Screen parent) {
        super(Text.literal("GM-Medic Einstellungen"), parent);
    }

    @Override
    protected void init() {
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("🚑 HUD-Einstellungen"), b -> this.client.setScreen(new HudSettingsScreen(this)))
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("🚗 Fahrzeug-Automatik"), b -> this.client.setScreen(new VehicleSettingsScreen(this)))
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("📡 API-Verwaltung"), b -> this.client.setScreen(new ApiSettingsScreen(this)))
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(ReviveReplyConfig.getInstance().isEnabled())
                        .build(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Text.literal("❤ Auto-Antwort bei Wiederbelebung"),
                                (btn, value) -> ReviveReplyConfig.getInstance().setEnabled(value))
        );
        y += ROW_SPACING;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Antworttext bearbeiten"), b -> this.client.setScreen(new ReviveReplyTextScreen(this)))
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y).setMessage(Text.literal("Fertig"));
    }
}
