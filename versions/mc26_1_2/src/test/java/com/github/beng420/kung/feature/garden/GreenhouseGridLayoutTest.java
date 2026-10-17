package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.ui.UiBounds;
import org.junit.Test;

public final class GreenhouseGridLayoutTest {
    private static final int CELLS = 10;
    private static final int CELL = 22;
    private static final int GAP = 2;

    private static GreenhouseGridLayout layout(int x, int y, float scale) {
        return GreenhouseGridLayout.of(x, y, CELLS, CELL, GAP, scale);
    }

    /** Menu scale 0.8 times GUI scale 1..6, plus the fractional scales small windows produce. */
    private static float[] scales() {
        float[] scales = new float[100];
        for (int i = 0; i < scales.length; i++) scales[i] = 0.3F + i * 0.07F;
        return scales;
    }

    @Test
    public void gapsAndCellsAreUniformAtEveryScale() {
        for (float scale : scales()) {
            for (int origin : new int[] {0, 7, 14, 50, 103}) {
                GreenhouseGridLayout grid = layout(origin, origin + 22, scale);
                for (int line = 0; line < CELLS; line++) {
                    UiBounds column = grid.cell(0, line);
                    UiBounds row = grid.cell(line, 0);
                    assertEquals("cell " + line + " at " + scale, grid.cell(), column.width());
                    assertEquals(grid.cell(), column.height());
                    if (line + 1 < CELLS) {
                        assertEquals("column gap " + line + " at " + scale, grid.gap(),
                            grid.cell(0, line + 1).x() - column.right());
                        assertEquals("row gap " + line + " at " + scale, grid.gap(),
                            grid.cell(line + 1, 0).y() - row.bottom());
                    }
                }
            }
        }
    }

    @Test
    public void theOldFractionalSchemeWasUneven() {
        // Menu scale 0.8 at GUI scale 2 (1920x1080): logical edges at 1.6 px per unit round to 3 or 4 px gaps.
        int smallest = Integer.MAX_VALUE;
        int largest = Integer.MIN_VALUE;
        for (int column = 0; column + 1 < CELLS; column++) {
            int right = (int) Math.ceil((14 + column * 24 + CELL) * 1.6F - 0.5F);
            int next = (int) Math.ceil((14 + (column + 1) * 24) * 1.6F - 0.5F);
            smallest = Math.min(smallest, next - right);
            largest = Math.max(largest, next - right);
        }
        assertTrue(largest > smallest);
        // The same scale through the layout: 38 px pitch, 3 px gap, everywhere.
        GreenhouseGridLayout grid = layout(14, 36, 1.6F);
        assertEquals(38, grid.pitch());
        assertEquals(3, grid.gap());
    }

    @Test
    public void cellsNeverOverlapAndAreSeparatedByAtLeastOnePixel() {
        for (float scale : scales()) {
            GreenhouseGridLayout grid = layout(14, 36, scale);
            assertTrue(grid.gap() >= 1);
            assertTrue(grid.cell() >= 1);
            for (int line = 0; line + 1 < CELLS; line++) {
                assertTrue(grid.cell(0, line + 1).x() >= grid.cell(0, line).right() + 1);
                assertTrue(grid.cell(line + 1, 0).y() >= grid.cell(line, 0).bottom() + 1);
            }
        }
    }

    @Test
    public void gridStaysWithinAHalfPixelPerCellOfTheLogicalSpan() {
        for (float scale : scales()) {
            GreenhouseGridLayout grid = layout(14, 36, scale);
            double slack = CELLS / 2.0 + 1;
            double logicalSize = (CELLS * (CELL + GAP) - GAP) * scale;
            assertTrue("left at " + scale, Math.abs(grid.left() - 14 * scale) <= slack);
            assertTrue("top at " + scale, Math.abs(grid.top() - 36 * scale) <= slack);
            assertTrue("size at " + scale, Math.abs(grid.size() - logicalSize) <= slack * 2);
            assertEquals(grid.cell(CELLS - 1, CELLS - 1).right() - grid.left(), grid.size());
        }
    }

    @Test
    public void hitTestRoundTripsEveryPixelOfEveryCell() {
        for (float scale : new float[] {0.8F, 1.6F, 2.4F, 3.0337F, 4.0449F, 1.0F}) {
            GreenhouseGridLayout grid = layout(14, 36, scale);
            for (int row = 0; row < CELLS; row++) {
                for (int column = 0; column < CELLS; column++) {
                    UiBounds bounds = grid.cell(row, column);
                    int[] expected = {row, column};
                    assertArrayEquals(expected, grid.at(bounds.x(), bounds.y()));
                    assertArrayEquals(expected, grid.at(bounds.right() - 1, bounds.bottom() - 1));
                    assertArrayEquals(expected, grid.at(bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2));
                }
            }
        }
    }

    @Test
    public void thePixelsBetweenCellsBelongToTheCellBeforeAndNothingIsPastTheGrid() {
        GreenhouseGridLayout grid = layout(14, 36, 1.6F);
        UiBounds first = grid.cell(0, 0);
        UiBounds second = grid.cell(0, 1);
        for (int x = first.right(); x < second.x(); x++) {
            assertArrayEquals(new int[] {0, 0}, grid.at(x, first.y()));
        }
        assertNull(grid.at(grid.left() - 1, grid.top()));
        assertNull(grid.at(grid.left(), grid.top() - 1));
        assertNull(grid.at(grid.left() + grid.size(), grid.top()));
        assertNull(grid.at(grid.left(), grid.top() + grid.size()));
        assertArrayEquals(new int[] {CELLS - 1, CELLS - 1},
            grid.at(grid.left() + grid.size() - 1, grid.top() + grid.size() - 1));
    }

    @Test
    public void tinyScalesStillGetSeparatedCells() {
        GreenhouseGridLayout grid = layout(0, 0, 0.05F);
        assertEquals(1, grid.gap());
        assertTrue(grid.cell() >= 1);
    }
}
