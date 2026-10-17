package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSolver.Goal;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveRequest;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveResult;
import java.util.List;
import org.junit.Test;

public final class GreenhouseSummaryTest {
    private static GreenhouseSummary summary(int fixed, int met) {
        return new GreenhouseSummary(
            List.of(new GreenhouseSummary.Goal("Cindershade 37 / 52", GreenhouseSummary.Level.SHORT, "note")),
            List.of("a note"), 5_040, fixed, met);
    }

    @Test
    public void headlineCountsFixedTargetsAndNamesAStop() {
        assertEquals("Solved in 5.0 s: 1 of 3 targets met", summary(3, 1).headline(false, false));
        assertEquals("Stopped after 5.0 s: 3 of 3 targets met", summary(3, 3).headline(true, false));
        assertEquals("Solved in 5.0 s", summary(0, 0).headline(false, false));
        // The solver ending the run itself is not the player stopping it.
        assertEquals("Stopped early: converged after 5.0 s: 2 of 3 targets met", summary(3, 2).headline(false, true));
        assertEquals("Stopped after 5.0 s", summary(0, 0).headline(true, true));
        assertEquals("Last solve: 5.0 s", summary(1, 1).lastSolve());
        assertEquals("Cindershade 37 / 52", summary(1, 0).firstLine());
        assertTrue(summary(2, 2).allMet());
        assertFalse(summary(2, 1).allMet());
    }

    private static GreenhouseSolver.GoalResult goal(String name, int requested, double expected, int sites) {
        return new GreenhouseSolver.GoalResult(name, requested, expected, sites, true, "");
    }

    private static GreenhouseSummary of(GreenhouseSolver.GoalResult... goals) {
        return GreenhouseSummary.of(new SolveResult(new GreenhouseGrid(), List.of(goals), List.of(), java.util.Map.of(),
            0, 0, List.of(), 1_200, List.of(), false));
    }

    @Test
    public void aFixedGoalIsReachedByItsSitesEvenWhenTheExpectedValueIsLower() {
        GreenhouseSummary summary = of(goal("Zombud", 2, 0.91, 2), goal("Dustgrain", 6, 6.0, 6),
            goal("Cindershade", 5, 3.2, 3), goal("Noctilume", 4, 3.97, 4));

        assertEquals("Zombud 2 / 2 (0.91 expected)", summary.goals().get(0).text());
        assertEquals(GreenhouseSummary.Level.REACHED, summary.goals().get(0).level());
        // The expected value is only added when it differs noticeably.
        assertEquals("Dustgrain 6 / 6", summary.goals().get(1).text());
        assertEquals("Cindershade 3 / 5 (3.20 expected)", summary.goals().get(2).text());
        assertEquals(GreenhouseSummary.Level.SHORT, summary.goals().get(2).level());
        assertEquals("Noctilume 4 / 4", summary.goals().get(3).text());
        assertEquals(4, summary.fixed());
        assertEquals(3, summary.met());
        assertFalse(summary.allMet());
        assertEquals("Solved in 1.2 s: 3 of 4 targets met", summary.headline(false, false));
        assertEquals("Zombud 2 / 2 (0.91 expected)", summary.firstLine());
    }

    private static GreenhouseSolver.SolveResult withCosts(double total, java.util.Map<String, Integer> ingredients) {
        java.util.Map<String, Double> unit = new java.util.LinkedHashMap<>();
        ingredients.keySet().forEach(crop -> unit.put(crop, 1.0));
        return new SolveResult(new GreenhouseGrid(), List.of(goal("Zombud", 2, 2.0, 2)), List.of(), ingredients,
            0, 0, List.of(), 1_200, List.of(), false, total, unit);
    }

    @Test
    public void theCostLineNamesTheTotalAndMarksGuessedPrices() {
        java.util.Map<String, GreenhouseCosts.Quote> quotes = new java.util.LinkedHashMap<>();
        quotes.put("Fire", new GreenhouseCosts.Quote(0, GreenhouseCosts.Basis.FREE));
        quotes.put("Carrot", new GreenhouseCosts.Quote(30, GreenhouseCosts.Basis.MEASURED));
        quotes.put("Cactus", new GreenhouseCosts.Quote(8, GreenhouseCosts.Basis.ESTIMATED));
        java.util.Map<String, Integer> measured = java.util.Map.of("Carrot", 4, "Fire", 6);
        java.util.Map<String, Integer> guessed = java.util.Map.of("Carrot", 4, "Cactus", 2);

        assertEquals("Cost: 13.9k coins", GreenhouseSummary.costLine(withCosts(13_900, measured),
            GreenhouseCosts.CostMode.BAZAAR, quotes));
        assertEquals("Cost: 13.9k coins (incl. estimated prices)", GreenhouseSummary.costLine(
            withCosts(13_900, guessed), GreenhouseCosts.CostMode.BAZAAR, quotes));
        assertEquals("Cost: 96 coins", GreenhouseSummary.costLine(
            withCosts(96, measured), GreenhouseCosts.CostMode.BAZAAR, quotes));
        assertEquals("Cost: difficulty 340 (by rarity)", GreenhouseSummary.costLine(withCosts(340, guessed),
            GreenhouseCosts.CostMode.IRONMAN, quotes));
        // A cheap crop that is a guess but not in the list does not mark the line.
        assertEquals("Cost: 5.0 coins", GreenhouseSummary.costLine(
            withCosts(5, java.util.Map.of("Carrot", 4)), GreenhouseCosts.CostMode.BAZAAR, quotes));
    }

