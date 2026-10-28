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

        // Row 2, column 3 is dx 3, dz 2; the 2x2 at rows 5..6, columns 0..1 covers x 0..2 and z 5..7.
        assertEquals(List.of(cell("Carrot", 3, 2), spot("Noctilume", 0, 5, 2)), footprints);
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
    public void aRecognisedCropWhereTheLayoutHasNothingIsAnExtraOnceTwoScansSawIt() {
        List<Footprint> expected = List.of(cell("Carrot", 5, 5));
        List<Found> found = List.of(at("Carrot", 5.5, 5.5), at("Cactus", 5.5, 6.5));

        Snapshot first = scan(expected, found, null);
        assertEquals(List.of(new GreenhouseScanLogic.Extra(5, 6, "Cactus")), first.rawExtras());
        assertTrue("one scan is not enough", first.extras().isEmpty());

        Snapshot second = scan(expected, found, first);
        assertEquals(List.of(new GreenhouseScanLogic.Extra(5, 6, "Cactus")), second.extras());

        // Gone: gone at once.
        assertTrue(scan(expected, List.of(at("Carrot", 5.5, 5.5)), second).extras().isEmpty());
    }

    @Test
    public void anExtraDoesNotTurnItsNeighbourOrTheSupplyNeedWrong() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0), cell("Potato", 1, 0));
        List<Found> found = List.of(at("Cactus", 2.5, 0.5));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals(State.EMPTY, snapshot.raw().get(0).state());
        assertEquals(State.EMPTY, snapshot.raw().get(1).state());
        assertEquals("it is still planted somewhere", 1, (int) snapshot.planted().get("Cactus"));
    }

    @Test
    public void unrecognisedThingsOrOnesInsideTheLayoutAreNeverExtras() {
        List<Footprint> expected = List.of(cell("Carrot", 0, 0), spot("Noctilume", 4, 4, 2));
        List<Found> found = List.of(
            at(null, 7.5, 7.5),                      // unknown thing where the layout has nothing: ignored
            at("Cactus", 0.5, 0.5),                  // wrong crop on a layout cell: a wrong crop, no extra
            at("Wheat Seeds", 5.0, 5.0),             // inside the 2x2 spot
            at("Potato", 6.1, 4.5),                  // 0.1 outside the spot: within the slack, counts for it
            at("Cactus", 12.0, 3.5),                 // outside the 10x10 cells altogether
            at("Cactus", -0.3, 3.5));                // a hair outside the grid, beside no footprint

        Snapshot snapshot = scan(expected, found, null);

        assertTrue(snapshot.rawExtras().toString(), snapshot.rawExtras().isEmpty());
        assertEquals(State.WRONG, snapshot.raw().get(0).state());
        assertEquals(State.WRONG, snapshot.raw().get(1).state());
    }

    @Test
    public void farmlandIsNotAThingThePlantScanSees() {
        // Soil is read separately; a snapshot with only soil-less cells has nothing extra at all.
        Snapshot snapshot = scan(List.of(cell("Carrot", 0, 0)), List.of(), null);

        assertTrue(snapshot.rawExtras().isEmpty());
        assertTrue(snapshot.extras().isEmpty());
    }

    @Test
    public void extrasAreListedOncePerCellAndCropAndKeepTheirOwnNames() {
        List<Footprint> expected = List.of(cell("Carrot", 5, 4));
        List<Found> found = List.of(at("Cactus", 5.4, 5.4), at("Cactus", 5.6, 5.6), at("Sunflower", 5.5, 5.5),
            at("Potato", 6.5, 5.5));

        Snapshot snapshot = scan(expected, found, null);

        assertEquals(List.of(new GreenhouseScanLogic.Extra(5, 5, "Cactus"), new GreenhouseScanLogic.Extra(5, 5, "Sunflower"),
            new GreenhouseScanLogic.Extra(6, 5, "Potato")), snapshot.rawExtras());
    }

    @Test
    public void cropsInLockedCellsAreNotUnplannedButStillCountAsPlanted() {
        List<Footprint> expected = List.of(cell("Carrot", 4, 4));
        List<Found> found = List.of(at("Carrot", 4.5, 4.5), at("Cactus", 5.5, 5.5), at("Potato", 5.5, 4.5));
        boolean[] locked = new boolean[GreenhouseGrid.SIZE * GreenhouseGrid.SIZE];
        locked[5 * GreenhouseGrid.SIZE + 5] = true;

        Snapshot snapshot = GreenhouseScanLogic.evaluate(expected, found, null, 1, locked);

        assertEquals(List.of(new GreenhouseScanLogic.Extra(5, 4, "Potato")), snapshot.rawExtras());
        assertEquals(1, (int) snapshot.planted().get("Cactus"));
        assertEquals("without a mask both are unplanned", 2, scan(expected, found, null).rawExtras().size());
        assertEquals("a null mask is no mask", 2,
            GreenhouseScanLogic.evaluate(expected, found, null, 1, null).rawExtras().size());
    }

    @Test
    public void aLockedCellInsideAFootprintIsJudgedAsUsual() {
        List<Footprint> expected = List.of(cell("Carrot", 5, 5));
        boolean[] locked = new boolean[GreenhouseGrid.SIZE * GreenhouseGrid.SIZE];
        locked[5 * GreenhouseGrid.SIZE + 5] = true;

        Snapshot snapshot = GreenhouseScanLogic.evaluate(expected, List.of(at("Cactus", 5.5, 5.5)), null, 1, locked);

        assertEquals(State.WRONG, snapshot.raw().getFirst().state());
    }

    @Test
    public void aLayoutIsCompleteDespiteCropsGrowingInLockedCells() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(4, 4, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        var placements = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY).placements();
        List<Footprint> expected = GreenhouseScanLogic.footprints(placements);
        List<Found> found = List.of(at("Carrot", 4.5, 4.5), at("Creambloom", 5.5, 5.5));
        boolean[] locked = new boolean[GreenhouseGrid.SIZE * GreenhouseGrid.SIZE];
        locked[5 * GreenhouseGrid.SIZE + 5] = true;

        Snapshot open = scan(expected, found, null);
        Snapshot withLock = GreenhouseScanLogic.evaluate(expected, found, null, 1, locked);

        assertFalse("an unplanned crop keeps it open", GreenhouseCompletion.allIngredientsCorrect(placements, open.raw(),
            open.rawExtras()));
        assertTrue(GreenhouseCompletion.allIngredientsCorrect(placements, withLock.raw(), withLock.rawExtras()));
    }

    @Test
    public void aPartialLayoutCompletesAlthoughTheRestOfThePlotGrowsOtherPlants() {
        // Kuna's dump (kung 0.6.4): a Turtlellini ring in the top right corner, all eight ingredients right, the spot
        // in the middle still empty, and 40 other plants elsewhere that used to keep the hologram on for ever.
        GreenhouseGrid grid = new GreenhouseGrid();
        int[][] choconut = {{0, 7}, {0, 8}, {0, 9}, {1, 9}};
        int[][] soggybud = {{1, 7}, {2, 7}, {2, 8}, {2, 9}};
        List<Found> found = new ArrayList<>();
        for (int[] at : choconut) {
            grid.set(at[0], at[1], new GreenhouseGrid.Cell("Choconut", GreenhouseGrid.INGREDIENT));
            found.add(at("Choconut", at[1] + 0.5, at[0] + 0.5));
        }
        for (int[] at : soggybud) {
            grid.set(at[0], at[1], new GreenhouseGrid.Cell("Soggybud", GreenhouseGrid.INGREDIENT));
            found.add(at("Soggybud", at[1] + 0.5, at[0] + 0.5));
        }
        grid.set(1, 8, new GreenhouseGrid.Cell("Turtlellini", GreenhouseGrid.TARGET));
        var placements = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY).placements();
        List<Footprint> expected = GreenhouseScanLogic.footprints(placements);
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) found.add(at("Duskbloom", dx + 0.5, dz + 0.5));
        }
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 5; dz < 10; dz++) found.add(at("Creambloom", dx + 0.5, dz + 0.5));
        }

        Snapshot snapshot = scan(expected, found, null);

        assertTrue("far from the layout: no pink box", snapshot.rawExtras().isEmpty());
        assertEquals(1, snapshot.raw().stream().filter(verdict -> verdict.state() == State.EMPTY).count());
        assertTrue(GreenhouseCompletion.allIngredientsCorrect(placements, snapshot.raw(), snapshot.rawExtras()));

        // A plant right beside the ring is another matter: the recipe may need that cell empty.
        found.add(at("Duskbloom", 6.5, 1.5));
        Snapshot beside = scan(expected, found, null);
        assertEquals(List.of(new GreenhouseScanLogic.Extra(6, 1, "Duskbloom")), beside.rawExtras());
        assertFalse(GreenhouseCompletion.allIngredientsCorrect(placements, beside.raw(), beside.rawExtras()));
    }

    @Test
    public void aSnapshotBuiltTheOldWayHasNoExtras() {
        Snapshot old = new Snapshot(1, List.of(), List.of(), List.of(), java.util.Map.of(), 0, 0, false);

        assertTrue(old.extras().isEmpty());
        assertTrue(old.rawExtras().isEmpty());
        assertTrue("a first sighting still needs a second",
            scan(List.of(), List.of(at("Cactus", 5.5, 5.5)), old).extras().isEmpty());
    }

    /** A 3x3 Snoozling planted as an ingredient: nine cells at raw (4..6, 0..2). */
    private static List<Footprint> snoozlingCells() {
        List<Footprint> cells = new ArrayList<>();
        for (int dx = 4; dx <= 6; dx++) {
            for (int dz = 0; dz <= 2; dz++) cells.add(cell("Snoozling", dx, dz));
        }
        return cells;
    }

    /** What the dump of a real Snoozling showed: its heads around the centre, wheat blocks on the corner cells. */
    private static List<Found> aRealSnoozling() {
        return List.of(at("Snoozling", 5.5, 2.344), at("Snoozling", 5.031, 1.125), at("Snoozling", 5.5, 0.594),
            at("Snoozling", 5.5, 0.875), at("Snoozling", 5.5, 1.5), at("Snoozling", 5.5, 1.906),
            at("Snoozling", 6.0, 1.125), at("Wheat Seeds", 4.5, 0.5), at("Wheat Seeds", 6.5, 0.5),
            at("Wheat Seeds", 4.5, 2.5), at("Wheat Seeds", 6.5, 2.5));
    }

    @Test
    public void aPlantedSnoozlingStandsRightOnAllNineCellsAlthoughItsBlocksAreOnTheCornersOnly() {
        Snapshot snapshot = scan(snoozlingCells(), aRealSnoozling(), null);

        for (var verdict : snapshot.raw()) assertEquals(State.CORRECT, verdict.state());
        for (State state : snapshot.shown()) assertEquals(State.CORRECT, state);
        assertTrue(snapshot.rawExtras().isEmpty());
    }

    @Test
    public void aSnoozlingSpotWithOnlyTheCornerBlocksIsNotPlantedYet() {
        List<Found> onlyWheat = List.of(at("Wheat Seeds", 4.5, 0.5), at("Wheat Seeds", 6.5, 0.5));

        Snapshot snapshot = scan(snoozlingCells(), onlyWheat, null);

        assertEquals(2, snapshot.raw().stream().filter(verdict -> verdict.state() == State.WRONG).count());
        assertEquals(7, snapshot.raw().stream().filter(verdict -> verdict.state() == State.EMPTY).count());
    }

    @Test
    public void aNoctilumeOfFourCellsIsOnePlantToo() {
        List<Footprint> cells = List.of(cell("Noctilume", 2, 0), cell("Noctilume", 2, 1), cell("Noctilume", 3, 0),
            cell("Noctilume", 3, 1));

        Snapshot snapshot = scan(cells, List.of(at("Noctilume", 2.6, 1.3)), null);

        for (var verdict : snapshot.raw()) assertEquals(State.CORRECT, verdict.state());
    }

    @Test
    public void cellsThatDoNotFillAMutationsBoxStayOnTheirOwn() {
        List<Footprint> eight = new ArrayList<>(snoozlingCells());
        eight.removeLast();
        List<Footprint> mixed = List.of(cell("Snoozling", 0, 0), cell("Carrot", 1, 0), cell("Carrot", 0, 1),
            cell("Cactus", 1, 1));

        assertTrue("eight of nine are no Snoozling", GreenhouseScanLogic.plantGroups(eight).isEmpty());
        assertTrue("one cell is no plant of its own", GreenhouseScanLogic.plantGroups(mixed).isEmpty());
        assertEquals("the stand's own cell only", State.CORRECT, stateOf(eight, List.of(at("Snoozling", 4.5, 0.5)), 0));
        assertEquals(State.EMPTY, stateOf(eight, List.of(at("Snoozling", 4.5, 0.5)), 1));
        assertEquals("a full box is one group", 1, GreenhouseScanLogic.plantGroups(snoozlingCells()).size());
        assertEquals(9, GreenhouseScanLogic.plantGroups(snoozlingCells()).getFirst().length);
    }

    @Test
    public void aSnoozlingGroupCompletesTheLayoutOnceItsHeadsStand() {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 0; row < 3; row++) {
            for (int column = 4; column <= 6; column++) {
                grid.set(row, column, new GreenhouseGrid.Cell("Snoozling", GreenhouseGrid.INGREDIENT));
            }
        }
        var placements = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY).placements();

        Snapshot snapshot = scan(GreenhouseScanLogic.footprints(placements), aRealSnoozling(), null);

        assertTrue(GreenhouseCompletion.allIngredientsCorrect(placements, snapshot.raw(), snapshot.rawExtras()));
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
