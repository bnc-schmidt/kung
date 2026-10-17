package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Moves a finished layout, as one rigid piece, toward the plot corner it is nearest to, so the free space stays
 * in one block instead of a frame around the layout. Pure geometry: it knows nothing about recipes.
 *
 * <p>A move is valid when every occupied cell (crops and every cell of a target footprint) stays on the plot and
 * on an unlocked cell. Valid moves are ranked by the gap between the occupied bounding box and its nearest
 * corner (empty rows plus empty columns), then by corner (top-left, top-right, bottom-left, bottom-right), then
 * by the smaller move. The ring of every site moves with it, so a move cannot change a site's crop counts; it
 * can still add a site in space the layout has just left (a footprint that used to hang over the plot edge),
 * which only the caller can tell, see {@link GreenhouseSolver}.
 */
final class GreenhouseCompaction {
    private static final int SIZE = GreenhouseGrid.SIZE;

    /** A rigid move: rows grow downward, columns to the right. */
    record Shift(int rows, int columns) {
        static final Shift NONE = new Shift(0, 0);

        /** Where a cell index (row * SIZE + column) lands; the caller keeps it on the plot. */
        int move(int cell) {
            return (cell / SIZE + rows) * SIZE + cell % SIZE + columns;
        }

        private int length() {
            return Math.abs(rows) + Math.abs(columns);
        }
    }

    private record Ranked(Shift shift, int gap, int corner) { }

    private static final Comparator<Ranked> BEST_FIRST = Comparator.comparingInt(Ranked::gap)
        .thenComparingInt(Ranked::corner)
        .thenComparingInt((Ranked ranked) -> ranked.shift().length())
        .thenComparingInt((Ranked ranked) -> ranked.shift().rows())
        .thenComparingInt((Ranked ranked) -> ranked.shift().columns());

    private GreenhouseCompaction() { }

    /**
     * Every valid move of the occupied cells of {@code grid}, best first; see {@link #shifts(boolean[], boolean[])}.
     */
    static List<Shift> shifts(GreenhouseGrid grid, boolean[] unlocked) {
        boolean[] occupied = new boolean[SIZE * SIZE];
        for (int p = 0; p < occupied.length; p++) occupied[p] = grid.get(p / SIZE, p % SIZE) != null;
        return shifts(occupied, unlocked);
    }

    /**
     * Every valid move of the {@code occupied} cells (row-major, 100 entries), best first; {@code unlocked} has the
     * same shape (null: all open). Nothing occupied gives only {@link Shift#NONE}; cells that already sit on a
     * locked one may leave no valid move at all.
     */
    static List<Shift> shifts(boolean[] occupied, boolean[] unlocked) {
        int top = SIZE;
        int bottom = -1;
        int left = SIZE;
        int right = -1;
        for (int p = 0; p < occupied.length; p++) {
            if (!occupied[p]) continue;
            top = Math.min(top, p / SIZE);
            bottom = Math.max(bottom, p / SIZE);
            left = Math.min(left, p % SIZE);
            right = Math.max(right, p % SIZE);
        }
        if (bottom < 0) return List.of(Shift.NONE);

        List<Ranked> ranked = new ArrayList<>();
        for (int rows = -top; rows < SIZE - bottom; rows++) {
            for (int columns = -left; columns < SIZE - right; columns++) {
                Shift shift = new Shift(rows, columns);
                if (!fits(occupied, shift, unlocked)) continue;
                // Empty rows or columns between the moved box and each edge.
                int above = top + rows;
                int below = SIZE - 1 - bottom - rows;
                int beside = left + columns;
                int opposite = SIZE - 1 - right - columns;
                int[] gaps = {above + beside, above + opposite, below + beside, below + opposite};
                int corner = 0;
                for (int c = 1; c < gaps.length; c++) if (gaps[c] < gaps[corner]) corner = c;
                ranked.add(new Ranked(shift, gaps[corner], corner));
            }
        }
        ranked.sort(BEST_FIRST);
        return ranked.stream().map(Ranked::shift).toList();
    }

    private static boolean fits(boolean[] occupied, Shift shift, boolean[] unlocked) {
        if (unlocked == null) return true;
        for (int p = 0; p < occupied.length; p++) if (occupied[p] && !unlocked[shift.move(p)]) return false;
        return true;
    }

    /** A copy of the grid moved by {@code shift}; the caller passes a valid one. */
    static GreenhouseGrid shifted(GreenhouseGrid grid, Shift shift) {
        GreenhouseGrid out = new GreenhouseGrid();
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null) out.set(row + shift.rows(), column + shift.columns(), cell);
            }
        }
        return out;
    }

    /** The grid moved by its best valid move, or an unchanged copy when none is valid. */
    static GreenhouseGrid compact(GreenhouseGrid grid, boolean[] unlocked) {
        List<Shift> all = shifts(grid, unlocked);
        return shifted(grid, all.isEmpty() ? Shift.NONE : all.get(0));
    }
}
