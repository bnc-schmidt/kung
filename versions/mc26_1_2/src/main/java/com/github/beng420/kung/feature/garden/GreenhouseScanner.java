package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Footprint;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Found;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Snapshot;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import com.github.beng420.kung.feature.garden.GreenhouseScanNames.Resolution;
import com.github.beng420.kung.message.KungMessages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The thin game side of the plot scan: once a second (and at once after the hologram or the corner changed) it
 * reads the armor stands and blocks of the greenhouse into {@link Found} entries and the soil layer into block ids,
 * lets {@link GreenhouseScanLogic} and {@link GreenhouseSoilCheck} decide, and keeps the result as an immutable
 * snapshot for the renderer. One entity query and about 200 block reads per scan (400 while the soil layer is
 * still unknown); client thread only. When the chunks are not all loaded the previous snapshot stays: missing
 * data never turns into "empty".
 *
 * <p>UNVERIFIED in game: that the crops stand at y 74, that special crops are armor stands wearing heads, the
 * head names and the soil layer.
 */
final class GreenhouseScanner {
    private static final long INTERVAL_NANOS = 1_000_000_000L;
    /** A stand slightly outside the grid still counts (stands sit on cell centres, but not exactly). */
    private static final double GRID_MARGIN = 0.6;
    private static final int CROP_Y = GreenhouseLocator.HOLOGRAM_Y;
    private static final int CELLS = GreenhouseGrid.SIZE * GreenhouseGrid.SIZE;

    private static final Map<Block, String> IDS = new HashMap<>();
    private static Snapshot snapshot;
    private static List<Ghost> ghosts = List.of();
    private static int soilLayer = -1;
    private static String[] soilBlocks;
    private static String soilNote = "not scanned yet";
    private static int version;
    private static BlockPos scannedCorner;
    private static int scannedVersion = -1;
    private static long scannedAt;
    private static boolean warned;
    private static boolean failed;
    private static final GreenhouseCompletion COMPLETION = new GreenhouseCompletion();

    private GreenhouseScanner() { }

    /** A hologram was shown or hidden: forget the snapshot, the soil layer and the warning. */
    static void reset() {
        snapshot = null;
        ghosts = List.of();
        soilLayer = -1;
        soilBlocks = null;
        soilNote = "not scanned yet";
        scannedCorner = null;
        scannedVersion = -1;
        warned = false;
        failed = false;
        COMPLETION.reset();
        version++;
    }

    /** Test seam: installs a snapshot as if a scan had produced it (the supply tests need planted counts). */
    static void setSnapshotForTest(Snapshot value) {
        snapshot = value;
        version++;
    }

    /** Test seam: installs soil ghosts on a soil layer as if a scan had found them (the soil pick tests). */
    static void setGhostsForTest(List<Ghost> value, int layer) {
        ghosts = List.copyOf(value);
        soilLayer = layer;
        version++;
    }

    /** Test seam: the completion state a run of scans would have settled on. */
    static void setCompleteForTest(boolean complete) {
        COMPLETION.setForTest(complete);
        version++;
    }

    /**
     * Whether every ingredient cell of the layout stood right in the last two completed scans (see
     * {@link GreenhouseCompletion}); false before a scan and after a reset.
     */
    static boolean complete() { return COMPLETION.complete(); }

    /**
     * Feeds one completed scan to the completion rule. When the layout is complete and {@code enabled}, the hologram
     * is deactivated through {@link GreenhouseHologram#hide()} (the path of {@code blueprint off}: the shown layout
     * goes, scanning stops, this state resets), so nothing re-arms it when the player enters a greenhouse again; only
     * an explicit show does. Returns true when it did.
     */
    static boolean completeIfDone(boolean allCorrect, boolean enabled) {
        if (!COMPLETION.update(allCorrect, enabled)) return false;
        GreenhouseHologram.hide();
        return true;
    }

    /** Changes whenever what is drawn from the scan changes, so the renderer rebuilds only then. */
    static int version() { return version; }

    /** Crops seen where the layout has no entry (shown ones); empty before a scan or for another layout. */
    static List<GreenhouseScanLogic.Extra> extras(int hologramVersion) {
        return snapshot != null && snapshot.layoutVersion() == hologramVersion ? snapshot.extras() : List.of();
    }

    /** What to draw per placement of the hologram version, or null when no scan matches it (then: all empty). */
    static List<State> states(int hologramVersion) {
        return snapshot != null && snapshot.layoutVersion() == hologramVersion ? snapshot.shown() : null;
    }

    static List<Ghost> ghosts() { return ghosts; }

    /** The y of the soil layer, or -1 when none was found. */
    static int soilLayer() { return soilLayer; }

    /** Recognised planted things of this crop in the grid area at the last scan; mushrooms count by own name. */
    static int plantedCount(String crop) {
        return snapshot == null ? 0 : snapshot.planted().getOrDefault(crop, 0);
    }

