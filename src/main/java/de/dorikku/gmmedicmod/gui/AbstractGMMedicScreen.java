package de.dorikku.gmmedicmod.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

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

    protected AbstractGMMedicScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected int centeredX(int width) {
        return (this.width - width) / 2;
    }

    protected ButtonWidget addBackButton(int y) {
        return this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Zurück"), b -> back())
                        .dimensions(centeredX(BUTTON_WIDTH), y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
    }

    /** Overridable so screens with unsaved input (e.g. a TextFieldWidget) can persist it before leaving. */
    protected void back() {
        this.client.setScreen(this.parent);
    }

    /** A "-value+" row: clicking the side buttons adjusts the stored setting by {@code step}, clamped to [min, max]. */
    protected void addStepper(int x, int y, String label, IntSupplier getter, IntConsumer setter,
                               int step, int min, int max, String suffix) {
        int smallWidth = 20;
        int gap = 4;
        int labelWidth = BUTTON_WIDTH - smallWidth * 2 - gap * 2;

        ButtonWidget valueLabel = ButtonWidget.builder(Text.literal(label + ": " + getter.getAsInt() + suffix), b -> {})
                .dimensions(x + smallWidth + gap, y, labelWidth, BUTTON_HEIGHT)
                .build();
        valueLabel.active = false;

        ButtonWidget minus = ButtonWidget.builder(Text.literal("-"), b -> {
                    int updated = Math.max(min, getter.getAsInt() - step);
                    setter.accept(updated);
                    valueLabel.setMessage(Text.literal(label + ": " + updated + suffix));
                })
                .dimensions(x, y, smallWidth, BUTTON_HEIGHT)
                .build();

        ButtonWidget plus = ButtonWidget.builder(Text.literal("+"), b -> {
                    int updated = Math.min(max, getter.getAsInt() + step);
                    setter.accept(updated);
                    valueLabel.setMessage(Text.literal(label + ": " + updated + suffix));
                })
                .dimensions(x + smallWidth + gap + labelWidth + gap, y, smallWidth, BUTTON_HEIGHT)
                .build();

        this.addDrawableChild(minus);
        this.addDrawableChild(valueLabel);
        this.addDrawableChild(plus);
    }

    @Override
    public void close() {
        back();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 6 - 12, 0xFFFFFF);
    }
}
