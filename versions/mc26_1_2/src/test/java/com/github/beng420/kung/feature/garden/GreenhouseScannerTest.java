package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Found;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Snapshot;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The scanner's state holder and its test seam; reading the world needs a client and is not tested. */
public final class GreenhouseScannerTest {
    @Before
    @After
    public void reset() {
        GreenhouseHologram.hide();
        GreenhouseScanner.reset();
    }

    private static GreenhouseGrid carrotAt(int row, int column) {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(row, column, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        return grid;
    }

    private static Snapshot snapshotOf(List<Found> found, int layoutVersion) {
        return GreenhouseScanLogic.evaluate(GreenhouseScanLogic.footprints(GreenhouseHologram.placements()), found,
            null, layoutVersion);
    }

    @Test
    public void theTestSeamInstallsPlantedCountsAndResetForgetsThem() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        assertEquals(0, GreenhouseScanner.plantedCount("Carrot"));
        int before = GreenhouseScanner.version();

        GreenhouseScanner.setSnapshotForTest(snapshotOf(
            List.of(new Found("Carrot", 3.5, 2.5), new Found("Carrot", 1.5, 1.5), new Found("Cactus", 9.5, 9.5)),
            GreenhouseHologram.version()));

        assertEquals(2, GreenhouseScanner.plantedCount("Carrot"));
        assertEquals(2, GreenhouseHologram.plantedCount("Carrot"));
        assertEquals(1, GreenhouseHologram.plantedCount("Cactus"));
        assertEquals(0, GreenhouseHologram.plantedCount("Potato"));
        assertTrue("the renderer rebuilds when the snapshot changes", GreenhouseScanner.version() != before);

        GreenhouseScanner.reset();
        assertEquals(0, GreenhouseScanner.plantedCount("Carrot"));
        assertNull(GreenhouseScanner.states(GreenhouseHologram.version()));
    }

    @Test
    public void statesAreOnlyHandedOutForTheLayoutTheyWereScannedFor() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        int version = GreenhouseHologram.version();
        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(new Found("Carrot", 3.5, 2.5)), version));

        assertEquals(List.of(State.CORRECT), GreenhouseScanner.states(version));
        assertNull("another layout: no states, everything shows its model", GreenhouseScanner.states(version + 1));
    }

    @Test
    public void cellBoxesHideTheCellsWhoseRightPlantStands() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        int version = GreenhouseHologram.version();
        assertTrue("before any scan", GreenhouseHologram.cellBoxes().getFirst().visible());

        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(new Found("Carrot", 3.5, 2.5)), version));
        assertFalse("planted right: the pick hook can look through it",
            GreenhouseHologram.cellBoxes().getFirst().visible());

        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(), version));
        assertTrue("empty again: the model is back", GreenhouseHologram.cellBoxes().getFirst().visible());

        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(new Found("Potato", 3.5, 2.5)), version));
        // A first wrong sighting is not shown yet; its cell still shows the model.
        assertTrue(GreenhouseHologram.cellBoxes().getFirst().visible());
    }

    @Test
    public void extrasAreHandedOutOnlyForTheLayoutTheyWereScannedFor() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        int version = GreenhouseHologram.version();
        var footprints = GreenhouseScanLogic.footprints(GreenhouseHologram.placements());
        List<Found> seen = List.of(new Found("Cactus", 5.5, 5.5));
        GreenhouseScanner.setSnapshotForTest(GreenhouseScanLogic.evaluate(footprints, seen,
            GreenhouseScanLogic.evaluate(footprints, seen, null, version), version));

        assertEquals(List.of(new GreenhouseScanLogic.Extra(5, 5, "Cactus")), GreenhouseScanner.extras(version));
        assertTrue(GreenhouseScanner.extras(version + 1).isEmpty());
        GreenhouseScanner.reset();
        assertTrue(GreenhouseScanner.extras(version).isEmpty());
    }

    @Test
    public void completionStartsFalseAndResetClearsIt() {
        assertFalse(GreenhouseScanner.complete());

        GreenhouseScanner.setCompleteForTest(true);
        assertTrue(GreenhouseScanner.complete());

        GreenhouseScanner.reset();
        assertFalse(GreenhouseScanner.complete());
    }

    @Test
    public void switchingTheCornerForgetsTheScanTheSoilAndTheCompletion() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(new Found("Carrot", 3.5, 2.5)),
            GreenhouseHologram.version()));
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 1, GreenhouseSoils.Soil.SAND)), 73);
        GreenhouseScanner.setCompleteForTest(true);
        int before = GreenhouseScanner.version();

        GreenhouseScanner.switchCorner(new BlockPos(10, 70, 20));

        assertFalse("the other greenhouse is not complete yet", GreenhouseScanner.complete());
        assertEquals(0, GreenhouseScanner.plantedCount("Carrot"));
        assertTrue(GreenhouseScanner.ghosts().isEmpty());
        assertEquals(-1, GreenhouseScanner.soilLayer());
        assertTrue("the renderer rebuilds", GreenhouseScanner.version() != before);
    }

    @Test
    public void theSummaryNamesTheNewStates() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(new Found("Carrot", 3.5, 2.5)),
            GreenhouseHologram.version()));
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 1, GreenhouseSoils.Soil.SAND)), 73);

        String summary = GreenhouseScanner.summary();

        for (String part : new String[] {"toPlace=0", "correct=1", "wrong=0", "unverified=0", "extra=0",
            "complete=false", "missing=1", "wrong_block=0", "wrong_state=0"}) {
            assertTrue(part + " in " + summary, summary.contains(part));
        }
    }

    @Test
    public void aSnapshotForAnotherLayoutNeverHidesCells() {
        GreenhouseHologram.show(carrotAt(2, 3), true);
        GreenhouseScanner.setSnapshotForTest(snapshotOf(List.of(new Found("Carrot", 3.5, 2.5)),
            GreenhouseHologram.version() + 5));

        assertTrue(GreenhouseHologram.cellBoxes().getFirst().visible());
    }
}