    @Test
    public void noModeOrNoUnitCostsLeavesTheCostLineEmpty() {
        SolveResult plain = new SolveResult(new GreenhouseGrid(), List.of(), List.of(), java.util.Map.of(), 0, 0,
            List.of(), 100, List.of(), false);

        assertEquals("", GreenhouseSummary.costLine(withCosts(1, java.util.Map.of("Carrot", 1)), null, java.util.Map.of()));
        assertEquals("", GreenhouseSummary.costLine(plain, GreenhouseCosts.CostMode.BAZAAR, java.util.Map.of()));
        assertEquals("", GreenhouseSummary.of(plain).costLine());
        assertEquals("", GreenhouseSummary.of(plain, GreenhouseCosts.CostMode.IRONMAN, java.util.Map.of()).costLine());
    }

    @Test
    public void theCostLineIsSavedAndASummaryWithoutOneStillLoads() {
        String layout = "\"layout\":[[0,0,\"Carrot\",1]]";
        String goal = "{\"text\":\"Zombud 2 / 2\",\"level\":\"REACHED\",\"note\":\"\"}";
        String withLine = "{\"version\":1," + layout + ",\"summary\":{\"goals\":[" + goal
            + "],\"notes\":[],\"elapsedMillis\":900,\"fixed\":1,\"met\":1,\"costLine\":\"Cost: 13.9k coins\"}}";
        String without = "{\"version\":1," + layout + ",\"summary\":{\"goals\":[" + goal
            + "],\"notes\":[],\"elapsedMillis\":900,\"fixed\":1,\"met\":1}}";
        String tooLong = withLine.replace("Cost: 13.9k coins", "x".repeat(301));

        assertEquals("Cost: 13.9k coins", GreenhouseState.fromJson(withLine).summary().costLine());
        assertEquals("", GreenhouseState.fromJson(without).summary().costLine());
        assertEquals(null, GreenhouseState.fromJson(tooLong).summary());
        GreenhouseState round = GreenhouseState.fromJson(withLine);
        assertEquals(round.summary(), GreenhouseState.fromJson(round.toJson()).summary());
    }

    @Test
    public void aMaximizeGoalShowsItsSitesAndIsNeverShort() {
        GreenhouseSummary summary = of(goal("Glasscorn", -1, 5.6, 7), goal("Zombud", 2, 2.0, 2));

        assertEquals("Glasscorn 7 (max)", summary.goals().get(0).text());
        assertEquals(GreenhouseSummary.Level.REACHED, summary.goals().get(0).level());
        assertEquals(1, summary.fixed());
        assertEquals(1, summary.met());
        assertTrue(summary.allMet());
    }

    @Test
    public void moreSitesThanRequestedStillCountsAsReachedAndAnUnsupportedGoalFails() {
        GreenhouseSummary summary = of(goal("Zombud", 2, 2.4, 3),
            new GreenhouseSolver.GoalResult("Godseed", 1, 0, 0, false, "Unsupported"));

        assertEquals("Zombud 3 / 2 (2.40 expected)", summary.goals().get(0).text());
        assertEquals(GreenhouseSummary.Level.REACHED, summary.goals().get(0).level());
        assertEquals("Godseed 0 / 1", summary.goals().get(1).text());
        assertEquals(GreenhouseSummary.Level.FAILED, summary.goals().get(1).level());
        assertEquals(2, summary.fixed());
        assertEquals(1, summary.met());
    }

    @Test
    public void linesSavedBeforeTheSiteCountsExistKeepLoadingAsPlainText() {
        // A stored summary is text and counts only, so one written under the old rule restores unchanged.
        String old = "{\"version\":1,\"layout\":[[0,0,\"Carrot\",1]],\"summary\":{\"goals\":[{\"text\":\"Zombud 0.91 / 2\","
            + "\"level\":\"SHORT\",\"note\":\"\"}],\"notes\":[],\"elapsedMillis\":900,\"fixed\":1,\"met\":0}}";

        GreenhouseSummary loaded = GreenhouseState.fromJson(old).summary();

        assertEquals("Zombud 0.91 / 2", loaded.goals().get(0).text());
        assertEquals(GreenhouseSummary.Level.SHORT, loaded.goals().get(0).level());
        assertEquals(1, loaded.fixed());
        assertEquals(0, loaded.met());
        assertEquals("Last solve: 0.9 s", loaded.lastSolve());
    }

    @Test
    public void aRealResultBecomesOneLinePerGoalWithItsCounts() {
        SolveResult result = GreenhouseSolver.solve(
            SolveRequest.of(Goal.fixed("Dustgrain", 6), Goal.maximize("Choconut")), 1_000, 3);

        GreenhouseSummary summary = GreenhouseSummary.of(result);

        assertEquals(2, summary.goals().size());
        assertTrue(summary.goals().get(0).text(), summary.goals().get(0).text().startsWith("Dustgrain "));
        assertTrue(summary.goals().get(0).text(), summary.goals().get(0).text().contains(" / 6"));
        assertTrue(summary.goals().get(1).text(), summary.goals().get(1).text().endsWith(" (max)"));
        assertEquals(GreenhouseSummary.Level.REACHED, summary.goals().get(1).level());
        assertEquals(1, summary.fixed());
        assertEquals(result.elapsedMillis(), summary.elapsedMillis());
        assertTrue(summary.notes().containsAll(result.notes()));
    }
}
