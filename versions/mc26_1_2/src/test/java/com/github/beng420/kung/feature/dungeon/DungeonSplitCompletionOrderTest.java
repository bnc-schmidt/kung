package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.SplitsConfig;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class DungeonSplitCompletionOrderTest {
    @Test public void suppliedScoreBeforeVictoryRunLearnsFinalBestForTheNextRun() throws Exception {
        var config = new SplitsConfig();
        config.setEnabled(true);
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        AtomicLong clock = new AtomicLong();
        var tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);
        tracker.startRun(0L, 1, false);
        try (var reader = new BufferedReader(new InputStreamReader(
            getClass().getResourceAsStream("/dungeon/splits-score-before-victory-2026-09-15.tsv"), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().filter(row -> !row.startsWith("#")).toList()) {
                String[] event = line.split("\t", 2);
                clock.set(Long.parseLong(event[0]));
                tracker.observeMessage(event[1], 0L);
                // The lifecycle owner also calls stopRun after each completion signal.
                if (DungeonLifecycleSignals.isRunFinished(event[1])) tracker.stopRun();
            }
        }
        assertEquals(13_736L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
        assertEquals(1, config.recentRunCount(1, false));
        assertEquals(13_736L, config.averageMillis(1, false, "Bonzo Phase 2"));
        assertEquals(5, tracker.completedSplits().size());
        assertEquals(140_699L, tracker.currentTotalDurationMillis());
        assertEquals(140_699L, tracker.predictedFinishMillis());
        assertFalse(tracker.running());

        // The missing final PB used to survive every run, blocking predictions until Phase 2.
        var restored = new Gson().fromJson(new Gson().toJson(config), SplitsConfig.class);
        var nextRun = new DungeonSplitTracker(clock::get, ignored -> { }, restored);
        nextRun.startRun(0L, 1, false);
        assertEquals(93_580L, nextRun.predictedFinishMillis());
        clock.addAndGet(30_000L);
        assertEquals(123_580L, nextRun.predictedFinishMillis());
        nextRun.observeMessage("The BLOOD DOOR has been opened!", 0L);
        assertEquals(65_127L, nextRun.predictedFinishMillis());
        restored.setPredictionMode(SplitsConfig.PredictionMode.PHASE_END);
        assertEquals(123_580L, nextRun.predictedFinishMillis());
        nextRun.stopRun();
        nextRun.startRun(0L, 1, true);
        assertEquals(-1L, nextRun.predictedFinishMillis());
    }

    @Test public void victoryConfirmationSavesOnceAndCannotChangeFrozenClocksOrMetadata() {
        var config = new SplitsConfig();
        config.setEnabled(true);
        var writes = new AtomicInteger();
        config.onChange(writes::incrementAndGet);
        var events = new ArrayList<String>();
        AtomicLong clock = new AtomicLong();
        var tracker = new DungeonSplitTracker(clock::get, events::add, config);
        tracker.startRun(0L, 1, false);
        clock.set(5_000L);
        tracker.observeMessage("[BOSS] Bonzo: Oh I'm dead!", 0L);
        tracker.serverTick(clock.get());
        clock.set(8_000L);
        tracker.observeMessage("Team Score: 177 (B)", 0L);
        var timings = tracker.currentTimings();
        var completed = tracker.completedSplits();
        assertEquals(-1L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
        assertEquals(0, writes.get());
        assertEquals(0, config.recentRunCount(1, false));
        assertTrue(events.stream().anyMatch(line -> line.startsWith("run-finished")
            && line.contains("pbFloor=F1") && line.contains("Bonzo Phase 2")));
        tracker.configureKnownFloor(7, true); // Reward metadata must not reassign this result.
        clock.set(8_003L);
        assertTrue(tracker.observeMessage("☠ Defeated Bonzo in 8s", 0L));
        tracker.serverTick(clock.get());
        tracker.stopRun();
        assertFalse(tracker.observeMessage("Team Score: 177 (B)", 0L));
        assertFalse(tracker.observeMessage("☠ Defeated Bonzo in 8s", 0L));
        assertEquals(1, writes.get());
        assertEquals(1, config.recentRunCount(1, false));
        assertEquals(3_000L, config.averageMillis(1, false, "Bonzo Phase 2"));
        assertEquals(3_000L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
        assertEquals(-1L, config.personalBestMillis(7, true, "Bonzo Phase 2"));
        assertEquals(timings, tracker.currentTimings());
        assertEquals(completed, tracker.completedSplits());
        assertEquals(8_000L, tracker.predictedFinishMillis());
        assertEquals(1, events.stream().filter(line -> line.startsWith("run-victory-confirmed")).count());
    }

    @Test public void masterSevenKingDeathLineAfterTheScoreConfirmsTheDragonsBest() {
        String decides = "[BOSS] Wither King: We will decide it all, here, now.";
        String incredible = "[BOSS] Wither King: Incredible. You did what I couldn't do myself.";
        var config = new SplitsConfig();
        config.setEnabled(true);
        var phases = new ArrayList<DungeonSplitTracker.PhaseMessage>();
        AtomicLong clock = new AtomicLong();
        var tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config, phases::add);
        tracker.startRun(0L, 7, true);
        clock.set(10_000L);
        tracker.observeMessage(decides, 0L);
        clock.set(30_000L);
        tracker.observeMessage("Team Score: 303 (S+)", 0L);
        assertEquals(-1L, config.personalBestMillis(7, true, "Dragons"));
        assertEquals(-1L, tracker.predictedFinishMillis());
        // Hypixel now sends the king's death line after the score, 2.6s later in the supplied trace.
        clock.set(32_600L);
        assertTrue(tracker.observeMessage(incredible, 0L));
        assertEquals(20_000L, config.personalBestMillis(7, true, "Dragons"));
        assertEquals("Dragons", phases.getLast().phase());
        assertTrue(phases.getLast().personalBest());
        assertEquals(1, config.recentRunCount(7, true));
        assertEquals(30_000L, tracker.predictedFinishMillis());
        assertEquals(30_000L, tracker.currentTotalDurationMillis());
        assertFalse(tracker.observeMessage(incredible, 0L));

        // A wipe prints the same score but never the king's death line; a stray one outside the window is ignored.
        var wipeConfig = new SplitsConfig();
        wipeConfig.setEnabled(true);
        var wipe = new DungeonSplitTracker(clock::get, ignored -> { }, wipeConfig);
        wipe.startRun(0L, 7, true);
        clock.set(40_000L);
        wipe.observeMessage(decides, 0L);
        clock.set(60_000L);
        wipe.observeMessage("Team Score: 100 (D)", 0L);
        clock.set(65_001L);
        assertFalse(wipe.observeMessage(incredible, 0L));
        assertEquals(-1L, wipeConfig.personalBestMillis(7, true, "Dragons"));
        assertEquals(0, wipeConfig.recentRunCount(7, true));
    }

    @Test public void scoreOnlyWipesAndUnrelatedMessagesCannotConfirmAFinalBest() {
        var config = new SplitsConfig();
        config.setEnabled(true);
        config.recordRun(1, false, Map.of("Bonzo Phase 2", 20_000L), Map.of());
        AtomicLong clock = new AtomicLong();
        var tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);
        tracker.startRun(0L, 1, false);
        clock.set(5_000L);
        tracker.observeMessage("[BOSS] Bonzo: Oh I'm dead!", 0L);
        clock.set(8_000L);
        tracker.observeMessage("Team Score: 100 (D)", 0L);
        for (String message : new String[] {"Team Score: 100 (D)", "Defeated Sadan in 8s",
            "Party > Ben: Defeated Bonzo in 8s", "Defeated Bonzo", "The Catacombs - Floor I"}) {
            assertFalse(message, tracker.observeMessage(message, 0L));
        }
        assertEquals(20_000L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
        assertEquals(-1L, tracker.predictedFinishMillis());
        clock.set(13_001L);
        assertFalse(tracker.observeMessage("Defeated Bonzo in 8s", 0L));
        assertEquals(20_000L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
        assertEquals(0, config.recentRunCount(1, false));
    }

    @Test public void resetAndNewCountdownDiscardUnconfirmedFinalMeasurements() {
        for (boolean newRun : new boolean[] {false, true}) {
            var config = new SplitsConfig();
            config.setEnabled(true);
            AtomicLong clock = new AtomicLong();
            var tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);
            tracker.startRun(0L, 1, false);
            clock.set(5_000L);
            tracker.observeMessage("[BOSS] Bonzo: Oh I'm dead!", 0L);
            clock.set(8_000L);
            tracker.observeMessage("Team Score: 177 (B)", 0L);
            if (newRun) tracker.startRun(0L, 6, true);
            else tracker.reset();
            tracker.observeMessage("Defeated Bonzo in 8s", 0L);
            assertEquals(-1L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
            assertEquals(-1L, config.personalBestMillis(6, true, "Bonzo Phase 2"));
            assertEquals(0, config.recentRunCount(1, false));
        }
    }

    @Test public void confirmationPreservesWhetherTrackingWasEnabledAtTheMeasuredBoundary() {
        for (boolean enabledAtScore : new boolean[] {false, true}) {
            var config = new SplitsConfig();
            config.setEnabled(enabledAtScore);
            AtomicLong clock = new AtomicLong();
            var tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);
            tracker.startRun(0L, 1, false);
            clock.set(5_000L);
            tracker.observeMessage("[BOSS] Bonzo: Oh I'm dead!", 0L);
            clock.set(8_000L);
            tracker.observeMessage("Team Score: 177 (B)", 0L);
            config.setEnabled(!enabledAtScore);
            clock.set(8_003L);
            tracker.observeMessage("Defeated Bonzo in 8s", 0L);
            assertEquals(enabledAtScore ? 3_000L : -1L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
            assertEquals(enabledAtScore ? 1 : 0, config.recentRunCount(1, false));
        }
    }

    @Test public void interruptedAndManualPhasesRemainIneligibleAfterVictoryText() {
        for (boolean manual : new boolean[] {false, true}) {
            var config = new SplitsConfig();
            config.setEnabled(true);
            AtomicLong clock = new AtomicLong();
            var tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);
            tracker.startRun(0L, 1, false);
            clock.set(5_000L);
            if (manual) tracker.mark("Bonzo Phase 2", 0L);
            else tracker.observeMessage("The BLOOD DOOR has been opened!", 0L);
            clock.set(8_000L);
            tracker.observeMessage("Team Score: 100 (D)", 0L);
            assertFalse(tracker.observeMessage("Defeated Bonzo in 8s", 0L));
            assertEquals(-1L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
            assertEquals(-1L, config.personalBestMillis(1, false, "Blood Clear"));
            assertEquals(0, config.recentRunCount(1, false));
        }
    }

    @Test public void matchingVictoryBannersCoverFloorsAndKeepNecronSeparateFromMasterSeven() {
        String[] bosses = {"The Watcher", "Bonzo", "Scarf", "The Professor", "Thorn", "Livid", "Sadan", "Necron"};
        for (int floor = 0; floor < bosses.length; floor++) {
            String message = "§c☠ Defeated " + bosses[floor] + " in 02m 19s (NEW RECORD!)";
            assertTrue(message, DungeonLifecycleSignals.isVictoryForFloor(message, floor, false));
            assertFalse(message, DungeonLifecycleSignals.isVictoryForFloor(message, (floor + 1) % 8, false));
            if (floor < 7) assertTrue(message, DungeonLifecycleSignals.isVictoryForFloor(message, floor, true));
        }
        assertTrue(DungeonLifecycleSignals.isVictoryForFloor("Defeated Maxor, Storm, Goldor, and Necron in 07m 52s", 7, false));
        assertTrue(DungeonLifecycleSignals.isVictoryForFloor("Defeated The Wither King in 06m 42s", 7, true));
        assertFalse(DungeonLifecycleSignals.isVictoryForFloor("Defeated Necron in 06m 42s", 7, true));
        assertFalse(DungeonLifecycleSignals.isVictoryForFloor("Defeated The Wither King in 06m 42s", 7, false));
        assertFalse(DungeonLifecycleSignals.isVictoryForFloor("Team Score: 300 (S+)", 7, true));
        assertFalse(DungeonLifecycleSignals.isVictoryForFloor(null, -1, false));
    }
}
