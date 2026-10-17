package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Crop names as Sky Mutations spells them; layouts may still carry names this list lacks. */
final class GreenhouseCrops {
    static final List<String> NAMES = List.of(
        "Ashwreath", "Choconut", "Dustgrain", "Gloomgourd", "Lonelily", "Scourroot", "Shadevine", "Veilshroom",
        "Witherbloom", "Chocoberry", "Cindershade", "Coalroot", "Creambloom", "Duskbloom", "Thornshade",
        "Blastberry", "Cheesebite", "Chloronite", "Do-not-eat-shroom", "Fleshtrap", "Magic Jellybean",
        "Noctilume", "Snoozling", "Soggybud", "Chorus Fruit", "PlantBoy Advance", "Puffercloud", "Shellfruit",
        "Startlevine", "Stoplight Petal", "Thunderling", "Turtlellini", "Zombud", "All-in Aloe", "Devourer",
        "Glasscorn", "Godseed", "Jerryflower", "Phantomleaf", "Timestalk", "Cocoa Beans", "Wild Rose",
        "Moonflower", "Sunflower", "Cactus", "Melon Seeds", "Pumpkin Seeds", "Brown Mushroom", "Red Mushroom",
        "Wheat Seeds", "Carrot", "Potato", "Nether Wart", "Sugar Cane", "Dead Plants", "Fire", "Helianthus",
        "Fermento", "Squash", "Cropie",
        // Only the site knows these.
        "Rose Dragon Egg", "Ethereal Vine", "Jerryseed", "Fertilized Jerryseed", "Condensed Helianthus");

    /** Patterns are compiled once: the grid builds a Cell per crop and the screens call these per frame. */
    private static final Pattern SEPARATORS = Pattern.compile("[-_\\s]+");
    private static final Set<String> KNOWN = Set.copyOf(NAMES);
    /** Normalized spelling of each entry of {@link #NAMES}, in the same order. */
    private static final List<String> NORMALIZED;
    /** Normalized spelling to the site's spelling, so a lookup is one hash probe instead of a scan. */
    private static final Map<String, String> BY_NORMALIZED = new HashMap<>();

    static {
        NORMALIZED = NAMES.stream().map(GreenhouseCrops::normalize).toList();
        for (int i = 0; i < NAMES.size(); i++) BY_NORMALIZED.putIfAbsent(NORMALIZED.get(i), NAMES.get(i));
    }

    private GreenhouseCrops() { }

    /** Case-insensitive, and hyphens, underscores and runs of spaces all count as one space. */
    static String normalize(String name) {
        return SEPARATORS.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    /** The site's spelling for a known crop, otherwise the name untouched. */
    static String canonical(String name) {
        if (KNOWN.contains(name)) return name;
        String known = BY_NORMALIZED.get(normalize(name));
        return known == null ? name : known;
    }

    /** Known crops whose name contains the query, in list order; a blank query matches all. */
    static List<String> search(String query) {
        String wanted = normalize(query);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < NAMES.size(); i++) {
            if (NORMALIZED.get(i).contains(wanted)) found.add(NAMES.get(i));
        }
        return List.copyOf(found);
    }

    /**
     * Up to three letters for a grid cell: word initials for multi-word names, else the first letters.
     * Never throws: a name with no letters ("-", "_", "") is shown as "?".
     */
    static String abbreviation(String name) {
        // split drops trailing empty strings, so "-" or "_" give no word at all.
        String[] words = SEPARATORS.split(name.trim());
        if (words.length == 0) return "?";
        StringBuilder out = new StringBuilder();
        if (words.length > 1) {
            for (int i = 0; i < words.length && out.length() < 3; i++) {
                if (!words[i].isEmpty()) out.append(Character.toUpperCase(words[i].charAt(0)));
            }
        } else {
            // Whole code points, so a surrogate pair is never cut in half.
            int end = words[0].offsetByCodePoints(0, Math.min(3, words[0].codePointCount(0, words[0].length())));
            out.append(words[0], 0, end);
        }
        return out.toString();
    }
}
