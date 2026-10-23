package com.github.beng420.kung.feature.misc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Gemstones Sack keeps every quality of a gem in one item, while the [Sacks] messages count them one by one
 * ("+311 Flawed Onyx Gemstone"). Without a filter the item lists every quality (" Flawed: 10,919 (873,520)") and
 * "Stored:" is their total in Rough gems (a Flawed is 80, a Fine 6,400); the sack's filter button picks one quality
 * to show instead, as " Amount: 604".
 */
final class SackGemstones {
    static final String SACK_TITLE = "Gemstones Sack";
    static final String DEFAULT_QUALITY = "Rough";
    private static final List<String> QUALITIES = List.of("Rough", "Flawed", "Fine");
    /** "Onyx Gemstones", "Flawed Onyx Gemstone", with the gem's symbol in front: "☠ Flawed Onyx Gemstone". */
    private static final Pattern NAME = Pattern.compile("^(?:(\\S) )?(?:(Rough|Flawed|Fine) )?([A-Za-z]+) Gemstones?$");
    private static final Pattern FILTER = Pattern.compile("^\\s*▶ (No filter|Rough|Flawed|Fine)\\s*$");
    private static final Pattern COUNT = Pattern.compile("^\\s*([A-Za-z]+): ([\\d.,]+[kKmMbB]?)(?: \\(.*\\))?\\s*$");

    private SackGemstones() { }

    /** A gem item's name: the symbol it starts with ("" when none), its quality (null when it names the gem alone), the gem. */
    record Gem(String symbol, String quality, String gem) {
        /** The name the [Sacks] messages use for one quality of this gem. */
        String trackedName(String quality) {
            return (symbol.isEmpty() ? "" : symbol + " ") + quality + " " + gem + " Gemstone";
        }
    }

    static boolean isGemstoneSack(String title) { return SACK_TITLE.equals(title); }

    /** The gem named by an item, or null for anything else in the sack. */
    static Gem parseName(String plainName) {
        Matcher matcher = NAME.matcher(plainName == null ? "" : plainName.strip());
        return matcher.matches() ? new Gem(matcher.group(1) == null ? "" : matcher.group(1), matcher.group(2), matcher.group(3)) : null;
    }

    /** The quality the sack's filter button has selected, null for "No filter" or when the lines have no filter. */
    static String filterQuality(List<String> lore) {
        for (String line : lore) {
            Matcher matcher = FILTER.matcher(line);
            if (matcher.matches()) return QUALITIES.contains(matcher.group(1)) ? matcher.group(1) : null;
        }
        return null;
    }

    /** The count of each quality an item shows; "Amount" belongs to the filtered quality (Rough without a filter). */
    static Map<String, Long> amounts(List<String> lore, String filter) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (String line : lore) {
            Matcher matcher = COUNT.matcher(line);
            if (!matcher.matches()) continue;
            String quality = matcher.group(1).equals("Amount") ? filter == null ? DEFAULT_QUALITY : filter : matcher.group(1);
            Long amount = SackTrackerFeature.parseAmount(matcher.group(2));
            if (QUALITIES.contains(quality) && amount != null) amounts.putIfAbsent(quality, amount);
        }
        return amounts;
    }

    /** How many Rough gems one gem of this quality is worth. */
    static long multiplier(String quality) {
        return switch (quality) {
            case "Flawed" -> 80L;
            case "Fine" -> 6_400L;
            default -> 1L;
        };
    }

    /** The total of an item's listed qualities in Rough gems, for an item without a "Stored:" line. */
    static long roughTotal(Map<String, Long> amounts) {
        return amounts.entrySet().stream().mapToLong(entry -> entry.getValue() * multiplier(entry.getKey())).sum();
    }

    /** What the track key tracks over this item once a quality is chosen: its own, else the sack filter. */
    static String trackedQuality(Gem gem, String filter) {
        return gem.quality() != null ? gem.quality() : filter != null ? filter : DEFAULT_QUALITY;
    }
}
