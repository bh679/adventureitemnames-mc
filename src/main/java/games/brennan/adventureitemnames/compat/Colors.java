package games.brennan.adventureitemnames.compat;

import net.minecraft.ChatFormatting;

import java.util.Locale;
import java.util.Map;

/**
 * Version-agnostic colour-name lookup. MC 26.x dropped {@code ChatFormatting.getByName}
 * and {@code ChatFormatting.isColor()} (only {@code getByCode(char)} remains), so the
 * pre-26 path no longer compiles. AIN only ever accepts the 16 named <em>colours</em>
 * (not formats like {@code bold}), so we resolve them from a small static map that works
 * identically on every MC version — no preprocessor branch needed.
 */
public final class Colors {

    /** The 16 vanilla text colours, by lower-case name. */
    private static final Map<String, ChatFormatting> BY_NAME = Map.ofEntries(
        Map.entry("black", ChatFormatting.BLACK),
        Map.entry("dark_blue", ChatFormatting.DARK_BLUE),
        Map.entry("dark_green", ChatFormatting.DARK_GREEN),
        Map.entry("dark_aqua", ChatFormatting.DARK_AQUA),
        Map.entry("dark_red", ChatFormatting.DARK_RED),
        Map.entry("dark_purple", ChatFormatting.DARK_PURPLE),
        Map.entry("gold", ChatFormatting.GOLD),
        Map.entry("gray", ChatFormatting.GRAY),
        Map.entry("dark_gray", ChatFormatting.DARK_GRAY),
        Map.entry("blue", ChatFormatting.BLUE),
        Map.entry("green", ChatFormatting.GREEN),
        Map.entry("aqua", ChatFormatting.AQUA),
        Map.entry("red", ChatFormatting.RED),
        Map.entry("light_purple", ChatFormatting.LIGHT_PURPLE),
        Map.entry("yellow", ChatFormatting.YELLOW),
        Map.entry("white", ChatFormatting.WHITE)
    );

    private Colors() {}

    /** Resolve a colour by name (case-insensitive). Returns {@code null} for unknown names
     *  or non-colour formats — mirrors the old {@code getByName(...)} + {@code isColor()} gate. */
    public static ChatFormatting byName(String name) {
        if (name == null) return null;
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code c} is one of the 16 named colours (replaces {@code ChatFormatting.isColor()}). */
    public static boolean isColor(ChatFormatting c) {
        return c != null && BY_NAME.containsValue(c);
    }
}
