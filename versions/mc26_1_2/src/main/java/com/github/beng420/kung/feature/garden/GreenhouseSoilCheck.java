package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Footprint;
import com.github.beng420.kung.feature.garden.GreenhouseSoils.Soil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which soil blocks are missing from the real greenhouse floor, as plain data: what block counts as which soil,
 * which crops' soils are trusted enough to draw, which layer of the floor is the soil layer, and the ghosts to draw
 * where the real block differs. The layer (73 or 72 are both plausible) is found from the world, not assumed.
 */
final class GreenhouseSoilCheck {
    /**
     * Candidate soil layers, best guess first so that a tie keeps the inferred crop floor (73). The crop layer
     * (74) is not one: plants are read there, and a layer of plants must not pass for the soil.
     */
    static final int[] LAYERS = {73, 72, 71};
    /**
     * At least this many of the 100 cells must be soil-type blocks for a layer to be trusted. Low on purpose: a
     * greenhouse with few unlocked cells (or a bare floor) has little soil, and without a layer no soil is drawn.
     */
    static final int MINIMUM = 10;
    /**
     * The layer used while no candidate has {@link #MINIMUM} soil-type blocks (all soil removed, or none placed yet):
     * the crop floor, directly under the plants. Without it a bare greenhouse would get no soil ghosts at all, and a
     * bare greenhouse is exactly where the soil is wanted. The scanner looks for the real layer again at every scan.
     */
    static final int ASSUMED_LAYER = GreenhouseLocator.FLOOR_Y;

    /** Blocks that look like a greenhouse floor; used to find the soil layer. */
    private static final Set<String> SOIL_TYPES = Set.of(
        "minecraft:farmland", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:podzol",
        "minecraft:grass_block", "minecraft:sand", "minecraft:red_sand", "minecraft:soul_sand", "minecraft:soul_soil",
        "minecraft:mycelium", "minecraft:end_stone", "minecraft:netherrack");

    /**
     * Crops whose soil is known well enough to complain about: every mutation (the recipe file names it) and the
     * base crops the wiki is clear on. Everything else is an assumption in {@link GreenhouseSoils}.
     */
    private static final Set<String> TRUSTED_BASE = Set.of(
        "Nether Wart", "Wheat Seeds", "Carrot", "Potato", "Melon Seeds", "Pumpkin Seeds");

    /**
     * How a cell's real block differs from the soil it should have: nothing is there ({@code MISSING}, drawn as a
     * block still to place), some other block is ({@code WRONG_BLOCK}, red), or it is farmland where dirt belongs or
     * dirt where farmland belongs ({@code WRONG_STATE}, orange: till it or replace it).
     */
    enum GhostKind { MISSING, WRONG_BLOCK, WRONG_STATE }

    /** A cell (raw offsets) whose real block is not the expected soil; {@code soil} is the soil that belongs there. */
    record Ghost(int dx, int dz, Soil soil, GhostKind kind) {
        /** A missing soil block. */
        Ghost(int dx, int dz, Soil soil) {
            this(dx, dz, soil, GhostKind.MISSING);
        }
    }

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    /** The blocks that count as dirt for the farmland/dirt pair: tilling turns them into farmland. */
    private static final Set<String> DIRT_FAMILY = Set.of(
        "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:grass_block");

    private GreenhouseSoilCheck() { }

    static boolean isSoilType(String blockId) {
        return blockId != null && SOIL_TYPES.contains(blockId);
    }

    /** Whether the real block is good enough for the soil the crop needs. */
    static boolean accepts(Soil expected, String blockId) {
        if (blockId == null) return false;
        return switch (expected) {
            case FARMLAND -> blockId.equals("minecraft:farmland");
            case SAND -> blockId.equals("minecraft:sand") || blockId.equals("minecraft:red_sand");
            case SOUL_SAND -> blockId.equals("minecraft:soul_sand") || blockId.equals("minecraft:soul_soil");
            case MYCELIUM -> blockId.equals("minecraft:mycelium");
            case END_STONE -> blockId.equals("minecraft:end_stone");
            // Farmland where dirt belongs is the other wrong state, not a match.
            case DIRT -> DIRT_FAMILY.contains(blockId);
            case NONE -> true;
            // A soil added to GreenhouseSoils later: accept at least its own block.
            default -> blockId.equals(expected.blockId());
        };
    }

    /**
     * What is wrong with the real block under the expected soil, or null when it is fine. Air is a missing block;
     * the farmland/dirt pair either way round is a wrong state; any other block is the wrong block.
     */
    static GhostKind classify(Soil expected, String blockId) {
        if (blockId == null || accepts(expected, blockId)) return null;
        if (AIR.contains(blockId)) return GhostKind.MISSING;
        boolean farmlandForDirt = expected == Soil.DIRT && blockId.equals("minecraft:farmland");
        boolean dirtForFarmland = expected == Soil.FARMLAND && DIRT_FAMILY.contains(blockId);
        return farmlandForDirt || dirtForFarmland ? GhostKind.WRONG_STATE : GhostKind.WRONG_BLOCK;
    }

