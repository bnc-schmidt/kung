package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.CellBox;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** What the supply counts as "placed correctly": the hologram's cells the last scan found right. */
public final class GreenhouseSupplyScanTest {
    private static final int INGREDIENT = GreenhouseGrid.INGREDIENT;

    @After
    public void reset() {
        GreenhouseSupply.setPlantedSource(null);
        GreenhouseHologram.hide();
        GreenhouseScanner.reset();
    }

    private static CellBox cell(String crop, int flag, boolean visible) {
        return new CellBox(0, 0, crop, flag, 0, 0, 0, 1, 1, 1).withVisible(visible);
    }

    private static void scan(GreenhouseGrid grid, Map<String, Integer> stands, State... states) {
        GreenhouseHologram.show(grid, true);
        assertEquals(states.length, GreenhouseHologram.placements().size());
        GreenhouseScanner.setSnapshotForTest(new GreenhouseScanLogic.Snapshot(GreenhouseHologram.version(), List.of(),
            List.of(states), List.of(), stands, 0, 0, false));
    }

    private static GreenhouseGrid cells(String... crops) {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int column = 0; column < crops.length; column++) {
            grid.set(0, column, new GreenhouseGrid.Cell(crops[column], INGREDIENT));
        }
        return grid;
    }

    @Test
    public void onlyHiddenIngredientCellsOfTheCropCount() {
        List<CellBox> boxes = List.of(cell("Carrot", INGREDIENT, false), cell("Carrot", GreenhouseGrid.BONUS, false),
            cell("Carrot", INGREDIENT, true), cell("Carrot", GreenhouseGrid.TARGET, false),
            cell("Potato", INGREDIENT, false));

        assertEquals(2, GreenhouseHologram.correctlyPlaced("Carrot", boxes));
        assertEquals(2, GreenhouseHologram.correctlyPlaced("carrot", boxes));
        assertEquals(1, GreenhouseHologram.correctlyPlaced("Potato", boxes));
        assertEquals(0, GreenhouseHologram.correctlyPlaced("Cactus", boxes));
        assertEquals(0, GreenhouseHologram.correctlyPlaced(null, boxes));
    }

    @Test
    public void theLiveCountFollowsTheLastScanOfTheShownLayout() {
        scan(cells("Carrot", "Carrot", "Carrot", "Potato"), Map.of("Carrot", 40), State.CORRECT, State.WRONG,
            State.CORRECT, State.CORRECT);

        assertEquals(2, GreenhouseHologram.correctlyPlaced("Carrot"));
        assertEquals(1, GreenhouseHologram.correctlyPlaced("Potato"));
        // Stands anywhere in the grid area are not what the supply subtracts.
        assertEquals(40, GreenhouseHologram.plantedCount("Carrot"));

        GreenhouseSupply.useScanner();
        assertEquals(2, GreenhouseSupply.planted("Carrot"));
        assertEquals(2, GreenhouseSupply.planted("carrot"));
    }

    @Test
    public void emptyWrongAndUnverifiedCellsStillNeedTheCrop() {
        scan(cells("Carrot", "Carrot", "Carrot"), Map.of(), State.EMPTY, State.WRONG, State.UNVERIFIED);

        assertEquals(0, GreenhouseHologram.correctlyPlaced("Carrot"));
    }

    @Test
    public void noScanOrAScanOfAnotherLayoutCountsNothing() {
        GreenhouseHologram.show(cells("Carrot", "Carrot"), true);
        GreenhouseScanner.setSnapshotForTest(null);
        assertEquals(0, GreenhouseHologram.correctlyPlaced("Carrot"));

        GreenhouseScanner.setSnapshotForTest(new GreenhouseScanLogic.Snapshot(GreenhouseHologram.version() - 1, List.of(),
            List.of(State.CORRECT, State.CORRECT), List.of(), Map.of(), 0, 0, false));
        assertEquals(0, GreenhouseHologram.correctlyPlaced("Carrot"));
    }

    @Test
    public void leavingOrJoiningAServerDropsTheLastScan() {
        scan(cells("Carrot", "Carrot"), Map.of("Carrot", 2), State.CORRECT, State.CORRECT);
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 1, GreenhouseSoils.Soil.SAND)), 73);
        assertEquals(2, GreenhouseHologram.correctlyPlaced("Carrot"));
        assertEquals(1, GreenhouseHologram.soilGhosts().size());

        GreenhouseHologram.onConnectionChange();

        assertEquals(0, GreenhouseHologram.correctlyPlaced("Carrot"));
        assertEquals(0, GreenhouseHologram.plantedCount("Carrot"));
        assertEquals(0, GreenhouseHologram.soilGhosts().size());
        assertEquals(-1, GreenhouseScanner.soilLayer());
    }

    @Test
    public void nothingIsPlacedWhileNoHologramIsShown() {
        scan(cells("Carrot"), Map.of(), State.CORRECT);
        GreenhouseHologram.hide();

        assertEquals(0, GreenhouseHologram.correctlyPlaced("Carrot"));
    }

    @Test
    public void soilGhostsComeFromTheLatestScanOnTheSoilLayer() {
        GreenhouseHologram.show(cells("Carrot"), true);
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(2, 3, GreenhouseSoils.Soil.SAND),
            new GreenhouseSoilCheck.Ghost(4, 5, GreenhouseSoils.Soil.SOUL_SAND)), 73);

        List<GreenhouseHologram.SoilGhost> ghosts = GreenhouseHologram.soilGhosts();

        assertEquals(2, ghosts.size());
        assertEquals(GreenhouseSoils.Soil.SAND, ghosts.get(0).soil());
        assertEquals(new net.minecraft.world.phys.AABB(102, 73, 203, 103, 74, 204),
            ghosts.get(0).bounds(new net.minecraft.core.BlockPos(100, 0, 200)));
    }

    @Test
    public void noSoilGhostsWithoutALayerOrAShownHologram() {
        GreenhouseHologram.show(cells("Carrot"), true);
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(2, 3, GreenhouseSoils.Soil.SAND)), -1);
        assertEquals(0, GreenhouseHologram.soilGhosts().size());

        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(2, 3, GreenhouseSoils.Soil.SAND)), 73);
        GreenhouseHologram.hide();
        assertEquals(0, GreenhouseHologram.soilGhosts().size());
    }

    @Test
    public void mushroomsArePooledByTheSupply() {
        scan(cells("Red Mushroom", "Red Mushroom", "Brown Mushroom", "Brown Mushroom"), Map.of(), State.CORRECT,
            State.EMPTY, State.CORRECT, State.EMPTY);
        GreenhouseSupply.useScanner();

        assertEquals(1, GreenhouseHologram.correctlyPlaced("Red Mushroom"));
        assertEquals(2, GreenhouseSupply.planted("Red Mushroom"));
        assertEquals(2, GreenhouseSupply.planted("Brown Mushroom"));
        assertEquals(4, GreenhouseSupply.required(GreenhouseHologram.grid().orElseThrow(), "Red Mushroom"));
    }
}
