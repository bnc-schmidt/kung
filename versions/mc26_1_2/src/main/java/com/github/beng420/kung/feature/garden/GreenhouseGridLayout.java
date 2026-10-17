package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.ui.UiBounds;

/**
 * Integer device-pixel geometry of a square cell grid. A fractional menu scale puts logical cell edges on
 * fractional pixels, which round one by one and leave some gaps a pixel wider than the rest. Here the
 * pitch and gap are rounded once, so every gap and every cell is identical at any scale; draw and
 * hit-test with the same instance, in device pixels.
 *
 * @param left   device x of the first cell
 * @param top    device y of the first cell
 * @param cells  cells per side
 * @param pitch  device pixels from one cell's edge to the next cell's same edge
 * @param gap    device pixels between neighbouring cells; at least 1
 */
public record GreenhouseGridLayout(int left, int top, int cells, int pitch, int gap) {
    public GreenhouseGridLayout {
        if (cells < 1 || gap < 1 || pitch <= gap) {
            throw new IllegalArgumentException("A grid needs cells and a gap smaller than its pitch.");
        }
    }

    /**
     * Snaps a logical grid to device pixels.
     *
     * @param logicalX     logical x of the first cell
     * @param logicalY     logical y of the first cell
     * @param cells        cells per side
     * @param logicalCell  logical cell size
     * @param logicalGap   logical gap between cells
     * @param deviceScale  device pixels per logical unit (menu scale times GUI scale)
     */
    public static GreenhouseGridLayout of(int logicalX, int logicalY, int cells, int logicalCell, int logicalGap,
                                          float deviceScale) {
        int gap = Math.max(1, Math.round(logicalGap * deviceScale));
        int pitch = Math.max(gap + 1, Math.round((logicalCell + logicalGap) * deviceScale));
        // Rounding the pitch grows or shrinks the grid by up to half a pixel per cell; centre that error
        // inside the logical span so it does not all land on the far edge.
        float slack = ((cells * (logicalCell + logicalGap) - logicalGap) * deviceScale - (cells * pitch - gap)) / 2;
        return new GreenhouseGridLayout(Math.round(logicalX * deviceScale + slack),
            Math.round(logicalY * deviceScale + slack), cells, pitch, gap);
    }

    /** Side of one cell in device pixels. */
    public int cell() {
        return pitch - gap;
    }

    /** Side of the whole grid in device pixels. */
    public int size() {
        return cells * pitch - gap;
    }

    public UiBounds cell(int row, int column) {
        return new UiBounds(left + column * pitch, top + row * pitch, cell(), cell());
    }

    /**
     * The {row, column} under a device pixel, or null outside the grid. The gap after a cell counts as
     * part of that cell so a drag across the grid does not skip cells.
     */
    public int[] at(int x, int y) {
        int column = index(x - left);
        int row = index(y - top);
        return column < 0 || row < 0 ? null : new int[] {row, column};
    }

    private int index(int offset) {
        return offset < 0 || offset >= size() ? -1 : offset / pitch;
    }
}