    static boolean trusted(String crop) {
        return TRUSTED_BASE.contains(crop) || GreenhouseMutations.find(crop).isPresent();
    }

    /**
     * A crop whose soil is only assumed (farmland, see {@link GreenhouseSoils}) still gets one complaint: plain dirt
     * where it stands. Fire is a flame, not a plant, so it has no soil to complain about.
     */
    private static boolean complainsAboutDirt(String crop) {
        return !crop.equals("Fire");
    }

    /**
     * The soil layer: the candidate y with the most soil-type blocks among the 100 cells, if that is at least
     * {@link #MINIMUM}; else -1 (the scanner then assumes {@link #ASSUMED_LAYER}). {@code soilCounts} is indexed like
     * {@link #LAYERS}.
     */
    static int detectLayer(int[] soilCounts) {
        int best = -1;
        int bestCount = MINIMUM - 1;
        for (int i = 0; i < LAYERS.length && i < soilCounts.length; i++) {
            if (soilCounts[i] > bestCount) {
                best = LAYERS[i];
                bestCount = soilCounts[i];
            }
        }
        return best;
    }

    /**
     * Per footprint (aligned with {@code footprints}): whether a soil ghost lies in one of its cells, so the crop
     * cannot be planted there yet. The hologram then draws neither the crop nor its to-place outline; the soil marker
     * stays and says what to fix first. Every ghost kind counts.
     */
    static boolean[] blocked(List<Footprint> footprints, List<Ghost> ghosts) {
        boolean[] blocked = new boolean[footprints.size()];
        if (ghosts.isEmpty()) return blocked;
        for (int i = 0; i < blocked.length; i++) {
            Footprint footprint = footprints.get(i);
            for (Ghost ghost : ghosts) {
                double centreX = ghost.dx() + 0.5;
                double centreZ = ghost.dz() + 0.5;
                if (centreX > footprint.minX() && centreX < footprint.maxX()
                    && centreZ > footprint.minZ() && centreZ < footprint.maxZ()) {
                    blocked[i] = true;
                    break;
                }
            }
        }
        return blocked;
    }

    /** The footprints {@link #blocked} flags, in order: the ones the hologram draws no model for. */
    static List<Footprint> blockedFootprints(List<Footprint> footprints, List<Ghost> ghosts) {
        boolean[] blocked = blocked(footprints, ghosts);
        List<Footprint> out = new ArrayList<>();
        for (int i = 0; i < blocked.length; i++) {
            if (blocked[i]) out.add(footprints.get(i));
        }
        return List.copyOf(out);
    }

    /**
     * The cells whose real block does not suit what is planned there, each with how it differs.
     * {@code blockIds} holds the real block id per cell at the soil layer, index {@code dx * 10 + dz}; a null entry
     * was not read and is skipped. A footprint puts its soil under every cell it covers; a crop with an untrusted
     * soil only gets a wrong-state ghost (dirt where farmland is assumed), and cells the layout has no entry for are never looked at (soil there is ignored).
     */
    static List<Ghost> ghosts(List<Footprint> expected, String[] blockIds) {
        List<Ghost> out = new ArrayList<>();
        for (Footprint footprint : expected) {
            boolean trusted = trusted(footprint.crop());
            if (!trusted && !complainsAboutDirt(footprint.crop())) continue;
            Soil soil = GreenhouseSoils.of(footprint.crop());
            if (soil == Soil.NONE) continue;
            int firstDx = (int) Math.round(footprint.minX());
            int firstDz = (int) Math.round(footprint.minZ());
            int width = (int) Math.round(footprint.maxX() - footprint.minX());
            int depth = (int) Math.round(footprint.maxZ() - footprint.minZ());
            for (int dx = firstDx; dx < firstDx + width; dx++) {
                for (int dz = firstDz; dz < firstDz + depth; dz++) {
                    if (dx < 0 || dx >= GreenhouseGrid.SIZE || dz < 0 || dz >= GreenhouseGrid.SIZE) continue;
                    GhostKind kind = classify(soil, blockIds[dx * GreenhouseGrid.SIZE + dz]);
                    // An assumed soil only tells dirt from farmland; any other block may well be right for the crop.
                    if (kind != null && (trusted || kind == GhostKind.WRONG_STATE)) out.add(new Ghost(dx, dz, soil, kind));
                }
            }
        }
        return List.copyOf(out);
    }
}
