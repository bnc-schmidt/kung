package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses the server's ordered Visitors tab widget, never rendered mod overlays. A full queue reads "Queue Full!". */
final class VisitorQueue {
    // Max Occupancy raises the cap from 5 to 10.
    private static final Pattern HEADER = Pattern.compile("Visitors: \\((10|[0-9])\\)");
    private static final Pattern DURATION = Pattern.compile("(?:(\\d{1,2})m(?: )?)?(?:(\\d{1,2})s)?");

    record Snapshot(Set<String> visitors, Long remainingMillis, boolean full) {
        Snapshot { visitors = Set.copyOf(visitors); }
        int count() { return visitors.size(); }
    }

    static Snapshot parse(List<String> orderedLines) {
        Set<String> visitors = null;
        Long remaining = null;
        boolean full = false;
        for (int i = 0; i < Math.min(orderedLines.size(), 80); i++) {
            String line = clean(orderedLines.get(i));
            var header = HEADER.matcher(line);
            if (header.matches()) {
                if (visitors != null) return null;
                int count = Integer.parseInt(header.group(1));
                visitors = new LinkedHashSet<>();
                for (int j = 1; j <= count; j++) {
                    if (i + j >= orderedLines.size()) return null;
                    String name = clean(orderedLines.get(i + j));
                    // A partial packet batch must not look like a visitor departed.
                    if (name.isBlank() || name.contains(":") || name.length() > 64 || !visitors.add(name)) return null;
                }
            }
            if (line.startsWith("Next Visitor: ")) {
                String value = line.substring("Next Visitor: ".length());
                full = value.equals("Queue Full!");
                remaining = duration(value);
            }
        }
        return visitors == null || (!full && remaining == null) ? null : new Snapshot(visitors, remaining, full);
    }

    static Long duration(String text) {
        var match = DURATION.matcher(text);
        if (!match.matches() || match.group(1) == null && match.group(2) == null) return null;
        long minutes = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
        long seconds = match.group(2) == null ? 0 : Long.parseLong(match.group(2));
        return seconds >= 60 || minutes > 15 ? null : (minutes * 60 + seconds) * 1_000;
    }

    static boolean ownGarden(String instance, List<String> sidebar, List<String> visible) {
        if (sidebar.isEmpty() || !FeastContext.garden(instance, visible)) return false;
        String title = clean(sidebar.getFirst());
        return title.startsWith("SKYBLOCK") && !title.contains("GUEST");
    }

    private static String clean(String text) {
        return HypixelLocation.clean(text.replace('\u00a0', ' ').replace('\u202f', ' '));
    }
}
