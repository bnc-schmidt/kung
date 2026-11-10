package com.github.beng420.kung.feature.hunting;

import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Attribute Menu: its title and the level of each attribute item. The formats follow SkyHanni's repo patterns
 * (AttributeShardsData): the title is "Attribute Menu", with a "(1/3) " prefix on a paged menu. An attribute item
 * is named "Berry Eater IX": the level is a Roman numeral at the end, and an attribute that is not unlocked yet has none
 * ("Nature Elemental", level 0). Only an attribute item has an "Enabled: Yes" line or a "Syphon 3 shards to level up!"
 * (or "... to unlock!") line in its lore, which tells it from the filler and button items of the menu.
 */
final class AttributeLevel {
    /** The highest attribute level. */
    static final int MAX = 10;

    private static final Pattern TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Attribute Menu$");
    private static final Pattern NAME = Pattern.compile("^.+? ([IVXL]+)$");
    private static final Pattern LORE = Pattern.compile("^(?:Enabled: .+|Syphon \\d+ shards? to (?:level up|unlock)!)$");

    private AttributeLevel() { }

    static boolean isMenuTitle(String title) {
        return TITLE.matcher(title.strip()).matches();
    }

    /** The level of the item, 0 to {@link #MAX}; empty for an item that is not an attribute. */
    static OptionalInt of(String name, List<String> lore) {
        if (lore.stream().noneMatch(line -> LORE.matcher(line.strip()).matches())) return OptionalInt.empty();
        Matcher numeral = NAME.matcher(name.strip());
        return OptionalInt.of(numeral.matches() ? roman(numeral.group(1)) : 0);
    }

    /** Whether the number shows on the item: always, or only below {@link #MAX} when {@code onlyBelowMax}. */
    static boolean showsNumber(int level, boolean onlyBelowMax) {
        return !onlyBelowMax || level < MAX;
    }

    /** Whether the slot gets the red background: every attribute that is not at {@link #MAX}. */
    static boolean isRed(int level) {
        return level < MAX;
    }

    /** I, V, X and L with the subtractive pair (IV, IX, XL): a smaller numeral before a larger one counts negative. */
    private static int roman(String numeral) {
        int total = 0;
        for (int index = 0; index < numeral.length(); index++) {
            int value = value(numeral.charAt(index));
            boolean subtractive = index + 1 < numeral.length() && value < value(numeral.charAt(index + 1));
            total += subtractive ? -value : value;
        }
        return total;
    }

    private static int value(char numeral) {
        return switch (numeral) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            default -> 50;
        };
    }
}
