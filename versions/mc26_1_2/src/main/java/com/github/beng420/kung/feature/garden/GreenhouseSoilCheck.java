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
    /** At least this many of the 100 cells must be soil-type blocks for a layer to be trusted. */
    static final int MAJORITY = 50;

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

    /** A cell (raw offsets) whose real block is not the expected soil. */
    record Ghost(int dx, int dz, Soil soil) { }

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
            case DIRT -> blockId.equals("minecraft:dirt") || blockId.equals("minecraft:coarse_dirt")
                || blockId.equals("minecraft:rooted_dirt") || blockId.equals("minecraft:grass_block")
                || blockId.equals("minecraft:farmland");
            case NONE -> true;
            // A soil added to GreenhouseSoils later: accept at least its own block.
            default -> blockId.equals(expected.blockId());
        };
    }

    static boolean trusted(String crop) {
        return TRUSTED_BASE.contains(crop) || GreenhouseMutations.find(crop).isPresent();
    }

    /**
     * The soil layer: the candidate y with the most soil-type blocks among the 100 cells, if that is at least
     * {@link #MAJORITY}; else -1 (draw no ghosts). {@code soilCounts} is indexed like {@link #LAYERS}.
     */
    static int detectLayer(int[] soilCounts) {
        int best = -1;
        int bestCount = MAJORITY - 1;
        for (int i = 0; i < LAYERS.length && i < soilCounts.length; i++) {
            if (soilCounts[i] > bestCount) {
                best = LAYERS[i];
                bestCount = soilCounts[i];
            }
        }
        return best;
    }

    /**
     * The cells whose real block does not suit what is planned there. {@code blockIds} holds the real block id per
     * cell at the soil layer, index {@code dx * 10 + dz}; a null entry was not read and is skipped. A footprint
     * puts its soil under every cell it covers; a crop with an untrusted soil gets no ghost.
     */
    static List<Ghost> ghosts(List<Footprint> expected, String[] blockIds) {
        List<Ghost> out = new ArrayList<>();
        for (Footprint footprint : expected) {
            if (!trusted(footprint.crop())) continue;
            Soil soil = GreenhouseSoils.of(footprint.crop());
            if (soil == Soil.NONE) continue;
            int firstDx = (int) Math.round(footprint.minX());
            int firstDz = (int) Math.round(footprint.minZ());
            int width = (int) Math.round(footprint.maxX() - footprint.minX());
            int depth = (int) Math.round(footprint.maxZ() - footprint.minZ());
            for (int dx = firstDx; dx < firstDx + width; dx++) {
                for (int dz = firstDz; dz < firstDz + depth; dz++) {
                    if (dx < 0 || dx >= GreenhouseGrid.SIZE || dz < 0 || dz >= GreenhouseGrid.SIZE) continue;
                    String real = blockIds[dx * GreenhouseGrid.SIZE + dz];
                    if (real != null && !accepts(soil, real)) out.add(new Ghost(dx, dz, soil));
                }
            }
        }
        return List.copyOf(out);
    }
}
