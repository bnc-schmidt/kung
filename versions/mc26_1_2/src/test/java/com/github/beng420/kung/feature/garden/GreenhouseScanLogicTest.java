package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Footprint;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Found;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Snapshot;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class GreenhouseScanLogicTest {
    private static final double[] OFFSETS = {0.0, 0.01, 0.1, 0.24, 0.26, 0.49};

    /** A 1x1 crop cell at raw offsets (dx, dz). */
    private static Footprint cell(String crop, int dx, int dz) {
        return new Footprint(crop, false, dx, dz, dx + 1, dz + 1);
    }

    private static Footprint spot(String mutation, int dx, int dz, int size) {
        return new Footprint(mutation, true, dx, dz, dx + size, dz + size);
    }

    private static Found at(String crop, double x, double z) {
        return new Found(crop, x, z);
    }

    private static Snapshot scan(List<Footprint> expected, List<Found> found, Snapshot previous) {
        return GreenhouseScanLogic.evaluate(expected, found, previous, 1);
    }

    private static State stateOf(List<Footprint> expected, List<Found> found, int index) {
        return scan(expected, found, null).raw().get(index).state();
    }

    @Test
    public void footprintsComeFromThePlacements() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(2, 3, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        for (int row = 5; row <= 6; row++) {
            for (int column = 0; column <= 1; column++) {
                grid.set(row, column, new GreenhouseGrid.Cell("Noctilume", GreenhouseGrid.TARGET));
            }
        }

        List<Footprint> footprints = GreenhouseScanLogic.footprints(
            GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY).placements());

        // Row 2, column 3 is dx 7, dz 3; the 2x2 at rows 5..6, columns 0..1 covers x 3..5 and z 0..2.
        assertEquals(List.of(cell("Carrot", 7, 3), spot("Noctilume", 3, 0, 2)), footprints);
    }

    @Test
    public void aStandAHairOffTheCentreNeverLeaksIntoTheNextCell() {
        // Carrots expected at dx 4 and 5; only dx 4 is planted, its stand a little east of the centre.
        List<Footprint> expected = List.of(cell("Carrot", 4, 0), cell("Potato", 5, 0));
        for (double offset : OFFSETS) {
            List<Found> found = List.of(at("Carrot", 4.5 + offset, 0.5));
            assertEquals("offset " + offset, State.CORRECT, stateOf(expected, found, 0));
            assertEquals("offset " + offset + " must not reach the next cell", State.EMPTY, stateOf(expected, found, 1));
            assertEquals(List.of(0), GreenhouseScanLogic.owners(expected, found.getFirst()));
        }
        // And the other way round: a stand a little west of cell 5's centre stays out of cell 4.
        for (double offset : OFFSETS) {
            List<Found> found = List.of(at("Potato", 5.5 - offset, 0.5));
            assertEquals("offset " + offset, State.CORRECT, stateOf(expected, found, 1));
            assertEquals("offset " + offset, State.EMPTY, stateOf(expected, found, 0));
        }
        // Across the other axis too.
        List<Footprint> column = List.of(cell("Carrot", 0, 4), cell("Potato", 0, 5));
        for (double offset : OFFSETS) {
            assertEquals(State.EMPTY, stateOf(column, List.of(at("Carrot", 0.5, 4.5 + offset)), 1));
        }
    }

    @Test
    public void anEmptyCellNextToAPlantedOneIsNeverWrongBecauseOfIt() {
        List<Footprint> expected = List.of(cell("Carrot", 4, 0), cell("Potato", 5, 0));
        for (double offset : OFFSETS) {
            Snapshot snapshot = scan(expected, List.of(at("Carrot", 4.5 + offset, 0.5)), null);
            assertEquals(State.EMPTY, snapshot.raw().get(1).state());
            assertEquals(State.EMPTY, snapshot.shown().get(1));
        }
    }

    @Test
    public void aStandOnASharedEdgeBelongsToBothSides() {
        List<Footprint> expected = List.of(cell("Carrot", 4, 0), cell("Carrot", 5, 0));

        assertEquals(List.of(0, 1), GreenhouseScanLogic.owners(expected, at("Carrot", 5.0, 0.5)));
        assertEquals(List.of(0, 1), GreenhouseScanLogic.owners(expected, at("Carrot", 5.0004, 0.5)));
        assertEquals(List.of(1), GreenhouseScanLogic.owners(expected, at("Carrot", 5.01, 0.5)));
    }

    @Test
    public void outsideEveryFootprintAStandStillCountsWithinTheSlack() {
        List<Footprint> expected = List.of(cell("Carrot", 4, 0));

        assertEquals(List.of(0), GreenhouseScanLogic.owners(expected, at("Carrot", 5.24, 0.5)));
        assertEquals(State.CORRECT, stateOf(expected, List.of(at("Carrot", 5.24, 0.5)), 0));
        assertEquals(List.of(), GreenhouseScanLogic.owners(expected, at("Carrot", 5.26, 0.5)));
        assertEquals(State.EMPTY, stateOf(expected, List.of(at("Carrot", 5.26, 0.5)), 0));
        assertEquals(List.of(0), GreenhouseScanLogic.owners(expected, at("Carrot", 3.76, 0.5)));
        assertEquals(List.of(), GreenhouseScanLogic.owners(expected, at("Carrot", 3.74, 0.5)));
        assertEquals(List.of(0), GreenhouseScanLogic.owners(expected, at("Carrot", 4.5, 1.24)));
        assertEquals(List.of(), GreenhouseScanLogic.owners(expected, at("Carrot", 4.5, 1.26)));
        // Diagonally the distance counts, not each axis on its own.
        assertEquals(List.of(), GreenhouseScanLogic.owners(expected, at("Carrot", 5.2, 1.2)));
        assertEquals(List.of(0), GreenhouseScanLogic.owners(expected, at("Carrot", 5.15, 1.15)));
    }

    @Test
    public void theSlackDoesNotApplyToAStandThatStandsInAnotherFootprint() {
        List<Footprint> expected = List.of(cell("Carrot", 4, 0), cell("Potato", 5, 0));

        // 0.01 inside cell 5 is far more than the slack from cell 4's edge, and 4.99 is inside cell 4.
        assertEquals(List.of(1), GreenhouseScanLogic.owners(expected, at("Carrot", 5.01, 0.5)));
        assertEquals(List.of(0), GreenhouseScanLogic.owners(expected, at("Carrot", 4.99, 0.5)));
        // 0.2 inside cell 5 would be within the slack of cell 4's rectangle if the slack always applied.
        assertEquals(List.of(1), GreenhouseScanLogic.owners(expected, at("Carrot", 5.2, 0.5)));
    }

    @Test
    public void theRightCropIsCorrectAnotherIsWrongNothingIsEmpty() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0), cell("Potato", 1, 0), cell("Cactus", 2, 0));
        List<Found> found = List.of(at("Carrot", 0.5, 0.5), at("Wheat Seeds", 1.5, 0.5));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals(State.CORRECT, snapshot.raw().get(0).state());
        assertEquals(State.WRONG, snapshot.raw().get(1).state());
        assertEquals("Wheat Seeds", snapshot.raw().get(1).signature());
        assertEquals(State.EMPTY, snapshot.raw().get(2).state());
    }

    @Test
    public void theMushroomsAreInterchangeable() {
        List<Footprint> expected = List.of(cell("Red Mushroom", 0, 0), cell("Brown Mushroom", 1, 0));
        List<Found> found = List.of(at("Brown Mushroom", 0.5, 0.5), at("Red Mushroom", 1.5, 0.5));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals(State.CORRECT, snapshot.raw().get(0).state());
        assertEquals(State.CORRECT, snapshot.raw().get(1).state());
        // Counted by their own names.
        assertEquals(1, (int) snapshot.planted().get("Brown Mushroom"));
        assertEquals(1, (int) snapshot.planted().get("Red Mushroom"));
        assertTrue(GreenhouseScanLogic.sameCrop("Red Mushroom", "Brown Mushroom"));
        assertFalse(GreenhouseScanLogic.sameCrop("Red Mushroom", "Cactus"));
    }

    @Test
    public void aBigFootprintIsJudgedAsAWhole() {
        List<Footprint> expected = List.of(spot("Snoozling", 3, 3, 3), spot("Noctilume", 0, 0, 2));
        // Snoozling's stand sits in the middle of its 3x3; a Carrot stands in the Noctilume spot.
        List<Found> found = List.of(at("Snoozling", 4.5, 4.5), at("Carrot", 0.5, 1.5));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals(State.CORRECT, snapshot.raw().get(0).state());
        assertEquals(State.WRONG, snapshot.raw().get(1).state());
    }

    @Test
    public void aTargetSpotIsFineEmptyOrWithItsMutationAndWrongWithAnotherCrop() {
        List<Footprint> expected = List.of(spot("Cindershade", 0, 0, 1));

        assertEquals(State.EMPTY, stateOf(expected, List.of(), 0));
        assertEquals(State.CORRECT, stateOf(expected, List.of(at("Cindershade", 0.5, 0.5)), 0));
        assertEquals(State.WRONG, stateOf(expected, List.of(at("Wheat Seeds", 0.5, 0.5)), 0));
        assertEquals(State.WRONG, stateOf(expected, List.of(at("Lonelily", 0.5, 0.5)), 0));
    }

    @Test
    public void ringCropsNextToASpotAreNotPartOfIt() {
        List<Footprint> expected = List.of(spot("Noctilume", 4, 4, 2));
        // The ring crops stand on the cells around the 2x2: their centres are half a block outside it.
        List<Found> ring = List.of(at("Wheat Seeds", 3.5, 4.5), at("Wheat Seeds", 6.5, 5.5), at("Carrot", 4.5, 3.5),
            at("Carrot", 5.5, 6.5));

        assertEquals(State.EMPTY, stateOf(expected, ring, 0));
    }

    @Test
    public void ringCropsWithTheirOwnCellsBelongToThoseCells() {
        List<Footprint> expected = List.of(spot("Noctilume", 4, 4, 2), cell("Wheat Seeds", 3, 4), cell("Carrot", 6, 5));
        List<Found> ring = List.of(at("Wheat Seeds", 3.55, 4.5), at("Carrot", 6.45, 5.5));

        Snapshot snapshot = scan(expected, ring, null);

        assertEquals(State.EMPTY, snapshot.raw().get(0).state());
        assertEquals(State.CORRECT, snapshot.raw().get(1).state());
        assertEquals(State.CORRECT, snapshot.raw().get(2).state());
    }

    @Test
    public void aWrongCropNeedsTwoIdenticalScansBeforeItIsShown() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0));
        List<Found> wrong = List.of(at("Potato", 0.5, 0.5));

        Snapshot first = scan(expected, wrong, null);
        assertEquals(State.WRONG, first.raw().getFirst().state());
        assertEquals("not yet shown", State.EMPTY, first.shown().getFirst());

        Snapshot second = scan(expected, wrong, first);
        assertEquals(State.WRONG, second.shown().getFirst());

        // Gone: shown as empty at once.
        assertEquals(State.EMPTY, scan(expected, List.of(), second).shown().getFirst());
    }

    @Test
    public void aChangingWrongThingRestartsTheCountButAShownOneStays() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0));
        Snapshot first = scan(expected, List.of(at("Potato", 0.5, 0.5)), null);
        Snapshot second = scan(expected, List.of(at("Wheat Seeds", 0.5, 0.5)), first);
        assertEquals("a different wrong crop is a new observation", State.EMPTY, second.shown().getFirst());
        Snapshot third = scan(expected, List.of(at("Wheat Seeds", 0.5, 0.5)), second);
        assertEquals(State.WRONG, third.shown().getFirst());
        // Once shown, swapping one wrong crop for another keeps it red.
        Snapshot fourth = scan(expected, List.of(at("Potato", 0.5, 0.5)), third);
        assertEquals(State.WRONG, fourth.shown().getFirst());
    }

    @Test
    public void aCorrectCropStaysShownCorrectThroughOneWrongScan() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0));
        Snapshot correct = scan(expected, List.of(at("Carrot", 0.5, 0.5)), null);
        assertEquals(State.CORRECT, correct.shown().getFirst());

        Snapshot blip = scan(expected, List.of(at("Potato", 0.5, 0.5)), correct);
        assertEquals(State.CORRECT, blip.shown().getFirst());
        assertEquals(State.WRONG, scan(expected, List.of(at("Potato", 0.5, 0.5)), blip).shown().getFirst());
    }

    @Test
    public void debounceNeedsTheSameLayout() {
        List<Footprint> before = List.of(cell("Carrot", 0, 0));
        List<Footprint> after = List.of(cell("Carrot", 1, 0));
        Snapshot first = scan(before, List.of(at("Potato", 0.5, 0.5)), null);

        Snapshot rotated = scan(after, List.of(at("Potato", 1.5, 0.5)), first);

        assertEquals("a changed layout starts counting again", State.EMPTY, rotated.shown().getFirst());
    }

    @Test
    public void somethingUnrecognisedIsNeverRedAlone() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0), spot("Cindershade", 1, 0, 1));
        List<Found> found = List.of(at(null, 0.5, 0.5), at(null, 1.5, 0.5));

        Snapshot first = scan(expected, found, null);

        assertEquals(State.UNVERIFIED, first.raw().get(0).state());
        assertEquals(State.UNVERIFIED, first.raw().get(1).state());
        assertEquals("?", first.raw().get(0).signature());
        // It needs two identical scans like any complaint.
        assertEquals(State.EMPTY, first.shown().get(0));
        Snapshot second = scan(expected, found, first);
        assertEquals(State.UNVERIFIED, second.shown().get(0));
        assertEquals(State.UNVERIFIED, second.shown().get(1));
        assertFalse(second.unrecognisedShare());
    }

    @Test
    public void onlyARecognisedDifferentCropIsWrong() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0), cell("Potato", 1, 0), cell("Cactus", 2, 0));
        List<Found> found = List.of(at(null, 0.5, 0.5), at("Wheat Seeds", 1.5, 0.5), at(null, 1.6, 0.4),
            at("Cactus", 2.5, 0.5), at(null, 2.6, 0.6));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals("an unknown thing alone", State.UNVERIFIED, snapshot.raw().get(0).state());
        assertEquals("a recognised wrong crop makes it wrong whatever else is there", State.WRONG,
            snapshot.raw().get(1).state());
        assertEquals("the right crop wins over an unknown neighbour", State.CORRECT, snapshot.raw().get(2).state());
    }

    @Test
    public void manyUnrecognisedHeadsOnlyRaiseTheWarningFlag() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0), cell("Potato", 1, 0));
        List<Found> found = new ArrayList<>(List.of(at(null, 0.5, 0.5), at("Wheat Seeds", 1.5, 0.5)));
        found.add(at(null, 5.5, 5.5));
        found.add(at(null, 6.5, 5.5));
        found.add(at("Cactus", 7.5, 5.5));

        Snapshot snapshot = scan(expected, found, null);

        assertTrue(snapshot.unrecognisedShare());
        assertEquals(3, snapshot.unknown());
        assertEquals(5, snapshot.total());
        assertEquals(State.UNVERIFIED, snapshot.raw().get(0).state());
        assertEquals(State.WRONG, snapshot.raw().get(1).state());
    }

    @Test
    public void theUnrecognisedShareRuleNeedsThreeAndThirtyPercent() {
        assertFalse(GreenhouseScanLogic.unrecognisedShare(2, 2));
        assertTrue(GreenhouseScanLogic.unrecognisedShare(3, 10));
        assertFalse(GreenhouseScanLogic.unrecognisedShare(3, 11));
        assertTrue(GreenhouseScanLogic.unrecognisedShare(6, 20));
        assertFalse(GreenhouseScanLogic.unrecognisedShare(5, 20));
        assertFalse(GreenhouseScanLogic.unrecognisedShare(0, 0));
    }

    @Test
    public void plantedCountsCoverTheWholeGridAreaAndSkipUnknowns() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0));
        List<Found> found = List.of(at("Carrot", 0.5, 0.5), at("Carrot", 8.5, 8.5), at("Cactus", 9.5, 9.5),
            at(null, 4.5, 4.5));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals(2, (int) snapshot.planted().get("Carrot"));
        assertEquals(1, (int) snapshot.planted().get("Cactus"));
        assertEquals(2, snapshot.planted().size());
        assertEquals(1, snapshot.unknown());
        assertEquals(4, snapshot.total());
    }

    @Test
    public void aPlantBoyAtTheCornerOfFourCellsCountsForAllFour() {
        // A 2x2 crop's stand sits where four cells meet.
        List<Footprint> expected = List.of(cell("PlantBoy Advance", 0, 0), cell("PlantBoy Advance", 1, 0),
            cell("PlantBoy Advance", 0, 1), cell("PlantBoy Advance", 1, 1));

        Snapshot snapshot = scan(expected, List.of(at("PlantBoy Advance", 1.0, 1.0)), null);

        for (var verdict : snapshot.raw()) assertEquals(State.CORRECT, verdict.state());
    }

    @Test
    public void aPlantBoyAndItsNeighboursDoNotMixUp() {
        // The 2x2 crop's stand at the corner, plus a Carrot in the cell to its east.
        List<Footprint> expected = List.of(cell("PlantBoy Advance", 0, 0), cell("PlantBoy Advance", 1, 0),
            cell("PlantBoy Advance", 0, 1), cell("PlantBoy Advance", 1, 1), cell("Carrot", 2, 0));
        List<Found> found = List.of(at("PlantBoy Advance", 1.0, 1.0), at("Carrot", 2.5, 0.5));

        Snapshot snapshot = scan(expected, found, null);

        for (var verdict : snapshot.raw()) assertEquals(State.CORRECT, verdict.state());
    }
}
