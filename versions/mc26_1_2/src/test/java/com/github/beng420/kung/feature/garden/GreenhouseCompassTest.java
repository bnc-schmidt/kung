package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public final class GreenhouseCompassTest {
    private static String[] labels(int rotation, boolean mirror) {
        return GreenhouseCompass.labels(new GreenhouseOrientation(rotation, mirror));
    }

    @Test
    public void theDefaultOrientationPutsEastAtTheTopAndNorthAtTheLeft() {
        // Top, right, bottom, left: grid row 0 is the east end and column 0 the north end (see GreenhouseOrientation).
        assertArrayEquals(new String[] {"E", "S", "W", "N"}, GreenhouseCompass.labels(GreenhouseOrientation.IDENTITY));
        assertEquals("E", labels(0, false)[GreenhouseCompass.TOP]);
        assertEquals("N", labels(0, false)[GreenhouseCompass.LEFT]);
    }

    @Test
    public void clockwiseQuarterTurnsAdvanceEveryLabelOneStep() {
        assertArrayEquals(new String[] {"S", "W", "N", "E"}, labels(1, false));
        assertArrayEquals(new String[] {"W", "N", "E", "S"}, labels(2, false));
        assertArrayEquals(new String[] {"N", "E", "S", "W"}, labels(3, false));
        assertArrayEquals(labels(0, false), labels(4, false));
    }

    @Test
    public void mirroringSwapsTheLeftAndRightEdges() {
        assertArrayEquals(new String[] {"E", "N", "W", "S"}, labels(0, true));
    }

    @Test
    public void everyOrientationLabelsTheFourEdgesWithFourDifferentDirections() {
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                Set<String> seen = new TreeSet<>(java.util.List.of(labels(rotation, mirror)));
                assertEquals(rotation + " " + mirror, Set.of("N", "E", "S", "W"), seen);
            }
        }
    }

    @Test
    public void theLabelsPointWhereTheHologramActuallyPutsEachEdgeCell() {
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                GreenhouseOrientation orientation = new GreenhouseOrientation(rotation, mirror);
                String[] labels = GreenhouseCompass.labels(orientation);
                GreenhouseOrientation.Offset centre = orientation.offset(4, 4);
                String where = rotation + " " + mirror;

                assertToward(where, labels[GreenhouseCompass.TOP], orientation.offset(0, 4), centre);
                assertToward(where, labels[GreenhouseCompass.RIGHT], orientation.offset(4, 9), centre);
                assertToward(where, labels[GreenhouseCompass.BOTTOM], orientation.offset(9, 4), centre);
                assertToward(where, labels[GreenhouseCompass.LEFT], orientation.offset(4, 0), centre);
            }
        }
    }

    private static void assertToward(String where, String label, GreenhouseOrientation.Offset edge,
                                     GreenhouseOrientation.Offset centre) {
        boolean toward = switch (label) {
            case "E" -> edge.dx() > centre.dx();
            case "W" -> edge.dx() < centre.dx();
            case "S" -> edge.dz() > centre.dz();
            default -> edge.dz() < centre.dz();
        };
        assertTrue(where + " " + label, toward);
    }
}
