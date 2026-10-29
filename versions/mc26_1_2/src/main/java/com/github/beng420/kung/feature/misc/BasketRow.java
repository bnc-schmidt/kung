package com.github.beng420.kung.feature.misc;

import java.util.function.BiPredicate;

/**
 * How many seeds a basket takes for one right-click. Per the player: it counts the soil blocks ahead that are
 * connected and unobstructed inside the plot, and takes that many seeds at once. A garden plot is 96x96, so a row is
 * at most 96 long. Unverified in game: where the row starts and what counts as obstructed (see the "storage-hud" trace).
 */
final class BasketRow {
    static final int PLOT_SIZE = 96;
    /** Minimum block coordinate of the garden on both axes: 5 plots of 96 around the origin. */
    static final int GARDEN_MIN = -240;
    private static final int PLOT_COUNT = 5;

    private BasketRow() { }

    /**
     * The soil blocks in a row from (x, z) in the direction (dx, dz), counting from the start block on, up to the
     * first one that is not plantable or lies in another plot. Outside the garden a row is only capped at 96.
     */
    static int length(int x, int z, int dx, int dz, BiPredicate<Integer, Integer> plantable) {
        int plotX = plot(x);
        int plotZ = plot(z);
        int length = 0;
        while (length < PLOT_SIZE) {
            int nextX = x + dx * length;
            int nextZ = z + dz * length;
            if (plot(nextX) != plotX || plot(nextZ) != plotZ || !plantable.test(nextX, nextZ)) break;
            length++;
        }
        return length;
    }

    /** The plot column or row of a coordinate; one shared value for everything outside the garden. */
    private static int plot(int coordinate) {
        if (coordinate < GARDEN_MIN || coordinate >= GARDEN_MIN + PLOT_COUNT * PLOT_SIZE) return Integer.MIN_VALUE;
        return Math.floorDiv(coordinate - GARDEN_MIN, PLOT_SIZE);
    }
}
