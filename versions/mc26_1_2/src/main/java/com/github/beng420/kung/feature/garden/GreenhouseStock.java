package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whether the Sacks hold what a crop of the shopping list needs, as far as the Sack Tracker knows. The planner has
 * the tracker watch every crop's Sack item ({@link #sackNames()}) without showing it, so a crop whose sack was never
 * opened since (or with the tracker off) is {@link Level#UNKNOWN}, never "none". Plain Java: the screen plugs the
 * tracker in as a {@link Source}.
 *
 * <p>Amounts are plants, like {@link GreenhousePlan#crops()}: a Pumpkin in a Sack gives 4 Pumpkin Seeds stems
 * ({@link GreenhouseItems.Item#plantsPerItem}). The inventory and the plants already placed do not count here; that
 * is {@link GreenhouseSupply#plan}, which a click on the crop runs.
 */
final class GreenhouseStock {
    enum Level {
        /** The Sacks hold at least what is needed. */
        ENOUGH,
        /** The Sacks hold less than is needed. */
        SHORT,
        /** The tracker has no count for the item. */
        UNKNOWN,
        /** The crop is in no Sack, so there is nothing to look up. */
        NO_SACK
    }

    /** A tracked sack item's amount; {@code approximate}: it may be too low (marked "~" in the tracker). */
    record Count(long amount, boolean approximate) { }

    /** The tracker's count of a sack item by the item's name in the Sacks; empty when it has none. */
    @FunctionalInterface
    interface Source {
        Optional<Count> count(String sackName);
    }

    /**
     * @param needed     plants the layout needs
     * @param plants     plants the Sacks can give, 0 unless {@code level} is ENOUGH or SHORT
     * @param missing    {@code needed} minus {@code plants} when short, else 0
     * @param conversion "10 Pumpkin = 40 plants" for an item that yields several plants, else empty
     */
    record Stock(Level level, int needed, long plants, long missing, boolean approximate, String conversion) {
        /**
         * The short text beside the crop, "in Sacks/needed": {@code 8/6}, {@code 0/2}, {@code ?/2} when unknown, with a
         * "~" before a short count that may be low. A crop in no Sack shows only what is needed. A big stock is cut to
         * {@code 999+} so the text stays narrow; the tooltip has the exact amount.
         */
        String badge() {
            return switch (level) {
                case ENOUGH -> (plants > MAX_SHOWN ? MAX_SHOWN + "+" : String.valueOf(plants)) + "/" + needed;
                case SHORT -> (approximate ? "~" : "") + plants + "/" + needed;
                case UNKNOWN -> "?/" + needed;
                case NO_SACK -> String.valueOf(needed);
            };
        }

        /** The tooltip lines about the Sacks. */
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            switch (level) {
                case ENOUGH -> lines.add("Sacks: " + compact(plants) + " (enough)");
                case SHORT -> {
                    lines.add("Sacks: " + compact(plants) + " of " + needed + " (" + missing + " short)");
                    if (approximate) lines.add("The count may be low: open the sack for the exact value");
                }
                case UNKNOWN -> {
                    lines.add("Sacks: unknown");
                    lines.add("Needs the Sack Tracker on; open its sack once to read it");
                }
                case NO_SACK -> lines.add("Not in any Sack");
            }
            if (!conversion.isEmpty() && (level == Level.ENOUGH || level == Level.SHORT)) lines.add(conversion);
            return lines;
        }
    }

    /** The most a badge shows of a stock that covers the need. */
    private static final int MAX_SHOWN = 999;

    private GreenhouseStock() { }

    /** {@code needed} plants of {@code crop} against what {@code source} knows of its sack item. */
    static Stock of(String crop, int needed, Source source) {
        Optional<GreenhouseItems.Item> found = GreenhouseItems.find(crop);
        if (found.isEmpty() || !found.get().sackable()) return new Stock(Level.NO_SACK, needed, 0, 0, false, "");
        GreenhouseItems.Item item = found.get();
        for (String name : sackNames(item)) {
            Optional<Count> count = source.count(name);
            if (count.isEmpty()) continue;
            long plants = count.get().amount() * item.plantsPerItem();
            long missing = Math.max(0, needed - plants);
            String conversion = item.plantsPerItem() == 1 ? ""
                : compact(count.get().amount()) + " " + name + " = " + compact(plants) + " plants";
            return new Stock(missing == 0 ? Level.ENOUGH : Level.SHORT, needed, plants, missing,
                count.get().approximate(), conversion);
        }
        return new Stock(Level.UNKNOWN, needed, 0, 0, false, "");
    }

    /** Every name a crop in a Sack may go by, for the Sack Tracker to read; each once. */
    static List<String> sackNames() {
        Set<String> names = new LinkedHashSet<>();
        for (GreenhouseItems.Item item : GreenhouseItems.all()) {
            if (item.sackable()) names.addAll(sackNames(item));
        }
        return List.copyOf(names);
    }

    /** The names the Sacks may show the item under: what is pulled from them, the crop, then the chat spellings. */
    private static List<String> sackNames(GreenhouseItems.Item item) {
        List<String> names = new ArrayList<>();
        names.add(item.unit());
        if (!names.contains(item.crop())) names.add(item.crop());
        for (String name : item.chatNames()) if (!names.contains(name)) names.add(name);
        return names;
    }

    /** 1,234 up to 9,999, then 12.3k and 1.2m: the room beside a crop is small. */
    static String compact(long value) {
        if (value < 10_000) return String.format(Locale.ROOT, "%,d", value);
        if (value < 999_950) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return String.format(Locale.ROOT, "%,.1fm", value / 1_000_000.0);
    }
}
