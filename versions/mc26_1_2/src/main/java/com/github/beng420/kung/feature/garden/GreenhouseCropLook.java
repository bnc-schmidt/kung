package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What stands for a crop (or mutation) in the hologram, as plain data: a player head by texture hash, a vanilla
 * block with the properties that make it look grown, or neither (a coloured cube). The heads come from the
 * planner's icon table ({@link GreenhouseIcons#table()}), so the hologram shows what the planner shows.
 * Resolving these to game objects is {@link GreenhouseModels}' job.
 */
final class GreenhouseCropLook {
    /** A vanilla block id with property values as text ({@code age=7}); resolved against the block's own states. */
    record BlockSpec(String id, Map<String, String> properties) { }

    /**
     * {@code head}: texture hash or null. {@code block}: the block that is the crop itself, for crops without a
     * head. {@code standIn}: the icon table's item id read as a block, tried if the head cannot be drawn.
     */
    record Look(String head, BlockSpec block, BlockSpec standIn) {
        static final Look NONE = new Look(null, null, null);

        /** The rungs to try, best first; the cube is always the last. */
        List<Rung> ladder() {
            List<Rung> rungs = new ArrayList<>(3);
            if (head != null) rungs.add(Rung.HEAD);
            if (block != null || standIn != null) rungs.add(Rung.BLOCK);
            rungs.add(Rung.CUBE);
            return rungs;
        }
    }

    enum Rung { HEAD, BLOCK, CUBE }

    /**
     * The crops that are vanilla blocks. Ages are the grown stage (crops 7, nether wart 3); the vine needs a
     * face set to draw anything. Condensed Helianthus (paper) has no block and stays a cube.
     */
    private static final Map<String, BlockSpec> VANILLA = Map.of(
        "Wheat Seeds", new BlockSpec("minecraft:wheat", Map.of("age", "7")),
        "Carrot", new BlockSpec("minecraft:carrots", Map.of("age", "7")),
        "Potato", new BlockSpec("minecraft:potatoes", Map.of("age", "7")),
        "Nether Wart", new BlockSpec("minecraft:nether_wart", Map.of("age", "3")),
        "Sugar Cane", new BlockSpec("minecraft:sugar_cane", Map.of()),
        "Dead Plants", new BlockSpec("minecraft:dead_bush", Map.of()),
        "Fire", new BlockSpec("minecraft:fire", Map.of()),
        "Ethereal Vine", new BlockSpec("minecraft:vine", Map.of("north", "true")));

    private GreenhouseCropLook() { }

    /** The look for a crop or mutation name; {@link Look#NONE} for a name the tables do not know. */
    static Look of(String crop) {
        GreenhouseIcons.Icon icon = GreenhouseIcons.table().get(crop);
        String head = icon == null ? null : icon.head();
        // A head wins: the vanilla block is only for crops the site draws as one.
        BlockSpec block = head != null ? null : VANILLA.get(crop);
        BlockSpec standIn = icon == null || icon.item() == null ? null : new BlockSpec(icon.item(), Map.of());
        return new Look(head, block, standIn);
    }
}