    /** For the dump: what the scanner currently believes. */
    static String summary() {
        if (snapshot == null) return "no scan yet; soil: " + soilNote;
        long wrong = snapshot.shown().stream().filter(state -> state == State.WRONG).count();
        long unverified = snapshot.shown().stream().filter(state -> state == State.UNVERIFIED).count();
        long correct = snapshot.shown().stream().filter(state -> state == State.CORRECT).count();
        long toPlace = snapshot.shown().stream().filter(state -> state == State.EMPTY).count();
        StringBuilder soil = new StringBuilder();
        for (GreenhouseSoilCheck.GhostKind kind : GreenhouseSoilCheck.GhostKind.values()) {
            soil.append(' ').append(kind.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                .append(ghosts.stream().filter(ghost -> ghost.kind() == kind).count());
        }
        return "planted=" + snapshot.planted() + " unrecognised=" + snapshot.unknown() + "/" + snapshot.total()
            + " toPlace=" + toPlace + " correct=" + correct + " wrong=" + wrong + " unverified=" + unverified
            + " extra=" + snapshot.extras().size() + " complete=" + COMPLETION.complete()
            + " soilLayer=" + soilLayer + " soil:" + soil + " (" + soilNote + ")";
    }

    /**
     * Scans now, whatever the interval says, for a caller that is about to act on the plot (the pick-block supply).
     * The same guards as {@link #tick} apply: nothing runs without a level, after a failed scan or while the chunks
     * are not loaded (the last scan stays). The next regular scan is due an interval after this one.
     */
    static void scanNow(Minecraft client, BlockPos corner, int hologramVersion) {
        long now = System.nanoTime();
        tick(client, corner, hologramVersion, now + INTERVAL_NANOS);
        if (scannedAt > now) scannedAt = now;
    }

    /** Runs a scan when one is due. {@code corner} is the confirmed greenhouse corner. */
    static void tick(Minecraft client, BlockPos corner, int hologramVersion, long nowNanos) {
        Level level = client.level;
        if (level == null || failed) return;
        if (!corner.equals(scannedCorner)) switchCorner(corner);
        if (scannedVersion == hologramVersion && nowNanos - scannedAt < INTERVAL_NANOS) return;
        scannedAt = nowNanos;
        if (!level.hasChunksAt(corner.getX() - 1, corner.getZ() - 1, corner.getX() + GreenhouseGrid.SIZE + 1,
            corner.getZ() + GreenhouseGrid.SIZE + 1)) {
            soilNote = "chunks not loaded, keeping the last scan";
            return;
        }
        scannedVersion = hologramVersion;
        try {
            scan(client, level, corner, hologramVersion);
        } catch (RuntimeException failure) {
            // A scan that cannot run once will not run again: stop rather than throw every second, say so once,
            // and let the next show try again (reset clears the flag).
            failed = true;
            KungMod.LOGGER.warn("Greenhouse scan stopped", failure);
            KungMessages.send(client, KungMessages.Type.WARNING, "Greenhouse",
                "The plot scan stopped after an error (see the log); show the hologram again to retry");
        }
    }

    /** Another greenhouse: the old one's results must not outlive it, even while its chunks are unloaded. */
    static void switchCorner(BlockPos corner) {
        snapshot = null;
        ghosts = List.of();
        soilLayer = -1;
        soilBlocks = null;
        scannedCorner = corner;
        scannedVersion = -1;
        COMPLETION.reset();
        version++;
    }

    private static void scan(Minecraft client, Level level, BlockPos corner, int hologramVersion) {
        List<Placement> placements = GreenhouseHologram.placements();
        List<Footprint> expected = GreenhouseScanLogic.footprints(placements);
        List<Found> found = readFound(level, corner);
        Snapshot next = GreenhouseScanLogic.evaluate(expected, found, snapshot, hologramVersion);

        int layerBefore = soilLayer;
        readSoil(level, corner);
        List<Ghost> nextGhosts = soilBlocks == null ? List.of() : GreenhouseSoilCheck.ghosts(expected, soilBlocks);

        boolean changed = snapshot == null || snapshot.layoutVersion() != next.layoutVersion()
            || !snapshot.shown().equals(next.shown()) || !snapshot.extras().equals(next.extras())
            || !ghosts.equals(nextGhosts) || layerBefore != soilLayer;
        snapshot = next;
        ghosts = nextGhosts;
        if (changed) version++;
        if (next.unrecognisedShare() && !warned) {
            warned = true;
            KungMessages.send(client, KungMessages.Type.WARNING, "Greenhouse",
                next.unknown() + " planted heads not recognised, run /kung greenhouse dump");
        }
        // Every ingredient right and no unplanned crop in two scans in a row: the hologram has done its job
        // (setting permitting) and turns itself off; this was the last scan of that layout.
        boolean allCorrect = GreenhouseCompletion.allIngredientsCorrect(placements, next.raw(), next.rawExtras());
        if (completeIfDone(allCorrect, GreenhouseHologramSettings.hideWhenComplete())) {
            KungMessages.send(client, KungMessages.Type.SUCCESS, "Greenhouse", GreenhouseCompletion.MESSAGE);
        }
    }

    private static List<Found> readFound(Level level, BlockPos corner) {
        int cornerX = corner.getX();
        int cornerZ = corner.getZ();
        List<Found> found = new ArrayList<>();
        boolean[] standInCell = new boolean[CELLS];
        AABB area = new AABB(cornerX - 1, 72, cornerZ - 1, cornerX + GreenhouseGrid.SIZE + 1, 80,
            cornerZ + GreenhouseGrid.SIZE + 1);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, area)) {
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty()) continue;
            double x = stand.getX() - cornerX;
            double z = stand.getZ() - cornerZ;
            if (x < -GRID_MARGIN || x > GreenhouseGrid.SIZE + GRID_MARGIN
                || z < -GRID_MARGIN || z > GreenhouseGrid.SIZE + GRID_MARGIN) continue;
            Resolution resolution = GreenhouseScanNames.resolve(head.getHoverName().getString(), textureHash(head));
            if (resolution.kind() == GreenhouseScanNames.Kind.IGNORED) continue;
            found.add(new Found(resolution.crop(), x, z));
            int dx = (int) Math.floor(x);
            int dz = (int) Math.floor(z);
            if (dx >= 0 && dx < GreenhouseGrid.SIZE && dz >= 0 && dz < GreenhouseGrid.SIZE) {
                standInCell[dx * GreenhouseGrid.SIZE + dz] = true;
            }
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < GreenhouseGrid.SIZE; dx++) {
            for (int dz = 0; dz < GreenhouseGrid.SIZE; dz++) {
                if (standInCell[dx * GreenhouseGrid.SIZE + dz]) continue;
                BlockState state = level.getBlockState(at.set(cornerX + dx, CROP_Y, cornerZ + dz));
                if (state.isAir()) continue;
                // A plain crop is its block; any other block in a crop cell is something we cannot place.
                found.add(new Found(GreenhouseScanNames.cropOfBlock(id(state)), dx + 0.5, dz + 0.5));
            }
        }
        return found;
    }

    /** The skin hash of a player head: the last segment of the url in its first readable "textures" property. */
    private static String textureHash(ItemStack head) {
        try {
            ResolvableProfile profile = head.get(DataComponents.PROFILE);
            if (profile == null) return null;
            for (var property : profile.partialProfile().properties().get("textures")) {
                Optional<GreenhouseDumpFormat.Texture> texture = GreenhouseDumpFormat.texture(property.value());
                if (texture.isPresent()) return texture.get().hash();
            }
        } catch (RuntimeException failure) {
            return null;
        }
        return null;
    }

    /**
     * Reads the soil layer: the first scan of a corner looks at all candidate layers and keeps the one that is mostly
     * soil; later scans read only that layer. Without a clear majority no layer is chosen and no ghost is drawn.
     */
    private static void readSoil(Level level, BlockPos corner) {
        if (soilLayer >= 0) {
            soilBlocks = readLayer(level, corner, soilLayer);
            return;
        }
        int[] counts = new int[GreenhouseSoilCheck.LAYERS.length];
        String[][] layers = new String[GreenhouseSoilCheck.LAYERS.length][];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = readLayer(level, corner, GreenhouseSoilCheck.LAYERS[i]);
            for (String id : layers[i]) {
                if (GreenhouseSoilCheck.isSoilType(id)) counts[i]++;
            }
        }
        int layer = GreenhouseSoilCheck.detectLayer(counts);
        if (layer < 0) {
            soilBlocks = null;
            soilNote = "no soil layer: soil-type blocks per y " + describe(counts);
            return;
        }
        soilLayer = layer;
        soilBlocks = layers[indexOf(layer)];
        soilNote = "soil layer y=" + layer + " chosen from " + describe(counts);
    }

    private static String[] readLayer(Level level, BlockPos corner, int y) {
        String[] ids = new String[CELLS];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < GreenhouseGrid.SIZE; dx++) {
            for (int dz = 0; dz < GreenhouseGrid.SIZE; dz++) {
                ids[dx * GreenhouseGrid.SIZE + dz] = id(level.getBlockState(at.set(corner.getX() + dx, y, corner.getZ() + dz)));
            }
        }
        return ids;
    }

    private static int indexOf(int layer) {
        for (int i = 0; i < GreenhouseSoilCheck.LAYERS.length; i++) {
            if (GreenhouseSoilCheck.LAYERS[i] == layer) return i;
        }
        return 0;
    }

    private static String describe(int[] counts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            out.append(i == 0 ? "" : ", ").append(GreenhouseSoilCheck.LAYERS[i]).append('=').append(counts[i]);
        }
        return out.toString();
    }

    private static String id(BlockState state) {
        return IDS.computeIfAbsent(state.getBlock(), block -> BuiltInRegistries.BLOCK.getKey(block).toString());
    }
}
