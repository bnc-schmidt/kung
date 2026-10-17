package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSupply.Action;
import com.github.beng420.kung.feature.garden.GreenhouseSupply.Plan;
import java.util.Map;
import java.util.Optional;
import org.junit.After;
import org.junit.Test;

public final class GreenhouseSupplyTest {
    private static final int INGREDIENT = GreenhouseGrid.INGREDIENT;

    @After
    public void restorePlantedSource() {
        GreenhouseSupply.setPlantedSource(null);
    }

    @Test
    public void tenNeededTwoPlantedThreeCarriedPullsFive() {
        Plan plan = GreenhouseSupply.plan("Carrot", INGREDIENT, 10, 2, Map.of("CARROT_ITEM", 3));

        assertEquals(Action.PULL, plan.action());
        assertEquals(5, plan.missing());
        assertEquals(5, plan.amount());
        assertEquals("gfs CARROT_ITEM 5", plan.command());
        assertEquals("Pulling 5 Carrot from Sacks (10 needed, 2 placed correctly, 3 in inventory)", plan.message());
        assertFalse(plan.clamped());
    }

    @Test
    public void enoughPlantedOrCarriedOnlySwitches() {
        Plan carried = GreenhouseSupply.plan("Carrot", INGREDIENT, 10, 2, Map.of("CARROT_ITEM", 8));
        assertEquals(Action.SWITCH, carried.action());
        assertEquals(0, carried.amount());

        Plan more = GreenhouseSupply.plan("Carrot", INGREDIENT, 10, 2, Map.of("CARROT_ITEM", 50));
        assertEquals(Action.SWITCH, more.action());

        Plan planted = GreenhouseSupply.plan("Carrot", INGREDIENT, 10, 12, Map.of());
        assertEquals(Action.SWITCH, planted.action());
        assertEquals(0, planted.missing());
        assertEquals("All 10 Carrot are already placed correctly.", planted.message());

        Plan held = GreenhouseSupply.plan("Carrot", INGREDIENT, 10, 2, Map.of("CARROT_ITEM", 8));
        assertEquals("Nothing to fetch: your inventory already holds enough Carrot (10 needed, 2 placed correctly, 8 in inventory)",
            held.message());

        Plan unused = GreenhouseSupply.plan("Carrot", INGREDIENT, 0, 0, Map.of());
        assertEquals("No Carrot needed in this layout", unused.message());
    }

    @Test
    public void bonusCellsAreIngredientsToo() {
        assertEquals(Action.PULL, GreenhouseSupply.plan("Potato", GreenhouseGrid.BONUS, 4, 0, Map.of()).action());
    }

    @Test
    public void aMutationSpotPlansNothing() {
        Plan plan = GreenhouseSupply.plan("Ashwreath", GreenhouseGrid.TARGET, 6, 0, Map.of());

        assertEquals(Action.TARGET_SPOT, plan.action());
        assertEquals("This is a mutation spot, nothing to plant", plan.message());
        assertEquals(0, plan.amount());
    }

    @Test
    public void aMutationUsedAsIngredientIsPulledFromTheMutationsSack() {
        Plan plan = GreenhouseSupply.plan("Do-not-eat-shroom", INGREDIENT, 2, 0, Map.of());

        assertEquals("gfs DO_NOT_EAT_SHROOM 2", plan.command());
    }

    @Test
    public void inventoryIdsAreMatchedAfterNormalizing() {
        Plan plan = GreenhouseSupply.plan("Cocoa Beans", INGREDIENT, 6, 0, Map.of("INK_SACK:3", 2));

        assertEquals("gfs INK_SACK:3 4", plan.command());
        assertEquals("INK_SACK:3", GreenhouseSupply.normalizeId("INK_SACK-3"));
        assertEquals("INK_SACK:3", GreenhouseSupply.normalizeId(" INK_SACK:3 "));
        assertEquals("", GreenhouseSupply.normalizeId(null));
    }

    @Test
    public void melonStemsAreCountedInSlices() {
        Plan plan = GreenhouseSupply.plan("Melon Seeds", INGREDIENT, 6, 1, Map.of("MELON", 2));

        assertEquals(3, plan.missing());
        assertEquals("gfs MELON 3", plan.command());
        assertEquals("Pulling 3 Melon Slice from Sacks for 3 Melon Seeds (6 needed, 1 placed correctly, 2 in inventory)",
            plan.message());
    }

