package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class GreenhouseItemsTest {
    private static GreenhouseItems.Item item(String crop) {
        return GreenhouseItems.find(crop).orElseThrow();
    }

    @Test
    public void bundleHoldsEveryCropOnTheListExactlyOnce() {
        assertEquals("", GreenhouseItems.loadError());
        Set<String> names = new HashSet<>();
        GreenhouseItems.all().forEach(item -> assertTrue(item.crop(), names.add(item.crop())));

        assertEquals(Set.copyOf(GreenhouseCrops.NAMES), names);
        assertEquals(65, GreenhouseItems.all().size());
    }

    @Test
    public void idsUseTheGameSpellingAndASackIdIsTheItemId() {
        for (GreenhouseItems.Item item : GreenhouseItems.all()) {
            if (item.id() != null) assertEquals(item.crop(), item.id(), GreenhouseSupply.normalizeId(item.id()));
            if (item.sackable()) assertEquals(item.crop(), item.id(), item.gfs());
            assertTrue(item.crop(), item.plantsPerItem() >= 1);
        }
        assertEquals("INK_SACK:3", item("Cocoa Beans").gfs());
        assertEquals("SEEDS", item("Wheat Seeds").gfs());
        assertEquals("DOUBLE_PLANT", item("Sunflower").gfs());
        assertEquals("NETHER_STALK", item("Nether Wart").gfs());
        assertEquals("CARROT_ITEM", item("Carrot").gfs());
        assertEquals("DO_NOT_EAT_SHROOM", item("Do-not-eat-shroom").gfs());
    }

    @Test
    public void everyMutationButJerryflowerComesFromTheMutationsSack() {
        for (GreenhouseMutations.Mutation mutation : GreenhouseMutations.all()) {
            assertEquals(mutation.name(), !mutation.name().equals("Jerryflower"), item(mutation.name()).sackable());
        }
    }

    @Test
    public void seedsArePlantedFromTheirStandInItem() {
        GreenhouseItems.Item melon = item("Melon Seeds");
        assertEquals("MELON", melon.id());
        assertEquals("MELON", melon.gfs());
        assertEquals("Melon Slice", melon.unit());
        assertEquals(1, melon.plantsPerItem());

        GreenhouseItems.Item pumpkin = item("Pumpkin Seeds");
        assertEquals("PUMPKIN", pumpkin.gfs());
        assertEquals("Pumpkin", pumpkin.unit());
        assertEquals(4, pumpkin.plantsPerItem());
        assertEquals("Carrot", item("Carrot").unit());
    }

    @Test
    public void whatNoSackHoldsIsMarkedForManualFetching() {
        for (String crop : new String[] {"Jerryflower", "Fertilized Jerryseed", "Condensed Helianthus"}) {
            assertFalse(crop, item(crop).sackable());
            assertTrue(crop, item(crop).id() != null);
        }
        for (String crop : new String[] {"Fire", "Rose Dragon Egg", "Jerryseed", "Dead Plants"}) {
            assertFalse(crop, item(crop).sackable());
            assertNull(crop, item(crop).id());
        }
    }

    @Test
    public void aSackAnswerIsRecognisedByTheItemsOwnNames() {
        GreenhouseItems.Item seeds = item("Wheat Seeds");
        assertTrue(seeds.answersTo("Seeds"));
        assertTrue(seeds.answersTo("wheat seeds"));
        assertFalse("another item", seeds.answersTo("Wheat"));
        assertFalse(seeds.answersTo("Carrot"));
        assertFalse(seeds.answersTo(null));

        GreenhouseItems.Item melon = item("Melon Seeds");
        assertTrue(melon.answersTo("Melon Slice"));
        assertTrue(melon.answersTo("Melon"));
        assertFalse(melon.answersTo("Melon Seeds Enchanted"));

        assertTrue(item("Do-not-eat-shroom").answersTo("Do-Not-Eat-Shroom"));
        assertTrue(item("Carrot").answersTo("  CARROT "));
        assertFalse(item("Carrot").answersTo("Potato"));
    }

    @Test
    public void everySoilBlockHasARowWithTheIdsOfTheNeuRepository() {
        assertEquals(6, GreenhouseItems.soils().size());
        for (GreenhouseSoils.Soil soil : GreenhouseSoils.Soil.values()) {
            if (soil == GreenhouseSoils.Soil.NONE) {
                assertTrue(GreenhouseItems.soil(soil).isEmpty());
                continue;
            }
            GreenhouseItems.Item item = GreenhouseItems.soil(soil).orElseThrow();
            assertEquals(soil.displayName(), item.crop());
            assertEquals(1, item.plantsPerItem());
            if (item.id() != null) assertEquals(soil.displayName(), item.id(), GreenhouseSupply.normalizeId(item.id()));
            if (item.sackable()) assertEquals(soil.displayName(), item.id(), item.gfs());
        }
        // Sacks.json: Mining holds SAND, MYCEL and ENDER_STONE; no sack holds SOUL_SAND or DIRT.
        assertEquals("SAND", GreenhouseItems.soil(GreenhouseSoils.Soil.SAND).orElseThrow().gfs());
        assertEquals("MYCEL", GreenhouseItems.soil(GreenhouseSoils.Soil.MYCELIUM).orElseThrow().gfs());
        assertEquals("ENDER_STONE", GreenhouseItems.soil(GreenhouseSoils.Soil.END_STONE).orElseThrow().gfs());
        assertEquals("SOUL_SAND", GreenhouseItems.soil(GreenhouseSoils.Soil.SOUL_SAND).orElseThrow().id());
        assertFalse(GreenhouseItems.soil(GreenhouseSoils.Soil.SOUL_SAND).orElseThrow().sackable());
        assertEquals("DIRT", GreenhouseItems.soil(GreenhouseSoils.Soil.DIRT).orElseThrow().id());
        assertFalse(GreenhouseItems.soil(GreenhouseSoils.Soil.DIRT).orElseThrow().sackable());
        assertNull(GreenhouseItems.soil(GreenhouseSoils.Soil.FARMLAND).orElseThrow().id());
    }

    @Test
    public void soilAnswersAreRecognisedByTheBlocksName() {
        GreenhouseItems.Item sand = GreenhouseItems.soil(GreenhouseSoils.Soil.SAND).orElseThrow();
        assertTrue(sand.answersTo("Sand"));
        assertFalse(sand.answersTo("Red Sand"));
        assertTrue(GreenhouseItems.soil(GreenhouseSoils.Soil.END_STONE).orElseThrow().answersTo("end stone"));
        assertTrue(GreenhouseItems.soil(GreenhouseSoils.Soil.MYCELIUM).orElseThrow().answersTo("Mycelium"));
    }

    @Test
    public void parseSoilsRejectsWhatIsNotASoilBlock() {
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parseSoils("{\"schema\":1,\"soils\":{\"Carrot\":{\"id\":\"CARROT_ITEM\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parseSoils("{\"schema\":1,\"soils\":{\"None\":{\"id\":\"AIR\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parseSoils("{\"schema\":1,\"soils\":{\"Sand\":{\"id\":\"sand\"}}}"));
        assertEquals(1, GreenhouseItems.parseSoils("{\"schema\":1,\"soils\":{\"Sand\":{\"id\":\"SAND\"}}}").size());
        assertEquals(0, GreenhouseItems.parseSoils("{\"schema\":1,\"crops\":{}}").size());
    }

    @Test
    public void lookupUsesTheCropListsLooseMatching() {
        assertEquals("Do-not-eat-shroom", item("do not eat shroom").crop());
        assertEquals("Wheat Seeds", item("wheat_seeds").crop());
        assertTrue(GreenhouseItems.find("Banana").isEmpty());
        assertTrue(GreenhouseItems.find(null).isEmpty());
    }

    @Test
    public void parseRejectsDataThatWouldPullTheWrongItem() {
        assertThrows(IllegalArgumentException.class, () -> GreenhouseItems.parse("{\"schema\":2,\"crops\":{}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Banana\":{\"id\":\"BANANA\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Cocoa Beans\":{\"id\":\"INK_SACK-3\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Carrot\":{\"id\":\"carrot_item\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Carrot\":{\"gfs\":\"CARROT_ITEM\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Carrot\":{\"id\":\"CARROT_ITEM\",\"plantsPerItem\":0}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Carrot\":{\"id\":\"CARROT_ITEM\",\"names\":[\" \"]}}}"));
        assertEquals(1, GreenhouseItems.parse("{\"schema\":1,\"crops\":{\"Carrot\":{\"id\":\"CARROT_ITEM\"}}}").size());
    }
}
