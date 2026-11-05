package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.regex.Pattern;

/** Shared tab counts and percentages describe the party, never an individual player. */
final class DungeonSecretCounts {
    private static final Pattern PARTY_FOUND = Pattern.compile("^Secrets Found: ([\\d,]+)$");
    private static final Pattern PERCENT = Pattern.compile("^Secrets Found: (\\d+(?:\\.\\d+)?)%$");
    private static final Pattern PARTY = Pattern.compile("^Secrets?: (\\d+)/(\\d+|\\?)(?: \\(Total: (\\d+)\\))?$");

    private DungeonSecretCounts() { }

    static int partyFound(String raw) {
        var match = PARTY_FOUND.matcher(HypixelLocation.clean(raw));
        return match.matches() ? Integer.parseInt(match.group(1).replace(",", "")) : -1;
    }

    static double percent(String raw) {
        var match = PERCENT.matcher(HypixelLocation.clean(raw));
        if (!match.matches()) return -1;
        double value = Double.parseDouble(match.group(1));
        return value <= 100 ? value : -1;
    }

    static Fraction party(String raw) {
        var match = PARTY.matcher(HypixelLocation.clean(raw));
        if (!match.matches()) return null;
        int found = Integer.parseInt(match.group(1));
        int total = match.group(2).equals("?") ? -1 : Integer.parseInt(match.group(2));
        if (match.group(3) != null) total = Integer.parseInt(match.group(3));
        return total >= 0 && found > total ? null : new Fraction(found, total);
    }

    static int fromPercent(double percent, int total) {
        if (percent < 0 || percent > 100 || total <= 0) return -1;
        int found = (int) Math.round(total * percent / 100.0);
        // A catalogue guess that cannot produce the displayed one-decimal percentage is not a count.
        return Math.abs(found * 100.0 / total - percent) <= 0.050001 ? found : -1;
    }

    record Fraction(int found, int total) { }

    /**
     * The secret totals whose found/total the tab shows as this one-decimal percentage. A single reading leaves
     * a few (1 found at 2.4% fits 41 and 42); the next reading narrows it (2 found at 4.9% fits only 41).
     */
    record TotalRange(int min, int max) {
        static final TotalRange ANY = new TotalRange(1, 250);

        static TotalRange of(int found, double percent) {
            // Hypixel shows one decimal, so any total within half a step of the shown percentage fits.
            if (found <= 0 || percent < 0.1 || percent > 100.0) return new TotalRange(1, 0);
            int min = (int) Math.ceil(100.0 * found / (percent + 0.05) - 1e-9);
            int max = (int) Math.floor(100.0 * found / (percent - 0.05) + 1e-9);
            return new TotalRange(Math.max(min, found), Math.min(max, ANY.max));
        }

        boolean isEmpty() { return min > max; }

        boolean contains(int total) { return total >= min && total <= max; }

        TotalRange intersect(TotalRange other) {
            return new TotalRange(Math.max(min, other.min), Math.min(max, other.max));
        }

        int closestTo(double ideal) { return Math.clamp(Math.round(ideal), min, max); }
    }
}
