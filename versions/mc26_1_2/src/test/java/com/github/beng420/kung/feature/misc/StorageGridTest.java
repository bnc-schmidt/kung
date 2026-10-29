package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public final class StorageGridTest {
    /** 54 slots; each pair is "item" then count, placed from the given slot on. */
    private static StorageGrid grid(int from, Object... stacks) {
        String[] keys = new String[54];
        int[] counts = new int[54];
        Arrays.fill(keys, "");
        for (int i = 0; i < stacks.length; i += 2) {
            keys[from + i / 2] = (String) stacks[i];
            counts[from + i / 2] = (Integer) stacks[i + 1];
        }
        return new StorageGrid(keys, counts);
    }

    @Test
    public void rightClickDrainsOnlyTheFirstItemEvenWhenAnotherFollows() {
        var grid = grid(0, "DIRT", 10, "COBBLESTONE", 5);
        assertEquals(10, grid.consume(10));
        assertEquals(0, grid.count(0));
        assertEquals(5, grid.count(1));
        assertEquals(5, grid.consume(5));
        assertEquals(-1, grid.firstFilled());
    }

    @Test
    public void emptySlotsBeforeTheFirstStackAreSkipped() {
        // The screenshot: slots 0-25 used up, a partial stack of 57 at slot 26, full stacks behind it.
        var grid = grid(26, "SNOW", 57, "SNOW", 64, "SNOW", 64);
        assertEquals(26, grid.firstFilled());
        assertEquals(28, grid.lastFilled());
        assertEquals(54, grid.consume(54));
        assertEquals(3, grid.count(26));
        assertEquals(64, grid.count(27));
    }

    @Test
    public void aLineLongerThanOneStackContinuesIntoTheNextStackOfTheSameItem() {
        var grid = grid(26, "SNOW", 57, "SNOW", 64);
        assertEquals(95, grid.consume(95));
        assertEquals(0, grid.count(26));
        assertEquals(26, grid.count(27));
    }

    @Test
    public void otherItemsInBetweenStayAndTheSameItemLaterIsUsed() {
        var grid = grid(0, "DIRT", 10, "COBBLESTONE", 5, "DIRT", 20);
        assertEquals(25, grid.consume(25));
        assertEquals(0, grid.count(0));
        assertEquals(5, grid.count(1));
        assertEquals(5, grid.count(2));
    }

    @Test
    public void scatteredStacksOfOneItemAreUsedInReadingOrder() {
        // The trace: single blocks in slots 7, 16, 25, a stack at 34 and a full one at 35; 89 built left 7 at slot 35.
        String[] keys = new String[54];
        int[] counts = new int[54];
        Arrays.fill(keys, "");
        for (int slot : new int[] {7, 16, 25, 34, 35}) keys[slot] = "GLASS";
        counts[7] = 1;
        counts[16] = 1;
        counts[25] = 1;
        counts[34] = 29;
        counts[35] = 64;
        var grid = new StorageGrid(keys, counts);
        assertEquals(89, grid.consume(89));
        assertEquals(35, grid.firstFilled());
        assertEquals(7, grid.count(35));
    }

    @Test
    public void aCountLargerThanTheStackIsCutOffNeverNegative() {
        var grid = grid(0, "DIRT", 10, "COBBLESTONE", 5);
        assertEquals(10, grid.consume(40));
        assertEquals(0, grid.count(0));
        assertEquals(5, grid.count(1));
        for (int slot = 0; slot < grid.size(); slot++) assertTrue(grid.count(slot) >= 0);
    }

    @Test
    public void theInfinidirtWandIsNeverUsedUp() {
        var grid = grid(0, "INFINIDIRT_WAND", 1, "COBBLESTONE", 5);
        assertEquals(0, grid.consume(50));
        assertEquals(1, grid.count(0));
        assertEquals(5, grid.count(1));
    }

    @Test
    public void aWandBehindOtherStacksStaysWhileTheStacksBeforeItDrain() {
        var grid = grid(0, "COBBLESTONE", 5, "INFINIDIRT_WAND", 1);
        assertEquals(5, grid.consume(8));
        assertEquals(1, grid.count(1));
        assertEquals(1, grid.firstFilled());
        assertEquals(0, grid.consume(8));
    }

    @Test
    public void emptyGridAndNonPositiveCountsChangeNothing() {
        var empty = grid(0);
        assertEquals(-1, empty.firstFilled());
        assertEquals(-1, empty.lastFilled());
        assertEquals(0, empty.total());
        assertEquals(0, empty.consume(10));
        var grid = grid(0, "DIRT", 10);
        assertEquals(0, grid.consume(0));
        assertEquals(0, grid.consume(-3));
        assertEquals(10, grid.count(0));
    }

    @Test
    public void totalCountsEveryBlockAndFallsWithUse() {
        var grid = grid(3, "DIRT", 10, "COBBLESTONE", 5);
        assertEquals(15, grid.total());
        grid.consume(4);
        assertEquals(11, grid.total());
    }

    @Test
    public void theGridKeepsNoReferenceToTheArraysItWasBuiltFrom() {
        String[] keys = {"DIRT"};
        int[] counts = {10};
        var grid = new StorageGrid(keys, counts);
        counts[0] = 0;
        assertEquals(10, grid.count(0));
        counts[0] = 10;
        grid.consume(4);
        assertEquals(6, grid.count(0));
        assertEquals(10, counts[0]);
    }

    @Test
    public void builtLinesGiveTheirBlockCount() {
        assertEquals(54, StorageGrid.builtBlocks("You built 54 blocks! UNDO"));
        assertEquals(2, StorageGrid.builtBlocks("You built 2 blocks! UNDO"));
        assertEquals(1, StorageGrid.builtBlocks("You built 1 block!"));
        assertEquals(1234, StorageGrid.builtBlocks("You built 1,234 blocks!"));
        assertEquals(95, StorageGrid.builtBlocks("  You built 95 blocks! UNDO (x17)"));
        assertEquals(89, StorageGrid.builtBlocks("§aYou built §e89§a blocks! §cUNDO"));
        assertEquals(89, StorageGrid.builtBlocks("You built 89 blocks!"));
    }

    @Test
    public void otherLinesGiveMinusOne() {
        assertEquals(-1, StorageGrid.builtBlocks("You removed 95 blocks! UNDO"));
        assertEquals(-1, StorageGrid.builtBlocks("You don't have enough inventory space"));
        assertEquals(-1, StorageGrid.builtBlocks("This block is already occupied!"));
        assertEquals(-1, StorageGrid.builtBlocks("Bob: You built 5 blocks!"));
        assertEquals(-1, StorageGrid.builtBlocks("You built 99999999999 blocks!"));
        assertEquals(-1, StorageGrid.builtBlocks(""));
    }
}
