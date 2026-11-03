package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.PlotCleaningHelperFeature.Plot;
import org.junit.Test;

public final class PlotCleaningStateTest {
    private static final Plot ONE = new Plot(-48, -144);
    private static final Plot TWO = new Plot(48, -144);

    @Test public void aCleaningPercentageIsReadInEitherOrder() {
        assertEquals(45, PlotCleaningState.percent("Cleaning: 45%"), 0);
        assertEquals(12.5, PlotCleaningState.percent("Plot Cleaning: 12.5%"), 0);
        assertEquals(12.5, PlotCleaningState.percent("Cleaned: 12,5 %"), 0);
        assertEquals(100, PlotCleaningState.percent("Clean Progress: 100%"), 0);
        assertEquals(60, PlotCleaningState.percent("60% cleaned"), 0);
        assertEquals(0, PlotCleaningState.percent("Cleaning 0%"), 0);
    }

    @Test public void otherPercentagesAndOtherCleanWordsAreNoProgress() {
        for (String line : new String[] {"Garden Level: V (55.8%)", "Farming 15: 57.9%", "Spray: None",
                "Pest Traps: 0/3", "Plot - 1 is now clean!", "Cleaned plots: 3 (of 24)", "", "Clean up"}) {
            assertTrue(line, Double.isNaN(PlotCleaningState.percent(line)));
        }
    }

    @Test public void onlyTheFinishedMessageCountsAsAnnouncedClean() {
        assertTrue(PlotCleaningState.cleanMessage("Plot - 1 is now clean!"));
        assertTrue(PlotCleaningState.cleanMessage("Plot - 12 is now clean!"));
        assertFalse(PlotCleaningState.cleanMessage("Plot - 1 is now dirty!"));
        assertFalse(PlotCleaningState.cleanMessage("Guild > A: Plot - 1 is now clean!"));
    }

    @Test public void aPlotIsCleanableOnlyAfterAProgressBelowAHundredWasShown() {
        var state = new PlotCleaningState();
        state.sync(5);
        assertFalse("a plot that never showed a progress", state.cleaning(ONE));
        state.progress(ONE, 40);
        assertTrue(state.cleaning(ONE));
        assertFalse("another plot", state.cleaning(TWO));
        state.progress(ONE, 100);
        assertFalse(state.cleaning(ONE));
        state.progress(ONE, 0);
        assertTrue(state.cleaning(ONE));
    }

    @Test public void anAnnouncedCleanPlotStaysCleanEvenWhenAStaleLineShowsLessThanAHundred() {
        var state = new PlotCleaningState();
        state.sync(5);
        state.progress(ONE, 98);
        state.announcedClean(ONE);
        assertFalse(state.cleaning(ONE));
        state.progress(ONE, 98);
        assertFalse(state.cleaning(ONE));
        state.progress(TWO, 10);
        assertTrue(state.cleaning(TWO));
    }

    @Test public void aNewGardenInstanceForgetsEverything() {
        var state = new PlotCleaningState();
        state.sync(5);
        state.progress(ONE, 40);
        state.sync(5);
        assertTrue(state.cleaning(ONE));
        state.sync(6);
        assertFalse(state.cleaning(ONE));
        state.announcedClean(TWO);
        state.sync(7);
        state.progress(TWO, 10);
        assertTrue(state.cleaning(TWO));
    }
}
