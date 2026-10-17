package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Which garden plot the player stands in and whether it is their greenhouse. The plot geometry is pure
 * (unit-tested); the Carpenter lookup is the only part that touches the world, and a plot it confirms is
 * remembered by {@link GreenhouseDetection} until the level changes.
 *
 * <p>Facts, taken from Skyblocker's greenhouse paste: plots are 96x96 blocks on a 5x5 grid centred on world
 * (0, 0); the crop grid's corner is the plot's minimum corner + (43, 73, 43) and the 10x10 cells extend
 * toward +X and +Z with the crop floor at y 73; the player's greenhouse is the plot holding an entity
 * whose custom name contains "Carpenter" (y 60..90).
 */
final class GreenhouseLocator {
    static final int PLOT_SIZE = 96;
    /** Minimum block coordinate of the garden on both axes (5 plots of 96 around the origin). */
    static final int GARDEN_MIN = -240;
    static final int PLOT_COUNT = 5;
    /** Plot minimum corner to the grid corner; (96 - 10) / 2 centres the grid in the plot. */
    static final int GRID_OFFSET = 43;
    static final int FLOOR_Y = 73;
    /** The hologram stands on the block above the crop floor. */
    static final int HOLOGRAM_Y = FLOOR_Y + 1;
    static final int PLOT_MIN_Y = 60;
    static final int PLOT_MAX_Y = 90;
    private static final GreenhouseDetection DETECTION = new GreenhouseDetection();
    private static final GreenhouseDetection.Probe WORLD = new GreenhouseDetection.Probe() {
        @Override public boolean garden() { return inGarden(); }

        @Override public boolean carpenter(int x, int z) {
            Level level = Minecraft.getInstance().level;
            return level != null && hasCarpenter(level, x, z);
        }

        @Override public void miss(int plotX, int plotZ) {
            KungDebugRecorder.event("greenhouse", "no Carpenter found in plot " + plotX + "," + plotZ
                + " (entity not loaded on the client?); /kung greenhouse blueprint here skips the search");
        }
    };

    private GreenhouseLocator() { }

    /** Minimum block coordinate of the plot column or row holding this coordinate. */
    static int plotOrigin(int coordinate) {
        return Math.floorDiv(coordinate - GARDEN_MIN, PLOT_SIZE) * PLOT_SIZE + GARDEN_MIN;
    }

    /** Whether a coordinate is inside the 5x5 plot grid at all. */
    static boolean inGardenBounds(int coordinate) {
        return coordinate >= GARDEN_MIN && coordinate < GARDEN_MIN + PLOT_COUNT * PLOT_SIZE;
    }

    /** The grid corner of the plot holding this position; empty outside the garden's 5x5 plots. */
    static Optional<BlockPos> cornerOf(int x, int z) {
        if (!inGardenBounds(x) || !inGardenBounds(z)) return Optional.empty();
        return Optional.of(new BlockPos(plotOrigin(x) + GRID_OFFSET, FLOOR_Y, plotOrigin(z) + GRID_OFFSET));
    }

    /** The plot holding this position, as the box the Carpenter is searched in. */
    static AABB plotBounds(int x, int z) {
        int minX = plotOrigin(x);
        int minZ = plotOrigin(z);
        return new AABB(minX, PLOT_MIN_Y, minZ, minX + PLOT_SIZE, PLOT_MAX_Y, minZ + PLOT_SIZE);
    }

    /**
     * The grid corner of the player's greenhouse when the player stands in it, else empty. A plot found to hold
     * the Carpenter stays the greenhouse until the level changes (see {@link GreenhouseDetection}); the Garden
     * check reruns once a second, at once after moving to another plot or world, and whenever {@code force}.
     * Client thread only.
     */
    static Optional<BlockPos> current(Minecraft client, boolean force) {
        if (!ready(client)) {
            DETECTION.forget();
            return Optional.empty();
        }
        return DETECTION.update(client.level, blockX(client), blockZ(client), System.nanoTime(), force, WORLD);
    }

    /** The plot the player stands in taken as the greenhouse without the Carpenter test; still needs the Garden. */
    static Optional<BlockPos> markHere(Minecraft client) {
        if (!ready(client)) return Optional.empty();
        return DETECTION.markHere(client.level, blockX(client), blockZ(client), System.nanoTime(), WORLD);
    }

    private static boolean ready(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    private static int blockX(Minecraft client) { return (int) Math.floor(client.player.getX()); }

    private static int blockZ(Minecraft client) { return (int) Math.floor(client.player.getZ()); }

    static boolean inGarden() {
        HypixelInstanceTracker instance = HypixelInstanceTracker.INSTANCE;
        return instance.tracking() && FeastContext.garden(instance.instanceLine(), instance.visibleLines());
    }

    private static boolean hasCarpenter(Level level, int x, int z) {
        if (!inGardenBounds(x) || !inGardenBounds(z)) return false;
        for (Entity entity : level.getEntities((Entity) null, plotBounds(x, z))) {
            var name = entity.getCustomName();
            if (name != null && name.getString().contains("Carpenter")) return true;
        }
        return false;
    }
}
