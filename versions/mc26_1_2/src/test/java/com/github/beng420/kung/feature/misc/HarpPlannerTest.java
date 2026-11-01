package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class HarpPlannerTest {
    private static final String QUARTZ = "minecraft:quartz_block";

    /** Slots 37-43 with a note lit in each given column (0-6), the rest shows the key's wool. */
    private static List<String> row(int... lit) {
        List<String> items = new ArrayList<>(List.of("minecraft:pink_wool", "minecraft:red_wool", "minecraft:orange_wool",
            "minecraft:yellow_wool", "minecraft:lime_wool", "minecraft:cyan_wool", "minecraft:purple_wool"));
        for (int column : lit) {
            items.set(column, QUARTZ);
        }
        return items;
    }

    @Test
    public void aQuartzBlockInTheBottomRowIsANoteToClick() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row());
        assertEquals(List.of(), planner.targets());

        planner.observe(row(2));

        assertEquals(List.of(39), planner.targets());
    }

    @Test
    public void aNoteIsClickedOnceHoweverLongItStaysLit() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(0));
        planner.markClicked(37);
        planner.observe(row(0));

        assertEquals(List.of(), planner.targets());
    }

    @Test
    public void theSameColumnLitAgainAfterItWentDarkIsANewNote() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(4));
        long first = planner.note(41);
        planner.markClicked(41);
        planner.observe(row());
        planner.observe(row(4));

        assertEquals(List.of(41), planner.targets());
        assertNotEquals("A new step for the pacer", first, planner.note(41));
    }

    @Test
    public void notesLitTogetherAreAllTargetsLeftToRight() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(6, 1));
        assertEquals(List.of(38, 43), planner.targets());

        planner.markClicked(38);

        assertEquals(List.of(43), planner.targets());
        assertEquals(0, planner.note(40));
    }

    @Test
    public void aNoteThatWentDarkUnclickedIsReportedOnce() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(1, 3));
        planner.markClicked(40);

        assertEquals("Slot 38 was never clicked; slot 40 was", List.of(38), planner.observe(row()));
        assertEquals(List.of(), planner.observe(row()));
    }

    @Test
    public void onlyAQuartzBlockCountsAndAShortReadingIsSafe() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(List.of("minecraft:quartz_slab", "", QUARTZ));

        assertEquals("Slot 39, the only quartz block", List.of(39), planner.targets());
    }
}
