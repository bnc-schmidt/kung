package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseCropLook.BlockSpec;
import com.github.beng420.kung.feature.garden.GreenhouseCropLook.Look;
import com.github.beng420.kung.feature.garden.GreenhouseCropLook.Rung;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class GreenhouseCropLookTest {
    @Test
    public void everyKnownCropResolvesAndOnlyCondensedHelianthusIsLeftToTheCube() {
        for (String name : GreenhouseCrops.NAMES) {
            Look look = GreenhouseCropLook.of(name);
            assertNotNull(name, look);
            assertEquals(name, Rung.CUBE, look.ladder().getLast());
            boolean hasModel = look.head() != null || look.block() != null;
            assertEquals(name, !name.equals("Condensed Helianthus"), hasModel);
        }
    }

    @Test
    public void vanillaCropsAreTheirGrownBlocks() {
        assertEquals(new BlockSpec("minecraft:wheat", Map.of("age", "7")), GreenhouseCropLook.of("Wheat Seeds").block());
        assertEquals(new BlockSpec("minecraft:carrots", Map.of("age", "7")), GreenhouseCropLook.of("Carrot").block());
        assertEquals(new BlockSpec("minecraft:potatoes", Map.of("age", "7")), GreenhouseCropLook.of("Potato").block());
        assertEquals(new BlockSpec("minecraft:nether_wart", Map.of("age", "3")),
            GreenhouseCropLook.of("Nether Wart").block());
        assertEquals("minecraft:sugar_cane", GreenhouseCropLook.of("Sugar Cane").block().id());
        assertEquals("minecraft:dead_bush", GreenhouseCropLook.of("Dead Plants").block().id());
        assertEquals("minecraft:fire", GreenhouseCropLook.of("Fire").block().id());
        assertEquals("minecraft:vine", GreenhouseCropLook.of("Ethereal Vine").block().id());
        // They have no head in the icon table, so the block is the model.
        assertNull(GreenhouseCropLook.of("Carrot").head());
    }

    @Test
    public void cropsWithAHeadInTheIconTableUseTheHead() {
        for (String name : List.of("Brown Mushroom", "Red Mushroom", "Cactus", "Sunflower", "Moonflower", "Wild Rose",
            "Cocoa Beans", "Melon Seeds", "Pumpkin Seeds", "Rose Dragon Egg", "Cindershade", "Godseed")) {
            Look look = GreenhouseCropLook.of(name);
            assertEquals(name, GreenhouseIcons.table().get(name).head(), look.head());
            assertNotNull(name, look.head());
            assertNull(name, look.block());
        }
    }

    @Test
    public void aHeadsStandInItemIsKeptAsABlockToTryWhenTheHeadFails() {
        Look cactus = GreenhouseCropLook.of("Cactus");
        assertEquals(new BlockSpec("minecraft:cactus", Map.of()), cactus.standIn());
        assertEquals(List.of(Rung.HEAD, Rung.BLOCK, Rung.CUBE), cactus.ladder());
        assertNull(GreenhouseCropLook.of("Cindershade").standIn());
        assertEquals(List.of(Rung.HEAD, Rung.CUBE), GreenhouseCropLook.of("Cindershade").ladder());
    }

    @Test
    public void ladderOrderIsHeadThenBlockThenCube() {
        assertEquals(List.of(Rung.BLOCK, Rung.CUBE), GreenhouseCropLook.of("Carrot").ladder());
        assertEquals(List.of(Rung.CUBE), Look.NONE.ladder());
        assertEquals(List.of(Rung.HEAD, Rung.BLOCK, Rung.CUBE),
            new Look("abc", new BlockSpec("minecraft:wheat", Map.of()), null).ladder());
    }

    @Test
    public void anUnknownNameIsOnlyACube() {
        Look look = GreenhouseCropLook.of("Brand New Crop");

        assertNull(look.head());
        assertNull(look.block());
        assertNull(look.standIn());
        assertEquals(List.of(Rung.CUBE), look.ladder());
    }

    @Test
    public void everyMutationInTheRecipeTableHasAHead() {
        for (var mutation : GreenhouseMutations.all()) {
            assertTrue(mutation.name(), GreenhouseCropLook.of(mutation.name()).head() != null);
        }
    }
}
