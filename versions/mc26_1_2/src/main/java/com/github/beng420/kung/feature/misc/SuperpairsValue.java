package com.github.beng420.kung.feature.misc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How much a Superpairs card is worth, so the best pairs are claimed first while clicks are limited. */
public final class SuperpairsValue {
    private static final Pattern ENCHANTMENT = Pattern.compile("[A-Za-z][A-Za-z '\\-]* ([IVXLCDM]+)(?: x\\d+)?");
    private static final Pattern ENCHANTING_XP = Pattern.compile("([\\d,.]+)([kKmM]?) Enchanting Exp(?: x\\d+)?");
    /** Enchantments of this level and up are the "T7" cards the player wants first. */
    static final int PRIORITY_LEVEL = 7;

    private SuperpairsValue() { }

    public static boolean isTitanic(String label) {
        return label.contains("Titanic Experience Bottle");
    }

    /** The level of an enchantment card ("Growth VII" is 7), 0 for every other card. */
    public static int enchantLevel(String label) {
        Matcher matcher = ENCHANTMENT.matcher(label);
        return matcher.matches() ? roman(matcher.group(1)) : 0;
    }

    /** Titanic bottles and level 7 (or higher) enchantments: claimed first, and the target of an Instant Find. */
    public static boolean isPriority(String label) {
        return isTitanic(label) || enchantLevel(label) >= PRIORITY_LEVEL;
    }

    /** Higher is better: Titanic, then T7 enchantments, other enchantments, bottles, then Enchanting XP by amount. */
    public static int value(String label) {
        if (isTitanic(label)) return 10_000;
        int level = enchantLevel(label);
        if (level >= PRIORITY_LEVEL) return 9_000 + level;
        if (level > 0) return 5_000 + level * 100;
        if (label.contains("Grand Experience Bottle")) return 3_000;
        if (label.contains("Experience Bottle")) return 2_000;
        Matcher xp = ENCHANTING_XP.matcher(label);
        if (xp.matches()) {
            try {
                double amount = Double.parseDouble(xp.group(1).replace(",", ""));
                String unit = xp.group(2).toLowerCase(Locale.ROOT);
                // In thousands, so 150k XP is 150 and stays below every bottle.
                return (int) Math.min(1_999, Math.round(unit.equals("m") ? amount * 1_000 : unit.equals("k") ? amount : amount / 1_000));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static int roman(String numerals) {
        int total = 0;
        int previous = 0;
        for (int index = numerals.length() - 1; index >= 0; index--) {
            int digit = switch (numerals.charAt(index)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            total += digit < previous ? -digit : digit;
            previous = Math.max(previous, digit);
        }
        return total;
    }
}
