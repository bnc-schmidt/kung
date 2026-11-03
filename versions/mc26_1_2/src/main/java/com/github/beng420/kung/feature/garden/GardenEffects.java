package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the plot, its spray and the Pesthunter bonus (the Farming Fortune buff) from the sidebar and the Pests
 * tab widget. Patterns follow SkyHanni 9.2.0's; a missing or unreadable row is unknown, never "no effect".
 */
final class GardenEffects {
    private static final Pattern PLOT = Pattern.compile("^[^A-Za-z0-9]*Plot - (.+)$");
    private static final Pattern PESTS_HEADER = Pattern.compile("^Pests:.*$");
    private static final Pattern SPRAY = Pattern.compile("^Spray: ([\\w ]+?) *(?:\\(.*\\))?$");
    private static final Pattern BONUS = Pattern.compile("^Bonus: (?:(INACTIVE)|\\+(\\d+)[^\\s\\d]* .*)$");

    /** @param plot the garden plot the player stands in; null in the Barn, the Greenhouse or when no row says it */
    record Reading(String plot, Boolean sprayed, Boolean ffBuff) {}

    static Reading read(List<String> sidebar, List<String> tab) {
        String plot = plot(sidebar);
        if (plot == null) plot = plot(tab);
        boolean pests = tab.stream().map(GardenEffects::clean).anyMatch(line -> PESTS_HEADER.matcher(line).matches());
        Boolean sprayed = null;
        Boolean ffBuff = null;
        if (pests) {
            for (String raw : tab) {
                String line = clean(raw);
                var spray = SPRAY.matcher(line);
                if (spray.matches()) sprayed = !spray.group(1).equalsIgnoreCase("None");
                var bonus = BONUS.matcher(line);
                if (bonus.matches()) ffBuff = bonus.group(1) == null;
            }
        }
        return new Reading(plot, sprayed, ffBuff);
    }

    private static String plot(List<String> lines) {
        for (String raw : lines) {
            var match = PLOT.matcher(clean(raw));
            if (match.matches()) return match.group(1);
        }
        return null;
    }

    private static String clean(String text) {
        return HypixelLocation.clean(text.replace(' ', ' ').replace(' ', ' '));
    }
}
