package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Incremental per-room resolution, shared by the ESP, waypoint and persistent map evidence. */
public final class DungeonMimicStaticChests {
    private static final DungeonStaticChestCatalog CATALOG = new DungeonStaticChestCatalog();
    private final DungeonStaticChestCatalog catalog;
    private final Map<DungeonStaticChestPattern.Room, CachedRoom> rooms = new HashMap<>();
    private long catalogRevision = Long.MIN_VALUE;
    private boolean loadFailed;
    private int remainingBlockReads;
    private static final int MAX_BLOCK_READS = 512;
    private static final int MAX_PATTERN_READS = DungeonStaticChestPattern.MAX_PROBES * 4;

    public DungeonMimicStaticChests() { this(CATALOG); }
    DungeonMimicStaticChests(DungeonStaticChestCatalog catalog) { this.catalog = catalog; }

    void clear() {
        rooms.clear();
        catalogRevision = Long.MIN_VALUE;
    }

    void prepare() {
        remainingBlockReads = MAX_BLOCK_READS;
        if (!loadFailed) {
            try { catalog.load(); }
            catch (IOException exception) {
                loadFailed = true;
                KungMod.LOGGER.warn("Could not load confirmed static chests; using ordinary Mimic detection.", exception);
            }
        }
        if (catalogRevision != catalog.revision()) {
            rooms.clear();
            catalogRevision = catalog.revision();
        }
    }

    Decision check(DungeonStateTracker tracker, ClientLevel level, BlockPos chest, long tick) {
        if (catalog.patterns().isEmpty()) return Decision.UNMAPPED;
        var room = roomAt(tracker, chest, false);
        if (room == null) return Decision.UNMAPPED;
        return check(room, chest, tick, pos -> blockAt(level, pos));
    }

    Decision check(DungeonStaticChestPattern.Room room, BlockPos chest, long tick, Function<BlockPos, String> blockAt) {
        CachedRoom cached = rooms.get(room);
        if (cached == null || tick >= cached.retryAt()) {
            Set<BlockPos> fixed = cached == null ? new HashSet<>() : new HashSet<>(cached.fixed());
            Set<DungeonStaticChestPattern> resolved = cached == null ? new HashSet<>() : new HashSet<>(cached.resolved());
            boolean mapped = cached != null && cached.mapped(), pending = false, deferred = false;
            int cursor = cached == null ? 0 : cached.nextPattern();
            int count = catalog.patterns().size();
            for (int checked = 0; checked < count; checked++) {
                int index = (cursor + checked) % count;
                var pattern = catalog.patterns().get(index);
                if (resolved.contains(pattern)) continue;
                if (pattern.transforms(room).isEmpty()) continue;
                mapped = true;
                if (remainingBlockReads < MAX_PATTERN_READS) {
                    cursor = index;
                    deferred = true;
                    break;
                }
                var result = pattern.resolve(room, pos -> { remainingBlockReads--; return blockAt.apply(pos); });
                fixed.addAll(result.fixedPositions());
                if (!result.fixedPositions().isEmpty()) resolved.add(pattern);
                if (result.fixedPositions().isEmpty()) pending = true;
            }
            boolean changed = cached == null || !cached.fixed().equals(fixed);
            // Resume deferred templates first so a room with several chests cannot starve later ones.
            cached = new CachedRoom(Set.copyOf(fixed), mapped, deferred ? tick + 1L : pending ? tick + 20L : Long.MAX_VALUE,
                cursor, Set.copyOf(resolved));
            rooms.put(room, cached);
            // Confirmed exclusions survive unloaded probes in the same instance.
            // A changed hash/room context gets its own cache entry, never this result.
            if (!fixed.isEmpty() && changed) KungDebugRecorder.event("mimic-static", "room=" + room.name()
                + " origin=" + room.originX() + "," + room.originZ() + " fixed=" + fixed.size() + " pending=" + pending);
        }
        return new Decision(cached.mapped(), cached.fixed().contains(chest));
    }

