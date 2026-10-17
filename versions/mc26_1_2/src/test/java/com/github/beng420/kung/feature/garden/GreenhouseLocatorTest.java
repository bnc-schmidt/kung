package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

public final class GreenhouseLocatorTest {
    @Test
    public void plotsAreNinetySixBlocksOnAFiveByFiveGridAroundTheOrigin() {
        // The centre plot spans -48..47: the origin sits in its middle.
        assertEquals(-48, GreenhouseLocator.plotOrigin(0));
        assertEquals(-48, GreenhouseLocator.plotOrigin(-1));
        assertEquals(-48, GreenhouseLocator.plotOrigin(47));
        assertEquals(48, GreenhouseLocator.plotOrigin(48));
        assertEquals(-48, GreenhouseLocator.plotOrigin(-48));
        assertEquals(-144, GreenhouseLocator.plotOrigin(-49));
        assertEquals(-240, GreenhouseLocator.plotOrigin(-240));
        assertEquals(-240, GreenhouseLocator.plotOrigin(-145));
        assertEquals(144, GreenhouseLocator.plotOrigin(239));
        assertEquals(144, GreenhouseLocator.plotOrigin(144));
    }

    @Test
    public void matchesTheReferenceFormulaAcrossTheWholeGarden() {
        for (int p = -240; p < 240; p++) {
            int reference = (int) ((p + 240) / 96.0) * 96 - 240;
            assertEquals("at " + p, reference, GreenhouseLocator.plotOrigin(p));
        }
    }

    @Test
    public void gridCornerIsFortyThreeIntoThePlotAtTheCropFloor() {
        assertEquals(corner(-5, 73, -5), GreenhouseLocator.cornerOf(0, 0));
        assertEquals(corner(-5, 73, -5), GreenhouseLocator.cornerOf(-1, -1));
        assertEquals(corner(-5, 73, -5), GreenhouseLocator.cornerOf(47, 47));
        assertEquals(corner(91, 73, -5), GreenhouseLocator.cornerOf(48, 0));
        assertEquals(corner(-5, 73, -101), GreenhouseLocator.cornerOf(0, -49));
        assertEquals(corner(-197, 73, -197), GreenhouseLocator.cornerOf(-240, -240));
        assertEquals(corner(187, 73, 187), GreenhouseLocator.cornerOf(239, 239));
    }

    @Test
    public void positionsOutsideTheGardenHaveNoPlot() {
        assertTrue(GreenhouseLocator.cornerOf(240, 0).isEmpty());
        assertTrue(GreenhouseLocator.cornerOf(0, 240).isEmpty());
        assertTrue(GreenhouseLocator.cornerOf(-241, 0).isEmpty());
        assertTrue(GreenhouseLocator.cornerOf(0, -241).isEmpty());
        assertTrue(GreenhouseLocator.cornerOf(5000, -5000).isEmpty());
        assertTrue(GreenhouseLocator.inGardenBounds(-240));
        assertTrue(GreenhouseLocator.inGardenBounds(239));
        assertFalse(GreenhouseLocator.inGardenBounds(240));
    }

    @Test
    public void theGridAlwaysFitsInsideItsPlot() {
        for (int x = -240; x < 240; x += 7) {
            BlockPos corner = GreenhouseLocator.cornerOf(x, x).orElseThrow();
            int origin = GreenhouseLocator.plotOrigin(x);
            assertTrue(corner.getX() >= origin && corner.getX() + 9 < origin + GreenhouseLocator.PLOT_SIZE);
        }
        assertEquals(74, GreenhouseLocator.HOLOGRAM_Y);
    }

    @Test
    public void plotBoundsSpanTheWholePlotFromSixtyToNinety() {
        assertEquals(new AABB(-48, 60, -48, 48, 90, 48), GreenhouseLocator.plotBounds(0, 0));
        assertEquals(new AABB(48, 60, -144, 144, 90, -48), GreenhouseLocator.plotBounds(100, -100));
    }

    private static Optional<BlockPos> corner(int x, int y, int z) {
        return Optional.of(new BlockPos(x, y, z));
    }
}
