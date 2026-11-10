package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DungeonSecretTallyTest {
    @Test
    public void theFirstReadingIsOnlyABaseline() {
        var tally = new DungeonSecretTally();
        assertFalse(tally.known());
        tally.action(0L);
        assertEquals(0, tally.total(12, 100L));
        assertTrue(tally.known());
        assertEquals(0, tally.own());
    }

    @Test
    public void anOwnActionBeforeTheRiseCountsAsOwn() {
        var tally = new DungeonSecretTally();
        tally.total(10, 0L);
        tally.action(1_000L);
        assertEquals(1, tally.total(11, 1_400L));
        assertEquals(1, tally.own());
    }

    @Test
    public void anOwnActionAfterTheRiseCountsAsOwn() {
        var tally = new DungeonSecretTally();
        tally.total(10, 0L);
        assertEquals(0, tally.total(11, 1_000L));
        assertTrue(tally.action(1_300L));
        assertEquals(1, tally.own());
    }

    @Test
    public void aRiseWithoutAnOwnActionBelongsToATeammate() {
        var tally = new DungeonSecretTally();
        tally.total(10, 0L);
        assertEquals(0, tally.total(13, 5_000L));
        assertEquals(0, tally.own());
    }

    @Test
    public void anActionAndARiseTooFarApartDoNotMatch() {
        var tally = new DungeonSecretTally();
        tally.total(10, 0L);
        tally.action(1_000L);
        assertEquals(0, tally.total(11, 1_000L + DungeonSecretTally.WINDOW_MILLIS + 1L));
        assertEquals(0, tally.own());
    }

    @Test
    public void oneActionClaimsOneRiseOfSeveral() {
        var tally = new DungeonSecretTally();
        tally.total(10, 0L);
        tally.action(1_000L);
        assertEquals(1, tally.total(12, 1_500L));
        assertEquals(1, tally.own());
    }

    @Test
    public void aResetForgetsTheBaselineAndTheCount() {
        var tally = new DungeonSecretTally();
        tally.total(10, 0L);
        tally.action(100L);
        tally.total(11, 200L);
        tally.reset();
        assertFalse(tally.known());
        assertEquals(0, tally.own());
    }
}
