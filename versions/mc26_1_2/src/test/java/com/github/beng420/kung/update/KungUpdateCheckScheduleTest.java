package com.github.beng420.kung.update;

import static org.junit.Assert.*;

import org.junit.Test;

public final class KungUpdateCheckScheduleTest {
    private static final long INTERVAL = KungUpdateCheckSchedule.INTERVAL_MILLIS;

    @Test public void checksImmediatelyAndThenStaysWithinGithubsHourlyLimit() {
        // 60 unauthenticated requests per hour are shared with release notes; keep polling to a couple.
        assertTrue(INTERVAL >= 30 * 60_000L);
        var schedule = new KungUpdateCheckSchedule();
        assertTrue(schedule.due(100));
        schedule.started(100);
        assertFalse(schedule.due(101));
        assertFalse(schedule.due(100 + INTERVAL - 1));
        assertTrue(schedule.due(100 + INTERVAL));
        schedule.started(100 + INTERVAL);
        assertFalse(schedule.due(100 + 2 * INTERVAL - 1));
        assertTrue(schedule.due(100 + 2 * INTERVAL));
    }

    @Test public void repeatedMenuOrJoinChecksCannotPostponeAnOverduePoll() {
        var schedule = new KungUpdateCheckSchedule();
        schedule.started(0);
        for (long now = 1; now < INTERVAL; now += INTERVAL / 600) assertFalse(schedule.due(now));
        assertTrue(schedule.due(INTERVAL));
        assertTrue(schedule.due(3 * INTERVAL)); // Busy worker: do not consume the interval until a check starts.
        schedule.started(3 * INTERVAL);
        assertFalse(schedule.due(3 * INTERVAL + 1));
        assertTrue(schedule.due(4 * INTERVAL));
    }

    @Test public void theButtonChecksAtOnceButNotInARowAndPostponesThePoll() {
        var schedule = new KungUpdateCheckSchedule();
        schedule.started(0);
        assertFalse(schedule.due(1_000)); // the button does not depend on the interval...
        assertTrue(schedule.manualAllowed(1_000));
        schedule.manualStarted(1_000);
        assertFalse(schedule.manualAllowed(1_000 + KungUpdateCheckSchedule.MANUAL_COOLDOWN_MILLIS - 1));
        assertTrue(schedule.manualAllowed(1_000 + KungUpdateCheckSchedule.MANUAL_COOLDOWN_MILLIS));
        // ...and the next automatic poll is a full interval after the manual one.
        assertFalse(schedule.due(1_000 + INTERVAL - 1));
        assertTrue(schedule.due(1_000 + INTERVAL));
    }

    @Test public void monotonicClockDoesNotRequireAPositiveEpoch() {
        var schedule = new KungUpdateCheckSchedule();
        assertTrue(schedule.due(-2 * INTERVAL));
        schedule.started(-2 * INTERVAL);
        assertFalse(schedule.due(-INTERVAL - 1));
        assertTrue(schedule.due(-INTERVAL));
    }
}
