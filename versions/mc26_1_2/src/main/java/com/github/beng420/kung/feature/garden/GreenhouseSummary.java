package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the last solve reported, in the form the screen lists it and the settings file keeps it. Plain Java:
 * the goal lines and notes are text already, so a restored summary needs no solver result.
 *
 * <p>A fixed goal is judged by the whole number of candidate sites the layout holds for it, which the solver keeps
 * at the request on purpose; the expected value can be lower because those sites are shared with other recipes, so
 * it is only added to the line when it differs noticeably.
 *
 * @param fixed number of goals with a requested count (maximize goals are not counted)
 * @param met   how many of those the layout reached
 */
record GreenhouseSummary(List<Goal> goals, List<String> notes, long elapsedMillis, int fixed, int met) {
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
    }

    static GreenhouseSummary of(GreenhouseSolver.SolveResult solved) {
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
        return new GreenhouseSummary(lines, notes, solved.elapsedMillis(), fixed, met);
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
