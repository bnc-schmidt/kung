package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Footprint;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import com.github.beng420.kung.feature.garden.GreenhouseSoils.Soil;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class GreenhouseSoilCheckTest {
    private static String[] floor(String id) {
        String[] blocks = new String[GreenhouseGrid.SIZE * GreenhouseGrid.SIZE];
        Arrays.fill(blocks, id);
        return blocks;
    }

    @Test
    public void soilEquivalences() {
        assertTrue(GreenhouseSoilCheck.accepts(Soil.FARMLAND, "minecraft:farmland"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.FARMLAND, "minecraft:dirt"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.FARMLAND, "minecraft:grass_block"));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.SAND, "minecraft:sand"));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.SAND, "minecraft:red_sand"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.SAND, "minecraft:soul_sand"));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.SOUL_SAND, "minecraft:soul_sand"));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.SOUL_SAND, "minecraft:soul_soil"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.SOUL_SAND, "minecraft:sand"));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.MYCELIUM, "minecraft:mycelium"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.MYCELIUM, "minecraft:dirt"));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.END_STONE, "minecraft:end_stone"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.END_STONE, "minecraft:stone"));
        for (String dirt : List.of("minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:grass_block", "minecraft:farmland")) {
            assertTrue(dirt, GreenhouseSoilCheck.accepts(Soil.DIRT, dirt));
        }
        assertFalse(GreenhouseSoilCheck.accepts(Soil.DIRT, "minecraft:sand"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.DIRT, "minecraft:air"));
        assertFalse(GreenhouseSoilCheck.accepts(Soil.FARMLAND, null));
        assertTrue(GreenhouseSoilCheck.accepts(Soil.NONE, "minecraft:air"));
    }

    @Test
    public void soilTypeBlocksAreWhatAGreenhouseFloorIsMadeOf() {
        for (String id : List.of("minecraft:farmland", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:sand", "minecraft:red_sand", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:mycelium",
            "minecraft:end_stone", "minecraft:netherrack", "minecraft:grass_block")) {
            assertTrue(id, GreenhouseSoilCheck.isSoilType(id));
        }
        assertFalse(GreenhouseSoilCheck.isSoilType("minecraft:air"));
        assertFalse(GreenhouseSoilCheck.isSoilType("minecraft:wheat"));
        assertFalse(GreenhouseSoilCheck.isSoilType(null));
    }

    @Test
    public void theSoilLayerIsTheOneWithAClearMajorityOfSoilBlocks() {
        // Indexed like LAYERS: 73, 72, 71 (never 74: that is where the plants are read).
        assertArrayEquals(new int[] {73, 72, 71}, GreenhouseSoilCheck.LAYERS);
        assertEquals(73, GreenhouseSoilCheck.detectLayer(new int[] {98, 4, 0}));
        assertEquals(72, GreenhouseSoilCheck.detectLayer(new int[] {3, 100, 0}));
        assertEquals(71, GreenhouseSoilCheck.detectLayer(new int[] {0, 0, 51}));
        assertEquals("the most soil wins", 72, GreenhouseSoilCheck.detectLayer(new int[] {55, 90, 0}));
        assertEquals("a tie keeps the inferred crop floor", 73, GreenhouseSoilCheck.detectLayer(new int[] {70, 70, 0}));
    }

    @Test
    public void withoutAClearMajorityThereIsNoLayer() {
        assertEquals(-1, GreenhouseSoilCheck.detectLayer(new int[] {49, 49, 49}));
        assertEquals(-1, GreenhouseSoilCheck.detectLayer(new int[] {0, 0, 0}));
        assertEquals(73, GreenhouseSoilCheck.detectLayer(new int[] {50, 0, 0}));
    }

    @Test
    public void onlyTrustedSoilsAreChecked() {
        for (String crop : List.of("Nether Wart", "Wheat Seeds", "Carrot", "Potato", "Melon Seeds", "Pumpkin Seeds",
            "Cindershade", "Lonelily", "Godseed")) {
            assertTrue(crop, GreenhouseSoilCheck.trusted(crop));
        }
        for (String crop : List.of("Cactus", "Sunflower", "Fire", "Sugar Cane", "Cocoa Beans", "Brown Mushroom")) {
            assertFalse(crop, GreenhouseSoilCheck.trusted(crop));
        }
    }

    @Test
    public void aGhostMarksEachCellWhoseRealBlockIsNotTheExpectedSoil() {
        // Nether wart wants soul sand.
        List<Footprint> expected = List.of(new Footprint("Nether Wart", false, 2, 3, 3, 4));
        String[] blocks = floor("minecraft:farmland");

        List<Ghost> ghosts = GreenhouseSoilCheck.ghosts(expected, blocks);

        assertEquals(List.of(new Ghost(2, 3, Soil.SOUL_SAND)), ghosts);

        blocks[2 * GreenhouseGrid.SIZE + 3] = "minecraft:soul_soil";
        assertTrue("soul soil is as good as soul sand", GreenhouseSoilCheck.ghosts(expected, blocks).isEmpty());
        blocks[2 * GreenhouseGrid.SIZE + 3] = "minecraft:air";
        assertEquals("a missing block is a ghost too", 1, GreenhouseSoilCheck.ghosts(expected, blocks).size());
    }

    @Test
    public void aFootprintPutsItsSoilUnderEveryCellItCovers() {
        List<Footprint> expected = List.of(new Footprint("Noctilume", true, 3, 4, 5, 6));
        Soil soil = GreenhouseSoils.of("Noctilume");
        String[] blocks = floor(soil.blockId().equals("minecraft:dirt") ? "minecraft:stone" : "minecraft:dirt");

        List<Ghost> ghosts = GreenhouseSoilCheck.ghosts(expected, blocks);

        assertEquals(List.of(new Ghost(3, 4, soil), new Ghost(3, 5, soil), new Ghost(4, 4, soil), new Ghost(4, 5, soil)),
            ghosts);
    }

    @Test
    public void unreadCellsUntrustedCropsAndMatchesGetNoGhost() {
        String[] blocks = floor("minecraft:farmland");
        blocks[0] = null;
        List<Footprint> expected = List.of(new Footprint("Carrot", false, 0, 0, 1, 1),
            new Footprint("Cactus", false, 1, 0, 2, 1), new Footprint("Potato", false, 2, 0, 3, 1));

        assertTrue("unread, untrusted and matching", GreenhouseSoilCheck.ghosts(expected, blocks).isEmpty());
    }

    @Test
    public void aFootprintPartlyOutsideTheGridIsClipped() {
        String[] blocks = floor("minecraft:air");
        List<Footprint> expected = List.of(new Footprint("Carrot", false, 9, 9, 11, 11));

        assertEquals(List.of(new Ghost(9, 9, Soil.FARMLAND)), GreenhouseSoilCheck.ghosts(expected, blocks));
    }
}
