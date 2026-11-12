package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class LobbyHopHelperFeatureTest {
    @Before
    @After
    public void clearHistory() {
        LobbyHopHelperFeature.clearHistory();
    }

    @Test
    public void revisitsUseLastPresenceAndOnlyAnnounceOncePerArrival() {
        assertEquals(-1L, visit("mini123", 0L));
        assertEquals(-1L, visit("mini123", 120_000L));
        assertEquals(-1L, visit("", 121_000L));
        assertEquals(-1L, visit("mini123", 122_000L));
        assertEquals(-1L, visit("mini456", 123_000L));
        assertEquals(65_000L, visit("mini123", 187_000L));
        assertEquals(-1L, visit("mini123", 188_000L));
        assertEquals(66_000L, visit("mini456", 189_000L));
        assertEquals(31_000L, visit("mini123", 219_000L));
        LobbyHopHelperFeature.clearHistory();
        assertEquals(-1L, visit("mini123", 220_000L));
    }

    @Test
    public void revisitReportsThePreviousStayFromArrivalToLeaving() {
        LobbyHopHelperFeature.recordVisit("mini123", 1_000L);
        LobbyHopHelperFeature.recordVisit("mini123", 5_000L);
        LobbyHopHelperFeature.recordVisit("mini456", 6_000L);
        assertEquals(new LobbyHopHelperFeature.Visit(1_000L, 5_000L), LobbyHopHelperFeature.recordVisit("mini123", 9_000L));
        assertEquals(new LobbyHopHelperFeature.Visit(6_000L, 6_000L), LobbyHopHelperFeature.recordVisit("mini456", 10_000L));
    }

    @Test
    public void theTitleAndSoundComeOnlyForALobbyLeftLessThanTheWarnTimeAgo() {
        assertTrue(LobbyHopHelperFeature.loud(29_999L, 30));
        assertFalse(LobbyHopHelperFeature.loud(30_000L, 30));
        assertFalse(LobbyHopHelperFeature.loud(65_000L, 30));
        assertTrue(LobbyHopHelperFeature.loud(65_000L, 300));
        // 0 never warns loudly, and the box may go past the slider's 300.
        assertFalse(LobbyHopHelperFeature.loud(0L, 0));
        assertTrue(LobbyHopHelperFeature.loud(1_000_000L, 1_000_000));
    }

    private static long visit(String lobbyId, long nowMillis) {
        var previous = LobbyHopHelperFeature.recordVisit(lobbyId, nowMillis);
        return previous == null ? -1L : nowMillis - previous.lastSeenMillis();
    }

    @Test
    public void historyStillEvictsTheOldestIdAfter128Lobbies() {
        for (int index = 0; index < 129; index++) {
            assertEquals(-1L, visit("mini" + index, index * 1_000L));
        }
        assertEquals(128_000L, visit("mini1", 129_000L));
        assertEquals(-1L, visit("mini0", 130_000L));
    }

    @Test
    public void lobbiesLeftMoreThanTwoHoursAgoCountAsUnknown() {
        long minute = 60_000L;
        visit("mini123", 0L);
        visit("mini456", 0L);
        visit("mini789", 0L);
        assertEquals(119 * minute, visit("mini123", 119 * minute));
        assertEquals(-1L, visit("mini456", 121 * minute));
    }

    @Test
    public void elapsedTimeUsesCompactMinutesAndWholeSeconds() {
        assertEquals("0s ago", LobbyHopHelperFeature.formatAgo(999L));
        assertEquals("31s ago", LobbyHopHelperFeature.formatAgo(31_999L));
        assertEquals("59s ago", LobbyHopHelperFeature.formatAgo(59_999L));
        assertEquals("1m0s ago", LobbyHopHelperFeature.formatAgo(60_000L));
        assertEquals("1m5s ago", LobbyHopHelperFeature.formatAgo(65_000L));
        assertEquals("60m5s ago", LobbyHopHelperFeature.formatAgo(3_605_000L));
    }
}
