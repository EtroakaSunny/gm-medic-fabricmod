package de.dorikku.gmmedicmod.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shared scaffolding for the GM-Medic settings screens: a centred title, a "Zurück" button
 * that returns to whatever screen opened this one (or closes the GUI if there was none), and
 * a reusable "-value+" stepper for the small set of integer settings (highlight range, exit
 * delay ticks).
 */
abstract class AbstractGMMedicScreen extends Screen {

    protected static final int BUTTON_WIDTH = 220;
    protected static final int BUTTON_HEIGHT = 20;
    protected static final int ROW_SPACING = 24;

    protected final Screen parent;

    protected AbstractGMMedicScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected int centeredX(int width) {
        return (this.width - width) / 2;
    }

    protected Button addBackButton(int y) {
        return this.addRenderableWidget(
                Button.builder(Component.literal("Zurück"), b -> back())
                        .bounds(centeredX(BUTTON_WIDTH), y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
    }

    /** Overridable so screens with unsaved input (e.g. an EditBox) can persist it before leaving. */
    protected void back() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** A "-value+" row: clicking the side buttons adjusts the stored setting by {@code step}, clamped to [min, max]. */
    protected void addStepper(int x, int y, String label, IntSupplier getter, IntConsumer setter,
                               int step, int min, int max, String suffix) {
        int smallWidth = 20;
        int gap = 4;
        int labelWidth = BUTTON_WIDTH - smallWidth * 2 - gap * 2;

        Button valueLabel = Button.builder(Component.literal(label + ": " + getter.getAsInt() + suffix), b -> {})
                .bounds(x + smallWidth + gap, y, labelWidth, BUTTON_HEIGHT)
                .build();
        valueLabel.active = false;

        Button minus = Button.builder(Component.literal("-"), b -> {
                    int updated = Math.max(min, getter.getAsInt() - step);
                    setter.accept(updated);
                    valueLabel.setMessage(Component.literal(label + ": " + updated + suffix));
                })
                .bounds(x, y, smallWidth, BUTTON_HEIGHT)
                .build();

        Button plus = Button.builder(Component.literal("+"), b -> {
                    int updated = Math.min(max, getter.getAsInt() + step);
                    setter.accept(updated);
                    valueLabel.setMessage(Component.literal(label + ": " + updated + suffix));
                })
                .bounds(x + smallWidth + gap + labelWidth + gap, y, smallWidth, BUTTON_HEIGHT)
                .build();

        this.addRenderableWidget(minus);
        this.addRenderableWidget(valueLabel);
        this.addRenderableWidget(plus);
    }

    @Override
    public void onClose() {
        back();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, this.height / 6 - 12, 0xFFFFFF);
    }
}
