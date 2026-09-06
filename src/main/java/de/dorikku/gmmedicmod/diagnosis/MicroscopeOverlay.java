package de.dorikku.gmmedicmod.diagnosis;

import de.dorikku.gmmedicmod.config.MicroscopeConfig;
import de.dorikku.gmmedicmod.mixin.ContainerScreenAccessor;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;

import java.util.List;
import java.util.Locale;

/**
 * The colour checklist a medic ticks off while reading a patient's sample in the server's
 * "Mikroskop" menu.
 *
 * <p>The panel is drawn next to that menu and holds all 16 Minecraft dye colours; clicking an
 * entry ticks it, and the ticks are kept per patient in
 * {@link MicroscopeDiagnosisManager} so paging through the menu — which closes and reopens the
 * screen every time — does not lose them.</p>
 *
 * <p>Two layouts exist because some medics play at a very large GUI scale: the normal one is a
 * full-height list, the compact one a small grid. {@link MicroscopeConfig} picks between them,
 * and the compact one is used regardless whenever the normal one would not fit on screen.
 * Entries are either the colour's name written in that colour or Minecraft's own dye icon,
 * which is the second thing {@link MicroscopeConfig} switches.</p>
 *
 * <p>Everything here is client-side and needs no server support at all.</p>
 */
public final class MicroscopeOverlay {

    /** The menu is recognised by its title, which reads {@code Mikroskop | <patient>}. */
    private static final String MENU_TITLE = "mikroskop";
    /** Anything between the title and the name is a decoration, not part of the name. */
    private static final String SEPARATORS = "|-–—»:·";
    /** Key used when the title carries no name at all, so the checklist still works. */
    private static final String UNKNOWN_PATIENT = "?";

    private static final int PADDING = 5;
    /** Space between the container menu and the panel. */
    private static final int GAP_TO_MENU = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int CELL_GAP = 3;
    private static final int BOX_SIZE = 9;
    private static final int BOX_GAP = 3;
    private static final int ICON_SIZE = 16;
    /**
     * A long name is cut off rather than allowed to stretch the whole panel — and the compact
     * layout keeps a tighter budget for name, hint and buttons alike, because the screen it is
     * meant for barely has room beside the menu in the first place.
     */
    private static final int HEADER_MAX_WIDTH = 120;
    private static final int HEADER_MAX_WIDTH_COMPACT = 78;
    private static final int HINT_MIN_WIDTH = 108;
    private static final int HINT_MIN_WIDTH_COMPACT = 84;
    private static final int HINT_MAX_WIDTH = 150;
    /** Room kept free for a hint when deciding between the two layouts. */
    private static final int HINT_RESERVE_LINES = 3;
    private static final String RESET_LABEL = "[ Neu starten ]";
    private static final String RESET_LABEL_COMPACT = "[ Neu ]";

    private static final int COLOR_PANEL = 0xE8101014;
    private static final int COLOR_BORDER = 0xFF4A4A55;
    private static final int COLOR_SEPARATOR = 0xFF3A3A42;
    private static final int COLOR_CELL = 0xFF20202A;
    private static final int COLOR_CELL_CHECKED = 0xFF1C3A1C;
    private static final int COLOR_CELL_HOVER = 0xFF34343F;
    private static final int COLOR_BOX = 0xFF2B2B33;
    private static final int COLOR_BOX_BORDER = 0xFF6A6A76;
    private static final int COLOR_CHECK = 0xFF55FF55;
    private static final int COLOR_PATIENT = 0xFF55FFFF;
    private static final int COLOR_MUTED = 0xFFA0A0AA;
    private static final int COLOR_EXPIRING = 0xFFFF5555;
    private static final int COLOR_ACTION = 0xFFC8C8D2;

    private MicroscopeOverlay() {}

    /** Hooks every screen that opens; only a "Mikroskop" container menu gets the panel. */
    public static void register() {
        ScreenEvents.AFTER_INIT.register(MicroscopeOverlay::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof AbstractContainerScreen<?> menuScreen)) return;
        String patient = patientOf(screen.getTitle().getString());
        if (patient == null) return;

