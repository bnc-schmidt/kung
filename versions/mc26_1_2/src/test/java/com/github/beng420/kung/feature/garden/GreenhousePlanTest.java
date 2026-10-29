package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class GreenhousePlanTest {
    private static void target(GreenhouseGrid grid, String name, int row, int column, int size) {
        for (int r = row; r < row + size; r++) {
            for (int c = column; c < column + size; c++) {
                grid.set(r, c, new GreenhouseGrid.Cell(name, GreenhouseGrid.TARGET));
            }
        }
    }

    private static void crop(GreenhouseGrid grid, String name, int row, int column) {
        grid.set(row, column, new GreenhouseGrid.Cell(name, GreenhouseGrid.INGREDIENT));
    }

    @Test
    public void aMutationPlantedAsIngredientIsOnePlantPerFootprint() {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < 3; column++) crop(grid, "Snoozling", row, column);
        }
        for (int row = 0; row < 2; row++) {
            for (int column = 5; column < 7; column++) crop(grid, "Noctilume", row, column);
        }
        for (int column = 0; column < 6; column++) crop(grid, "Thunderling", 9, column);

        Map<String, Integer> crops = GreenhousePlan.of(grid).crops();

        assertEquals("two 3x3 Snoozlings", 2, (int) crops.get("Snoozling"));
        assertEquals("one 2x2 Noctilume", 1, (int) crops.get("Noctilume"));
        assertEquals("Thunderlings are single cells", 6, (int) crops.get("Thunderling"));
    }

    @Test
    public void ingredientPlantsAreFootprintsTheGridDrawsAsOneIcon() {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 1; row < 4; row++) {
            for (int column = 4; column < 7; column++) crop(grid, "Snoozling", row, column);
        }
        for (int row = 5; row < 7; row++) {
            for (int column = 0; column < 2; column++) crop(grid, "Noctilume", row, column);
        }
        // Eight of nine cells, a single cell and a target are no plant.
        for (int row = 6; row < 9; row++) {
            for (int column = 6; column < 9; column++) {
                if (row != 8 || column != 8) crop(grid, "Snoozling", row, column);
            }
        }
        crop(grid, "Thunderling", 9, 0);
        target(grid, "PlantBoy Advance", 0, 0, 2);

        GreenhousePlan plan = GreenhousePlan.of(grid);

        assertEquals(List.of(new GreenhousePlan.Block("Snoozling", 1, 4, 3, 3),
            new GreenhousePlan.Block("Noctilume", 5, 0, 2, 2)), plan.plants());
        assertEquals(0, plan.plantAt(1, 4));
        assertEquals(0, plan.plantAt(3, 6));
        assertEquals(1, plan.plantAt(6, 1));
        assertEquals("a broken square stays single cells", -1, plan.plantAt(6, 6));
        assertEquals(-1, plan.plantAt(9, 0));
        assertEquals("a target is a block, not a plant", -1, plan.plantAt(0, 0));
    }

    @Test
    public void emptyGridHasNothingToShow() {
        GreenhousePlan plan = GreenhousePlan.of(new GreenhouseGrid());

        assertTrue(plan.blocks().isEmpty());
        assertTrue(plan.crops().isEmpty());
        assertTrue(plan.soils().isEmpty());
        assertEquals(-1, plan.blockAt(4, 4));
    }

    @Test
    public void footprintsFollowTheRecipeSize() {
        GreenhouseGrid grid = new GreenhouseGrid();
        target(grid, "Cindershade", 0, 0, 1);
        target(grid, "Noctilume", 2, 3, 2);
        target(grid, "Snoozling", 6, 6, 3);

        GreenhousePlan plan = GreenhousePlan.of(grid);

        assertEquals(List.of(new GreenhousePlan.Block("Cindershade", 0, 0, 1, 1),
            new GreenhousePlan.Block("Noctilume", 2, 3, 2, 2),
            new GreenhousePlan.Block("Snoozling", 6, 6, 3, 3)), plan.blocks());
        assertEquals(1, plan.blockAt(3, 4));
        assertEquals(2, plan.blockAt(8, 8));
        assertEquals(-1, plan.blockAt(5, 5));
    }

    @Test
    public void touchingFootprintsOfOneMutationStaySeparate() {
        GreenhouseGrid grid = new GreenhouseGrid();
        target(grid, "Noctilume", 0, 0, 2);
        target(grid, "Noctilume", 0, 2, 2);
        target(grid, "Noctilume", 2, 1, 2);

        GreenhousePlan plan = GreenhousePlan.of(grid);

        assertEquals(List.of(new GreenhousePlan.Block("Noctilume", 0, 0, 2, 2),
            new GreenhousePlan.Block("Noctilume", 0, 2, 2, 2),
            new GreenhousePlan.Block("Noctilume", 2, 1, 2, 2)), plan.blocks());
    }

    @Test
    public void aBrokenFootprintFallsBackToSingleCells() {
        GreenhouseGrid grid = new GreenhouseGrid();
        target(grid, "Noctilume", 0, 0, 2);
        grid.set(1, 1, null);

        GreenhousePlan plan = GreenhousePlan.of(grid);

        assertEquals(3, plan.blocks().size());
        assertTrue(plan.blocks().stream().allMatch(block -> block.width() == 1 && block.height() == 1));
    }

    @Test
    public void footprintsSurviveRotationAndMirroring() {
        GreenhouseGrid grid = new GreenhouseGrid();
        target(grid, "Noctilume", 1, 1, 2);
        target(grid, "Cindershade", 7, 2, 1);

        for (GreenhouseGrid shown : List.of(grid.rotatedLeft(), grid.rotatedRight(), grid.mirrored())) {
            GreenhousePlan plan = GreenhousePlan.of(shown);
            assertEquals(2, plan.blocks().size());
            assertEquals(2, plan.blocks().stream().filter(b -> b.mutation().equals("Noctilume")).findFirst()
                .orElseThrow().width());
        }
    }

    @Test
    public void shoppingListCountsPlantedCropsMostFirst() {
        GreenhouseGrid grid = new GreenhouseGrid();
        target(grid, "Cindershade", 5, 5, 1);
        crop(grid, "Ashwreath", 0, 0);
        crop(grid, "Witherbloom", 0, 1);
        crop(grid, "Ashwreath", 0, 2);
        crop(grid, "Fire", 1, 0);
        crop(grid, "Ashwreath", 1, 1);
        grid.set(9, 9, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.BONUS));

        Map<String, Integer> crops = GreenhousePlan.of(grid).crops();

        assertEquals(List.of("Ashwreath", "Carrot", "Fire", "Witherbloom"), List.copyOf(crops.keySet()));
        assertEquals(3, (int) crops.get("Ashwreath"));
        assertEquals(1, (int) crops.get("Carrot"));
    }

    @Test
    public void soilsListEveryGrownMutationOnce() {
        GreenhouseGrid grid = new GreenhouseGrid();
        target(grid, "Cindershade", 0, 0, 1);
        target(grid, "Witherbloom", 0, 2, 1);
        target(grid, "Noctilume", 4, 4, 2);

        assertEquals(List.of("Soul Sand", "Farmland"), GreenhousePlan.of(grid).soils());
    }
}
