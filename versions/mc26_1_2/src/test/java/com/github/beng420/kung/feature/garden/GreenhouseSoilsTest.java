package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSoils.Soil;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class GreenhouseSoilsTest {
    private static void assertSoil(Soil expected, String... crops) {
        for (String crop : crops) assertEquals(crop, expected, GreenhouseSoils.of(crop));
    }

    @Test
    public void everyKnownCropResolvesToARealSoil() {
        for (String crop : GreenhouseCrops.NAMES) {
            Soil soil = GreenhouseSoils.of(crop);
            assertNotNull(crop, soil);
            assertNotEquals(crop, Soil.NONE, soil);
        }
        assertEquals(65, GreenhouseCrops.NAMES.size());
    }

    @Test
    public void mutationsThatNeedAnotherGroundKeepItAndTheRestStandOnFarmland() {
        assertSoil(Soil.SOUL_SAND, "Ashwreath", "Witherbloom", "Cindershade", "Zombud", "Phantomleaf");
        assertSoil(Soil.SAND, "Blastberry", "Magic Jellybean", "All-in Aloe", "Glasscorn");
        assertSoil(Soil.MYCELIUM, "Veilshroom");
        assertSoil(Soil.END_STONE, "Chorus Fruit", "Timestalk");
        // The first listed alternative wins: Lonelily may also use dirt, Shadevine is shown on farmland.
        assertSoil(Soil.FARMLAND, "Lonelily", "Shadevine");
        assertSoil(Soil.FARMLAND, "Choconut", "Dustgrain", "Gloomgourd", "Scourroot", "Chocoberry", "Coalroot",
            "Creambloom", "Duskbloom", "Thornshade", "Cheesebite", "Chloronite", "Do-not-eat-shroom", "Fleshtrap",
            "Noctilume", "Snoozling", "Soggybud", "Turtlellini", "PlantBoy Advance", "Puffercloud", "Startlevine",
            "Stoplight Petal", "Thunderling", "Devourer", "Shellfruit", "Jerryflower", "Godseed");
    }

    @Test
    public void everyMutationFollowsTheFirstSoilOfItsRecipe() {
        for (GreenhouseMutations.Mutation mutation : GreenhouseMutations.all()) {
            assertEquals(mutation.name(), mutation.soil().get(0), GreenhouseSoils.of(mutation.name()).displayName());
        }
    }

    @Test
    public void baseCropsUseTheirKnownGroundAndOthersTheUnverifiedFarmlandDefault() {
        assertSoil(Soil.SOUL_SAND, "Nether Wart");
        assertSoil(Soil.FARMLAND, "Wheat Seeds", "Carrot", "Potato", "Melon Seeds", "Pumpkin Seeds");
        // UNVERIFIED crops: the wiki is silent, so they take the default floor.
        assertSoil(Soil.FARMLAND, "Cocoa Beans", "Wild Rose", "Moonflower", "Sunflower", "Cactus", "Sugar Cane",
            "Brown Mushroom", "Red Mushroom", "Dead Plants", "Fire", "Helianthus", "Fermento", "Squash", "Cropie",
            "Rose Dragon Egg", "Ethereal Vine", "Jerryseed", "Fertilized Jerryseed", "Condensed Helianthus");
    }

    @Test
    public void looseSpellingsAndUnknownNamesNeverThrow() {
        assertSoil(Soil.SOUL_SAND, "nether  wart", "NETHER-WART", "cindershade");
        assertSoil(Soil.END_STONE, "chorus fruit");
        assertSoil(Soil.FARMLAND, "Totally New Crop", "", "-", "x".repeat(500));
        assertEquals(Soil.NONE, GreenhouseSoils.of(null));
    }

    @Test
    public void soilsCarryBlockIdsNamesAndDistinctColours() {
        assertEquals("minecraft:farmland", Soil.FARMLAND.blockId());
        assertEquals("minecraft:sand", Soil.SAND.blockId());
        assertEquals("minecraft:soul_sand", Soil.SOUL_SAND.blockId());
        assertEquals("minecraft:mycelium", Soil.MYCELIUM.blockId());
        assertEquals("minecraft:end_stone", Soil.END_STONE.blockId());
        assertEquals("minecraft:dirt", Soil.DIRT.blockId());
        assertEquals("Soul Sand", Soil.SOUL_SAND.displayName());
        assertEquals("End Stone", Soil.END_STONE.displayName());

        Set<Integer> colours = new HashSet<>();
        for (Soil soil : Soil.values()) {
            assertEquals(soil.name(), 0, soil.rgb() >>> 24);
            assertTrue(soil.name(), colours.add(soil.rgb()));
        }
    }

    @Test
    public void cellBackgroundsAreOpaqueDarkenedSoilColoursThatDifferFromAnEmptyCell() {
        int empty = 0xFF282828;
        Set<Integer> seen = new HashSet<>();
        for (Soil soil : Soil.values()) {
            if (soil == Soil.NONE) continue;
            int background = GreenhouseGridPainter.background(soil);

            assertEquals(soil.name(), 0xFF, background >>> 24);
            assertTrue(soil.name(), (background & 0xFF) <= (soil.rgb() & 0xFF));
            assertTrue(soil.name(), (background >> 16 & 0xFF) <= (soil.rgb() >> 16 & 0xFF));
            assertNotEquals(soil.name(), empty, background);
            assertTrue(soil.name(), seen.add(background));
        }
    }
}