        // Registered even while the feature is switched off, so toggling it in the settings
        // takes effect on the menu that is already open.
        ScreenEvents.afterTick(screen).register(s -> scanSamples(menuScreen, patient));
        ScreenEvents.afterExtract(screen).register(
                (s, graphics, mouseX, mouseY, tickProgress) -> render(menuScreen, patient, graphics, mouseX, mouseY));
        ScreenMouseEvents.allowMouseClick(screen).register(
                (s, event) -> !handleClick(menuScreen, patient, event));
    }

    /**
     * The patient the menu belongs to, or {@code null} when this is not the microscope at all.
     * Titles read {@code Mikroskop | <patient>}; a title without a name still opens a checklist,
     * under {@link #UNKNOWN_PATIENT}.
     */
    static String patientOf(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.replaceAll("§.", "").trim();
        if (!title.toLowerCase(Locale.ROOT).startsWith(MENU_TITLE)) return null;

        String rest = title.substring(MENU_TITLE.length());
        // "Mikroskopie" and friends are a different menu, not this one with a long name.
        if (!rest.isEmpty() && Character.isLetter(rest.charAt(0))) return null;

        int start = 0;
        while (start < rest.length()
                && (Character.isWhitespace(rest.charAt(start)) || SEPARATORS.indexOf(rest.charAt(start)) >= 0)) {
            start++;
        }
        rest = rest.substring(start).trim();
        return rest.isEmpty() ? UNKNOWN_PATIENT : rest;
    }

    private static boolean isActive() {
        return MicroscopeConfig.getInstance().isEnabled() && ApiConnection.getInstance().isFeatureUnlocked();
    }

    /**
     * Reads the colours on the page that is currently open into the patient's run. Only the
     * menu's own slots are looked at — the medic's inventory below it is not part of the sample.
     */
    private static void scanSamples(AbstractContainerScreen<?> screen, String patient) {
        if (!isActive()) return;
        MicroscopeSession session = MicroscopeDiagnosisManager.getInstance().session(patient);
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            DyeColor color = MicroscopeColors.colorOf(slot.getItem());
            if (color != null) session.observe(color);
        }
    }

    // --- layout ---

    /**
     * How the 16 entries are arranged. Entries are filled column by column, so a column always
     * holds neighbouring colours from {@link MicroscopeColors#ORDER}.
     */
    private record Layout(int columns, int rows, int cellWidth, int cellHeight,
                          boolean compact, boolean icons, boolean shortNames, boolean checkbox) {
        int gridWidth() {
            return columns * cellWidth + (columns - 1) * CELL_GAP;
        }

        int gridHeight() {
            return rows * cellHeight;
        }

        String resetLabel() {
            return compact ? RESET_LABEL_COMPACT : RESET_LABEL;
        }

        int headerMaxWidth() {
            return compact ? HEADER_MAX_WIDTH_COMPACT : HEADER_MAX_WIDTH;
        }

        int hintMinWidth() {
            return compact ? HINT_MIN_WIDTH_COMPACT : HINT_MIN_WIDTH;
        }
    }

    /** A fully measured panel — the single source of truth for both drawing and hit-testing. */
    private record Panel(Layout layout, int x, int y, int width, int height,
                         int gridX, int gridY, int resetY,
                         List<FormattedCharSequence> hintLines, int hintColor) {}

    private static Layout layoutFor(Font font, boolean compact) {
        if (compact) {
            return MicroscopeConfig.getInstance().isIconLabels()
                    // No checkbox fits beside a 16px icon at this size; the cell frame carries
                    // the tick state instead, which is why the cell is a pixel wider than the
                    // icon on each side — the frame would clip it otherwise.
                    ? new Layout(4, 4, ICON_SIZE + 2, ICON_SIZE + 2, true, true, false, false)
                    : new Layout(2, 8, BOX_SIZE + BOX_GAP + widestShortName(font), 11, true, false, true, true);
        }
        return MicroscopeConfig.getInstance().isIconLabels()
                ? new Layout(2, 8, BOX_SIZE + BOX_GAP + ICON_SIZE, ICON_SIZE + 2, false, true, false, true)
                : new Layout(1, 16, BOX_SIZE + BOX_GAP + widestName(font), 12, false, false, false, true);
    }

    private static int widestName(Font font) {
        int widest = 0;
        for (DyeColor color : MicroscopeColors.ORDER) {
            widest = Math.max(widest, font.width(MicroscopeColors.nameOf(color)));
        }
        return widest;
    }

    private static int widestShortName(Font font) {
        int widest = 0;
        for (DyeColor color : MicroscopeColors.ORDER) {
            widest = Math.max(widest, font.width(MicroscopeColors.shortNameOf(color)));
        }
        return widest;
    }

    /**
     * Measures and places the panel. The compact layout is used when it is configured and also
     * whenever the normal one would run off the screen, which is what a very large GUI scale
     * does to the 16-row list.
     *
     * <p>The fit test budgets for {@value #HINT_RESERVE_LINES} lines of hint even while no hint
     * is showing, so the layout a medic starts a diagnosis in is the one they finish it in — a
     * hint appearing after a minute must not reshuffle every checkbox out from under the
     * cursor.</p>
     */
    private static Panel measure(AbstractContainerScreen<?> screen, String patient,
                                 MicroscopeSession session, Font font) {
        boolean compact = MicroscopeConfig.getInstance().isCompactMode();
        if (!compact) {
            Panel probe = build(screen, patient, session, font, false);
            int reserved = probe.hintLines().isEmpty() ? 4 + HINT_RESERVE_LINES * LINE_HEIGHT : 0;
            if (probe.height() + reserved > screen.height || probe.width() > screen.width) {
                compact = true;
            }
        }
        return build(screen, patient, session, font, compact);
    }

    private static Panel build(AbstractContainerScreen<?> screen, String patient,
                               MicroscopeSession session, Font font, boolean compact) {
        Layout layout = layoutFor(font, compact);
        MicroscopeHints.Hint hint = MicroscopeHints.forSession(session);

        int contentWidth = Math.max(layout.gridWidth(),
                Math.max(Math.min(font.width(patient), layout.headerMaxWidth()),
                        Math.max(font.width(statusText(session)), font.width(layout.resetLabel()))));

        List<FormattedCharSequence> hintLines = List.of();
        if (hint != null) {
            int wrapWidth = Math.max(layout.hintMinWidth(), Math.min(HINT_MAX_WIDTH, contentWidth));
            hintLines = font.split(Component.literal(hint.text()), wrapWidth);
            contentWidth = Math.max(contentWidth, wrapWidth);
        }

        int width = contentWidth + PADDING * 2;
        int gridY = PADDING + LINE_HEIGHT * 2 + 4;
        int belowGrid = gridY + layout.gridHeight();
        int hintHeight = hintLines.isEmpty() ? 0 : 4 + hintLines.size() * LINE_HEIGHT;
        int resetY = belowGrid + hintHeight + 4;
        int height = resetY + LINE_HEIGHT + PADDING - 2;

        ContainerScreenAccessor bounds = (ContainerScreenAccessor) screen;
        int menuLeft = bounds.gmmedic$getLeftPos();
        int menuTop = bounds.gmmedic$getTopPos();
        int menuWidth = bounds.gmmedic$getImageWidth();

        int x = menuLeft + menuWidth + GAP_TO_MENU;
        if (x + width > screen.width) {
            x = menuLeft - GAP_TO_MENU - width;
        }
        x = clamp(x, 0, Math.max(0, screen.width - width));
        int y = clamp(menuTop, 0, Math.max(0, screen.height - height));

        return new Panel(layout, x, y, width, height, x + PADDING, y + gridY, y + resetY,
                hintLines, hint != null ? hint.color() : 0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Top-left corner of the entry at {@code index}, filled column by column. */
    private static int cellX(Panel panel, int index) {
        return panel.gridX() + index / panel.layout().rows() * (panel.layout().cellWidth() + CELL_GAP);
    }

    private static int cellY(Panel panel, int index) {
        return panel.gridY() + index % panel.layout().rows() * panel.layout().cellHeight();
    }

    // --- drawing ---

    private static void render(AbstractContainerScreen<?> screen, String patient,
                               GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!isActive()) return;
        Font font = screen.getFont();
        MicroscopeSession session = MicroscopeDiagnosisManager.getInstance().session(patient);
        Panel panel = measure(screen, patient, session, font);

        graphics.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + panel.height(), COLOR_PANEL);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), COLOR_BORDER);

        int textX = panel.x() + PADDING;
        graphics.text(font, trimToWidth(font, patient, panel.layout().headerMaxWidth()),
                textX, panel.y() + PADDING, COLOR_PATIENT);
        graphics.text(font, statusText(session), textX, panel.y() + PADDING + LINE_HEIGHT,
                remainingMs(session) <= 60_000L ? COLOR_EXPIRING : COLOR_MUTED);
        int separatorY = panel.gridY() - 3;
        graphics.fill(textX, separatorY, panel.x() + panel.width() - PADDING, separatorY + 1, COLOR_SEPARATOR);

        renderEntries(graphics, font, panel, session, mouseX, mouseY);

        int y = panel.gridY() + panel.layout().gridHeight() + 4;
        for (FormattedCharSequence line : panel.hintLines()) {
            graphics.text(font, line, textX, y, panel.hintColor());
            y += LINE_HEIGHT;
        }

        boolean overReset = isOverReset(panel, font, mouseX, mouseY);
        graphics.text(font, panel.layout().resetLabel(), textX, panel.resetY(),
                overReset ? COLOR_PATIENT : COLOR_ACTION);
    }

    private static void renderEntries(GuiGraphicsExtractor graphics, Font font, Panel panel,
                                      MicroscopeSession session, int mouseX, int mouseY) {
        Layout layout = panel.layout();
        for (int index = 0; index < MicroscopeColors.ORDER.size(); index++) {
            DyeColor color = MicroscopeColors.ORDER.get(index);
            boolean checked = session.isChecked(color);
            int x = cellX(panel, index);
            int y = cellY(panel, index);
            boolean hovered = isInside(mouseX, mouseY, x, y, layout.cellWidth(), layout.cellHeight());

            // The cell background carries the state on its own, so the icon layouts never need
            // an overlay drawn on top of an item.
            if (hovered || checked || !layout.checkbox()) {
                int background = hovered ? COLOR_CELL_HOVER : (checked ? COLOR_CELL_CHECKED : COLOR_CELL);
                graphics.fill(x, y, x + layout.cellWidth(), y + layout.cellHeight(), background);
            }
            if (!layout.checkbox() && checked) {
                graphics.outline(x, y, layout.cellWidth(), layout.cellHeight(), COLOR_CHECK);
            }

            int contentX = x;
            if (layout.checkbox()) {
                int boxY = y + (layout.cellHeight() - BOX_SIZE) / 2;
                drawCheckbox(graphics, font, x, boxY, checked);
                contentX = x + BOX_SIZE + BOX_GAP;
            }

            if (layout.icons()) {
                graphics.item(MicroscopeColors.iconOf(color), contentX, y + (layout.cellHeight() - ICON_SIZE) / 2);
            } else {
                String label = layout.shortNames()
                        ? MicroscopeColors.shortNameOf(color)
                        : MicroscopeColors.nameOf(color);
                graphics.text(font, label, contentX, y + (layout.cellHeight() - 8) / 2,
                        MicroscopeColors.textColorOf(color));
            }
        }
    }

    private static void drawCheckbox(GuiGraphicsExtractor graphics, Font font, int x, int y, boolean checked) {
        graphics.fill(x, y, x + BOX_SIZE, y + BOX_SIZE, COLOR_BOX);
        graphics.outline(x, y, BOX_SIZE, BOX_SIZE, checked ? COLOR_CHECK : COLOR_BOX_BORDER);
        if (checked) {
            graphics.text(font, "✔", x + (BOX_SIZE - font.width("✔")) / 2, y + 1, COLOR_CHECK);
        }
    }

    private static String statusText(MicroscopeSession session) {
        return session.checkedCount() + "/" + MicroscopeColors.ORDER.size()
                + " · " + formatRemaining(remainingMs(session));
    }

    private static long remainingMs(MicroscopeSession session) {
        return Math.max(0L, MicroscopeDiagnosisManager.SESSION_TTL_MS - session.getAgeMs());
    }

    private static String formatRemaining(long millis) {
        long seconds = (millis + 999L) / 1000L;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String trimmed = text;
        while (!trimmed.isEmpty() && font.width(trimmed + "…") > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "…";
    }

    // --- input ---

    /**
     * Ticks an entry, or restarts the run. Returns whether the click belonged to the panel; the
     * caller cancels it in that case, so a click on the panel never reaches the container behind
     * it — where a click beside the menu would try to drop whatever the medic is carrying.
     * Every button is swallowed for that reason, but only the left one acts.
     */
    private static boolean handleClick(AbstractContainerScreen<?> screen, String patient, MouseButtonEvent event) {
        if (!isActive()) return false;
        Font font = screen.getFont();
        MicroscopeSession session = MicroscopeDiagnosisManager.getInstance().session(patient);
        Panel panel = measure(screen, patient, session, font);

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (!isInside(mouseX, mouseY, panel.x(), panel.y(), panel.width(), panel.height())) return false;
        if (event.button() != 0) return true;

        if (isOverReset(panel, font, mouseX, mouseY)) {
            MicroscopeDiagnosisManager.getInstance().reset(patient);
            return true;
        }

        Layout layout = panel.layout();
        for (int index = 0; index < MicroscopeColors.ORDER.size(); index++) {
            if (isInside(mouseX, mouseY, cellX(panel, index), cellY(panel, index),
                    layout.cellWidth(), layout.cellHeight())) {
                session.toggle(MicroscopeColors.ORDER.get(index));
                return true;
            }
        }
        // Still swallowed: the click landed on the panel's frame, not on the menu behind it.
        return true;
    }

    private static boolean isOverReset(Panel panel, Font font, int mouseX, int mouseY) {
        return isInside(mouseX, mouseY, panel.x() + PADDING, panel.resetY(),
                font.width(panel.layout().resetLabel()), 8);
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
