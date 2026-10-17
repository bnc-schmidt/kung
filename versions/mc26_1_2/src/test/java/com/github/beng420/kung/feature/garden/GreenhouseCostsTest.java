package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseCosts.Basis;
import com.github.beng420.kung.feature.garden.GreenhouseCosts.CostMode;
import com.github.beng420.kung.feature.garden.GreenhouseCosts.Quote;
import com.github.beng420.kung.feature.garden.GreenhouseCosts.Tier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GreenhouseCostsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private GreenhousePrices prices(Map<String, Double> buyPrices) {
        return GreenhousePricesTest.fixed(temporary.getRoot().toPath().resolve("kung").resolve("p.json"), buyPrices);
    }

    private static Map<String, Quote> bazaar(GreenhousePrices prices) { return GreenhouseCosts.quotes(CostMode.BAZAAR, prices); }

    @Test
    public void everyCropHasAFiniteNonNegativeCostInEveryModeAndPriceState() {
        List<GreenhousePrices> sources = new ArrayList<>();
        sources.add(null);
        sources.add(prices(Map.of()));
        sources.add(prices(Map.of("MELON", 4.0, "THORNSHADE", 177_845.06, "CONDENSED_HELIANTHUS", 4_618_021.47,
            "INK_SACK:3", 8.65)));
        for (CostMode mode : CostMode.values()) {
            for (GreenhousePrices source : sources) {
                Map<String, Double> costs = GreenhouseCosts.costs(mode, source);
                assertEquals(GreenhouseCrops.NAMES.size(), costs.size());
                assertEquals(65, costs.size());
                for (String crop : GreenhouseCrops.NAMES) {
                    double cost = costs.get(crop);
                    assertTrue(mode + " " + crop, Double.isFinite(cost) && cost >= 0 && cost <= GreenhouseCosts.MAX_COST);
                    if (!GreenhouseCosts.FREE_CROPS.contains(crop)) assertTrue(mode + " " + crop, cost > 0);
                }
            }
        }
    }

    @Test
    public void everyCropFallsIntoExactlyOneGroup() {
        Map<Tier, Integer> counts = new EnumMap<>(Tier.class);
        for (String crop : GreenhouseCrops.NAMES) counts.merge(GreenhouseCosts.tierOf(crop), 1, Integer::sum);

        assertEquals(Integer.valueOf(2), counts.get(Tier.FREE));
        assertEquals(Integer.valueOf(14), counts.get(Tier.BASE));
        assertEquals(Integer.valueOf(9), counts.get(Tier.SPECIAL));
        assertEquals(Integer.valueOf(9), counts.get(Tier.COMMON));
        assertEquals(Integer.valueOf(6), counts.get(Tier.UNCOMMON));
        assertEquals(Integer.valueOf(10), counts.get(Tier.RARE));
        assertEquals(Integer.valueOf(8), counts.get(Tier.EPIC));
        assertEquals(Integer.valueOf(7), counts.get(Tier.LEGENDARY));
        assertTrue(GreenhouseCrops.NAMES.containsAll(GreenhouseCosts.SPECIAL_CROPS));
        assertEquals(Tier.BASE, GreenhouseCosts.tierOf("Moonflower"));
        assertEquals(Tier.RARE, GreenhouseCosts.tierOf("do not eat shroom"));
        assertEquals(Tier.FREE, GreenhouseCosts.tierOf("dead plants"));
    }

    @Test
    public void deadPlantsAndFireAreFreeInBothModes() {
        for (CostMode mode : CostMode.values()) {
            Map<String, Quote> quotes = GreenhouseCosts.quotes(mode, prices(Map.of("MELON", 4.0)));
            for (String crop : List.of("Dead Plants", "Fire")) {
                assertEquals(new Quote(0, Basis.FREE), quotes.get(crop));
                assertEquals("free", GreenhouseCosts.describe(crop, quotes));
            }
        }
    }

    @Test
    public void ironmanDifficultyRisesWithRarity() {
        Map<String, Double> costs = GreenhouseCosts.costs(CostMode.IRONMAN, null);

        double previous = 0;
        for (Tier tier : List.of(Tier.BASE, Tier.COMMON, Tier.UNCOMMON, Tier.RARE, Tier.EPIC, Tier.LEGENDARY)) {
            assertTrue(tier.name(), tier.difficulty() > previous);
            previous = tier.difficulty();
        }
        assertEquals(1, costs.get("Wheat Seeds"), 0);
        assertEquals(3, costs.get("Choconut"), 0);
        assertEquals(9, costs.get("Chocoberry"), 0);
        assertEquals(27, costs.get("Blastberry"), 0);
        assertEquals(81, costs.get("Startlevine"), 0);
        assertEquals(243, costs.get("Glasscorn"), 0);
        assertEquals(27, costs.get("Fermento"), 0);
        // Epic Shellfruit and Legendary Godseed follow their own rarity, not their recipe.
        assertEquals(81, costs.get("Shellfruit"), 0);
        assertEquals(243, costs.get("Godseed"), 0);
    }

    @Test
    public void ironmanIgnoresThePrices() {
        assertEquals(GreenhouseCosts.costs(CostMode.IRONMAN, null),
            GreenhouseCosts.costs(CostMode.IRONMAN, prices(Map.of("MELON", 4.0, "THORNSHADE", 9e6))));
    }

    @Test
    public void bazaarUsesMeasuredPricesWithColonIdsAndSeedStandIns() {
        Map<String, Quote> quotes = bazaar(prices(Map.of("INK_SACK:3", 8.65, "MELON", 4.0, "PUMPKIN", 42.0,
            "SEEDS", 2.8895888, "DOUBLE_PLANT", 8.48, "CHOCONUT", 1886.81)));

        assertEquals(new Quote(8.65, Basis.MEASURED), quotes.get("Cocoa Beans"));
        // Melon Seeds are planted from a Melon Slice, one each; one Pumpkin gives four stems.
        assertEquals(new Quote(4.0, Basis.MEASURED), quotes.get("Melon Seeds"));
        assertEquals(new Quote(10.5, Basis.MEASURED), quotes.get("Pumpkin Seeds"));
        // Rounded to two decimals.
        assertEquals(new Quote(2.89, Basis.MEASURED), quotes.get("Wheat Seeds"));
        assertEquals(new Quote(8.48, Basis.MEASURED), quotes.get("Sunflower"));
        assertEquals(new Quote(1886.81, Basis.MEASURED), quotes.get("Choconut"));
    }

    @Test
    public void aCropWithoutABazaarProductIsEstimatedFromItsRarityPeers() {
        // Dustgrain and Thornshade showed "No data" in game; Jerryflower is not on the Bazaar at all.
        Map<String, Double> listed = new LinkedHashMap<>();
        listed.put("CHOCONUT", 1000.0);
        listed.put("ASHWREATH", 3000.0);
        listed.put("LONELILY", 2000.0);
        listed.put("CHOCOBERRY", 100_000.0);
        listed.put("CINDERSHADE", 200_000.0);
        Map<String, Quote> quotes = bazaar(prices(listed));

        // Odd number of Common prices: the middle one; both unpriced Commons get it.
        assertEquals(new Quote(2000, Basis.ESTIMATED), quotes.get("Dustgrain"));
        assertEquals(new Quote(2000, Basis.ESTIMATED), quotes.get("Gloomgourd"));
        // Even number of Uncommon prices: the mean of the middle two.
        assertEquals(new Quote(150_000, Basis.ESTIMATED), quotes.get("Thornshade"));
        assertEquals(new Quote(1000, Basis.MEASURED), quotes.get("Choconut"));
    }

    @Test
    public void estimatesComeFromTheSameGroupOnly() {
        Map<String, Quote> quotes = bazaar(prices(Map.of("CHOCONUT", 1000.0, "CARROT_ITEM", 6.0, "POTATO_ITEM", 12.0,
            "FERMENTO", 250_000.0)));

        assertEquals(new Quote(1000, Basis.ESTIMATED), quotes.get("Dustgrain"));
        assertEquals(new Quote(Tier.UNCOMMON.fallbackCoins(), Basis.ESTIMATED), quotes.get("Thornshade"));
        assertEquals(new Quote(9, Basis.ESTIMATED), quotes.get("Wild Rose"));
        // Specials are their own group: Fermento prices the other Garden items, not the mutations.
        assertEquals(new Quote(250_000, Basis.ESTIMATED), quotes.get("Squash"));
        assertEquals(new Quote(250_000, Basis.ESTIMATED), quotes.get("Jerryseed"));
        assertEquals(new Quote(Tier.RARE.fallbackCoins(), Basis.ESTIMATED), quotes.get("Blastberry"));
    }

    @Test
    public void withoutAnyPriceEveryCropUsesTheRarityTable() {
        for (GreenhousePrices source : new GreenhousePrices[] {null, prices(Map.of())}) {
            Map<String, Quote> quotes = bazaar(source);
            for (String crop : GreenhouseCrops.NAMES) {
                Tier tier = GreenhouseCosts.tierOf(crop);
                if (tier == Tier.FREE) continue;
                assertEquals(crop, new Quote(tier.fallbackCoins(), Basis.ESTIMATED), quotes.get(crop));
            }
        }
        double previous = 0;
        for (Tier tier : List.of(Tier.BASE, Tier.COMMON, Tier.UNCOMMON, Tier.RARE, Tier.EPIC, Tier.LEGENDARY)) {
            assertTrue(tier.name(), tier.fallbackCoins() > previous);
            previous = tier.fallbackCoins();
        }
    }

    @Test
    public void absurdAndTinyPricesStayInTheSaneRange() {
        Map<String, Quote> quotes = bazaar(prices(Map.of("CHOCONUT", 5.0e8, "SEEDS", 0.001, "CARROT_ITEM", 0.004)));

        assertEquals(GreenhouseCosts.MAX_COST, quotes.get("Choconut").cost(), 0);
        // Never rounded down to free, which is reserved for Dead Plants and Fire.
        assertEquals(GreenhouseCosts.MIN_PRICE, quotes.get("Wheat Seeds").cost(), 0);
        assertEquals(GreenhouseCosts.MIN_PRICE, quotes.get("Carrot").cost(), 0);
    }

    @Test
    public void describeNamesTheSourceOfEachNumber() {
        Map<String, Quote> bazaar = bazaar(prices(Map.of("CHOCONUT", 1234.4, "SEEDS", 2.8895888, "ASHWREATH", 3000.0)));
        Map<String, Quote> ironman = GreenhouseCosts.quotes(CostMode.IRONMAN, null);

        assertEquals("1,234 coins (bazaar)", GreenhouseCosts.describe("Choconut", bazaar));
        assertEquals("2.9 coins (bazaar)", GreenhouseCosts.describe("Wheat Seeds", bazaar));
        assertEquals("~2,117 coins (estimated)", GreenhouseCosts.describe("Dustgrain", bazaar));
        assertEquals("difficulty 27 (Rare)", GreenhouseCosts.describe("Blastberry", ironman));
        assertEquals("difficulty 1 (base crop)", GreenhouseCosts.describe("Carrot", ironman));
        assertEquals("difficulty 3 (Common)", GreenhouseCosts.describe("Dustgrain", ironman));
        assertEquals("difficulty 27 (special)", GreenhouseCosts.describe("Fermento", ironman));
        assertEquals("difficulty 27 (Rare)", GreenhouseCosts.describe("do not eat shroom", ironman));
        assertEquals("unknown", GreenhouseCosts.describe("Moon Cheese", ironman));
    }

    @Test
    public void totalSumsCostTimesCellsAndSkipsWhatIsUnknown() {
        Map<String, Double> costs = Map.of("Carrot", 7.5, "Choconut", 1000.0, "Dead Plants", 0.0);
        Map<String, Integer> list = new HashMap<>();
        list.put("Carrot", 10);
        list.put("choconut", 3);
        list.put("Dead Plants", 20);
        list.put("Mystery Crop", 5);
        list.put("Fire", null);
        list.put("Cactus", -4);

        assertEquals(75 + 3000, GreenhouseCosts.total(list, costs), 1e-9);
        assertEquals(0, GreenhouseCosts.total(Map.of(), costs), 0);
    }

    @Test
    public void formatTotalIsShortForTheSummaryLine() {
        assertEquals("12 coins", GreenhouseCosts.formatTotal(12, CostMode.BAZAAR));
        assertEquals("3,500 coins", GreenhouseCosts.formatTotal(3500, CostMode.BAZAAR));
        assertEquals("125.0k coins", GreenhouseCosts.formatTotal(125_000, CostMode.BAZAAR));
        assertEquals("1.25M coins", GreenhouseCosts.formatTotal(1_250_000, CostMode.BAZAAR));
        assertEquals("4.60B coins", GreenhouseCosts.formatTotal(4.6e9, CostMode.BAZAAR));
        assertEquals("difficulty 340", GreenhouseCosts.formatTotal(340, CostMode.IRONMAN));
        assertEquals("difficulty 1,010", GreenhouseCosts.formatTotal(1010, CostMode.IRONMAN));
    }

    @Test
    public void theMapsCannotBeChangedByTheCaller() {
        Map<String, Double> costs = GreenhouseCosts.costs(CostMode.BAZAAR, null);

        assertThrows(UnsupportedOperationException.class, () -> costs.put("Carrot", 0.0));
        assertThrows(UnsupportedOperationException.class,
            () -> GreenhouseCosts.quotes(CostMode.IRONMAN, null).put("Carrot", new Quote(0, Basis.FREE)));
    }
}
