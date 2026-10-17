package com.github.beng420.kung.feature.garden;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

/** The 10x10 greenhouse layout in the site's orientation: row 0 is the top row, column 0 the left. */
final class GreenhouseGrid {
    static final int SIZE = 10;
    /** The mutation being grown; the other flags describe what feeds it. */
    static final int TARGET = 0;
    static final int INGREDIENT = 1;
    /** Kept as the site wrote it; the editor has no brush for it. */
    static final int BONUS = 2;

    /** Crop names that are not on the known list are kept as they came. */
    record Cell(String name, int flag) {
        Cell {
            name = GreenhouseCrops.canonical(name);
        }
    }

    private final Cell[] cells = new Cell[SIZE * SIZE];

    static boolean inside(int row, int column) {
        return row >= 0 && row < SIZE && column >= 0 && column < SIZE;
    }

    private static int index(int row, int column) {
        return row * SIZE + column;
    }

    /** Null for an empty cell. */
    Cell get(int row, int column) {
        return cells[index(row, column)];
    }

    /** A null cell empties the position. */
    void set(int row, int column, Cell cell) {
        cells[index(row, column)] = cell;
    }

    int filled() {
        return (int) Arrays.stream(cells).filter(cell -> cell != null).count();
    }

    GreenhouseGrid rotatedRight() {
        return remap((row, column) -> index(SIZE - 1 - column, row));
    }

    GreenhouseGrid rotatedLeft() {
        return remap((row, column) -> index(column, SIZE - 1 - row));
    }

    /** Horizontal flip: left and right swap. */
    GreenhouseGrid mirrored() {
        return remap((row, column) -> index(row, SIZE - 1 - column));
    }

    GreenhouseGrid cleared() {
        return new GreenhouseGrid();
    }

    /** The result takes, at each (row, column), the cell found at the source index. */
    private GreenhouseGrid remap(IntBinaryOperator source) {
        GreenhouseGrid out = new GreenhouseGrid();
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                out.cells[index(row, column)] = cells[source.applyAsInt(row, column)];
            }
        }
        return out;
    }

    @Override public boolean equals(Object other) {
        return other instanceof GreenhouseGrid grid && Arrays.equals(cells, grid.cells);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(cells);
    }
}
