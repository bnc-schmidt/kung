package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseCosts.CostMode;
import com.github.beng420.kung.feature.garden.GreenhouseCosts.Quote;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the last solve reported, in the form the screen lists it and the settings file keeps it. Plain Java:
 * the goal lines and notes are text already, so a restored summary needs no solver result.
 *
 * <p>A fixed goal is judged by the whole number of candidate sites the layout holds for it, which the solver keeps
 * at the request on purpose; the expected value can be lower because those sites are shared with other recipes, so
 * it is only added to the line when it differs noticeably.
 *
 * @param fixed    number of goals with a requested count (maximize goals are not counted)
 * @param met      how many of those the layout reached
 * @param costLine what the layout costs, for example {@code Cost: 13.9k coins (incl. estimated prices)}; empty
 *                 when no costs were used or the summary was saved before costs existed
 */
record GreenhouseSummary(List<Goal> goals, List<String> notes, long elapsedMillis, int fixed, int met,
                         String costLine) {
    enum Level { REACHED, SHORT, FAILED }

    /** Expected values closer than this to the site count are not worth printing. */
    private static final double NOTICEABLE = 0.05;

    /**
     * One list row: {@code Cindershade 37 / 52}, {@code Zombud 2 / 2 (0.91 expected)} or {@code Glasscorn 7 (max)},
     * with the solver's note as its tooltip.
     */
    record Goal(String text, Level level, String note) { }

    GreenhouseSummary {
        goals = List.copyOf(goals);
        notes = List.copyOf(notes);
        costLine = costLine == null ? "" : costLine;
    }

    GreenhouseSummary(List<Goal> goals, List<String> notes, long elapsedMillis, int fixed, int met) {
        this(goals, notes, elapsedMillis, fixed, met, "");
    }

    /** The lines of a result without costs. */
    static GreenhouseSummary of(GreenhouseSolver.SolveResult solved) {
        return of(solved, null, Map.of());
    }

    /**
     * The lines of a result that was solved with the costs of {@code mode}; {@code quotes} says which of them were
     * guesses. A null mode leaves the cost line empty.
     */
    static GreenhouseSummary of(GreenhouseSolver.SolveResult solved, CostMode mode, Map<String, Quote> quotes) {
        List<Goal> lines = new ArrayList<>();
        int fixed = 0;
        int met = 0;
        for (GreenhouseSolver.GoalResult goal : solved.goals()) {
            boolean maximize = goal.requested() < 0;
            boolean reached = maximize || goal.sites() >= goal.requested();
            if (!maximize) fixed++;
            if (!maximize && reached) met++;
            String text = goal.mutation() + " " + goal.sites() + (maximize ? " (max)" : " / " + goal.requested());
            if (!maximize && Math.abs(goal.achieved() - goal.sites()) >= NOTICEABLE) {
                text += " (" + GreenhouseSolver.quantity(goal.achieved()) + " expected)";
            }
            lines.add(new Goal(text, !goal.supported() ? Level.FAILED : reached ? Level.REACHED : Level.SHORT,
                goal.note()));
        }
        List<String> notes = new ArrayList<>(solved.notes());
        if (solved.unrequestedSites() > 0) {
            notes.add(solved.unrequestedSites() + " empty site(s) would grow mutations you did not request");
        }
        return new GreenhouseSummary(lines, notes, solved.elapsedMillis(), fixed, met, costLine(solved, mode, quotes));
    }

    /**
     * "Cost: 13.9k coins", with " (incl. estimated prices)" when a crop in the shopping list is priced by a guess,
     * or "Cost: difficulty 340 (by rarity)" in Ironman mode. Empty without a mode or without any unit costs.
     */
    static String costLine(GreenhouseSolver.SolveResult solved, CostMode mode, Map<String, Quote> quotes) {
        if (mode == null || solved.unitCosts().isEmpty()) return "";
        String total = GreenhouseCosts.formatTotal(solved.totalCost(), mode);
        if (mode == CostMode.IRONMAN) return "Cost: " + total + " (by rarity)";
        boolean estimated = solved.ingredients().entrySet().stream().anyMatch(entry -> entry.getValue() > 0
            && quotes.get(GreenhouseCrops.canonical(entry.getKey())) != null
            && quotes.get(GreenhouseCrops.canonical(entry.getKey())).basis() == GreenhouseCosts.Basis.ESTIMATED);
        return "Cost: " + total + (estimated ? " (incl. estimated prices)" : "");
    }

    /**
     * "Solved in 5.0 s: 2 of 3 targets met"; "Stopped after ..." when the player stopped the run; "Stopped early:
     * converged after ..." when the solver ended it itself because it had stopped improving.
     */
    String headline(boolean stopped, boolean converged) {
        String text = stopped ? "Stopped after " + seconds()
            : converged ? "Stopped early: converged after " + seconds() : "Solved in " + seconds();
        return fixed > 0 ? text + ": " + met + " of " + fixed + " targets met" : text;
    }

    /** "Last solve: 5.0 s". */
    String lastSolve() { return "Last solve: " + seconds(); }

    boolean allMet() { return met == fixed; }

    /** The first goal line, which names what was planned; empty when there are none. */
    String firstLine() { return goals.isEmpty() ? "" : goals.get(0).text(); }

    private String seconds() { return String.format(Locale.ROOT, "%.1f s", elapsedMillis / 1000.0); }
}
