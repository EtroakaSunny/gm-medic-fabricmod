package de.dorikku.gmmedicmod.diagnosis;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The 16 Minecraft dye colours as the checklist presents them: display order, German names,
 * a two-letter short form for the compact layout, a readable text colour, the dye item used
 * as the icon, and the coarse colour family a stage-3 hint narrows down to.
 *
 * <p>Also holds {@link #colorOf(ItemStack)}, which turns a slot's item back into the colour it
 * stands for. Only the 16 vanilla dyes and items literally named after a colour count — the
 * navigation buttons a paged menu puts in its bottom row (player heads, books) must never be
 * mistaken for a sample.</p>
 */
public final class MicroscopeColors {

    /**
     * Display order: the spectrum first, neutrals last. Deliberately not
     * {@link DyeColor#values()}, whose order is the historic wool-metadata one and reads as
     * random on screen.
     */
    public static final List<DyeColor> ORDER = List.of(
            DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME,
            DyeColor.GREEN, DyeColor.CYAN, DyeColor.LIGHT_BLUE, DyeColor.BLUE,
            DyeColor.PURPLE, DyeColor.MAGENTA, DyeColor.PINK, DyeColor.BROWN,
            DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BLACK
    );

    private static final Map<DyeColor, String> NAMES = Map.ofEntries(
            Map.entry(DyeColor.WHITE, "Weiß"),
            Map.entry(DyeColor.ORANGE, "Orange"),
            Map.entry(DyeColor.MAGENTA, "Magenta"),
            Map.entry(DyeColor.LIGHT_BLUE, "Hellblau"),
            Map.entry(DyeColor.YELLOW, "Gelb"),
            Map.entry(DyeColor.LIME, "Hellgrün"),
            Map.entry(DyeColor.PINK, "Rosa"),
            Map.entry(DyeColor.GRAY, "Grau"),
            Map.entry(DyeColor.LIGHT_GRAY, "Hellgrau"),
            Map.entry(DyeColor.CYAN, "Türkis"),
            Map.entry(DyeColor.PURPLE, "Lila"),
            Map.entry(DyeColor.BLUE, "Blau"),
            Map.entry(DyeColor.BROWN, "Braun"),
            Map.entry(DyeColor.GREEN, "Grün"),
            Map.entry(DyeColor.RED, "Rot"),
            Map.entry(DyeColor.BLACK, "Schwarz")
    );

    /** Two letters for the compact text layout; the entry is drawn in its own colour anyway. */
    private static final Map<DyeColor, String> SHORT_NAMES = Map.ofEntries(
            Map.entry(DyeColor.WHITE, "WS"),
            Map.entry(DyeColor.ORANGE, "OR"),
            Map.entry(DyeColor.MAGENTA, "MG"),
            Map.entry(DyeColor.LIGHT_BLUE, "HB"),
            Map.entry(DyeColor.YELLOW, "GE"),
            Map.entry(DyeColor.LIME, "HN"),
            Map.entry(DyeColor.PINK, "RS"),
            Map.entry(DyeColor.GRAY, "GA"),
            Map.entry(DyeColor.LIGHT_GRAY, "HA"),
            Map.entry(DyeColor.CYAN, "TK"),
            Map.entry(DyeColor.PURPLE, "LI"),
            Map.entry(DyeColor.BLUE, "BL"),
            Map.entry(DyeColor.BROWN, "BR"),
            Map.entry(DyeColor.GREEN, "GN"),
            Map.entry(DyeColor.RED, "RT"),
            Map.entry(DyeColor.BLACK, "SW")
    );

    /**
     * Colour families for the third hint stage. Every family holds at least two colours on
     * purpose: naming a one-member family would be the same as naming the colour, which is
     * what the last stage is for.
     */
    private static final Map<DyeColor, String> FAMILIES = Map.ofEntries(
            Map.entry(DyeColor.RED, "Rot/Braun"),
            Map.entry(DyeColor.BROWN, "Rot/Braun"),
            Map.entry(DyeColor.ORANGE, "Orange/Gelb"),
            Map.entry(DyeColor.YELLOW, "Orange/Gelb"),
            Map.entry(DyeColor.LIME, "Grün"),
            Map.entry(DyeColor.GREEN, "Grün"),
            Map.entry(DyeColor.CYAN, "Blau/Türkis"),
            Map.entry(DyeColor.LIGHT_BLUE, "Blau/Türkis"),
            Map.entry(DyeColor.BLUE, "Blau/Türkis"),
            Map.entry(DyeColor.PURPLE, "Violett/Rosa"),
            Map.entry(DyeColor.MAGENTA, "Violett/Rosa"),
            Map.entry(DyeColor.PINK, "Violett/Rosa"),
            Map.entry(DyeColor.WHITE, "Grautöne"),
            Map.entry(DyeColor.LIGHT_GRAY, "Grautöne"),
            Map.entry(DyeColor.GRAY, "Grautöne"),
            Map.entry(DyeColor.BLACK, "Grautöne")
    );

    private static final Map<DyeColor, ItemStack> ICONS = new EnumMap<>(DyeColor.class);

    static {
        for (DyeColor color : ORDER) {
            ICONS.put(color, new ItemStack(dyeItem(color)));
        }
    }

    private MicroscopeColors() {}

    public static String nameOf(DyeColor color) {
        return NAMES.get(color);
    }

    public static String shortNameOf(DyeColor color) {
        return SHORT_NAMES.get(color);
    }

    public static String familyOf(DyeColor color) {
        return FAMILIES.get(color);
    }

    /**
     * Minecraft's own dye icon for the colour, used by the icon label style. The panel redraws
     * all 16 of these every frame, so they are built once; copy one before handing it anywhere
     * that could change it.
     */
    public static ItemStack iconOf(DyeColor color) {
        return ICONS.get(color);
    }

    /**
     * Which colour an item in the microscope stands for, or {@code null} when it is not a
     * sample at all.
     *
     * <p>One of the 16 vanilla dye items is the normal case; they are matched by identity so a
     * server-renamed dye is still recognised. As a fallback the item's display name is accepted
     * when it is exactly a colour's German or English name — a menu that shows its samples as
     * something other than dyes still works, while a "Weiter"/"Zurück" navigation button never
     * matches.</p>
     */
    public static DyeColor colorOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        for (DyeColor color : ORDER) {
            if (dyeItem(color) == item) return color;
        }
        String name = stripColorCodes(stack.getHoverName().getString());
        if (name.isEmpty()) return null;
        for (DyeColor color : ORDER) {
            if (name.equalsIgnoreCase(NAMES.get(color)) || name.equalsIgnoreCase(color.getName())) {
                return color;
            }
        }
        return null;
    }

    /**
     * A brighter variant of {@link DyeColor#getTextColor()} for very dark colours, so blue,
     * brown, purple and black stay legible on the panel's dark background. Returns an opaque
     * ARGB value.
     */
    public static int textColorOf(DyeColor color) {
        int rgb = color.getTextColor();
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        if (luminance <= 0 || luminance >= MIN_TEXT_LUMINANCE) {
            return 0xFF000000 | rgb;
        }
        // Scale the channels instead of blending towards white: blending brightens a colour by
        // draining it, and a washed-out blue, purple and brown all end up the same pale grey.
        // Scaling keeps the hue that names the entry. A channel that reaches 255 simply stops
        // there — red is already legible without turning pink.
        r = Math.min(255, r * MIN_TEXT_LUMINANCE / luminance);
        g = Math.min(255, g * MIN_TEXT_LUMINANCE / luminance);
        b = Math.min(255, b * MIN_TEXT_LUMINANCE / luminance);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * Kept just under grey's own brightness, so lifting black does not make "Schwarz" and
     * "Grau" the same colour.
     */
    private static final int MIN_TEXT_LUMINANCE = 105;

    private static Item dyeItem(DyeColor color) {
        return Items.DYE.pick(color);
    }

    /** Display names may carry legacy {@code §x} formatting codes. */
    private static String stripColorCodes(String name) {
        return name == null ? "" : name.replaceAll("§.", "").trim();
    }
}
