package com.github.beng420.kung.feature.garden;

/**
 * Tells sustained farming from a few harvests. Client-thread state; times are milliseconds on a monotonic clock.
 * A run is a chain of harvests with no pause longer than {@link #MAX_GAP_MILLIS}; it counts as farming once it
 * holds {@link #MIN_HARVESTS} harvests over {@link #MIN_RUN_MILLIS}, so breaking three crops or walking through
 * a row never does.
 */
final class FarmingActivity {
    static final long MAX_GAP_MILLIS = 5_000;
    static final int MIN_HARVESTS = 40;
    static final long MIN_RUN_MILLIS = 15_000;

    private long runStart = -1;
    private long lastHarvest = -1;
    private int harvests;

    void harvest(long now) {
        if (lastHarvest < 0 || now - lastHarvest > MAX_GAP_MILLIS) {
            runStart = now;
            harvests = 0;
        }
        lastHarvest = now;
        harvests++;
    }

    boolean active(long now) {
        return lastHarvest >= 0 && now - lastHarvest <= MAX_GAP_MILLIS
            && harvests >= MIN_HARVESTS && lastHarvest - runStart >= MIN_RUN_MILLIS;
    }

    void reset() {
        runStart = -1;
        lastHarvest = -1;
        harvests = 0;
    }

    /** Farming a garden plot of the own Garden: the Barn, the Greenhouse and guest Gardens never count. */
    static boolean farmingInPlot(boolean ownGarden, boolean greenhouse, String plot, boolean runActive) {
        return ownGarden && !greenhouse && plot != null && runActive;
    }
}
