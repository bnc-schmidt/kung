package com.github.beng420.kung.feature.mining;

import static com.github.beng420.kung.feature.mining.GourmandTimer.Event;
import static com.github.beng420.kung.feature.mining.GourmandTimer.Kind;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class GourmandTimerTest {
    private static final String ONE_MINUTE =
        "Don Expresso is leaving in 1 minute! Make sure to feed him everything you have before he leaves!";
    private static final String TWENTY_SECONDS = "Don Expresso is leaving in 20 seconds! Make sure to feed him "
        + "everything you have before he leaves! [Teleport to Don Expresso]";

    /** Ticks every 50 ms from {@code from} to {@code to}; returns what showed up, as "KIND seconds". */
    private static List<String> run(GourmandTimer timer, long from, long to, boolean mithril, int countdown) {
        List<String> shown = new ArrayList<>();
        for (long now = from; now <= to; now += 50) {
            var alert = timer.tick(now, mithril, countdown);
            if (alert.kind() != Kind.NONE) shown.add(alert.kind() + " " + alert.seconds() + (alert.link() ? " link" : ""));
        }
        return shown;
    }

    @Test
    public void readsTheWikiLinesAndIgnoresPlayersAndUnrelatedText() {
        var timer = new GourmandTimer();
        assertEquals(Event.STARTED, timer.observe("MITHRIL GOURMAND STARTED!", 0));
        assertEquals(Event.LEAVING, timer.observe(ONE_MINUTE, 0));
        assertEquals(Event.LEAVING, timer.observe(TWENTY_SECONDS, 0));
        assertEquals(Event.ENDED, timer.observe("MITHRIL GOURMAND ENDED!", 0));
        assertEquals(Event.STARTED, timer.observe("   ⚑ MITHRIL GOURMAND STARTED!  ", 0));
        // Anyone can type into public chat; a name in front of the text is not Hypixel.
        assertEquals(Event.NONE, timer.observe("[MVP+] Steve: Don Expresso is leaving in 1 minute!", 0));
        assertEquals(Event.NONE, timer.observe("[VIP] Steve: MITHRIL GOURMAND ENDED!", 0));
        assertEquals(Event.NONE, timer.observe("Don Expresso wants some Tasty Mithril from the Dwarven Village.", 0));
        assertEquals(Event.NONE, timer.observe("⚑ The Mithril Gourmand event starts in 20 seconds!", 0));
        assertEquals(Event.NONE, timer.observe(null, 0));
    }

    /** The banner lines exactly as a SkyHanni chat log recorded them (2026-10-20), centre padding included. */
    @Test
    public void readsTheRealBannerLinesAndIgnoresTheLinesAroundThem() {
        var timer = new GourmandTimer();
        assertEquals(Event.NONE, timer.observe("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬", 0));
        assertEquals(Event.STARTED, timer.observe("                    MITHRIL GOURMAND STARTED!", 0));
        assertEquals(Event.NONE, timer.observe("                                 Mining Event", 0));
        assertEquals(Event.NONE, timer.observe("  Don Expresso wants some Tasty Mithril from the Upper Mines.", 0));
        assertEquals(Event.NONE, timer.observe("        Bring 200 Tasty Mithril to him at the Dwarven Village!", 0));
        assertEquals(Event.NONE, timer.observe(" ⚑ The Mithril Gourmand event starts in 20 seconds!", 0));
        assertEquals(Event.ENDED, timer.observe("                      MITHRIL GOURMAND ENDED!", 0));
        assertEquals(Event.NONE, timer.observe("                          You didn't participate...", 0));
        assertEquals(Event.NONE, timer.observe("[358] ჶ [MVP+] Ubeykim: You fed 816 Tasty Mithril to Don Expresso!", 0));
    }

    @Test
    public void oneMinuteLineWarnsOnceThenCountsDownTenToOne() {
        var timer = new GourmandTimer();
        timer.observe(ONE_MINUTE, 1_000);
        List<String> shown = run(timer, 1_000, 61_000, true, 10);
        List<String> expected = new ArrayList<>(List.of("WARNING 60 link"));
        for (int second = 10; second >= 1; second--) expected.add("COUNT " + second + (second == 10 ? " link" : ""));
        assertEquals(expected, shown);
        assertFalse("Don is gone after the last second", timer.active());
    }

    @Test
    public void theCountdownLengthComesFromTheSlider() {
        var timer = new GourmandTimer();
        timer.observe(ONE_MINUTE, 0);
        List<String> shown = run(timer, 0, 60_000, true, 5);
        assertEquals(List.of("WARNING 60 link", "COUNT 5 link", "COUNT 4", "COUNT 3", "COUNT 2", "COUNT 1"), shown);
    }

    @Test
    public void nothingShowsWithoutTastyMithril() {
        var timer = new GourmandTimer();
        timer.observe(ONE_MINUTE, 0);
        assertEquals(List.of(), run(timer, 0, 61_000, false, 10));
    }

    @Test
    public void mithrilPickedUpLateWarnsWithTheTimeLeftAndKeepsCounting() {
        var timer = new GourmandTimer();
        timer.observe(ONE_MINUTE, 0);
        assertEquals(List.of(), run(timer, 0, 29_950, false, 10));
        List<String> shown = run(timer, 30_000, 60_000, true, 10);
        assertEquals("WARNING 30 link", shown.get(0));
        assertEquals(11, shown.size());
    }

    @Test
    public void mithrilGivenAwayStopsTheCountdownAndComingBackResumesIt() {
        var timer = new GourmandTimer();
        timer.observe(ONE_MINUTE, 0);
        run(timer, 0, 52_000, true, 10);
        assertEquals(List.of(), run(timer, 52_050, 55_000, false, 10));
        // 5 s left when it comes back: the seconds already shown (10 to 8) stay shown once, the rest follow.
        assertEquals(List.of("COUNT 5", "COUNT 4", "COUNT 3", "COUNT 2", "COUNT 1"),
            run(timer, 55_050, 60_000, true, 10));
    }

    @Test
    public void theTwentySecondLineStartsTheCountdownWithTheTeleportLink() {
        var timer = new GourmandTimer();
        timer.observe(TWENTY_SECONDS, 5_000);
        List<String> shown = run(timer, 5_000, 25_000, true, 10);
        assertEquals("WARNING 20 link", shown.get(0));
        assertEquals("COUNT 10 link", shown.get(1));
        assertEquals(11, shown.size());
    }

    @Test
    public void aCountdownLongerThanTheTimeLeftLeadsWithTheLinkItself() {
        var timer = new GourmandTimer();
        timer.observe(TWENTY_SECONDS, 0);
        List<String> shown = run(timer, 0, 20_000, true, 30);
        assertEquals("COUNT 20 link", shown.get(0));
        assertEquals(20, shown.size());
        assertEquals(1, shown.stream().filter(entry -> entry.endsWith("link")).count());
    }

    @Test
    public void theOneMinuteLineAfterTheOwnTimerAlreadyWarnedDoesNotWarnTwice() {
        var timer = new GourmandTimer();
        timer.observe("MITHRIL GOURMAND STARTED!", 0);
        long minuteBeforeEnd = GourmandTimer.EVENT_MILLIS - GourmandTimer.WARNING_MILLIS;
        assertEquals(List.of(), run(timer, 0, minuteBeforeEnd - 50, true, 10));
        assertEquals(List.of("WARNING 60 link"), run(timer, minuteBeforeEnd, minuteBeforeEnd, true, 10));
        timer.observe(ONE_MINUTE, minuteBeforeEnd + 200);
        List<String> rest = run(timer, minuteBeforeEnd + 250, GourmandTimer.EVENT_MILLIS + 200, true, 10);
        assertEquals(10, rest.size());
        assertTrue(rest.stream().allMatch(entry -> entry.startsWith("COUNT")));
    }

    @Test
    public void startedLineWarnsAMinuteBeforeTheTenMinuteTimerRunsOut() {
        var timer = new GourmandTimer();
        timer.observe("MITHRIL GOURMAND STARTED!", 100_000);
        assertEquals(List.of(), run(timer, 100_000, 100_000 + 539_950, true, 10));
        assertEquals("WARNING 60 link", run(timer, 100_000 + 540_000, 100_000 + 540_000, true, 10).get(0));
    }

    @Test
    public void anEarlyFinishByOthersMovesTheDeadlineForward() {
        var timer = new GourmandTimer();
        timer.observe("MITHRIL GOURMAND STARTED!", 0);
        assertEquals(List.of(), run(timer, 0, 100_000, true, 10));
        timer.observe(ONE_MINUTE, 100_000);
        assertEquals("WARNING 60 link", run(timer, 100_050, 100_100, true, 10).get(0));
    }

    @Test
    public void endedLineAndANewEventClearTheState() {
        var timer = new GourmandTimer();
        timer.observe(ONE_MINUTE, 0);
        run(timer, 0, 30_000, true, 10);
        timer.observe("MITHRIL GOURMAND ENDED!", 30_000);
        assertFalse(timer.active());
        assertEquals(List.of(), run(timer, 30_050, 70_000, true, 10));
        timer.observe(ONE_MINUTE, 100_000);
        assertEquals("WARNING 60 link", run(timer, 100_000, 100_050, true, 10).get(0));
    }

    @Test
    public void aTimerThatNeverSawAnyLineStaysQuiet() {
        assertEquals(List.of(), run(new GourmandTimer(), 0, 120_000, true, 10));
    }
}
