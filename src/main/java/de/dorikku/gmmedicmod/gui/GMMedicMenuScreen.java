package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.ReviveReplyConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Hub screen for every GM-Medic setting that used to be a chat command (HUD, vehicle
 * automation, API management) plus the revive auto-reply toggle. Only {@code /gmapi url}
 * stays a command — everything else lives here now.
 */
public class GMMedicMenuScreen extends AbstractGMMedicScreen {

    public GMMedicMenuScreen(Screen parent) {
        super(Component.literal("GM-Medic Einstellungen"), parent);
    }

    @Override
    protected void init() {
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addRenderableWidget(
                Button.builder(Component.literal("🚑 HUD-Einstellungen"), b -> this.minecraft.gui.setScreen(new HudSettingsScreen(this)))
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                Button.builder(Component.literal("🚗 Fahrzeug-Automatik"), b -> this.minecraft.gui.setScreen(new VehicleSettingsScreen(this)))
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                Button.builder(Component.literal("📡 API-Verwaltung"), b -> this.minecraft.gui.setScreen(new ApiSettingsScreen(this)))
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(ReviveReplyConfig.getInstance().isEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("❤ Auto-Antwort bei Wiederbelebung"),
                                (btn, value) -> ReviveReplyConfig.getInstance().setEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                Button.builder(Component.literal("Antworttext bearbeiten"), b -> this.minecraft.gui.setScreen(new ReviveReplyTextScreen(this)))
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y).setMessage(Component.literal("Fertig"));
    }
}
