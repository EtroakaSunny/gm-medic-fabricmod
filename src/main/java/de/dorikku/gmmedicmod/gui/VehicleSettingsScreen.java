package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.VehicleConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Replaces the old {@code /gmvehicle} command tree. */
public class VehicleSettingsScreen extends AbstractGMMedicScreen {

    private static final int EXIT_DELAY_STEP = 5;

    public VehicleSettingsScreen(Screen parent) {
        super(Component.literal("Fahrzeug-Automatik"), parent);
    }

    @Override
    protected void init() {
        VehicleConfig cfg = VehicleConfig.getInstance();
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;
        int gap = 4;
        int halfWidth = (BUTTON_WIDTH - gap) / 2;

        y = addFeatureRow(x, y, halfWidth, gap, "Motor", cfg.isMotorEnabled(), cfg.isMotorAlways(),
                VehicleConfig::setMotorEnabled, VehicleConfig::setMotorAlways);
        y = addFeatureRow(x, y, halfWidth, gap, "Gangschaltung", cfg.isGearEnabled(), cfg.isGearAlways(),
                VehicleConfig::setGearEnabled, VehicleConfig::setGearAlways);
        y = addFeatureRow(x, y, halfWidth, gap, "Sirene", cfg.isSirenEnabled(), cfg.isSirenAlways(),
                VehicleConfig::setSirenEnabled, VehicleConfig::setSirenAlways);

        addStepper(x, y, "Ausstiegs-Verzögerung",
                () -> VehicleConfig.getInstance().getExitDelayTicks(),
                value -> VehicleConfig.getInstance().setExitDelayTicks(value),
                EXIT_DELAY_STEP, VehicleConfig.MIN_EXIT_DELAY_TICKS, VehicleConfig.MAX_EXIT_DELAY_TICKS, " Ticks");
        y += ROW_SPACING + 10;

        addBackButton(y);
    }

    /**
     * One feature's pair of toggles: "enabled" and "always active (even off duty)". Turning
     * "always" on force-enables the feature too, mirroring the old command's behaviour — an
     * "always on" flag with the feature itself off would otherwise be dead config.
     */
    private int addFeatureRow(int x, int y, int halfWidth, int gap, String label, boolean enabled, boolean always,
                               java.util.function.BiConsumer<VehicleConfig, Boolean> setEnabled,
                               java.util.function.BiConsumer<VehicleConfig, Boolean> setAlways) {
        CycleButton<Boolean> enabledButton = this.addRenderableWidget(
                CycleButton.onOffBuilder(enabled)
                        .create(x, y, halfWidth, BUTTON_HEIGHT, Component.literal(label),
                                (btn, value) -> setEnabled.accept(VehicleConfig.getInstance(), value))
        );

        this.addRenderableWidget(
                CycleButton.onOffBuilder(always)
                        .create(x + halfWidth + gap, y, halfWidth, BUTTON_HEIGHT, Component.literal(label + ": immer"),
                                (btn, value) -> {
                                    setAlways.accept(VehicleConfig.getInstance(), value);
                                    if (value) {
                                        setEnabled.accept(VehicleConfig.getInstance(), true);
                                        enabledButton.setValue(true);
                                    }
                                })
        );

        return y + ROW_SPACING;
    }
}
