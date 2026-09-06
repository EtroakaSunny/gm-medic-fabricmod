package de.dorikku.gmmedicmod.diagnosis;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The dyes the server's Diagnostik-Leitfaden lists, and the illness each one stands for.
 *
 * <p>The slide holds every one of these; the single dye that is <em>absent</em> is the
 * diagnosis ("Influenza | Kein Roter Farbstoff"). At most one is ever missing, which is what
 * lets {@link MicroscopeHints} tell "the medic has not paged through everything yet" apart from
 * "the medic has the wrong answer".</p>
 *
 * <p>Order, wording and illnesses follow the Leitfaden line for line, so the panel reads the
 * same way as the guide a medic already knows. The guide's "Pinker Farbstoff" is Minecraft's
 * {@code pink_dye}; its other three dyes (magenta, grey and light grey) are not on the guide and
 * never diagnose anything, so they are left out of the checklist entirely — see
 * {@link #colorOf(ItemStack)}.</p>
 */
public final class MicroscopeColors {

    /** The 13 diagnosable dyes, in the Leitfaden's own order. */
    public static final List<DyeColor> ORDER = List.of(
            DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.GREEN,
            DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.CYAN, DyeColor.PINK, DyeColor.PURPLE,
            DyeColor.BROWN, DyeColor.BLACK, DyeColor.WHITE
    );

    /** The Leitfaden's wording, not Minecraft's — "Violett" and "Pink", not "Lila" and "Rosa". */
    private static final Map<DyeColor, String> NAMES = new EnumMap<>(DyeColor.class);
    /** Two letters for the compact layout; the entry is drawn in its own colour anyway. */
    private static final Map<DyeColor, String> SHORT_NAMES = new EnumMap<>(DyeColor.class);
    /** The illness a missing dye diagnoses. */
    private static final Map<DyeColor, String> ILLNESSES = new EnumMap<>(DyeColor.class);
    /**
     * Colour families for the third hint stage. Every family holds at least two dyes on
     * purpose: naming a one-member family would be the same as naming the dye, which is what
     * the last stage is for.
     */
    private static final Map<DyeColor, String> FAMILIES = new EnumMap<>(DyeColor.class);

    private static void entry(DyeColor color, String name, String shortName, String illness, String family) {
        NAMES.put(color, name);
        SHORT_NAMES.put(color, shortName);
        ILLNESSES.put(color, illness);
        FAMILIES.put(color, family);
    }

    static {
        entry(DyeColor.RED,        "Rot",      "RT", "Influenza",          "Rot/Braun");
        entry(DyeColor.ORANGE,     "Orange",   "OR", "Erkältung",          "Orange/Gelb");
        entry(DyeColor.YELLOW,     "Gelb",     "GE", "Reizhusten",         "Orange/Gelb");
        entry(DyeColor.LIME,       "Hellgrün", "HN", "Allergie",           "Grün");
        entry(DyeColor.GREEN,      "Grün",     "GN", "Grippe",             "Grün");
        entry(DyeColor.LIGHT_BLUE, "Hellblau", "HB", "Corona",             "Blau/Türkis");
        entry(DyeColor.BLUE,       "Blau",     "BL", "Norovirus",          "Blau/Türkis");
        entry(DyeColor.CYAN,       "Türkis",   "TK", "Meningitis",         "Blau/Türkis");
        entry(DyeColor.PINK,       "Pink",     "PK", "Eisenmangel",        "Pink/Violett");
        entry(DyeColor.PURPLE,     "Violett",  "VI", "Wassermangel",       "Pink/Violett");
        entry(DyeColor.BROWN,      "Braun",    "BR", "Untergewichtigkeit", "Rot/Braun");
        entry(DyeColor.BLACK,      "Schwarz",  "SW", "Adipositas",         "Schwarz/Weiß");
        entry(DyeColor.WHITE,      "Weiß",     "WS", "Diabetes",           "Schwarz/Weiß");
    }

    private static final Map<DyeColor, ItemStack> ICONS = new EnumMap<>(DyeColor.class);
    /** Every vanilla dye, so {@link #colorOf} recognises one before deciding it is diagnosable. */
    private static final Map<Item, DyeColor> BY_ITEM = new java.util.IdentityHashMap<>();
    /**
     * German colour words longest first, so "Hellblauer Farbstoff" is not read as "Blau".
     * Only used when an item is not one of the vanilla dyes.
     */
    private static final List<Map.Entry<String, DyeColor>> NAME_LOOKUP;

    static {
        for (DyeColor color : ORDER) {
            ICONS.put(color, new ItemStack(dyeItem(color)));
        }
        for (DyeColor color : DyeColor.values()) {
            BY_ITEM.put(dyeItem(color), color);
        }
        NAME_LOOKUP = NAMES.entrySet().stream()
                .map(e -> Map.entry(e.getValue().toLowerCase(java.util.Locale.ROOT), e.getKey()))
                .sorted(Comparator.comparingInt((Map.Entry<String, DyeColor> e) -> e.getKey().length()).reversed())
                .toList();
    }

    private MicroscopeColors() {}

    public static String nameOf(DyeColor color) {
        return NAMES.get(color);
    }

    public static String shortNameOf(DyeColor color) {
        return SHORT_NAMES.get(color);
    }

    /** The illness a missing dye stands for, per the Leitfaden. */
    public static String illnessOf(DyeColor color) {
        return ILLNESSES.get(color);
    }

    public static String familyOf(DyeColor color) {
        return FAMILIES.get(color);
    }

    /**
     * Minecraft's own dye icon for the colour, used by the icon label style. The panel redraws
     * all of these every frame, so they are built once; copy one before handing it anywhere
     * that could change it.
     */
    public static ItemStack iconOf(DyeColor color) {
        return ICONS.get(color);
    }

    /**
     * Which diagnosable dye an item in the microscope is, or {@code null} when it is not a
     * sample at all — the navigation buttons a paged menu puts in its bottom row (player heads,
     * books) must never be mistaken for one.
     *
     * <p>Vanilla dyes are matched by identity, so a server-renamed dye still counts; the display
     * name is only a fallback for a menu that shows its samples as something else. A dye that is
     * not on the guide is not a sample either — magenta, grey and light grey diagnose nothing
     * and are dropped.</p>
     */
    public static DyeColor colorOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        DyeColor dye = BY_ITEM.get(stack.getItem());
        if (dye == null) {
            String name = stripColorCodes(stack.getHoverName().getString()).toLowerCase(java.util.Locale.ROOT);
            for (Map.Entry<String, DyeColor> candidate : NAME_LOOKUP) {
                if (name.startsWith(candidate.getKey())) {
                    dye = candidate.getValue();
                    break;
                }
            }
        }
        return diagnosable(dye);
    }

    private static DyeColor diagnosable(DyeColor dye) {
        return dye != null && NAMES.containsKey(dye) ? dye : null;
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
        return switch (color) {
            case WHITE -> Items.WHITE_DYE;
            case ORANGE -> Items.ORANGE_DYE;
            case MAGENTA -> Items.MAGENTA_DYE;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_DYE;
            case YELLOW -> Items.YELLOW_DYE;
            case LIME -> Items.LIME_DYE;
            case PINK -> Items.PINK_DYE;
            case GRAY -> Items.GRAY_DYE;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_DYE;
            case CYAN -> Items.CYAN_DYE;
            case PURPLE -> Items.PURPLE_DYE;
            case BLUE -> Items.BLUE_DYE;
            case BROWN -> Items.BROWN_DYE;
            case GREEN -> Items.GREEN_DYE;
            case RED -> Items.RED_DYE;
            case BLACK -> Items.BLACK_DYE;
        };
    }

    /** Display names may carry legacy {@code §x} formatting codes. */
    private static String stripColorCodes(String name) {
        return name == null ? "" : name.replaceAll("§.", "").trim();
    }
}
