package com.github.beng420.kung.feature.dungeon;

import java.util.regex.Pattern;

/** Shared tab counts and percentages describe the party, never an individual player. */
final class DungeonSecretCounts {
    private static final Pattern PARTY_FOUND = Pattern.compile("^Secrets Found: ([\\d,]+)$");
    private static final Pattern PERCENT = Pattern.compile("^Secrets Found: (\\d+(?:\\.\\d+)?)%$");
    private static final Pattern PARTY = Pattern.compile("^Secrets?: (\\d+)/(\\d+|\\?)(?: \\(Total: (\\d+)\\))?$");

    private DungeonSecretCounts() { }

    static int partyFound(String raw) {
        var match = PARTY_FOUND.matcher(DungeonLifecycleSignals.clean(raw));
        return match.matches() ? Integer.parseInt(match.group(1).replace(",", "")) : -1;
    }

    static double percent(String raw) {
        var match = PERCENT.matcher(DungeonLifecycleSignals.clean(raw));
        if (!match.matches()) return -1;
        double value = Double.parseDouble(match.group(1));
        return value <= 100 ? value : -1;
    }

    static Fraction party(String raw) {
        var match = PARTY.matcher(DungeonLifecycleSignals.clean(raw));
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
}