    @Test
    public void pumpkinsYieldFourStemsAndPullRoundsUp() {
        // 10 stems needed, one Pumpkin carried (4 stems): 6 missing is two Pumpkins, 8 stems for 6.
        Plan plan = GreenhouseSupply.plan("Pumpkin Seeds", INGREDIENT, 10, 0, Map.of("PUMPKIN", 1));
        assertEquals(4, plan.inInventory());
        assertEquals(6, plan.missing());
        assertEquals("gfs PUMPKIN 2", plan.command());

        assertEquals("gfs PUMPKIN 3", GreenhouseSupply.plan("Pumpkin Seeds", INGREDIENT, 9, 0, Map.of()).command());
        assertEquals("gfs PUMPKIN 2", GreenhouseSupply.plan("Pumpkin Seeds", INGREDIENT, 8, 0, Map.of()).command());
        assertEquals("gfs PUMPKIN 1", GreenhouseSupply.plan("Pumpkin Seeds", INGREDIENT, 1, 0, Map.of()).command());
        assertEquals(Action.SWITCH, GreenhouseSupply.plan("Pumpkin Seeds", INGREDIENT, 8, 0, Map.of("PUMPKIN", 2)).action());
    }

    @Test
    public void whatNoSackHoldsIsNeverPulled() {
        Plan plan = GreenhouseSupply.plan("Jerryflower", INGREDIENT, 3, 0, Map.of("JERRYFLOWER", 1));

        assertEquals(Action.NOT_SACKABLE, plan.action());
        assertEquals(2, plan.missing());
        assertEquals("Fetch Jerryflower manually (not in any Sack): 2 more needed", plan.message());

        Plan fire = GreenhouseSupply.plan("Fire", INGREDIENT, 2, 0, Map.of());
        assertEquals(Action.NOT_SACKABLE, fire.action());
        assertEquals(0, fire.inInventory());
        assertEquals("Fetch Fire manually (a block, no item): 2 more needed", fire.message());
        assertEquals(Action.SWITCH, GreenhouseSupply.plan("Jerryflower", INGREDIENT, 1, 0, Map.of("JERRYFLOWER", 1)).action());
    }

    @Test
    public void anUnknownCropIsReportedNotGuessed() {
        Plan plan = GreenhouseSupply.plan("Banana", INGREDIENT, 3, 0, Map.of());

        assertEquals(Action.UNKNOWN_CROP, plan.action());
        assertEquals("Unknown crop Banana", plan.message());
        assertEquals(Action.UNKNOWN_CROP, GreenhouseSupply.plan(null, INGREDIENT, 3, 0, Map.of()).action());
    }

    @Test
    public void anUnknownNameSaysWhyWhenTheItemDataIsBroken() {
        assertEquals("Unknown crop Banana", GreenhouseSupply.unknown("crop", "Banana", ""));
        assertEquals("Unknown soil Sand (item data: soils: Bad item id sand for Sand)",
            GreenhouseSupply.unknown("soil", "Sand", "soils: Bad item id sand for Sand"));
        assertEquals("", GreenhouseItems.loadError());
    }

    @Test
    public void cropNamesUseTheListsLooseMatching() {
        Plan plan = GreenhouseSupply.plan("wheat_seeds", INGREDIENT, 2, 0, Map.of());

        assertEquals("Wheat Seeds", plan.crop());
        assertEquals("gfs SEEDS 2", plan.command());
    }

    @Test
    public void anAmountBeyondTheInventoryIsCappedAndSaid() {
        Optional<GreenhouseItems.Item> carrot = GreenhouseItems.find("Carrot");

        Plan plan = GreenhouseSupply.plan(carrot, "Carrot", INGREDIENT, 5000, 0, Map.of());

        assertTrue(plan.clamped());
        assertEquals(2304, plan.amount());
        assertEquals("gfs CARROT_ITEM 2304", plan.command());
        assertTrue(plan.message(), plan.message().endsWith(", capped at 2304"));
        assertFalse(GreenhouseSupply.plan(carrot, "Carrot", INGREDIENT, 2304, 0, Map.of()).clamped());
    }