    public static String confirmLookedChest(Minecraft client, DungeonStateTracker tracker) {
        try {
            BlockPos chest = lookedChest(client, tracker);
            var room = roomAt(tracker, chest, true);
            if (room == null) return "Wait until this entire room is recognized, then try again.";
            var pattern = capture(room, chest, pos -> blockAt(client.level, pos));
            CATALOG.load();
            // A repeat confirmation after rotation must not add the same chest again.
            for (var saved : CATALOG.patterns()) {
                if (saved.resolve(room, pos -> blockAt(client.level, pos)).fixedPositions().contains(chest)) {
                    return "This fixed chest is already known (" + room.name() + ").";
                }
            }
            CATALOG.add(pattern);
            KungDebugRecorder.event("mimic-static", "confirmed room=" + room.name() + " chest=" + chest.toShortString()
                + " local=" + pattern.chest() + " probes=" + pattern.probes().size());
            return "Fixed chest marked for " + room.name() + " until Minecraft restarts. Use /kung mimic copy to share the capture; /kung mimic undo reverses it.";
        } catch (IOException | IllegalArgumentException exception) {
            return "Chest was not marked: " + exception.getMessage();
        }
    }

    public static String undo() {
        try {
            var removed = CATALOG.undo();
            return removed == null ? "No session markings to undo. Built-in exclusions stay active."
                : "Removed the last session marking for " + removed.room() + ".";
        } catch (IOException exception) { return "Could not remove the session marking: " + exception.getMessage(); }
    }

    public static String copyCaptures(Minecraft client) {
        try {
            CATALOG.load();
            if (CATALOG.sessionCount() == 0) return "No new captures to copy. Known fixed chests are already built in.";
            client.keyboardHandler.setClipboard(CATALOG.exportSession());
            return "Copied " + CATALOG.sessionCount() + " fixed chest capture(s). Share them to include them in a future Kung update.";
        } catch (IOException exception) { return "Could not copy the captures: " + exception.getMessage(); }
    }

    public static String status(Minecraft client, DungeonStateTracker tracker) {
        try {
            CATALOG.load();
            String prefix = CATALOG.bundledCount() + " built-in fixed chests, " + CATALOG.sessionCount() + " session markings. ";
            if (client.level == null || client.player == null || !tracker.isInDungeonArea()
                || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                return prefix + "Use /kung mimic ignore for a new fixed chest. New markings last until Minecraft restarts; /kung mimic copy exports them.";
            }
            var room = roomAt(tracker, hit.getBlockPos(), false);
            if (room == null) return prefix + "The targeted room is not fully recognized yet.";
            int possible = 0, confirmed = 0;
            for (var pattern : CATALOG.patterns()) {
                var result = pattern.resolve(room, pos -> blockAt(client.level, pos));
                possible += result.possibleRotations();
                confirmed += result.confirmedRotations();
                if (result.fixedPositions().contains(hit.getBlockPos())) return prefix + "This is a confirmed fixed chest in " + room.name() + ".";
            }
            return prefix + "Room: " + room.name() + ". This position is not excluded. Rotations checked: " + confirmed + "/" + possible + ".";
        } catch (IOException exception) { return "Cannot read saved chests: " + exception.getMessage(); }
    }

