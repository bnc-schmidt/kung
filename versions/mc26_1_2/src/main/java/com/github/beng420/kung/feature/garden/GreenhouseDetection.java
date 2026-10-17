package com.github.beng420.kung.feature.garden;

import java.lang.ref.WeakReference;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Remembers which garden plot is the player's greenhouse. The Carpenter NPC is only a way to find out: the
 * client unloads entities beyond its tracking range, so once a plot has been confirmed it stays the greenhouse
 * until the level changes, even when the NPC is no longer around. Only the Garden check is repeated, at most
 * once a second; the entity search reruns only while no greenhouse is known, and a miss is reported once per
 * plot. The world is held weakly so a disconnect does not keep the old level's chunks and entities alive.
 * Plain Java around a {@link Probe}, so the rules are unit-tested without a client.
 */
final class GreenhouseDetection {
    /** What the game answers. */
    interface Probe {
        boolean garden();

        /** Whether the Carpenter is within the plot holding this block position. */
        boolean carpenter(int x, int z);

        /** Called once per plot (not per refresh) when the search found nothing. */
        void miss(int plotX, int plotZ);
    }

    private static final long REFRESH_NANOS = 1_000_000_000L;
    private static final int NONE = Integer.MIN_VALUE;

    private WeakReference<Object> world = new WeakReference<>(null);
    private BlockPos confirmed;
    private boolean garden;
    private boolean checked;
    private long checkedAt;
    private int lastPlotX = NONE;
    private int lastPlotZ = NONE;
    private int missPlotX = NONE;
    private int missPlotZ = NONE;

    /**
     * The grid corner of the player's greenhouse when the player stands in it and is in the Garden, else empty.
     * {@code force} repeats the Garden check and a pending search at once.
     */
    Optional<BlockPos> update(Object level, int x, int z, long nowNanos, boolean force, Probe probe) {
        adopt(level);
        int plotX = GreenhouseLocator.plotOrigin(x);
        int plotZ = GreenhouseLocator.plotOrigin(z);
        boolean moved = plotX != lastPlotX || plotZ != lastPlotZ;
        lastPlotX = plotX;
        lastPlotZ = plotZ;
        boolean here = isConfirmedPlot(plotX, plotZ);
        if (force || moved || !checked || nowNanos - checkedAt >= REFRESH_NANOS) {
            checked = true;
            checkedAt = nowNanos;
            garden = probe.garden();
            if (garden && !here && GreenhouseLocator.inGardenBounds(x) && GreenhouseLocator.inGardenBounds(z)) {
                if (probe.carpenter(x, z)) {
                    confirmed = GreenhouseLocator.cornerOf(x, z).orElse(null);
                    here = confirmed != null;
                } else if (plotX != missPlotX || plotZ != missPlotZ) {
                    missPlotX = plotX;
                    missPlotZ = plotZ;
                    probe.miss(plotX, plotZ);
                }
            }
        }
        return garden && here ? Optional.of(confirmed) : Optional.empty();
    }

    /**
     * Declares the plot the player stands in to be the greenhouse without looking for the Carpenter. Still needs
     * the Garden and a position inside the 5x5 plots; empty (and nothing remembered) otherwise.
     */
    Optional<BlockPos> markHere(Object level, int x, int z, long nowNanos, Probe probe) {
        adopt(level);
        if (!GreenhouseLocator.inGardenBounds(x) || !GreenhouseLocator.inGardenBounds(z) || !probe.garden()) {
            return Optional.empty();
        }
        confirmed = GreenhouseLocator.cornerOf(x, z).orElseThrow();
        garden = true;
        checked = true;
        checkedAt = nowNanos;
        lastPlotX = GreenhouseLocator.plotOrigin(x);
        lastPlotZ = GreenhouseLocator.plotOrigin(z);
        return Optional.of(confirmed);
    }

    /** No client, player or level: nothing is remembered. */
    void forget() {
        world = new WeakReference<>(null);
        confirmed = null;
        garden = false;
        checked = false;
        lastPlotX = NONE;
        lastPlotZ = NONE;
        missPlotX = NONE;
        missPlotZ = NONE;
    }

    private void adopt(Object level) {
        if (world.get() == level) return;
        forget();
        world = new WeakReference<>(level);
    }

    private boolean isConfirmedPlot(int plotX, int plotZ) {
        return confirmed != null && plotX == confirmed.getX() - GreenhouseLocator.GRID_OFFSET
            && plotZ == confirmed.getZ() - GreenhouseLocator.GRID_OFFSET;
    }
}
