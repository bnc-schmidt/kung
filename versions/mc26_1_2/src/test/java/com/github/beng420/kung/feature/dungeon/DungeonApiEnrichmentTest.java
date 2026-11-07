package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
