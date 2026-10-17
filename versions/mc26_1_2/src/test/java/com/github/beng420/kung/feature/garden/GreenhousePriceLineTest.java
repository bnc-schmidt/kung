package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.feature.garden.GreenhouseCosts.CostMode;
import com.github.beng420.kung.feature.garden.GreenhousePrices.Status;
import org.junit.Test;

public final class GreenhousePriceLineTest {
    private static final long MINUTE = 60_000L;

    @Test
    public void ironmanIgnoresThePriceSourceEntirely() {
        assertEquals("Ironman: by rarity", GreenhousePriceLine.of(CostMode.IRONMAN, Status.FRESH, 0, false));
        assertEquals("Ironman: by rarity", GreenhousePriceLine.of(CostMode.IRONMAN, Status.OFFLINE, -1, true));
        assertEquals("Ironman: by rarity", GreenhousePriceLine.of(CostMode.IRONMAN, null, -1, false));
    }

    @Test
    public void bazaarSaysHowFreshThePricesAre() {
        assertEquals("Bazaar prices: fresh just now", GreenhousePriceLine.of(CostMode.BAZAAR, Status.FRESH, 5_000, false));
        assertEquals("Bazaar prices: fresh 2 min ago",
            GreenhousePriceLine.of(CostMode.BAZAAR, Status.FRESH, 2 * MINUTE, false));
        // Old prices say so, whether or not a refresh is already under way.
        assertEquals("Bazaar prices: stale, 15 min ago",
            GreenhousePriceLine.of(CostMode.BAZAAR, Status.STALE, 15 * MINUTE, false));
        assertEquals("Bazaar prices: stale, 15 min ago, updating...",
            GreenhousePriceLine.of(CostMode.BAZAAR, Status.STALE, 15 * MINUTE, true));
    }

    @Test
    public void bazaarSaysWhenItIsOfflineOrHasNothingYet() {
        assertEquals("Bazaar offline: using estimates", GreenhousePriceLine.of(CostMode.BAZAAR, Status.OFFLINE, -1, false));
        assertEquals("Bazaar offline: prices from 3 h ago",
            GreenhousePriceLine.of(CostMode.BAZAAR, Status.OFFLINE, 3 * 60 * MINUTE, false));
        assertEquals("Bazaar prices: loading...", GreenhousePriceLine.of(CostMode.BAZAAR, Status.NONE, -1, true));
        assertEquals("Bazaar prices: none yet, using estimates",
            GreenhousePriceLine.of(CostMode.BAZAAR, Status.NONE, -1, false));
        assertEquals("Bazaar: no price source, using estimates", GreenhousePriceLine.of(CostMode.BAZAAR, null, -1, false));
    }

    @Test
    public void agesAreRoundedToTheUnitThatReadsBest() {
        assertEquals("just now", GreenhousePriceLine.age(0));
        assertEquals("just now", GreenhousePriceLine.age(44_999));
        assertEquals("1 min ago", GreenhousePriceLine.age(45_000));
        assertEquals("1 min ago", GreenhousePriceLine.age(89_000));
        assertEquals("2 min ago", GreenhousePriceLine.age(100_000));
        assertEquals("89 min ago", GreenhousePriceLine.age(89 * MINUTE));
        assertEquals("2 h ago", GreenhousePriceLine.age(110 * MINUTE));
        assertEquals("47 h ago", GreenhousePriceLine.age(47 * 60 * MINUTE));
        assertEquals("2 d ago", GreenhousePriceLine.age(48 * 60 * MINUTE));
        assertEquals("just now", GreenhousePriceLine.age(-5));
    }
}
