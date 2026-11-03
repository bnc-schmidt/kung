package com.github.beng420.kung.feature.dungeon;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;

/** Explicitly confirmed chest position, bound to room hashes and independent structural probes. */
record DungeonStaticChestPattern(String room, List<Cell> cells, Point chest, List<Probe> probes) {
    static final int MIN_PROBES = 12;
    static final int MAX_PROBES = 48;
    private static final java.util.regex.Pattern COLORED_BLOCK = java.util.regex.Pattern.compile(
        "(?:white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_(?:terracotta|wool)");

    DungeonStaticChestPattern {
        if (room == null || room.isBlank() || room.length() > 100 || cells == null || cells.isEmpty() || cells.size() > 4
            || chest == null || probes == null || probes.size() < MIN_PROBES || probes.size() > MAX_PROBES) {
            throw new IllegalArgumentException("Invalid static chest pattern");
        }
        cells = List.copyOf(cells);
        probes = List.copyOf(probes);
        if (cells.stream().map(c -> c.x() + "," + c.z()).distinct().count() != cells.size()
            || cells.stream().mapToInt(Cell::x).min().orElse(-1) != 0
            || cells.stream().mapToInt(Cell::z).min().orElse(-1) != 0
            || probes.stream().map(Probe::pos).distinct().count() != probes.size()) {
            throw new IllegalArgumentException("Duplicate or unnormalized room coordinates");
        }
        int width = span(cells, true), depth = span(cells, false);
        if (!inside(chest, width, depth) || probes.stream().anyMatch(p -> !inside(p.pos(), width, depth)
            || p.pos().equals(chest))) throw new IllegalArgumentException("Probe outside the room or on the chest");
    }

    private static boolean inside(Point p, int width, int depth) {
        return p.x() >= 0 && p.x() < width && p.z() >= 0 && p.z() < depth && p.y() >= 0 && p.y() <= 255;
    }

    static int span(List<Cell> cells, boolean x) {
        return cells.stream().mapToInt(c -> x ? c.x() : c.z()).max().orElse(0) * 32 + 31;
    }

    List<DungeonRoomTransform> transforms(Room observed) {
        if (!room.equals(observed.name()) || cells.size() != observed.cells().size()) return List.of();
        var result = new java.util.ArrayList<DungeonRoomTransform>();
        int width = span(cells, true), depth = span(cells, false);
        for (int rotation = 0; rotation < 4; rotation++) {
            var transform = new DungeonRoomTransform(observed.originX(), observed.originZ(), width, depth, rotation);
            boolean matches = true;
            for (Cell cell : cells) {
                BlockPos center = transform.world(new BlockPos(cell.x() * 32 + 15, 0, cell.z() * 32 + 15));
                int dx = (center.getX() - observed.originX() - 15) / 32;
                int dz = (center.getZ() - observed.originZ() - 15) / 32;
                if (observed.cells().stream().noneMatch(c -> c.x() == dx && c.z() == dz && cell.sharesHash(c))) {
                    matches = false;
                    break;
                }
            }
            if (matches) result.add(transform);
        }
        return List.copyOf(result);
    }

    Resolution resolve(Room observed, Function<BlockPos, String> blockAt) {
        Set<BlockPos> intersection = null;
        int possible = 0, confirmed = 0;
        for (var transform : transforms(observed)) {
            boolean rejected = false, loaded = true;
            for (Probe probe : probes) {
                String block = blockAt.apply(transform.world(probe.pos().blockPos()));
                if (block == null) loaded = false;
                else if (!probe.block().equals(block)) { rejected = true; break; }
            }
            if (rejected) continue;
            possible++;
            if (loaded) confirmed++;
            Set<BlockPos> fixed = Set.of(transform.world(chest.blockPos()));
            if (intersection == null) intersection = new HashSet<>(fixed);
            else intersection.retainAll(fixed);
        }
        // Unloaded probes preserve an orientation as possible; they never prove a mismatch.
        // Exclude only a position common to EVERY possible rotation, with a fully checked template.
        return new Resolution(confirmed > 0 && intersection != null ? Set.copyOf(intersection) : Set.of(), possible, confirmed);
    }

    record Point(int x, int y, int z) {
        BlockPos blockPos() { return new BlockPos(x, y, z); }
        static Point of(BlockPos pos) { return new Point(pos.getX(), pos.getY(), pos.getZ()); }
    }

    record Probe(Point pos, String block) {
        Probe {
            if (pos == null || block == null || !structuralBlock(block)) throw new IllegalArgumentException("Unstable orientation probe");
        }
    }

    static boolean structuralBlock(String id) {
        if (id == null || !id.startsWith("minecraft:")) return false;
        String name = id.substring(10);
        return switch (name) {
            case "stone", "cobblestone", "mossy_cobblestone", "stone_bricks", "mossy_stone_bricks",
                "cracked_stone_bricks", "chiseled_stone_bricks", "smooth_stone", "andesite", "polished_andesite",
                "diorite", "polished_diorite", "granite", "polished_granite", "bricks", "obsidian", "terracotta" -> true;
            default -> COLORED_BLOCK.matcher(name).matches();
        };
    }

    record Cell(int x, int z, Set<Integer> cores, Set<Integer> stableCores) {
        Cell {
            if (x < 0 || z < 0 || x > 3 || z > 3 || cores == null || stableCores == null
                || cores.size() > 64 || stableCores.size() > 64
                || cores.contains(0) || stableCores.contains(0) || cores.isEmpty() && stableCores.isEmpty()) {
                throw new IllegalArgumentException("Missing room variant hashes");
            }
            cores = Set.copyOf(cores);
            stableCores = Set.copyOf(stableCores);
        }
        boolean sharesHash(Cell other) {
            return cores.stream().anyMatch(other.cores()::contains) || stableCores.stream().anyMatch(other.stableCores()::contains);
        }
    }

    record Room(String name, int originX, int originZ, List<Cell> cells) {
        Room { cells = List.copyOf(cells); }
    }
    record Resolution(Set<BlockPos> fixedPositions, int possibleRotations, int confirmedRotations) { }
}
