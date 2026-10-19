package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.SplitsConfig;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Hypixel's end-of-run report is one chat block; Kung's split lines must land after it, on every floor. */
public final class DungeonSplitReportTest {
    private static final String DASHES = "-----------------------------------------------------";
    /** Lines that reach the last phase of floors 1 to 6, and the boss named by that floor's Defeated line. */
    private static final List<List<String>> LAST_PHASE_LINES = List.of(
        List.of("[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.", "[BOSS] Bonzo: Oh I'm dead!"),
        List.of("[BOSS] Scarf: This is where the journey ends for you, Adventurers.",
            "[BOSS] Scarf: Did you forget? I was taught by the best! Let's dance."),
        List.of("[BOSS] The Professor: I was burdened with terrible news recently...",
            "[BOSS] The Professor: Oh? You found my Guardians' one weakness?",
            "[BOSS] The Professor: What?! My Guardian power is unbeatable!"),
        List.of("[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!"),
        List.of("[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows."),
        List.of("[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!",
            "[BOSS] Sadan: ENOUGH!", "[BOSS] Sadan: You did it. I understand now, you have earned my respect."));
    private static final List<String> BOSSES = List.of("Bonzo", "Scarf", "The Professor", "Thorn", "Livid", "Sadan");
    private final KungConfig config = KungConfig.get();
    private SplitsConfig previous;

    @Before public void enableSplits() {
        previous = config.splits;
        config.splits = new SplitsConfig();
        config.splits.setEnabled(true);
        config.splits.setRunEndChat(true);
    }

    @After public void restoreSplits() {
        config.splits = previous;
    }

    @Test public void everyFloorsLastPhaseLineWaitsForTheClosingDashedLine() throws Exception {
        var state = new DungeonStateTracker();
        for (boolean master : new boolean[] {false, true}) {
            for (int floor = 1; floor <= 6; floor++) {
                String label = (master ? "M" : "F") + floor;
                String[] names = DungeonSplitTracker.namesFor(floor, master);
                String lastPhase = names[names.length - 1];
                reachLastPhase(state, floor, master);
                line(state, DASHES, 0L); // The report's opening line: nothing has ended yet.
                line(state, "Master Mode The Catacombs - Floor V", 0L);
                line(state, "Team Score: 303 (S+)", 0L);
                line(state, "Defeated " + BOSSES.get(floor - 1) + " in 02m 01s", 0L);
                line(state, "> EXTRA STATS <", 0L);
                line(state, "+129,381 Catacombs Experience", 0L);
                assertEquals(label, List.of(), state.dueSplitNotices(19L));
                line(state, DASHES, 1L); // The closing line, then a short safety delay.
                assertEquals(label, List.of(), state.dueSplitNotices(5L));
                List<String> due = state.dueSplitNotices(6L).stream().map(DungeonSplitMessages.Notice::text).toList();
                assertTrue(label + " " + due, due.getFirst().startsWith(label + " " + lastPhase + ": "));
                int summary = due.indexOf(label + " Run Splits (server time in parentheses)");
                assertTrue(label + " " + due, summary > 0);
                assertEquals(label, List.of(), state.dueSplitNotices(1_000L));
            }
        }
    }

    @Test public void missingClosingLineStillReleasesAfterTheFallback() throws Exception {
        var state = new DungeonStateTracker();
        reachLastPhase(state, 5, true);
        line(state, "Team Score: 303 (S+)", 0L);
        line(state, "Defeated Livid in 02m 01s", 0L);
        assertEquals(List.of(), state.dueSplitNotices(19L));
        assertTrue(state.dueSplitNotices(20L).getFirst().text().startsWith("M5 Livid: "));
        assertEquals(List.of(), state.dueSplitNotices(21L));
    }

    private static void reachLastPhase(DungeonStateTracker state, int floor, boolean master) throws InterruptedException {
        state.splitTracker().startRun(0L, floor, master);
        for (String message : LAST_PHASE_LINES.get(floor - 1)) line(state, message, 0L);
        Thread.sleep(5L); // The live clock must see the boss phase last a millisecond, or it reports nothing.
    }

    /** The two steps DungeonStateTracker takes for every chat line of a run. */
    private static void line(DungeonStateTracker state, String text, long tick) {
        state.observeSplitReport(text, tick);
        state.splitTracker().observeMessage(text, tick);
    }
}
