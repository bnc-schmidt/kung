package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public final class DungeonApiEnrichmentTest {
    @SuppressWarnings("unchecked")
    private static Set<String> set(DungeonApiEnrichment api, String field) throws Exception {
        var reflected = DungeonApiEnrichment.class.getDeclaredField(field);
        reflected.setAccessible(true);
        return (Set<String>) reflected.get(api);
    }

    @Test
    public void aPlayerWhoseEndValueIsRequestedGetsNoSecondTotalLookup() throws Exception {
        var api = new DungeonApiEnrichment();
        set(api, "requestedFinals").add("alice");
        // A second lookup would reach the null client and throw: the skip must come first.
        api.requestTotal(null, "Alice", result -> { });
        assertTrue(set(api, "requestedTotals").isEmpty());
    }

    @Test
    public void theLastEndValueStandsInForAStartValueOnlyWhileItIsYoung() {
        var api = new DungeonApiEnrichment();
        long[] now = {1_000_000L};
        api.clock = () -> now[0];
        assertNull(api.endValueOf("alice"));
        api.rememberEndValue("alice", 480);
        api.rememberEndValue("bob", -1);
        now[0] += 9 * 60_000L;
        assertEquals(Integer.valueOf(480), api.endValueOf("alice"));
        assertNull("a hidden counter is no value", api.endValueOf("bob"));
        api.reset();
        assertEquals("a new run keeps it", Integer.valueOf(480), api.endValueOf("alice"));
        now[0] += 2 * 60_000L;
        assertNull(api.endValueOf("alice"));
    }

    @Test
    public void theSummaryNamesTheLateAnswersWhenItStopsWaiting() throws Exception {
        var api = new DungeonApiEnrichment();
        var pending = DungeonApiEnrichment.class.getDeclaredField("pendingFinalFetches");
        pending.setAccessible(true);
        pending.setInt(api, 1);
        api.startFinalFetch(100);
        assertFalse(api.finalFetchCompleteOrTimedOut(299, 200));
        assertEquals("", api.problem());
        assertTrue(api.finalFetchCompleteOrTimedOut(300, 200));
        assertEquals("the answers came too late", api.problem());
        api.reset();
        assertEquals("", api.problem());
    }
}
