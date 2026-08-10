package de.dorikku.gmmedicmod.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shared scaffolding for the GM-Medic settings screens: a centred title, a "Zurück" button
 * that returns to whatever screen opened this one (or closes the GUI if there was none), a
 * reusable "-value+" stepper for the small set of integer settings (highlight range, exit
 * delay ticks), and mouse-wheel scrolling for when a large GUI scale leaves too little room
 * to show every row.
 *
 * <p>Subclasses lay out their rows in {@link #initWidgets()} exactly as before — fixed
 * {@code this.height / 6 + 24}-based Y coordinates, no awareness of scrolling needed.
 * {@link #init()} is sealed here so it can measure that layout afterward and, only if it
 * actually overflows the screen, reposition it and enable the scrollbar; a layout that
 * already fits is left completely untouched.</p>
 */
abstract class AbstractGMMedicScreen extends Screen {

    protected static final int BUTTON_WIDTH = 220;
    protected static final int BUTTON_HEIGHT = 20;
    protected static final int ROW_SPACING = 24;

    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_MARGIN = 4;
    private static final int SCROLL_STEP = 16;
    private static final int VIEWPORT_BOTTOM_MARGIN = 10;

    protected final Screen parent;

    /** Each scrollable widget's original Y, captured once right after {@link #initWidgets()}. */
    private final Map<AbstractWidget, Integer> baseWidgetY = new HashMap<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int viewportTop = 0;
    private int viewportBottom = 0;

    protected AbstractGMMedicScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected final void init() {
        baseWidgetY.clear();
        scrollOffset = 0;
        initWidgets();
        setUpScrolling();
    }

    /** Subclasses build their rows here — {@link #init()} takes care of scrolling afterward. */
    protected abstract void initWidgets();

    private void setUpScrolling() {
        viewportTop = this.height / 6 + 12;
        viewportBottom = this.height - VIEWPORT_BOTTOM_MARGIN;

        int contentBottom = viewportTop;
        for (var listener : this.children()) {
            if (listener instanceof AbstractWidget widget) {
                baseWidgetY.put(widget, widget.getY());
                contentBottom = Math.max(contentBottom, widget.getY() + widget.getHeight());
            }
        }
        maxScroll = Math.max(0, contentBottom - viewportBottom);
        applyScroll();
    }

    /**
     * No-op while everything already fits ({@code maxScroll == 0}) so a normal GUI scale is
     * never touched — widgets keep exactly the position/visibility {@link #initWidgets()} gave
     * them.
     */
    private void applyScroll() {
        if (maxScroll <= 0) return;
        for (Map.Entry<AbstractWidget, Integer> entry : baseWidgetY.entrySet()) {
            AbstractWidget widget = entry.getKey();
            int y = entry.getValue() - scrollOffset;
            widget.setY(y);
            boolean onScreen = y + widget.getHeight() > viewportTop && y < viewportBottom;
            widget.visible = onScreen;
            widget.active = onScreen;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.round(scrollY * SCROLL_STEP)));
        applyScroll();
        return true;
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

        if (maxScroll > 0) {
            int trackHeight = viewportBottom - viewportTop;
            int thumbHeight = Math.max(10, trackHeight * trackHeight / (trackHeight + maxScroll));
            int thumbY = viewportTop + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
            int trackX = this.width - SCROLLBAR_MARGIN - SCROLLBAR_WIDTH;
            guiGraphics.fill(trackX, viewportTop, trackX + SCROLLBAR_WIDTH, viewportBottom, 0x40FFFFFF);
            guiGraphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFFAAAAAA);
        }
    }
}
