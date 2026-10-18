package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GreenhouseBackoffTest {
    private static final long TEN_SECONDS = 10_000_000_000L;

    @Test
    public void aFreshLatchLetsTheCallbackRun() {
        long[] now = {0};
        assertTrue(new GreenhouseBackoff(TEN_SECONDS, () -> now[0]).ready());
    }

    @Test
    public void aFailureHoldsTheCallbackOffUntilTheDelayHasPassedThenRearms() {
        long[] now = {5_000_000_000L};
        GreenhouseBackoff backoff = new GreenhouseBackoff(TEN_SECONDS, () -> now[0]);

        backoff.trip();
        assertFalse("right after the failure", backoff.ready());
        now[0] += TEN_SECONDS - 1;
        assertFalse("one tick early", backoff.ready());
        now[0] += 1;
        assertTrue("the delay has passed", backoff.ready());
        assertTrue("and it stays armed", backoff.ready());
    }

    @Test
    public void aFailureOnTheRetryRestartsTheWait() {
        long[] now = {0};
        GreenhouseBackoff backoff = new GreenhouseBackoff(TEN_SECONDS, () -> now[0]);
        backoff.trip();
        now[0] = TEN_SECONDS;
        assertTrue(backoff.ready());

        backoff.trip();

        assertFalse(backoff.ready());
        now[0] = 2 * TEN_SECONDS - 1;
        assertFalse(backoff.ready());
        now[0] = 2 * TEN_SECONDS;
        assertTrue(backoff.ready());
    }

    @Test
    public void aClockThatWrapsAroundStillCountsTheDelay() {
        long[] now = {Long.MAX_VALUE - 1_000};
        GreenhouseBackoff backoff = new GreenhouseBackoff(TEN_SECONDS, () -> now[0]);

        backoff.trip();
        now[0] += 5_000_000_000L;
        assertFalse("half the delay, across the wrap", backoff.ready());
        now[0] += 5_000_000_000L;
        assertTrue(backoff.ready());
    }
}
