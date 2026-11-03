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

    /** Notes waiting behind the hit row, per column: {@code behind(column, count, ...)} pairs. */
    private static int[] behind(int... columnAndCount) {
        int[] behind = new int[HarpPlanner.COLUMNS];
        for (int i = 0; i < columnAndCount.length; i += 2) {
            behind[columnAndCount[i]] = columnAndCount[i + 1];
        }
        return behind;
    }

    @Test
    public void aRepeatedNoteBehindTheLitOneIsANewNoteWhenItStepsIn() {
        // Trace 20261101-192258, notes 14 and 28: the same column twice in a row keeps one quartz block lit
        // for two steps, so the planner clicked it once and the second note was lost.
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(1), behind(1, 1));
        long first = planner.note(38);
        planner.markClicked(38);
        assertEquals(List.of(), planner.targets());

        planner.observe(row(1), behind(1, 0));

        assertEquals(List.of(38), planner.targets());
        assertNotEquals("A new step for the pacer", first, planner.note(38));
    }

    @Test
    public void aLitNoteStaysOneNoteWhileTheNotesBehindItHaveNotMoved() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(1), behind(1, 1));
        planner.markClicked(38);
        planner.observe(row(1), behind(1, 1));

        assertEquals(List.of(), planner.targets());
    }

    @Test
    public void aRunOfNotesInOneColumnIsAClickEveryStep() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(3), behind(3, 2));
        planner.markClicked(40);
        planner.observe(row(3), behind(3, 1));
        assertEquals(List.of(40), planner.targets());
        planner.markClicked(40);
        planner.observe(row(3), behind(3, 0));

        assertEquals(List.of(40), planner.targets());
    }

    @Test
    public void notesEnteringAtTheTopAreNotAStep() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(0), behind());
        planner.markClicked(37);
        planner.observe(row(0), behind(0, 1));

        assertEquals(List.of(), planner.targets());
    }

    @Test
    public void aRepeatReplacingAnUnclickedNoteIsReported() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(row(2), behind(2, 1));

        assertEquals(List.of(39), planner.observe(row(2), behind(2, 0)));
    }

    @Test
    public void notesBehindAreTheWoolBlocksAboveEachButton() {
        List<String> top = new ArrayList<>(java.util.Collections.nCopies(54, "minecraft:lime_stained_glass_pane"));
        top.set(28 + 1, "minecraft:red_wool");
        top.set(19 + 1, "minecraft:red_wool");
        top.set(1 + 1, "minecraft:red_wool");
        top.set(10 + 4, "minecraft:blue_wool");

        assertEquals("Column 1 counts the two blocks touching the hit row, not the one past a gap",
            2, HarpPlanner.notesBehind(top)[1]);
        assertEquals("A block that is not next to the hit row is no note behind it", 0, HarpPlanner.notesBehind(top)[4]);
        assertEquals(0, HarpPlanner.notesBehind(top)[0]);
    }

    @Test
    public void onlyAQuartzBlockCountsAndAShortReadingIsSafe() {
        HarpPlanner planner = new HarpPlanner();
        planner.observe(List.of("minecraft:quartz_slab", "", QUARTZ));

        assertEquals("Slot 39, the only quartz block", List.of(39), planner.targets());
    }
}