    private static BlockPos lookedChest(Minecraft client, DungeonStateTracker tracker) {
        if (client.level == null || client.player == null || !tracker.isInDungeonArea()) {
            throw new IllegalArgumentException("Enter a Catacombs room first.");
        }
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
            || !client.level.getBlockState(hit.getBlockPos()).is(Blocks.TRAPPED_CHEST)) {
            throw new IllegalArgumentException("Look directly at a known fixed Trapped Chest.");
        }
        return hit.getBlockPos().immutable();
    }

    static DungeonStaticChestPattern capture(DungeonStaticChestPattern.Room room, BlockPos chest,
                                             Function<BlockPos, String> blockAt) {
        int width = DungeonStaticChestPattern.span(room.cells(), true), depth = DungeonStaticChestPattern.span(room.cells(), false);
        var reference = new DungeonRoomTransform(room.originX(), room.originZ(), width, depth, 0);
        var localChest = DungeonStaticChestPattern.Point.of(reference.local(chest));
        List<DungeonStaticChestPattern.Probe> candidates = new ArrayList<>();
        // This larger read happens only for an explicit confirmation, never in a scan/render loop.
        // Fixed grid samples avoid doors, chests, redstone state and the player's view direction.
        for (var cell : room.cells()) for (int x = 2; x <= 28; x += 4) for (int z = 2; z <= 28; z += 4) {
            for (int y = Math.max(12, chest.getY() - 12); y <= Math.min(140, chest.getY() + 28); y += 4) {
                var point = new DungeonStaticChestPattern.Point(cell.x() * 32 + x, y, cell.z() * 32 + z);
                String block = blockAt.apply(reference.world(point.blockPos()));
                if (DungeonStaticChestPattern.structuralBlock(block)) candidates.add(new DungeonStaticChestPattern.Probe(point, block));
            }
        }
        if (candidates.size() < DungeonStaticChestPattern.MIN_PROBES) {
            throw new IllegalArgumentException("Not enough loaded building blocks to identify this room safely.");
        }
        // Spread the final probes throughout the structure rather than filling from one corner.
        candidates.sort(Comparator.comparingInt(p -> Integer.rotateLeft(p.pos().x() * 73856093, 7)
            ^ p.pos().y() * 19349663 ^ p.pos().z() * 83492791));
        var initial = new DungeonStaticChestPattern(room.name(), room.cells(), localChest,
            candidates.subList(0, DungeonStaticChestPattern.MIN_PROBES));
        Map<DungeonStaticChestPattern.Point, DungeonStaticChestPattern.Probe> chosen = new LinkedHashMap<>();
        // Include an independently observed distinguishing block for every alternative rotation.
        for (var transform : initial.transforms(room)) {
            if (transform.rotation() == 0) continue;
            for (var probe : candidates) {
                String other = blockAt.apply(transform.world(probe.pos().blockPos()));
                if (other != null && !other.equals(probe.block())) { chosen.put(probe.pos(), probe); break; }
            }
        }
        for (var probe : candidates) {
            chosen.putIfAbsent(probe.pos(), probe);
            if (chosen.size() == DungeonStaticChestPattern.MAX_PROBES) break;
        }
        var pattern = new DungeonStaticChestPattern(room.name(), room.cells(), localChest, List.copyOf(chosen.values()));
        if (!pattern.resolve(room, blockAt).fixedPositions().contains(chest)) {
            throw new IllegalArgumentException("Room rotation is still ambiguous or chunks are missing. No exclusion was saved.");
        }
        return pattern;
    }

    private static String blockAt(ClientLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        return chunk == null || chunk.isEmpty() ? null : BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).toString();
    }

    private static DungeonStaticChestPattern.Room roomAt(DungeonStateTracker tracker, BlockPos pos, boolean capture) {
        var grid = DungeonScanUtils.getRoomGridPosition(pos);
        if (grid.gridX() < 0 || grid.gridX() > 5 || grid.gridZ() < 0 || grid.gridZ() > 5) return null;
        var plan = tracker.renderPlan();
        var match = plan.matches().stream().filter(m -> m.contains(grid.gridX(), grid.gridZ())).findFirst().orElse(null);
        if (match == null || match.components().size() != match.template().components().size()) return null;
        var owner = plan.roomOwners().get(new DungeonLiveMapWriter.CellKey(grid.gridX(), grid.gridZ()));
        if (owner != null && plan.roomOwners().values().stream().filter(owner::equals).count() != match.components().size()) return null;
        int minX = match.components().stream().mapToInt(DungeonKnownRoomCatalog.MatchedComponent::roomGridX).min().orElseThrow();
        int minZ = match.components().stream().mapToInt(DungeonKnownRoomCatalog.MatchedComponent::roomGridZ).min().orElseThrow();
        List<DungeonStaticChestPattern.Cell> cells = new ArrayList<>();
        for (var component : match.components()) {
            var point = tracker.mapSnapshot().pointAt(component.roomGridX() * 2, component.roomGridZ() * 2);
            if (point == null) return null;
            Set<Integer> cores = new HashSet<>(), stable = new HashSet<>();
            if (point.coreHash() != 0) cores.add(point.coreHash());
            if (point.stableCoreHash() != 0) stable.add(point.stableCoreHash());
            if (cores.isEmpty() && stable.isEmpty()) return null;
            if (capture) for (var templateCell : match.template().components()) {
                if (templateCell.matches(point.coreHash(), point.stableCoreHash())) {
                    cores.addAll(templateCell.coreHashes());
                    stable.addAll(templateCell.stableCoreHashes());
                }
            }
            cells.add(new DungeonStaticChestPattern.Cell(component.roomGridX() - minX, component.roomGridZ() - minZ, cores, stable));
        }
        cells.sort(Comparator.comparingInt(DungeonStaticChestPattern.Cell::x).thenComparingInt(DungeonStaticChestPattern.Cell::z));
        String name = match.template().name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return new DungeonStaticChestPattern.Room(name, DungeonScanUtils.START_X + minX * 32 - 15,
            DungeonScanUtils.START_Z + minZ * 32 - 15, cells);
    }

    record Decision(boolean hasPattern, boolean fixed) {
        static final Decision UNMAPPED = new Decision(false, false);
    }
    private record CachedRoom(Set<BlockPos> fixed, boolean mapped, long retryAt, int nextPattern,
                              Set<DungeonStaticChestPattern> resolved) { }
}
