package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.Test;

public final class GreenhouseDetectionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final BlockPos CENTRE_PLOT = new BlockPos(-5, 73, -5);
    private static final BlockPos EAST_PLOT = new BlockPos(91, 73, -5);

    /** A scripted game: counts every question and can unload the Carpenter. */
    private static final class FakeWorld implements GreenhouseDetection.Probe {
        boolean garden = true;
        boolean carpenterLoaded = true;
        int gardenChecks;
        int searches;
        final List<String> misses = new ArrayList<>();

        @Override public boolean garden() {
            gardenChecks++;
            return garden;
        }

        @Override public boolean carpenter(int x, int z) {
            searches++;
            return carpenterLoaded;
        }

        @Override public void miss(int plotX, int plotZ) {
            misses.add(plotX + "," + plotZ);
        }
    }

    @Test
    public void aFoundGreenhouseStaysTheGreenhouseAfterTheCarpenterUnloads() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        Object level = new Object();

        assertEquals(Optional.of(CENTRE_PLOT), detection.update(level, 0, 0, 0, false, world));
        world.carpenterLoaded = false;
        for (int second = 1; second <= 30; second++) {
            assertEquals("second " + second, Optional.of(CENTRE_PLOT),
                detection.update(level, 3, -4, second * SECOND, false, world));
        }

        assertEquals("the NPC is looked for once", 1, world.searches);
        assertTrue(world.misses.isEmpty());
    }

    @Test
    public void aMissIsRetriedEverySecondButReportedOncePerPlot() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        world.carpenterLoaded = false;
        Object level = new Object();

        for (int second = 0; second < 5; second++) {
            assertTrue(detection.update(level, 0, 0, second * SECOND, false, world).isEmpty());
        }
        assertEquals(5, world.searches);
        assertEquals(List.of("-48,-48"), world.misses);

        assertTrue(detection.update(level, 60, 0, 5 * SECOND, false, world).isEmpty());
        assertEquals(List.of("-48,-48", "48,-48"), world.misses);

        // The NPC loads later: found at the next refresh.
        world.carpenterLoaded = true;
        assertEquals(Optional.of(EAST_PLOT), detection.update(level, 60, 0, 6 * SECOND, false, world));
    }

    @Test
    public void theGardenCheckIsThrottledToOnceASecondUnlessForcedOrMoved() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        Object level = new Object();

        detection.update(level, 0, 0, 0, false, world);
        for (int tick = 1; tick < 60; tick++) detection.update(level, 0, 0, tick * SECOND / 60, false, world);
        assertEquals(1, world.gardenChecks);

        detection.update(level, 0, 0, SECOND / 2, true, world);
        assertEquals("forced", 2, world.gardenChecks);
        detection.update(level, 0, 0, SECOND / 2 + SECOND - 1, false, world);
        assertEquals("just under a second after the forced check", 2, world.gardenChecks);
        detection.update(level, 0, 0, SECOND / 2 + SECOND, false, world);
        assertEquals("a full second after it", 3, world.gardenChecks);
        detection.update(level, 60, 0, SECOND / 2 + SECOND + 1, false, world);
        assertEquals("moving to another plot checks at once", 4, world.gardenChecks);
    }

    @Test
    public void leavingTheGardenHidesItButKeepsWhatWasFound() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        Object level = new Object();
        detection.update(level, 0, 0, 0, false, world);
        world.carpenterLoaded = false;

        world.garden = false;
        assertTrue(detection.update(level, 0, 0, SECOND, false, world).isEmpty());
        world.garden = true;
        assertEquals(Optional.of(CENTRE_PLOT), detection.update(level, 0, 0, 2 * SECOND, false, world));
        assertEquals(1, world.searches);
    }

    @Test
    public void aNewLevelForgetsTheGreenhouse() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        detection.update(new Object(), 0, 0, 0, false, world);
        world.carpenterLoaded = false;

        assertTrue(detection.update(new Object(), 0, 0, SECOND / 10, false, world).isEmpty());
        assertEquals(2, world.searches);
    }

    @Test
    public void otherPlotsAreNotTheGreenhouseAndComingBackNeedsNoSearch() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        Object level = new Object();
        detection.update(level, 0, 0, 0, false, world);
        world.carpenterLoaded = false;

        assertTrue(detection.update(level, 60, 0, SECOND, false, world).isEmpty());
        assertEquals(Optional.of(CENTRE_PLOT), detection.update(level, 0, 0, 2 * SECOND, false, world));
        assertEquals("one search at the start, one in the east plot", 2, world.searches);
    }

    @Test
    public void noSearchOutsideThe5x5Plots() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();

        assertTrue(detection.update(new Object(), 500, 0, 0, false, world).isEmpty());
        assertEquals(0, world.searches);
        assertTrue(world.misses.isEmpty());
    }

    @Test
    public void markHereSkipsTheCarpenterButNeedsTheGardenAndAPlot() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        world.carpenterLoaded = false;
        Object level = new Object();

        world.garden = false;
        assertTrue(detection.markHere(level, 0, 0, 0, world).isEmpty());
        world.garden = true;
        assertTrue(detection.markHere(level, 300, 0, 0, world).isEmpty());
        assertEquals(Optional.of(CENTRE_PLOT), detection.markHere(level, 10, 10, 0, world));
        assertEquals(Optional.of(CENTRE_PLOT), detection.update(level, 10, 10, SECOND / 10, false, world));
        assertEquals(0, world.searches);
        assertTrue("another plot is still unmarked", detection.update(level, 60, 0, SECOND, false, world).isEmpty());
    }

    @Test
    public void markHereOnABadSpotRemembersNothing() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        world.carpenterLoaded = false;
        Object level = new Object();
        world.garden = false;

        detection.markHere(level, 0, 0, 0, world);
        world.garden = true;

        assertTrue(detection.update(level, 0, 0, SECOND, false, world).isEmpty());
    }

    @Test
    public void forgetClearsEverything() {
        GreenhouseDetection detection = new GreenhouseDetection();
        FakeWorld world = new FakeWorld();
        Object level = new Object();
        detection.update(level, 0, 0, 0, false, world);
        world.carpenterLoaded = false;

        detection.forget();

        assertTrue(detection.update(level, 0, 0, SECOND / 10, false, world).isEmpty());
    }
}
