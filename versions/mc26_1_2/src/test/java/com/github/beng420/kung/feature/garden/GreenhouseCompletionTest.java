package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class GreenhouseCompletionTest {
    /** Two crops and a one-cell mutation spot, in placement order (crops row-major, then the spot). */
    private static List<Placement> layout() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Potato", GreenhouseGrid.INGREDIENT));
        grid.set(5, 5, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));
        List<Placement> placements = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY).placements();
        assertEquals(3, placements.size());
        assertTrue(placements.get(2).mutation());
        return placements;
    }

    private static List<Verdict> verdicts(State... states) {
        List<Verdict> out = new ArrayList<>();
        for (State state : states) out.add(new Verdict(state, ""));
        return out;
    }

    /** Whether a scan with these verdicts and no unplanned crop counts as complete. */
    private static boolean complete(List<Placement> placements, List<Verdict> verdicts) {
        return GreenhouseCompletion.allIngredientsCorrect(placements, verdicts, List.of());
    }

    @Test
    public void everyIngredientCorrectIsCompleteAndTheMutationSpotDoesNotCount() {
        List<Placement> placements = layout();

        assertTrue(complete(placements, verdicts(State.CORRECT, State.CORRECT, State.EMPTY)));
        assertTrue("a wrong thing on the spot is not an ingredient",
            complete(placements, verdicts(State.CORRECT, State.CORRECT, State.WRONG)));
        assertFalse(complete(placements, verdicts(State.CORRECT, State.EMPTY, State.CORRECT)));
        assertFalse(complete(placements, verdicts(State.WRONG, State.CORRECT, State.CORRECT)));
        assertFalse(complete(placements, verdicts(State.CORRECT, State.UNVERIFIED, State.CORRECT)));
    }

    @Test
    public void anUnplannedCropBlocksCompletionButSoilMarkersDoNot() {
        List<Placement> placements = layout();
        List<Verdict> allRight = verdicts(State.CORRECT, State.CORRECT, State.EMPTY);

        assertTrue(GreenhouseCompletion.allIngredientsCorrect(placements, allRight, List.of()));
        assertFalse("a pink extra crop still stands: not done", GreenhouseCompletion.allIngredientsCorrect(
            placements, allRight, List.of(new GreenhouseScanLogic.Extra(4, 4, "Cactus"))));
        // The soil markers are not an input at all: the signature has no way to let them block it.
    }

    @Test
    public void aLayoutWithoutIngredientsOrWithoutAMatchingScanIsNeverComplete() {
        GreenhouseGrid onlySpot = new GreenhouseGrid();
        onlySpot.set(5, 5, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));
        List<Placement> spots = GreenhouseHologramShapes.build(onlySpot, GreenhouseOrientation.IDENTITY).placements();

        assertFalse("no ingredient at all", complete(spots, verdicts(State.CORRECT)));
        assertFalse(complete(List.of(), List.of()));
        assertFalse("verdicts of another layout", complete(layout(), verdicts(State.CORRECT)));
        assertFalse("no verdicts yet", complete(layout(), List.of()));
    }

    @Test
    public void twoAgreeingScansComplete_andTheMessageComesOnce() {
        GreenhouseCompletion completion = new GreenhouseCompletion();

        assertFalse(completion.update(true, true));
        assertFalse("one scan is not enough", completion.complete());
        assertTrue("the second agrees: announce", completion.update(true, true));
        assertTrue(completion.complete());
        assertFalse("no second message", completion.update(true, true));
        assertFalse(completion.update(true, true));
        assertTrue(completion.complete());
    }

    @Test
    public void aSingleOddScanChangesNothing() {
        GreenhouseCompletion completion = new GreenhouseCompletion();
        completion.update(true, true);
        completion.update(true, true);
        assertTrue(completion.complete());

        assertFalse(completion.update(false, true));
        assertTrue("one scan without it: still complete", completion.complete());
        assertFalse(completion.update(true, true));
        assertTrue(completion.complete());
        assertFalse("and no repeat message", completion.update(true, true));

        // And the other way round: one lone success does not complete a layout.
        GreenhouseCompletion fresh = new GreenhouseCompletion();
        fresh.update(false, true);
        fresh.update(false, true);
        fresh.update(true, true);
        assertFalse(fresh.complete());
        fresh.update(false, true);
        fresh.update(true, true);
        assertFalse(fresh.complete());
    }

    @Test
    public void aHarvestEndsItAfterTwoScansAndTheNextCompletionIsAnnouncedAgain() {
        GreenhouseCompletion completion = new GreenhouseCompletion();
        completion.update(true, true);
        assertTrue(completion.update(true, true));

        completion.update(false, true);
        assertTrue(completion.complete());
        completion.update(false, true);
        assertFalse("two scans with a crop missing: the hologram is back", completion.complete());

        assertFalse(completion.update(true, true));
        assertTrue("planted again: announced once more", completion.update(true, true));
        assertTrue(completion.complete());
    }

    @Test
    public void withTheSettingOffNothingIsAnnouncedButCompletionIsTrackedAndAnnouncedWhenItIsSwitchedOn() {
        GreenhouseCompletion completion = new GreenhouseCompletion();

        assertFalse(completion.update(true, false));
        assertFalse(completion.update(true, false));
        assertTrue("tracked all the same", completion.complete());
        assertFalse(completion.update(true, false));
        assertTrue("switched on while complete: announced now, once", completion.update(true, true));
        assertFalse(completion.update(true, true));
    }

    @Test
    public void resetForgetsEverything() {
        GreenhouseCompletion completion = new GreenhouseCompletion();
        completion.update(true, true);
        completion.update(true, true);

        completion.reset();

        assertFalse(completion.complete());
        assertFalse("a new hologram needs two scans again", completion.update(true, true));
        assertTrue(completion.update(true, true));
    }

    @Test
    public void theTestSeamSettlesOnAnAnswerWithoutAnnouncing() {
        GreenhouseCompletion completion = new GreenhouseCompletion();

        completion.setForTest(true);
        assertTrue(completion.complete());
        completion.setForTest(false);
        assertFalse(completion.complete());
    }

    @Test
    public void theForcedShowEndsWithTheCompletionAndAfterAReset() {
        GreenhouseCompletion completion = new GreenhouseCompletion();
        assertFalse(completion.forced());
        completion.update(true, true);
        completion.update(true, true);
        completion.setForced(true);
        assertTrue(completion.forced());

        completion.update(true, true);
        completion.update(false, true);
        assertTrue("one odd scan: still complete, still forced", completion.forced());
        completion.update(true, true);
        completion.update(false, true);
        completion.update(false, true);
        assertFalse(completion.complete());
        assertFalse("the layout is incomplete again: the override is over", completion.forced());

        completion.update(true, true);
        completion.update(true, true);
        assertTrue(completion.complete());
        assertFalse("a new completion is not forced", completion.forced());

        completion.setForced(true);
        completion.reset();
        assertFalse(completion.forced());
        completion.setForced(true);
        completion.setForTest(true);
        assertFalse("the test seam starts unforced", completion.forced());
    }
}
