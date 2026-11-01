package com.github.beng420.kung.feature.safari;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the Safari HUD shows and how big it is, with no game classes so tests can cover it. The zone the player
 * is in comes first and lists every critter; the others shrink to one count line. "All zones" opens every zone,
 * side by side.
 */
final class SafariLayout {
    static final int COLUMN_WIDTH = 104;
    static final int PADDING = 4;
    static final int HEADER_HEIGHT = 12;
    static final int ROW_HEIGHT = 10;
    static final int SECTION_GAP = 4;

    record Row(String name, boolean found) { }

    /** {@code rows} is empty unless the zone is open. */
    record Section(SafariCritters.Region region, int found, int total, boolean open, List<Row> rows) {
        boolean complete() { return found == total; }
        int height() { return HEADER_HEIGHT + rows.size() * ROW_HEIGHT; }
    }

    private SafariLayout() { }

    /** {@code current} is null before the player has stood in a zone: every zone then shows its count only. */
    static List<Section> sections(SafariCritters.Region current, Set<String> caught, boolean allOpen) {
        List<Section> sections = new ArrayList<>();
        if (current != null && !allOpen) sections.add(section(current, caught, true));
        for (SafariCritters.Region region : SafariCritters.Region.values()) {
            if (current != null && !allOpen && region == current) continue;
            sections.add(section(region, caught, allOpen));
        }
        return List.copyOf(sections);
    }

    private static Section section(SafariCritters.Region region, Set<String> caught, boolean open) {
        List<Row> rows = open
            ? region.critters().stream().map(name -> new Row(name, caught.contains(name))).toList() : List.of();
        return new Section(region, count(region, caught), region.critters().size(), open, rows);
    }

    static int count(SafariCritters.Region region, Set<String> caught) {
        return (int) region.critters().stream().filter(caught::contains).count();
    }

    static int width(List<Section> sections, boolean columns) {
        return columns ? COLUMN_WIDTH * sections.size() : COLUMN_WIDTH;
    }

    static int height(List<Section> sections, boolean columns) {
        int content = 0;
        for (Section section : sections) {
            content = columns ? Math.max(content, section.height()) : content + section.height();
        }
        return PADDING * 2 + content + (columns ? 0 : SECTION_GAP * Math.max(0, sections.size() - 1));
    }
}
