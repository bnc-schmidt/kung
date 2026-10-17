package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class GreenhouseOrientationTest {
    private static final GreenhouseOrientation DEFAULT = GreenhouseOrientation.IDENTITY;

    @Test
    public void defaultIsNorthUp() {
        // dx = column, dz = row: row 0 is the north edge, column 0 the west edge.
        assertEquals(new GreenhouseOrientation.Offset(0, 0), DEFAULT.offset(0, 0));
        assertEquals(new GreenhouseOrientation.Offset(9, 0), DEFAULT.offset(0, 9));
        assertEquals(new GreenhouseOrientation.Offset(0, 9), DEFAULT.offset(9, 0));
        assertEquals(new GreenhouseOrientation.Offset(9, 9), DEFAULT.offset(9, 9));
        assertEquals(new GreenhouseOrientation.Offset(5, 3), DEFAULT.offset(3, 5));
        assertEquals(new GreenhouseOrientation.Point(0, 0), DEFAULT.point(0, 0));
        assertEquals(new GreenhouseOrientation.Point(10, 10), DEFAULT.point(10, 10));
    }

    @Test
    public void theDefaultIsTheOldMappingTurnedAQuarterClockwise() {
        // The old default (dx = 9 - row, dz = column) drew east on top. Turning that picture a quarter clockwise,
        // new[r][c] = old[9 - c][r], gives the new default: the same cell stands at the same world offset.
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                int oldRow = GreenhouseGrid.SIZE - 1 - column;
                int oldColumn = row;
                GreenhouseOrientation.Offset now = DEFAULT.offset(row, column);
                assertEquals(9 - oldRow, now.dx());
                assertEquals(oldColumn, now.dz());
            }
        }
    }

    @Test
    public void rotatingClockwiseMovesTheNorthWestCornerToTheNorthEast() {
        GreenhouseOrientation turned = DEFAULT.rotatedClockwise();

        assertEquals(new GreenhouseOrientation.Offset(9, 0), turned.offset(0, 0));
        assertEquals(new GreenhouseOrientation.Offset(9, 9), turned.rotatedClockwise().offset(0, 0));
        assertEquals(new GreenhouseOrientation.Offset(0, 9), turned.rotatedClockwise().rotatedClockwise().offset(0, 0));
    }

    @Test
    public void mirrorSwapsTheGridsLeftAndRight() {
        GreenhouseOrientation mirrored = DEFAULT.mirrored();

        assertEquals(DEFAULT.offset(0, 9), mirrored.offset(0, 0));
        assertEquals(DEFAULT.offset(5, 2), mirrored.offset(5, 7));
        assertEquals(new GreenhouseOrientation.Point(10, 0), mirrored.point(0, 0));
    }

    @Test
    public void fourTurnsOrTwoMirrorsAreTheIdentity() {
        GreenhouseOrientation turned = DEFAULT;
        for (int i = 0; i < 4; i++) turned = turned.rotatedClockwise();
        assertEquals(DEFAULT, turned);
        assertEquals(DEFAULT, DEFAULT.mirrored().mirrored());
        assertEquals(DEFAULT, new GreenhouseOrientation(-4, false));
        assertEquals(new GreenhouseOrientation(3, true), new GreenhouseOrientation(-1, true));
    }

    @Test
    public void everyOrientationIsABijectionOntoTheSquare() {
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                GreenhouseOrientation orientation = new GreenhouseOrientation(rotation, mirror);
                Set<GreenhouseOrientation.Offset> seen = new HashSet<>();
                for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
                    for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                        GreenhouseOrientation.Offset at = orientation.offset(row, column);
                        assertTrue(at.dx() >= 0 && at.dx() < 10 && at.dz() >= 0 && at.dz() < 10);
                        seen.add(at);
                    }
                }
                assertEquals(100, seen.size());
            }
        }
    }

    @Test
    public void theEightOrientationsPlaceAnOffCentreCellInEightDifferentSpots() {
        Set<GreenhouseOrientation.Offset> spots = new HashSet<>();
        for (int rotation = 0; rotation < 4; rotation++) {
            spots.add(new GreenhouseOrientation(rotation, false).offset(0, 1));
            spots.add(new GreenhouseOrientation(rotation, true).offset(0, 1));
        }
        assertEquals(8, spots.size());
    }

    @Test
    public void cellEdgesAgreeWithCellOffsets() {
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                GreenhouseOrientation orientation = new GreenhouseOrientation(rotation, mirror);
                GreenhouseOrientation.Point a = orientation.point(3, 5);
                GreenhouseOrientation.Point b = orientation.point(4, 6);
                GreenhouseOrientation.Offset cell = orientation.offset(3, 5);
                assertEquals(Math.min(a.x(), b.x()), cell.dx(), 0);
                assertEquals(Math.min(a.z(), b.z()), cell.dz(), 0);
                assertEquals(1, Math.abs(a.x() - b.x()), 0);
                assertEquals(1, Math.abs(a.z() - b.z()), 0);
            }
        }
    }

    @Test
    public void describesTheStateForChat() {
        assertEquals("rotated 0 degrees clockwise, not mirrored", DEFAULT.describe());
        assertEquals("rotated 90 degrees clockwise, mirrored", DEFAULT.rotatedClockwise().mirrored().describe());
        assertNotEquals(DEFAULT.describe(), DEFAULT.mirrored().describe());
    }
}
