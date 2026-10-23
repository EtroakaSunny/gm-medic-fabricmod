package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.HudConfig;
import net.minecraft.client.gui.components.Button;
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
    protected void initWidgets() {
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
                CycleButton.onOffBuilder(cfg.isBloodChatNoteCanDonateEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Blut-Chat (Kann spenden)"),
                                (btn, value) -> HudConfig.getInstance().setBloodChatNoteCanDonateEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isBloodChatNoteCannotDonateEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Blut-Chat (Kann nicht)"),
                                (btn, value) -> HudConfig.getInstance().setBloodChatNoteCannotDonateEnabled(value))
        );
        y += ROW_SPACING;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isCallTimerEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Notruf-Timer"),
                                (btn, value) -> HudConfig.getInstance().setCallTimerEnabled(value))
        );
        y += ROW_SPACING;

        // Hiding the chat blocks only makes sense while the panel can stand in for their buttons,
        // and the distance stands in for the hidden "Distanz" line, so it depends on the hiding.
        CycleButton<Boolean> distanceButton = CycleButton.onOffBuilder(cfg.isCallDistanceEnabled())
                .create(x, y + ROW_SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Eigene Entfernung (5 s)"),
                        (btn, value) -> HudConfig.getInstance().setCallDistanceEnabled(value));
        distanceButton.active = cfg.isClickableCallsEnabled() && cfg.isHideCallChatEnabled();
        CycleButton<Boolean> hideChatButton = CycleButton.onOffBuilder(cfg.isHideCallChatEnabled())
                .create(x, y + ROW_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Notrufe im Chat ausblenden"),
                        (btn, value) -> {
                            HudConfig.getInstance().setHideCallChatEnabled(value);
                            distanceButton.active = HudConfig.getInstance().isClickableCallsEnabled() && value;
                        });
        hideChatButton.active = cfg.isClickableCallsEnabled();
        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isClickableCallsEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Klickbare Notrufe"),
                                (btn, value) -> {
                                    HudConfig.getInstance().setClickableCallsEnabled(value);
                                    hideChatButton.active = value;
                                    distanceButton.active = value && HudConfig.getInstance().isHideCallChatEnabled();
                                })
        );
        this.addRenderableWidget(hideChatButton);
        this.addRenderableWidget(distanceButton);
        y += ROW_SPACING * 3;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isAlarmBannerEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Alarm-Banner"),
                                (btn, value) -> HudConfig.getInstance().setAlarmBannerEnabled(value))
        );
        y += ROW_SPACING;

        addStepper(x, y, "Highlight-Reichweite",
                () -> (int) HudConfig.getInstance().getHighlightRange(),
                value -> HudConfig.getInstance().setHighlightRange(value),
                RANGE_STEP, (int) HudConfig.MIN_HIGHLIGHT_RANGE, (int) HudConfig.MAX_HIGHLIGHT_RANGE, " Blöcke");
        y += ROW_SPACING + 10;

        this.addRenderableWidget(
                Button.builder(Component.literal("📍 HUD-Position anpassen"), b -> this.minecraft.setScreen(new HudPositionScreen(this)))
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y);
    }
}
