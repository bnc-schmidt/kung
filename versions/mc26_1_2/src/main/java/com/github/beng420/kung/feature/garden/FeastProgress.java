package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Menu totals establish the baseline; only automatic Seasoning donations advance it. */
public final class FeastProgress {
    public enum Kind {
        HARVEST("Harvest Feast", 5, 250), GRAND("Grand Feast", 9, 750);

        private final String title;
        private final int tiers;
        private final int defaultGoal;

        Kind(String title, int tiers, int defaultGoal) {
            this.title = title;
            this.tiers = tiers;
            this.defaultGoal = defaultGoal;
        }

        public String title() { return title; }
        public int defaultGoal() { return defaultGoal; }

        public static Kind fromTitle(String title) {
            String clean = HypixelLocation.clean(title);
            for (Kind kind : values()) if (kind.title.equals(clean)) return kind;
            return null;
        }
    }

    private static final Pattern SPACES = Pattern.compile("[\\p{Zs}\\t]+");
    private static final Pattern MILESTONE =Pattern.compile(
        "^Feast Milestone ([1-9][0-9]?|I|II|III|IV|V|VI|VII|VIII|IX)$");
    private static final Pattern DONATIONS = Pattern.compile(
        "(?<![\\p{L}\\p{N},/.-])([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)\\s+Donations$");
    private static final Pattern SEASONING = Pattern.compile(
        "^(?:\\[\\d{2}:\\d{2}:\\d{2}\\] )?RARE CROP! (?:(\\d{1,4})x )?Seasoning(?: x(\\d{1,4}))? "
            + "\\(\\+[0-9][0-9.,]*\\s*(?:%|\uE02B)\\) \\(automatically donated!?\\)$");

    private Kind kind;
    private List<Integer> goals = List.of();
    private int donations;

    public record MenuItem(String name, List<String> lore) {
        public MenuItem { lore = List.copyOf(lore); }
    }

    public record MenuRead(Snapshot snapshot, String reason) {}

    public record Snapshot(Kind kind, int donations, List<Integer> goals) {
        public Snapshot { goals = List.copyOf(goals); }
        public int goal() { return goals.getLast(); }
        public int toNext() {
            for (int goal : goals) if (goal > donations) return goal - donations;
            return 0;
        }
        public boolean complete() { return donations >= goal(); }
    }

    public Snapshot snapshot() {
        return kind == null ? null : new Snapshot(kind, donations, goals);
    }

    public void reset() {
        kind = null;
        goals = List.of();
        donations = 0;
    }

    public void synchronize(Snapshot menu) {
        kind = menu.kind();
        goals = menu.goals();
        donations = menu.donations();
    }

    public boolean donate(String message) {
        int amount = donationAmount(message);
        if (kind == null || amount == 0) return false;
        donations = Math.min(goals.getLast(), donations + amount);
        return true;
    }

    static int donationAmount(String message) {
        var matcher = SEASONING.matcher(HypixelLocation.clean(message));
        if (!matcher.matches() || matcher.group(1) != null && matcher.group(2) != null) return 0;
        String amount = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return amount == null ? 1 : Integer.parseInt(amount);
    }

    public static MenuRead inspectMenu(String title, List<MenuItem> items) {
        Kind kind = Kind.fromTitle(title);
        if (kind == null || items.size() > 54) return new MenuRead(null, "unrelated-or-oversized-menu");
        var tiers = new TreeMap<Integer, int[]>();
        List<Integer> missingProgress = new ArrayList<>();
        for (MenuItem item : items) {
            int tier = milestoneTier(item.name());
            if (tier == 0) continue;
            if (tier > kind.tiers || tiers.containsKey(tier) || missingProgress.contains(tier)) {
                return new MenuRead(null, "unexpected-or-duplicate-tier:" + tier);
            }
            for (String raw : item.lore()) {
                var line = DONATIONS.matcher(cleanMenuText(raw));
                if (!line.find()) continue;
                int current = number(line.group(1));
                int goal = number(line.group(2));
                if (current < 0 || goal <= 0 || current > goal) {
                    return new MenuRead(null, "invalid-donations:tier=" + tier);
                }
                tiers.put(tier, new int[] {current, goal});
                break;
            }
            if (!tiers.containsKey(tier)) missingProgress.add(tier);
        }
        // Wait for the complete menu: packet batches can temporarily mix old and new totals.
        if (tiers.size() != kind.tiers) return new MenuRead(null,
            "incomplete-tiers:" + tiers.size() + "/" + kind.tiers + " missing-progress=" + missingProgress);
        int total = tiers.values().stream().mapToInt(tier -> tier[0]).max().orElse(0);
        List<Integer> goals = new ArrayList<>();
        int previous = 0;
        for (int index = 1; index <= kind.tiers; index++) {
            int[] tier = tiers.get(index);
            if (tier == null || tier[1] <= previous || tier[0] != Math.min(total, tier[1])) {
                return new MenuRead(null, "inconsistent-progress:tier=" + index + " total=" + total);
            }
            goals.add(tier[1]);
            previous = tier[1];
        }
        return new MenuRead(new Snapshot(kind, total, goals), "synchronized");
    }

    static int milestoneTier(String name) {
        var matcher = MILESTONE.matcher(cleanMenuText(name));
        if (!matcher.matches()) return 0;
        return switch (matcher.group(1)) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            default -> Integer.parseInt(matcher.group(1));
        };
    }

    static boolean completedMilestone(MenuItem item) {
        for (String raw : item.lore().stream().limit(40).toList()) {
            var line = DONATIONS.matcher(cleanMenuText(raw));
            if (line.find()) {
                int goal = number(line.group(2));
                return goal > 0 && number(line.group(1)) == goal;
            }
        }
        return false;
    }

    static String cleanMenuText(String text) {
        return HypixelLocation.clean(text == null ? "" : SPACES.matcher(text).replaceAll(" "));
    }

    private static int number(String value) {
        try {
            int number = Integer.parseInt(value.replace(",", ""));
            return number <= 1_000_000 ? number : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
