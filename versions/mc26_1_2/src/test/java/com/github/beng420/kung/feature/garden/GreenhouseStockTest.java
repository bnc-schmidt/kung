package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseStock.Count;
import com.github.beng420.kung.feature.garden.GreenhouseStock.Level;
import com.github.beng420.kung.feature.garden.GreenhouseStock.Source;
import com.github.beng420.kung.feature.garden.GreenhouseStock.Stock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public final class GreenhouseStockTest {
    /** A tracker that knows exactly these sack items; the amounts are not approximate. */
    private static Source tracked(Map<String, Long> amounts) {
        return name -> Optional.ofNullable(amounts.get(name)).map(amount -> new Count(amount, false));
    }

    private static final Source NOTHING = name -> Optional.empty();

    @Test
    public void aSackThatHoldsTheNeedIsEnoughEvenWithNothingToSpare() {
        Stock more = GreenhouseStock.of("Cindershade", 37, tracked(Map.of("Cindershade", 52L)));
        assertEquals(Level.ENOUGH, more.level());
        assertEquals(0, more.missing());
        assertEquals("52/37", more.badge());
        assertEquals(List.of("Sacks: 52 (enough)"), more.lines());

        Stock exact = GreenhouseStock.of("Cindershade", 37, tracked(Map.of("Cindershade", 37L)));
        assertEquals(Level.ENOUGH, exact.level());
    }

    @Test
    public void aSackBelowTheNeedNamesTheShortfall() {
        Stock stock = GreenhouseStock.of("Cindershade", 37, tracked(Map.of("Cindershade", 25L)));
        assertEquals(Level.SHORT, stock.level());
        assertEquals(12, stock.missing());
        assertEquals("25/37", stock.badge());
        assertEquals(List.of("Sacks: 25 of 37 (12 short)"), stock.lines());

        // Tracked, sack read, and empty: that is a shortfall of everything, not an unknown.
        Stock none = GreenhouseStock.of("Cindershade", 5, tracked(Map.of("Cindershade", 0L)));
        assertEquals(Level.SHORT, none.level());
        assertEquals(5, none.missing());
        assertEquals("0/5", none.badge());
    }

    @Test
    public void aCountThatMayBeLowIsMarkedOnlyWhenShort() {
        Source low = name -> Optional.of(new Count(25, true));
        Stock shortStock = GreenhouseStock.of("Cindershade", 37, low);
        assertEquals("~25/37", shortStock.badge());
        assertTrue(shortStock.lines().get(1), shortStock.lines().get(1).startsWith("The count may be low"));

        // Counts only run low, so a low-marked count that already covers the need is plain enough.
        Stock enough = GreenhouseStock.of("Cindershade", 20, low);
        assertEquals("25/20", enough.badge());
        assertEquals(1, enough.lines().size());
    }

    @Test
    public void aCropTheTrackerDoesNotKnowIsUnknownNotEmpty() {
        Stock stock = GreenhouseStock.of("Cindershade", 37, NOTHING);
        assertEquals(Level.UNKNOWN, stock.level());
        assertEquals("?/37", stock.badge());
        assertEquals(0, stock.missing());
        assertEquals("Sacks: unknown", stock.lines().get(0));
    }

    @Test
    public void aCropInNoSackHasNothingToLookUp() {
        for (String crop : List.of("Jerryflower", "Fire", "Rose Dragon Egg", "Fertilized Jerryseed")) {
            Stock stock = GreenhouseStock.of(crop, 3, name -> {
                throw new AssertionError("asked the tracker for " + name);
            });
            assertEquals(crop, Level.NO_SACK, stock.level());
            assertEquals("3", stock.badge()); // only the need
            assertEquals(List.of("Not in any Sack"), stock.lines());
        }
        assertEquals(Level.NO_SACK, GreenhouseStock.of("No such crop", 3, NOTHING).level());
    }

    @Test
    public void aPumpkinInTheSackGivesFourStems() {
        // Only the Pumpkin is in a Sack; 10 of them plant 40 Pumpkin Seeds stems.
        Source sacks = tracked(Map.of("Pumpkin", 10L));
        Stock enough = GreenhouseStock.of("Pumpkin Seeds", 40, sacks);
        assertEquals(Level.ENOUGH, enough.level());
        assertEquals(40, enough.plants());
        assertEquals(List.of("Sacks: 40 (enough)", "10 Pumpkin = 40 plants"), enough.lines());

        assertEquals("40/40", enough.badge());
        Stock shortStock = GreenhouseStock.of("Pumpkin Seeds", 41, sacks);
        assertEquals(Level.SHORT, shortStock.level());
        assertEquals(1, shortStock.missing());
    }

    @Test
    public void everySpellingTheSacksUseFindsTheItem() {
        // Melon Seeds are pulled as "Melon Slice", but the game's Sacks say "Melon".
        assertEquals(Level.ENOUGH, GreenhouseStock.of("Melon Seeds", 2, tracked(Map.of("Melon", 5L))).level());
        assertEquals(Level.ENOUGH, GreenhouseStock.of("Melon Seeds", 2, tracked(Map.of("Melon Slice", 5L))).level());
        assertEquals(Level.ENOUGH, GreenhouseStock.of("Wheat Seeds", 2, tracked(Map.of("Seeds", 5L))).level());
        // The crop list's loose matching of names applies.
        assertEquals(Level.ENOUGH,
            GreenhouseStock.of("do not eat shroom", 2, tracked(Map.of("Do-not-eat-shroom", 5L))).level());
    }

    @Test
    public void everySackableCropOnTheListGetsALevelFromItsOwnName() {
        for (GreenhouseItems.Item item : GreenhouseItems.all()) {
            Stock stock = GreenhouseStock.of(item.crop(), 1, tracked(Map.of(item.unit(), 1L)));
            assertEquals(item.crop(), item.sackable() ? Level.ENOUGH : Level.NO_SACK, stock.level());
        }
    }

    @Test
    public void theTrackerIsToldEveryNameACropInASackGoesByOnceAndNoOtherCrop() {
        List<String> names = GreenhouseStock.sackNames();
        assertEquals(names.size(), Set.copyOf(names).size());
        assertTrue(names.containsAll(List.of("Cindershade", "Melon Slice", "Melon", "Seeds", "Pumpkin", "Dead Plant")));
        for (String crop : List.of("Jerryflower", "Fire", "Rose Dragon Egg", "Fertilized Jerryseed")) {
            assertFalse(crop, names.contains(crop));
        }
    }

    @Test
    public void aBigStockKeepsTheBadgeNarrow() {
        assertEquals("999/6", GreenhouseStock.of("Carrot", 6, tracked(Map.of("Carrot", 999L))).badge());
        assertEquals("999+/6", GreenhouseStock.of("Carrot", 6, tracked(Map.of("Carrot", 1_000L))).badge());
        // The tooltip still has the exact amount.
        assertEquals("Sacks: 12.3k (enough)",
            GreenhouseStock.of("Carrot", 6, tracked(Map.of("Carrot", 12_345L))).lines().get(0));
    }

    @Test
    public void amountsShrinkToFitBesideTheCropName() {
        assertEquals("0", GreenhouseStock.compact(0));
        assertEquals("9,999", GreenhouseStock.compact(9_999));
        assertEquals("10.0k", GreenhouseStock.compact(10_000));
        assertEquals("12.3k", GreenhouseStock.compact(12_345));
        assertEquals("999.9k", GreenhouseStock.compact(999_949));
        assertEquals("1.0m", GreenhouseStock.compact(999_950));
        assertEquals("1.2m", GreenhouseStock.compact(1_234_567));
        assertFalse(GreenhouseStock.compact(2_000_000_000L).isEmpty());
    }
}
