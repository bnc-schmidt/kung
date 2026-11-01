package com.github.beng420.kung.feature.experiment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.SplittableRandom;
import org.junit.Test;

public final class AutoPacerTest {
    @Test
    public void aStepFiresOnceAfterItsDelayAndIsThenConsumed() {
        AutoPacer pacer = new AutoPacer(new SplittableRandom(1));
        assertFalse("Arming never clicks", pacer.poll(5, 1_000, 400, false));
        assertFalse(pacer.poll(5, 1_200, 400, false));
        assertTrue("Past the longest delay of 500 ms", pacer.poll(5, 1_500, 400, false));
        // The same step again waits a fresh delay: a click that changed nothing is not repeated at once.
        assertFalse(pacer.poll(5, 1_501, 400, false));
        assertTrue(pacer.poll(5, 2_100, 400, false));
    }

    @Test
    public void everyDelayStaysWithinAQuarterOfTheSetting() {
        SplittableRandom random = new SplittableRandom(7);
        for (int run = 0; run < 200; run++) {
            AutoPacer pacer = new AutoPacer(random);
            pacer.poll(run, 0, 400, false);
            assertFalse("Not before 300 ms", pacer.poll(run, 299, 400, false));
            assertTrue("By 500 ms", pacer.poll(run, 500, 400, false));
        }
    }

    @Test
    public void aSlowStartWaitsTwiceAsLong() {
        AutoPacer pacer = new AutoPacer(new SplittableRandom(3));
        pacer.poll(0, 0, 400, true);
        assertFalse(pacer.poll(0, 599, 400, true));
        assertTrue(pacer.poll(0, 1_000, 400, true));
    }

    @Test
    public void thePingComesOffTheWaitDownToTheShortestWait() {
        assertEquals(120, AutoPacer.waitFor(120, 0));
        assertEquals("The ping is part of the time to the server", 70, AutoPacer.waitFor(120, 50));
        assertEquals(AutoPacer.MIN_WAIT_MILLIS, AutoPacer.waitFor(60, 200));
        assertEquals("A negative reading counts as none", 120, AutoPacer.waitFor(120, -5));
    }

    @Test
    public void aNewStepRestartsTheWaitAndIdleForgetsTheArmedOne() {
        AutoPacer pacer = new AutoPacer(new SplittableRandom(5));
        pacer.poll(1, 0, 400, false);
        assertFalse("Step 2 starts its own wait", pacer.poll(2, 450, 400, false));
        assertTrue(pacer.poll(2, 950, 400, false));

        pacer.poll(3, 2_000, 400, false);
        pacer.idle();
        assertFalse("Idle re-arms", pacer.poll(3, 2_600, 400, false));
    }
}
