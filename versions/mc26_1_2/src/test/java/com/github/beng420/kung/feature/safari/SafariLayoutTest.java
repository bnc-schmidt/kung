package com.github.beng420.kung.feature.safari;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.safari.SafariCritters.Region;
import com.github.beng420.kung.feature.safari.SafariLayout.Section;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class SafariLayoutTest {
    private static final Set<String> CAUGHT = Set.of("Tepid", "Mantis Shrimp", "Foxtrot");

    @Test
    public void theCurrentZoneComesFirstAndAloneListsItsCritters() {
        List<Section> sections = SafariLayout.sections(Region.ICY, CAUGHT, false);
        assertEquals(List.of(Region.ICY, Region.FOREST, Region.CAVERN, Region.HAUNTED),
            sections.stream().map(Section::region).toList());
        assertTrue(sections.get(0).open());
        assertEquals(9, sections.get(0).rows().size());
        for (Section other : sections.subList(1, 4)) {
            assertFalse(other.open());
            assertTrue(other.rows().isEmpty());
        }
    }

    @Test
    public void rowsKeepTheZoneOrderAndMarkWhatWasCaught() {
        Section icy = SafariLayout.sections(Region.ICY, CAUGHT, false).get(0);
        assertEquals(Region.ICY.critters(), icy.rows().stream().map(SafariLayout.Row::name).toList());
        assertEquals(2, icy.found());
        assertEquals(9, icy.total());
        assertTrue(icy.rows().get(1).found());
        assertFalse(icy.rows().get(0).found());
    }

    @Test
    public void everyZoneShowsItsCountEvenWhenCollapsed() {
        List<Section> sections = SafariLayout.sections(Region.ICY, CAUGHT, false);
        assertEquals(1, sections.get(1).found());
        assertEquals(9, sections.get(1).total());
        assertEquals(10, sections.get(3).total());
        assertFalse(sections.get(1).complete());
    }

    @Test
    public void aZoneIsCompleteWhenEveryCritterIsCaught() {
        Section haunted = SafariLayout.sections(Region.HAUNTED, Set.copyOf(Region.HAUNTED.critters()), false).get(0);
        assertTrue(haunted.complete());
        assertEquals(10, haunted.found());
    }

    @Test
    public void withoutAZoneEveryZoneIsCollapsedInTheFixedOrder() {
        List<Section> sections = SafariLayout.sections(null, CAUGHT, false);
        assertEquals(List.of(Region.FOREST, Region.CAVERN, Region.ICY, Region.HAUNTED),
            sections.stream().map(Section::region).toList());
        assertTrue(sections.stream().noneMatch(Section::open));
    }

    @Test
    public void allZonesOpenKeepsTheFixedOrderWhateverTheCurrentZone() {
        List<Section> sections = SafariLayout.sections(Region.HAUNTED, CAUGHT, true);
        assertEquals(List.of(Region.FOREST, Region.CAVERN, Region.ICY, Region.HAUNTED),
            sections.stream().map(Section::region).toList());
        assertTrue(sections.stream().allMatch(Section::open));
        assertEquals(10, sections.get(3).rows().size());
    }

    @Test
    public void stackedSizeAddsTheSectionsAndColumnSizeTakesTheTallest() {
        List<Section> stacked = SafariLayout.sections(Region.ICY, CAUGHT, false);
        int open = SafariLayout.HEADER_HEIGHT + 9 * SafariLayout.ROW_HEIGHT;
        assertEquals(SafariLayout.COLUMN_WIDTH, SafariLayout.width(stacked, false));
        assertEquals(SafariLayout.PADDING * 2 + open + 3 * SafariLayout.HEADER_HEIGHT + 3 * SafariLayout.SECTION_GAP,
            SafariLayout.height(stacked, false));

        List<Section> columns = SafariLayout.sections(Region.ICY, CAUGHT, true);
        assertEquals(SafariLayout.COLUMN_WIDTH * 4, SafariLayout.width(columns, true));
        assertEquals(SafariLayout.PADDING * 2 + SafariLayout.HEADER_HEIGHT + 10 * SafariLayout.ROW_HEIGHT,
            SafariLayout.height(columns, true));
    }

    @Test
    public void everySpeciesIsListedExactlyOnce() {
        List<Section> sections = SafariLayout.sections(null, Set.of(), true);
        assertEquals(37, sections.stream().mapToInt(section -> section.rows().size()).sum());
    }
}
