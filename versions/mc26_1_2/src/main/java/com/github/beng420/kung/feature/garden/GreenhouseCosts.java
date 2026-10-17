package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What one placed plant of each crop costs the solver, so it can prefer cheap ingredients, share an expensive one
 * between several mutations and push free Dead Plants to the edge. Two weightings share one map shape (crop name to
 * a non-negative number per placed cell): {@link CostMode#BAZAAR} is coins, {@link CostMode#IRONMAN} is difficulty
 * by rarity. Another weighting is one more {@link CostMode} and one more branch in {@link #quotes}.
 */
final class GreenhouseCosts {
    enum CostMode { BAZAAR, IRONMAN }

    /** How a number came about, so a screen can mark guesses. */
    enum Basis { MEASURED, ESTIMATED, FREE, DIFFICULTY }

    /**
     * The groups a crop falls into, with the numbers each weighting uses. This table is the one place to retune.
     *
     * <p>{@code difficulty} (Ironman): about x3 per step because a mutation is grown from several plants of the
     * tier below it (an Epic needs several Rares, a Rare several Uncommons, ...), and an Ironman has to farm each of
     * those first. A plain base crop is the unit. Free crops are not farmed at all.
     *
     * <p>{@code fallbackCoins} (Bazaar): the price assumed for a group when not one of its crops has a Bazaar price.
     * Rounded medians of the 2026-10-17 Bazaar; Legendary is lifted to keep the table rising, the real median is
     * skewed by the cheap All-in Aloe and Phantomleaf.
     */
    enum Tier {
        FREE(0, 0, "free"),
        BASE(1, 8, "base crop"),
        COMMON(3, 2_000, "Common"),
        UNCOMMON(9, 100_000, "Uncommon"),
        RARE(27, 150_000, "Rare"),
        EPIC(81, 500_000, "Epic"),
        LEGENDARY(243, 750_000, "Legendary"),
        /** Garden and event items that are no mutation (Fermento, Squash, Jerryseed, ...): between Rare and Epic. */
        SPECIAL(27, 150_000, "special");

        private final double difficulty;
        private final double fallbackCoins;
        private final String label;

        Tier(double difficulty, double fallbackCoins, String label) {
            this.difficulty = difficulty;
            this.fallbackCoins = fallbackCoins;
            this.label = label;
        }

        double difficulty() { return difficulty; }
        double fallbackCoins() { return fallbackCoins; }
        String label() { return label; }
    }

    /** A cost and where it comes from. */
    record Quote(double cost, Basis basis) { }

    /** Dead Plants appear by themselves and Fire is a block: nothing to buy. */
    static final Set<String> FREE_CROPS = Set.of("Dead Plants", "Fire");
    /** Crops with no rarity that are not base crops either. Every other unrated, non-free crop is a base crop. */
    static final Set<String> SPECIAL_CROPS = Set.of("Helianthus", "Fermento", "Squash", "Cropie", "Ethereal Vine",
        "Condensed Helianthus", "Rose Dragon Egg", "Jerryseed", "Fertilized Jerryseed");
    /** Largest cost handed to the solver; Condensed Helianthus, the dearest crop, is about 4.6 million. */
    static final double MAX_COST = 100_000_000;
    /** A real price never rounds down to zero, which would make it free. */
    static final double MIN_PRICE = 0.01;

    private GreenhouseCosts() { }

    /** The group of a crop name (loose spelling is fine). */
    static Tier tierOf(String crop) {
        String name = GreenhouseCrops.canonical(crop);
        if (FREE_CROPS.contains(name)) return Tier.FREE;
        Optional<GreenhouseRarity.Rarity> rarity = GreenhouseRarity.of(name);
        if (rarity.isPresent()) return Tier.valueOf(rarity.get().name());
        return SPECIAL_CROPS.contains(name) ? Tier.SPECIAL : Tier.BASE;
    }

    /** A cost for every name on the crop list. {@code prices} may be null, then every Bazaar cost is an estimate. */
    static Map<String, Quote> quotes(CostMode mode, GreenhousePrices prices) {
        Map<String, Quote> out = new LinkedHashMap<>();
        if (mode == CostMode.IRONMAN) {
            for (String crop : GreenhouseCrops.NAMES) {
                Tier tier = tierOf(crop);
                out.put(crop, new Quote(tier.difficulty(), tier == Tier.FREE ? Basis.FREE : Basis.DIFFICULTY));
            }
            return Collections.unmodifiableMap(out);
        }
        Map<String, Double> measured = new LinkedHashMap<>();
        Map<Tier, List<Double>> byTier = new EnumMap<>(Tier.class);
        for (String crop : GreenhouseCrops.NAMES) {
            Tier tier = tierOf(crop);
            if (tier == Tier.FREE) continue;
            Optional<Double> price = perPlant(crop, prices);
            if (price.isEmpty()) continue;
            measured.put(crop, price.get());
            byTier.computeIfAbsent(tier, key -> new ArrayList<>()).add(price.get());
        }
        for (String crop : GreenhouseCrops.NAMES) {
            Tier tier = tierOf(crop);
            if (tier == Tier.FREE) {
                out.put(crop, new Quote(0, Basis.FREE));
            } else if (measured.containsKey(crop)) {
                out.put(crop, new Quote(sane(measured.get(crop)), Basis.MEASURED));
            } else {
                List<Double> peers = byTier.get(tier);
                out.put(crop, new Quote(sane(peers == null ? tier.fallbackCoins() : median(peers)), Basis.ESTIMATED));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** What the solver takes: {@link #quotes} without the basis. */
    static Map<String, Double> costs(CostMode mode, GreenhousePrices prices) {
        Map<String, Double> out = new LinkedHashMap<>();
        quotes(mode, prices).forEach((crop, quote) -> out.put(crop, quote.cost()));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Instant-buy price of one placed plant: the item's price over the plants one item gives (Melon Seeds are
     * planted from a Melon Slice, one each; one Pumpkin gives 4 stems). Empty without an item id or a price.
     */
    private static Optional<Double> perPlant(String crop, GreenhousePrices prices) {
        if (prices == null) return Optional.empty();
        return GreenhouseItems.find(crop).filter(item -> item.id() != null)
            .flatMap(item -> prices.unitPrice(item.id()).map(price -> price / item.plantsPerItem()));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    /** Coins to two decimals, within [{@value #MIN_PRICE}, {@value #MAX_COST}]: safe for the solver's fixed point. */
    private static double sane(double coins) {
        double clamped = Math.min(MAX_COST, Math.max(MIN_PRICE, coins));
        return Math.round(clamped * 100) / 100.0;
    }

    /** Tooltip text: "1,234 coins (bazaar)", "~800 coins (estimated)", "difficulty 27 (Rare)", "free". */
    static String describe(String crop, Map<String, Quote> quotes) {
        Quote quote = quotes.get(GreenhouseCrops.canonical(crop));
        if (quote == null) return "unknown";
        return switch (quote.basis()) {
            case FREE -> "free";
            case MEASURED -> coins(quote.cost()) + " (bazaar)";
            case ESTIMATED -> "~" + coins(quote.cost()) + " (estimated)";
            case DIFFICULTY -> "difficulty " + number(quote.cost()) + " (" + tierOf(crop).label() + ")";
        };
    }

    /** Cost of a shopping list (crop name to cells, like {@code GreenhousePlan.crops()}); crops without a cost count 0. */
    static double total(Map<String, Integer> shoppingList, Map<String, Double> costs) {
        double sum = 0;
        for (Map.Entry<String, Integer> entry : shoppingList.entrySet()) {
            Double cost = costs.get(GreenhouseCrops.canonical(entry.getKey()));
            if (cost != null && entry.getValue() != null && entry.getValue() > 0) sum += cost * entry.getValue();
        }
        return sum;
    }

    /** The total for the result summary: "1.25M coins" or "difficulty 340". */
    static String formatTotal(double total, CostMode mode) {
        if (mode == CostMode.IRONMAN) return "difficulty " + number(total);
        if (total < 10_000) return coins(total);
        if (total < 1_000_000) return String.format(Locale.ROOT, "%.1fk coins", total / 1_000);
        if (total < 1_000_000_000) return String.format(Locale.ROOT, "%.2fM coins", total / 1_000_000);
        return String.format(Locale.ROOT, "%.2fB coins", total / 1_000_000_000);
    }

    private static String coins(double value) {
        return value >= 10 ? String.format(Locale.ROOT, "%,.0f coins", value)
            : String.format(Locale.ROOT, "%.1f coins", value);
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%,.0f", value)
            : String.format(Locale.ROOT, "%.1f", value);
    }
}
