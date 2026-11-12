package com.github.beng420.kung.feature.crimson;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.crimson.MiniBossTimer.Kind;
import org.junit.Test;

public final class MiniBossTimerTest {
    private static final long T = 1_000_000;
    private static final long DUE = MiniBossTimer.DOWN_TO_SPAWN;

    @Test
    public void aSeenDeathGivesTheTimeThenSoonThenGivesUp() {
        var timer = new MiniBossTimer();
        timer.down(T);
        var due = timer.estimate(T + 100_000);
        assertEquals(Kind.KNOWN, due.kind());
        assertEquals(T + DUE, due.to());
        assertEquals("23s", timer.text(T + 100_000).text());
        assertEquals(0xFFAA00, timer.text(T + 115_000).color()); // the last 10 seconds
        // Not spawned until something shows it: the respawn varies by a second or two.
        assertEquals(Kind.SOON, timer.estimate(T + DUE + 1).kind());
        assertEquals("Soon!", timer.text(T + DUE + 1).text());
        assertEquals("Soon!", timer.text(T + DUE + MiniBossTimer.SOON_HOLD).text());
        assertEquals(Kind.UNKNOWN, timer.estimate(T + DUE + MiniBossTimer.SOON_HOLD + 1).kind());
    }

    @Test
    public void aBeaconSeenWithoutTheDeathGivesARangeThatTightensAsItStands() {
        var timer = new MiniBossTimer();
        timer.observe(T, true, true, false, false);
        assertEquals("~59s - 2m 4s", timer.text(T).text());
        timer.observe(T + 30_000, true, true, false, false);
        var range = timer.estimate(T + 30_000);
        assertEquals(Kind.RANGE, range.kind());
        assertEquals(T + 30_000 + MiniBossTimer.DOWN_TO_SPAWN_MIN - MiniBossTimer.BEACON_MAX, range.from());
        assertEquals(T + MiniBossTimer.DOWN_TO_SPAWN_MAX, range.to());
        assertEquals(Kind.SOON, timer.estimate(T + 30_000 + MiniBossTimer.DOWN_TO_SPAWN_MAX).kind());
    }

    @Test
    public void aBeaconSeenToEndPinpointsTheRespawn() {
        var timer = new MiniBossTimer();
        timer.observe(T, true, true, true, false);
        timer.observe(T + 1_000, true, true, true, false);
        timer.observe(T + 40_000, true, false, true, false);
        var due = timer.estimate(T + 41_000);
        assertEquals(Kind.KNOWN, due.kind());
        assertEquals(T + 40_000 + MiniBossTimer.OFF_TO_SPAWN, due.to());
    }

    @Test
    public void aBeaconFoundGoneAfterBeingAwayOnlyCapsTheRange() {
        var timer = new MiniBossTimer();
        timer.observe(T, true, true, false, false);
        timer.observe(T + 1_000, false, false, false, false); // walked off: no view
        timer.observe(T + 10_000, true, false, false, false); // back, beacon gone
        var range = timer.estimate(T + 10_000);
        assertEquals(Kind.RANGE, range.kind());
        assertEquals(T + 10_000 + MiniBossTimer.OFF_TO_SPAWN_MAX, range.to());
    }

    @Test
    public void noBossAndNoBeaconMeansDeadButProvesNothingAboutTheBeacon() {
        var timer = new MiniBossTimer();
        for (long t = T; t < T + 2_500; t += 500) timer.observe(t, true, false, true, false);
        assertEquals(Kind.UNKNOWN, timer.estimate(T + 2_500).kind()); // not looked long enough yet
        for (long t = T + 2_500; t <= T + 5_000; t += 500) timer.observe(t, true, false, true, false);
        var range = timer.estimate(T + 5_000);
        assertEquals(Kind.RANGE, range.kind());
        assertEquals(T + 5_000, range.from()); // not back yet, or the boss would show
        // No beacon seen does not mean it is gone: the whole wait counts, never just a minute.
        assertEquals(T + MiniBossTimer.DOWN_TO_SPAWN_MAX, range.to());
        assertEquals("~Now - 1m 59s", timer.text(T + 5_000).text());
        assertEquals(Kind.SOON, timer.estimate(T + MiniBossTimer.DOWN_TO_SPAWN_MAX + 1).kind());
    }

    @Test
    public void aBeaconThatShowsASecondAfterTheWarpIsNotMissed() {
        var timer = new MiniBossTimer();
        timer.observe(T - 10_000, false, false, false, false);
        timer.observe(T, true, false, true, false); // warped in, nothing yet
        timer.observe(T + 1_100, true, true, true, false); // the beacon arrives
        for (long t = T + 1_600; t <= T + 6_000; t += 500) timer.observe(t, true, true, true, false);
        var range = timer.estimate(T + 6_000);
        assertEquals(Kind.RANGE, range.kind()); // a beacon seen standing, the death unseen
        assertEquals(T + 1_100 + MiniBossTimer.DOWN_TO_SPAWN_MAX, range.to());
    }

    @Test
    public void aBossThatFlickersNeedsALongerLookBeforeItIsGone() {
        var timer = new MiniBossTimer(8_000);
        for (long t = T; t <= T + 7_500; t += 500) timer.observe(t, true, false, true, false);
        assertEquals(Kind.UNKNOWN, timer.estimate(T + 7_500).kind());
        timer.observe(T + 8_000, true, false, true, false);
        assertEquals(Kind.RANGE, timer.estimate(T + 8_000).kind());
    }

