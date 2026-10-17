package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseCosts.CostMode;
import com.github.beng420.kung.feature.garden.GreenhousePrices.Status;

/**
 * The one line that tells the player where the solver's costs come from: "Bazaar prices: fresh 2 min ago",
 * "Bazaar offline: using estimates" or "Ironman: by rarity". Plain text from plain inputs, so it is unit-tested.
 */
final class GreenhousePriceLine {
    static final String IRONMAN = "Ironman: by rarity";

    private GreenhousePriceLine() { }

    /**
     * @param status     the price source's state; null when there is no price source at all
     * @param ageMillis  age of the cached prices, negative when there are none
     * @param refreshing a fetch is under way
     */
    static String of(CostMode mode, Status status, long ageMillis, boolean refreshing) {
        if (mode == CostMode.IRONMAN) return IRONMAN;
        if (status == null) return "Bazaar: no price source, using estimates";
        return switch (status) {
            case FRESH -> "Bazaar prices: fresh " + age(ageMillis);
            case STALE -> "Bazaar prices: stale, " + age(ageMillis) + (refreshing ? ", updating..." : "");
            case OFFLINE -> ageMillis < 0 ? "Bazaar offline: using estimates"
                : "Bazaar offline: prices from " + age(ageMillis);
            case NONE -> refreshing ? "Bazaar prices: loading..." : "Bazaar prices: none yet, using estimates";
        };
    }

    /** "just now", "2 min ago", "3 h ago", "2 d ago". */
    static String age(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 45) return "just now";
        long minutes = Math.max(1, Math.round(seconds / 60.0));
        if (minutes < 90) return minutes + " min ago";
        long hours = Math.round(minutes / 60.0);
        if (hours < 48) return hours + " h ago";
        return Math.round(hours / 24.0) + " d ago";
    }
}
