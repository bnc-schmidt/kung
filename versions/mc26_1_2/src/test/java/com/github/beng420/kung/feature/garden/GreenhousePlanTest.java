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