    @Test
    public void closedViewsSayNothing() {
        var timer = new MiniBossTimer();
        for (long t = T; t <= T + 20_000; t += 500) timer.observe(t, true, false, false, false); // beacon view only
        assertEquals(Kind.UNKNOWN, timer.estimate(T + 20_000).kind()); // the boss may just be far
    }

    @Test
    public void aBossSeenIsAliveEvenFromOutsideTheView() {
        var timer = new MiniBossTimer();
        timer.observe(T, true, false, false, true);
        assertEquals(Kind.ALIVE, timer.estimate(T).kind());
    }

    @Test
    public void onlyTheBossItselfIsAliveNotItsDyingNameplate() {
        var timer = new MiniBossTimer();
        timer.down(T);
        timer.observe(T + 1_000, true, true, true, true); // 0-health nameplate beside the new beacon
        assertEquals(Kind.KNOWN, timer.estimate(T + 1_000).kind());
        timer.observe(T + 3_000, true, false, true, true); // no beacon, still inside the 5 s after the message
        assertEquals(Kind.KNOWN, timer.estimate(T + 3_000).kind());
        timer.observe(T + 6_000, true, false, true, true);
        assertEquals(Kind.ALIVE, timer.estimate(T + 6_000).kind());
        assertEquals("Spawned", timer.text(T + 6_000).text());
        assertEquals(Kind.ALIVE, timer.estimate(T + 66_000).kind());
        assertEquals(Kind.UNKNOWN, timer.estimate(T + 66_001).kind());
    }

    @Test
    public void aBeaconAppearingInAnOpenViewIsTheDeathEvenWithoutTheDownMessage() {
        var timer = new MiniBossTimer();
        // Standing by while others fight: the boss in view, the beacon view open and empty.
        for (long t = T; t < T + 5_000; t += 100) timer.observe(t, true, false, true, true);
        assertEquals(Kind.ALIVE, timer.estimate(T + 4_900).kind());
        // It dies: the beacon appears, the nameplate lingers a moment.
        timer.observe(T + 5_000, true, true, true, true);
        timer.observe(T + 5_100, true, true, true, true);
        var due = timer.estimate(T + 5_100);
        assertEquals(Kind.KNOWN, due.kind());
        assertEquals(T + 5_000 - MiniBossTimer.BEACON_AFTER_DOWN + DUE, due.to());
    }

    @Test
    public void aBeaconInAViewThatJustOpenedIsNoDeath() {
        var timer = new MiniBossTimer();
        timer.observe(T, true, false, false, false);
        timer.observe(T + 500, true, true, false, false); // chunk still loading, or just arrived
        assertEquals(Kind.RANGE, timer.estimate(T + 500).kind());
    }

    @Test
    public void theDownMessageIsNotMovedByTheBeaconThatFollowsIt() {
        var timer = new MiniBossTimer();
        timer.observe(T - 5_000, true, false, false, false);
        timer.observe(T - 4_000, true, false, false, false);
        timer.down(T);
        timer.observe(T + 60, true, true, false, false);
        assertEquals(T + DUE, timer.estimate(T + 100).to());
    }

    @Test
    public void aBeaconAfterTheEndOfACycleStartsANewOne() {
        var timer = new MiniBossTimer();
        timer.observe(T, true, true, false, false);
        timer.observe(T + 40_000, true, false, false, false);
        assertEquals(T + 40_000 + MiniBossTimer.OFF_TO_SPAWN, timer.estimate(T + 41_000).to());
        // Killed again while we watched the beacon view: the appearance is the death.
        timer.observe(T + 150_000, true, true, false, false);
        assertEquals(T + 150_000 - MiniBossTimer.BEACON_AFTER_DOWN + DUE, timer.estimate(T + 150_000).to());
        // Killed again while we were away: only a range.
        var away = new MiniBossTimer();
        away.observe(T, true, true, false, false);
        away.observe(T + 40_000, true, false, false, false);
        away.observe(T + 100_000, false, false, false, false);
        away.observe(T + 150_000, true, true, false, false);
        var range = away.estimate(T + 150_000);
        assertEquals(Kind.RANGE, range.kind());
        assertEquals(T + 150_000 + MiniBossTimer.DOWN_TO_SPAWN_MAX, range.to());
    }

    @Test
    public void theSpawningMessageIsAliveAndANewDeathRestarts() {
        var timer = new MiniBossTimer();
        timer.spawned(T);
        assertEquals(Kind.ALIVE, timer.estimate(T + 10_000).kind());
        timer.down(T + 8_000);
        assertEquals(T + 8_000 + DUE, timer.estimate(T + 9_000).to());
    }

    @Test
    public void theClockCountsAStartedSecondAsWhole() {
        assertEquals("0s", MiniBossTimer.clock(0));
        assertEquals("1s", MiniBossTimer.clock(1));
        assertEquals("59s", MiniBossTimer.clock(59_000));
        assertEquals("1m 0s", MiniBossTimer.clock(59_001));
        assertEquals("2m 4s", MiniBossTimer.clock(124_000));
    }
}
