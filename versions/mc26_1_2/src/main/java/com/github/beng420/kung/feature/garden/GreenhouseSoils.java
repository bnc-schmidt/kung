package com.github.beng420.kung.feature.garden;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ground each greenhouse crop needs under it. The greenhouse floor is dirt tilled to farmland; a few mutations
 * grow only on another block, and a crop planted on the wrong ground is blocked in game, so the planner and the
 * hologram show the matching block behind every crop. Plain Java: the planner draws {@link Soil#rgb()} behind a
 * cell and the hologram can use {@link Soil#blockId()} for the floor.
 *
 * <p>Mutations take the first soil listed in {@code kung-greenhouse-mutations.json} (Lonelily: Farmland, Dirt;
 * Shadevine: the file says Farmland although some wiki pages also allow Sand). Base crops are only partly
 * known: Nether Wart on Soul Sand (low confidence), wheat, carrot, potato, melon and pumpkin on Farmland.
 * UNVERIFIED: every other base crop and item (cocoa, roses, flowers, cactus, mushrooms, sugar cane, fire and the
 * drop items) is assumed to stand on Farmland because the wiki says nothing about it.
 */
final class GreenhouseSoils {
    enum Soil {
        FARMLAND("Farmland", "minecraft:farmland", 0x6B4A2E),
        SAND("Sand", "minecraft:sand", 0xD8CB9B),
        SOUL_SAND("Soul Sand", "minecraft:soul_sand", 0x4E3F35),
        MYCELIUM("Mycelium", "minecraft:mycelium", 0x6F6272),
        END_STONE("End Stone", "minecraft:end_stone", 0xDFE0A8),
        DIRT("Dirt", "minecraft:dirt", 0x866043),
        /** No ground: an empty cell. */
        NONE("None", "minecraft:air", 0x282828);

        private final String displayName;
        private final String blockId;
        private final int rgb;

        Soil(String displayName, String blockId, int rgb) {
            this.displayName = displayName;
            this.blockId = blockId;
            this.rgb = rgb;
        }

        String displayName() { return displayName; }

        /** The block that is this soil in the world, for example {@code minecraft:soul_sand}. */
        String blockId() { return blockId; }

        /** A colour (0xRRGGBB) that resembles the block's top face. */
        int rgb() { return rgb; }
    }

    private static final Map<String, Soil> BASE_CROPS = Map.of("Nether Wart", Soil.SOUL_SAND);
    /** Soil by crop name, filled on first use; the screen asks per grid change, not per frame. */
    private static final Map<String, Soil> CACHE = new ConcurrentHashMap<>();

    private GreenhouseSoils() { }

    /**
     * The soil under a crop or mutation, by name (loose spelling is accepted); {@link Soil#NONE} for null. A name
     * the planner does not know stands on farmland like the unverified base crops.
     */
    static Soil of(String cropName) {
        if (cropName == null) return Soil.NONE;
        Soil known = CACHE.get(cropName);
        if (known == null) {
            known = resolve(GreenhouseCrops.canonical(cropName));
            CACHE.put(cropName, known);
        }
        return known;
    }

    private static Soil resolve(String name) {
        Soil base = BASE_CROPS.get(name);
        if (base != null) return base;
        return GreenhouseMutations.find(name)
            .filter(mutation -> !mutation.soil().isEmpty())
            .map(mutation -> named(mutation.soil().get(0)))
            .orElse(Soil.FARMLAND);
    }

    /** A soil from the spelling the recipe file uses ("Soul Sand"); farmland when it is not one of ours. */
    private static Soil named(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Soil soil : Soil.values()) {
            if (soil != Soil.NONE && soil.displayName.toLowerCase(Locale.ROOT).equals(wanted)) return soil;
        }
        return Soil.FARMLAND;
    }
}
