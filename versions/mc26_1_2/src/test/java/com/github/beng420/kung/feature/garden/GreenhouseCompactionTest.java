package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseCompaction.Shift;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class GreenhouseCompactionTest {
    private static final int SIZE = GreenhouseGrid.SIZE;

    /** '.' empty, 'C' crop, 'T' target cell, 'B' bonus crop; short rows and a short list are padded with empties. */
    private static GreenhouseGrid grid(String... rows) {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                char letter = rows[row].charAt(column);
                if (letter == 'C') grid.set(row, column, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
                if (letter == 'T') grid.set(row, column, new GreenhouseGrid.Cell("Dustgrain", GreenhouseGrid.TARGET));
                if (letter == 'B') grid.set(row, column, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.BONUS));
            }
        }
        return grid;
    }

    /** Ten rows of ten characters, for readable assertion failures. */
    private static String show(GreenhouseGrid grid) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                text.append(cell == null ? '.' : cell.flag() == GreenhouseGrid.TARGET ? 'T'
                    : cell.flag() == GreenhouseGrid.BONUS ? 'B' : 'C');
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String expected(String... rows) {
        return show(grid(rows));
    }

    private static boolean[] open(String... rows) {
        boolean[] open = new boolean[SIZE * SIZE];
        Arrays.fill(open, true);
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == 'X') open[row * SIZE + column] = false;
            }
        }
        return open;
    }

    @Test
    public void aFloatingLayoutMovesIntoTheTopLeftCornerOfAnOpenPlot() {
        GreenhouseGrid floating = grid(
            "..........",
            "..........",
            "..CCCCCCC.",
            "..CTTTTTC.",
            "..CCCCCCC.",
            "..........");

        String moved = show(GreenhouseCompaction.compact(floating, open()));

        assertEquals(expected(
            "CCCCCCC",
            "CTTTTTC",
            "CCCCCCC"), moved);
        assertEquals(moved, show(GreenhouseCompaction.compact(floating, null)));
    }

    @Test
    public void relativePositionsAndCellsSurviveTheMove() {
        GreenhouseGrid source = grid(
            ".......B..",
            "......CCC.",
            "......CTT.",
            "......CTTC",
            "..........");

        GreenhouseGrid moved = GreenhouseCompaction.compact(source, open());

        assertEquals(expected(
            ".B..",
            "CCC.",
            "CTT.",
            "CTTC"), show(moved));
        assertEquals(source.filled(), moved.filled());
        assertEquals(GreenhouseGrid.BONUS, moved.get(0, 1).flag());
        assertEquals("Carrot", moved.get(0, 1).name());
    }

    @Test
    public void aNineCellTargetFootprintStaysWhole() {
        GreenhouseGrid source = grid(
            "..........",
            "...CCCCC..",
            "...CTTTC..",
            "...CTTTC..",
            "...CTTTC..",
            "...CCCCC..");

        GreenhouseGrid moved = GreenhouseCompaction.compact(source, open());

        assertEquals(expected(
            "CCCCC",
            "CTTTC",
            "CTTTC",
            "CTTTC",
            "CCCCC"), show(moved));
        assertEquals(new GreenhouseGrid.Cell("Dustgrain", GreenhouseGrid.TARGET), moved.get(2, 2));
    }

    @Test
    public void aLockedCornerCellSendsTheLayoutToAnotherCorner() {
        GreenhouseGrid block = grid(
            "..........",
            "..........",
            "..........",
            "..........",
            "....CCC...",
            "....CTC...");

        // (0, 0) is locked, so the top-left corner is out; the top-right one is next in line.
        GreenhouseGrid moved = GreenhouseCompaction.compact(block, open("X"));

        assertEquals(expected(
            ".......CCC",
            ".......CTC"), show(moved));
    }

    @Test
    public void aLockedBandKeepsTheLayoutOffTheLockedCells() {
        boolean[] mask = open(
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            "XXXXXXXXXX");
        GreenhouseGrid block = grid(
            "..........",
            "..........",
            "..........",
            "...CCC....",
            "...CTC....");

        List<Shift> ranked = GreenhouseCompaction.shifts(block, mask);
        GreenhouseGrid moved = GreenhouseCompaction.compact(block, mask);

        // Rows 0-2 are locked, so the block cannot touch a top corner; the bottom-left one comes before the
        // bottom-right one.
        assertEquals(expected(
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "CCC.......",
            "CTC......."), show(moved));
        for (Shift shift : ranked) {
            for (int row = 0; row < SIZE; row++) {
                for (int column = 0; column < SIZE; column++) {
                    if (block.get(row, column) == null) continue;
                    assertTrue(shift.toString(), mask[shift.move(row * SIZE + column)]);
                }
            }
        }
    }

    @Test
    public void theMovesWithTheSameGapAndCornerPreferTheSmallestOne() {
        // Crop at (3, 3); open cells only at (1, 3) and (4, 0), both four empty rows plus columns from the
        // top-left corner. (1, 3) is two rows away, (4, 0) is one row and three columns away.
        boolean[] mask = new boolean[SIZE * SIZE];
        mask[1 * SIZE + 3] = true;
        mask[4 * SIZE + 0] = true;

        GreenhouseGrid moved = GreenhouseCompaction.compact(grid("", "", "", "...C"), mask);

        assertEquals(expected("", "...C"), show(moved));
    }

    @Test
    public void aLayoutThatCannotMoveBetterStaysWhereItIs() {
        GreenhouseGrid source = grid(
            "..........",
            "..........",
            "..CC......");
        // Only the cells it already occupies are open.
        boolean[] mask = new boolean[SIZE * SIZE];
        mask[2 * SIZE + 2] = true;
        mask[2 * SIZE + 3] = true;

        assertEquals(List.of(Shift.NONE), GreenhouseCompaction.shifts(source, mask));
        assertEquals(show(source), show(GreenhouseCompaction.compact(source, mask)));
    }

    @Test
    public void aLayoutOnLockedCellsHasNoValidMoveAndIsCopiedAsItIs() {
        GreenhouseGrid source = grid("C");

        assertTrue(GreenhouseCompaction.shifts(source, new boolean[SIZE * SIZE]).isEmpty());
        GreenhouseGrid copy = GreenhouseCompaction.compact(source, new boolean[SIZE * SIZE]);
        assertEquals(source, copy);
        assertNotSame(source, copy);
    }

    @Test
    public void emptyAndFullGridsAreLeftAlone() {
        GreenhouseGrid empty = new GreenhouseGrid();
        GreenhouseGrid full = new GreenhouseGrid();
        for (int p = 0; p < SIZE * SIZE; p++) {
            full.set(p / SIZE, p % SIZE, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        }

        assertEquals(List.of(Shift.NONE), GreenhouseCompaction.shifts(empty, open()));
        assertEquals(List.of(Shift.NONE), GreenhouseCompaction.shifts(full, open()));
        assertEquals(empty, GreenhouseCompaction.compact(empty, open()));
        assertEquals(full, GreenhouseCompaction.compact(full, open()));
    }

    @Test
    public void everyOffsetOfASmallLayoutIsRankedExactlyOnce() {
        // A 2x3 block can sit at 9 x 8 positions on the 10x10 plot.
        List<Shift> shifts = GreenhouseCompaction.shifts(grid("CCC", "CCC"), open());

        assertEquals(9 * 8, shifts.size());
        assertEquals(shifts.size(), shifts.stream().distinct().count());
        assertEquals(Shift.NONE, shifts.get(0));
    }

    @Test
    public void compactingTwiceChangesNothing() {
        List<GreenhouseGrid> layouts = List.of(
            grid("", "", "....CTTC", "....CTTC", "......."),
            grid("", "", "", "", "", "", "", "", "........CC", "........CT"),
            grid("C", ".C", "..C"),
            grid("", "...C", "", "C"));
        List<boolean[]> masks = List.of(open(), open("X", "XX", "XXX"), open("..........", ".XXXXXXXX."),
            open("....X.....", "....X.....", "....X....."));

        for (GreenhouseGrid layout : layouts) {
            for (boolean[] mask : masks) {
                GreenhouseGrid once = GreenhouseCompaction.compact(layout, mask);
                assertEquals(show(once), show(GreenhouseCompaction.compact(once, mask)));
                assertEquals(layout.filled(), once.filled());
            }
        }
    }
}