    @Test
    public void requiredCountsIngredientCellsOfTheCropOnly() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("carrot", GreenhouseGrid.INGREDIENT));
        grid.set(5, 5, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.BONUS));
        grid.set(9, 9, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.TARGET));
        grid.set(1, 1, new GreenhouseGrid.Cell("Potato", GreenhouseGrid.INGREDIENT));

        assertEquals(3, GreenhouseSupply.required(grid, "Carrot"));
        assertEquals(3, GreenhouseSupply.required(grid, "carrot"));
        assertEquals(1, GreenhouseSupply.required(grid, "Potato"));
        assertEquals(0, GreenhouseSupply.required(grid, "Cactus"));
        assertEquals(0, GreenhouseSupply.required(new GreenhouseGrid(), "Carrot"));
    }

    @Test
    public void plantedCountComesFromThePluggedInScanner() {
        assertEquals(0, GreenhouseSupply.planted("Carrot"));

        GreenhouseSupply.setPlantedSource(crop -> crop.equals("Carrot") ? 4 : -2);
        assertEquals(4, GreenhouseSupply.planted("Carrot"));
        assertEquals(0, GreenhouseSupply.planted("Potato"));

        GreenhouseSupply.setPlantedSource(null);
        assertEquals(0, GreenhouseSupply.planted("Carrot"));
    }

    @Test
    public void thePlantedLookupUsesThePlannersSpellingOfTheCrop() {
        GreenhouseSupply.setPlantedSource(crop -> crop.equals("Wheat Seeds") ? 7 : 0);

        assertEquals(7, GreenhouseSupply.planted("wheat_seeds"));
        assertEquals(0, GreenhouseSupply.planted("Carrot"));
        assertEquals(0, GreenhouseSupply.planted(null));
    }

    @Test
    public void theScannerSourceCountsNothingBeforeAScan() {
        GreenhouseSupply.useScanner();

        assertEquals(0, GreenhouseSupply.planted("Carrot"));
    }

    @Test
    public void aSoilIsPulledByTheNumberOfGhostsMinusTheBlocksCarried() {
        Plan plan = GreenhouseSupply.planSoil(GreenhouseSoils.Soil.SAND, 5, Map.of("SAND", 2));

        assertEquals(Action.PULL, plan.action());
        assertEquals(3, plan.amount());
        assertEquals("gfs SAND 3", plan.command());
        assertEquals("Pulling 3 Sand from Sacks (5 needed, 2 in inventory)", plan.message());
        assertEquals("gfs MYCEL 1", GreenhouseSupply.planSoil(GreenhouseSoils.Soil.MYCELIUM, 1, Map.of()).command());
        assertEquals("gfs ENDER_STONE 4", GreenhouseSupply.planSoil(GreenhouseSoils.Soil.END_STONE, 4, Map.of()).command());
    }

    @Test
    public void enoughSoilCarriedOnlySwitches() {
        Plan plan = GreenhouseSupply.planSoil(GreenhouseSoils.Soil.SAND, 5, Map.of("SAND", 5));

        assertEquals(Action.SWITCH, plan.action());
        assertEquals("Nothing to fetch: your inventory already holds enough Sand (5 needed, 5 in inventory)",
            plan.message());
    }

    @Test
    public void soulSandAndDirtAreNotInAnySackAndFarmlandHasNoItem() {
        Plan soul = GreenhouseSupply.planSoil(GreenhouseSoils.Soil.SOUL_SAND, 5, Map.of("SOUL_SAND", 1));
        assertEquals(Action.NOT_SACKABLE, soul.action());
        assertEquals(4, soul.missing());
        assertEquals("Fetch Soul Sand manually (not in any Sack): 4 more needed", soul.message());
        assertEquals(Action.NOT_SACKABLE, GreenhouseSupply.planSoil(GreenhouseSoils.Soil.DIRT, 2, Map.of()).action());

        Plan farmland = GreenhouseSupply.planSoil(GreenhouseSoils.Soil.FARMLAND, 9, Map.of());
        assertEquals(Action.NOT_SACKABLE, farmland.action());
        assertEquals("Farmland: till dirt with a hoe", farmland.message());
        assertEquals("Unknown soil None", GreenhouseSupply.planSoil(GreenhouseSoils.Soil.NONE, 1, Map.of()).message());
    }

    @Test
    public void redAndBrownMushroomCellsAndPlantsArePooled() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Red Mushroom", INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Red Mushroom", INGREDIENT));
        grid.set(0, 2, new GreenhouseGrid.Cell("Red Mushroom", INGREDIENT));
        grid.set(1, 0, new GreenhouseGrid.Cell("Brown Mushroom", INGREDIENT));
        grid.set(1, 1, new GreenhouseGrid.Cell("Brown Mushroom", GreenhouseGrid.BONUS));
        grid.set(9, 9, new GreenhouseGrid.Cell("Brown Mushroom", GreenhouseGrid.TARGET));
        GreenhouseSupply.setPlantedSource(crop -> switch (crop) {
            case "Red Mushroom" -> 1;
            case "Brown Mushroom" -> 2;
            default -> 0;
        });

        assertEquals(5, GreenhouseSupply.required(grid, "Red Mushroom"));
        assertEquals(5, GreenhouseSupply.required(grid, "brown mushroom"));
        assertEquals(3, GreenhouseSupply.planted("Red Mushroom"));
        assertEquals(3, GreenhouseSupply.planted("Brown Mushroom"));

        // Pooled need 5 - 3, and only the picked crop's own item counts as carried.
        Plan red = GreenhouseSupply.plan("Red Mushroom", INGREDIENT, 5, GreenhouseSupply.planted("Red Mushroom"),
            Map.of("RED_MUSHROOM", 1, "BROWN_MUSHROOM", 9));
        assertEquals("gfs RED_MUSHROOM 1", red.command());
        Plan enough = GreenhouseSupply.plan("Red Mushroom", INGREDIENT, 5, 3, Map.of("RED_MUSHROOM", 2));
        assertEquals(Action.SWITCH, enough.action());
    }

    @Test
    public void otherCropsAreNotPooled() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Carrot", INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Potato", INGREDIENT));
        GreenhouseSupply.setPlantedSource(crop -> crop.equals("Carrot") ? 4 : 1);

        assertEquals(1, GreenhouseSupply.required(grid, "Carrot"));
        assertEquals(4, GreenhouseSupply.planted("Carrot"));
        assertEquals(1, GreenhouseSupply.planted("Potato"));
    }

    @Test
    public void chatColourCodesAreStrippedBeforeMatching() {
        assertEquals("Moved 5 Carrot", GreenhouseSupply.stripColors("§aMoved §e5 §aCarrot"));
        assertEquals("", GreenhouseSupply.stripColors(null));
    }

    @Test
    public void movedLineGivesTheAmountActuallyMoved() {
        var moved = GreenhouseSupply.parseMoved("§aMoved §e5 §aCarrot§a from your Sacks to your inventory.")
            .orElseThrow();
        assertEquals(5, moved.amount());
        assertEquals("Carrot", moved.item());

        var big = GreenhouseSupply.parseMoved("Moved 1,152 Wheat Seeds from your Sacks to your inventory.").orElseThrow();
        assertEquals(1152, big.amount());
        assertEquals("Wheat Seeds", big.item());

        assertTrue(GreenhouseSupply.parseMoved("Moved 5 Carrot to your Sacks").isEmpty());
        assertTrue(GreenhouseSupply.parseMoved("Moved 99999999999 Carrot from your Sacks to your inventory.").isEmpty());
        assertTrue(GreenhouseSupply.parseMoved(null).isEmpty());
    }

    @Test
    public void noItemLineNamesTheMissingItem() {
        assertEquals("Carrot",
            GreenhouseSupply.parseNoItem("§cYou have no §aCarrot§c in your Sacks!").orElseThrow());
        assertEquals("Wheat Seeds", GreenhouseSupply.parseNoItem("You have no Wheat Seeds in your Sacks!").orElseThrow());
        assertTrue(GreenhouseSupply.parseNoItem("You have no room in your inventory").isEmpty());
        assertTrue(GreenhouseSupply.parseNoItem("").isEmpty());
    }
}
