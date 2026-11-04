package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.PlotCleaningConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the leaves, grass and flowers of the garden plot the player stands in as sponge, so everything left to
 * clear stands out. Only a plot that Hypixel shows a cleaning progress for counts (see {@link PlotCleaningState});
 * a plot that is already clean keeps its look. Like {@link com.github.beng420.kung.feature.dungeon.ColoredPillarsFeature} it replaces render
 * snapshots only: the world, collisions and the blocks the player breaks stay as the server has them, so a block
 * shows as sponge until it is gone. Display only; it never clicks, builds or moves anything.
 */
public final class PlotCleaningHelperFeature extends ConfigurableFeature<PlotCleaningConfig> {
    public static final PlotCleaningHelperFeature INSTANCE = new PlotCleaningHelperFeature();
    private static final BlockState SPONGE = Blocks.SPONGE.defaultBlockState();
    // The middle plot is the Barn: nothing to buy or clear there.
    private static final int BARN_ORIGIN = GreenhouseLocator.plotOrigin(0);
    private static volatile RenderContext renderContext;
    private static final int MAX_LOGGED_LINES = 40;

    private final PlotCleaningState state = new PlotCleaningState();
    private final Set<String> loggedLines = new HashSet<>();
    private List<String> scannedLines = List.of();
    // The plot the player stands in, null outside the 24 plots; client thread only.
    private Plot here;

    PlotCleaningHelperFeature() { super(config -> config.plotCleaning); }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled(); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onReset());
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (here != null && isEnabled()) observeMessage(message.getString(), overlay);
            return true;
        });
    }

    @Override
    protected void onReset() {
        state.clear();
        loggedLines.clear();
        scannedLines = List.of();
        here = null;
        update(Minecraft.getInstance(), null);
    }

    private void tick(Minecraft client) {
        here = null;
        if (client.player != null && client.level != null && isEnabled() && GreenhouseLocator.inGarden()) {
            here = Plot.at((int) Math.floor(client.player.getX()), (int) Math.floor(client.player.getZ()));
        }
        Plot plot = null;
        if (here != null) {
            var instance = HypixelInstanceTracker.INSTANCE;
            state.sync(instance.instanceEpoch());
            // The tab list and sidebar: scanned when they change, not every tick.
            List<String> lines = instance.visibleLines();
            if (lines != scannedLines) {
                scannedLines = lines;
                for (String line : lines) observeLine("tab/sidebar", line);
            }
            if (state.cleaning(here)) plot = here;
        }
        update(client, plot == null ? null : new RenderContext(client.level, plot));
    }

    private void observeMessage(String message, boolean overlay) {
        String text = HypixelLocation.clean(message);
        if (!overlay && PlotCleaningState.cleanMessage(text)) {
            announceClean();
        } else if (overlay || text.startsWith("Plot - ")) {
            observeLine(overlay ? "actionbar" : "chat", text);
        }
    }

    /** A line that may show the cleaning progress of the plot the player stands in. */
    private void observeLine(String source, String line) {
        String text = HypixelLocation.clean(line);
        double percent = PlotCleaningState.percent(text);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // The display line is not known; the trace shows the wording to match.
        if ((lower.contains("clean") || lower.contains("plot")) && loggedLines.size() < MAX_LOGGED_LINES
                && loggedLines.add(text)) {
            KungDebugRecorder.event("plot-cleaning", source + " line \"" + text + "\" percent=" + percent);
        }
        if (!Double.isNaN(percent)) state.progress(here, percent);
    }

    /** Hypixel announced the plot the player stands in as clean: stop drawing it. */
    private void announceClean() {
        var level = Minecraft.getInstance().level;
        // The trace shows what Hypixel did not count, to tune the block set from.
        if (level != null) {
            KungDebugRecorder.event("plot-cleaning", "plot " + here.minX + "," + here.minZ
                + " announced clean, still clutter: " + leftovers(level, here));
        }
        state.announcedClean(here);
    }

    /** Count, height range and first position per clutter block id left in the plot, for the trace. */
    private static String leftovers(ClientLevel level, Plot plot) {
        Map<String, int[]> stats = new TreeMap<>();
        var pos = new BlockPos.MutableBlockPos();
        for (int x = plot.minX; x < plot.minX + GreenhouseLocator.PLOT_SIZE; x++) {
            for (int z = plot.minZ; z < plot.minZ + GreenhouseLocator.PLOT_SIZE; z++) {
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (!isClutter(state)) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    int[] stat = stats.get(id);
                    if (stat == null) stats.put(id, stat = new int[] {0, y, y, x, y, z});
                    stat[0]++;
                    stat[1] = Math.min(stat[1], y);
                    stat[2] = Math.max(stat[2], y);
                }
            }
        }
        if (stats.isEmpty()) return "none";
        var text = new StringBuilder();
        stats.forEach((id, stat) -> text.append(id).append(" x").append(stat[0]).append(" y").append(stat[1])
            .append("..").append(stat[2]).append(" first ").append(stat[3]).append(',').append(stat[4])
            .append(',').append(stat[5]).append("; "));
        return text.toString();
    }

    private static void update(Minecraft client, RenderContext next) {
        RenderContext previous = renderContext;
        if (java.util.Objects.equals(previous, next)) return;
        renderContext = next;
        KungDebugRecorder.event("plot-cleaning", next == null ? "render off"
            : "render plot=" + next.plot.minX + "," + next.plot.minZ);
        if (client.level == null) return;
        // Same reasoning as the colored pillars: one reload also catches meshes that are still in flight.
        McCompat.rebuildChunks(client);
    }

    /** Captured once by each render snapshot, never read from mutable config on a mesh worker. */
    public static Plot plotFor(ClientLevel level) {
        RenderContext context = renderContext;
        return context != null && context.level == level ? context.plot : null;
    }

    public static BlockState replace(int x, int z, BlockState original, Plot plot) {
        return plot != null && plot.contains(x, z) && isClutter(original) ? SPONGE : original;
    }

    /**
     * Leaves, grass and flowers, tall ones included; the grass block itself stays. By block class, not by tag:
     * tags are synced from the server, and a mesh worker must not depend on that having happened.
     */
    static boolean isClutter(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LeavesBlock || block instanceof FlowerBlock || block instanceof TallFlowerBlock
            || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS;
    }

    /** A garden plot by its minimum corner. */
    public record Plot(int minX, int minZ) {
        /** The plot holding this position; null outside the 5x5 plots and in the Barn. */
        static Plot at(int x, int z) {
            if (!GreenhouseLocator.inGardenBounds(x) || !GreenhouseLocator.inGardenBounds(z)) return null;
            int minX = GreenhouseLocator.plotOrigin(x);
            int minZ = GreenhouseLocator.plotOrigin(z);
            return minX == BARN_ORIGIN && minZ == BARN_ORIGIN ? null : new Plot(minX, minZ);
        }

        boolean contains(int x, int z) {
            return x >= minX && x < minX + GreenhouseLocator.PLOT_SIZE
                && z >= minZ && z < minZ + GreenhouseLocator.PLOT_SIZE;
        }
    }

    private record RenderContext(ClientLevel level, Plot plot) { }
}
