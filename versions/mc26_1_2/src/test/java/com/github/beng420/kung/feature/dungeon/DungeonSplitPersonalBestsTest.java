package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.SplitsConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class DungeonSplitPersonalBestsTest {
    private static final String BLOOD = "The BLOOD DOOR has been opened!";
    private static final String CLEAR = "[BOSS] The Watcher: You have proven yourself. You may pass.";
    private static final String SADAN = "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!";
    private static final String MAXOR = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!";
    private final AtomicLong clock = new AtomicLong();
    private final SplitsConfig config = new SplitsConfig();
    private final DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);

    private void event(long millis, String message) {
        clock.set(millis);
        tracker.observeMessage(message, 0L);
    }

    private void seed(int floor, boolean master, long perPhase) {
        var names = new DungeonSplitTracker(clock::get);
        names.configureKnownFloor(floor, master);
        Map<String, Long> bests = new HashMap<>();
        for (String phase : names.splitNames()) bests.put(phase, perPhase);
        config.recordPersonalBests(floor, master, bests);
    }

    @Test public void predictionUsesSettledRealTimeAndRemainingIndividualBests() {
        seed(6, false, 10_000L);
        tracker.startRun(0L, 6, false);
        assertEquals(60_000L, tracker.predictedFinishMillis());
        clock.set(5_000L);
        tracker.serverTick(clock.get());
        assertEquals(60_000L, tracker.predictedFinishMillis());
        event(15_000L, BLOOD);
        assertEquals(65_000L, tracker.predictedFinishMillis());
        clock.set(90_000L);
        assertEquals(65_000L, tracker.predictedFinishMillis());
        event(95_000L, CLEAR);
        assertEquals(135_000L, tracker.predictedFinishMillis());
        event(100_000L, SADAN);
        assertEquals(130_000L, tracker.predictedFinishMillis());
        assertEquals("2m 10.00s", DungeonSplitsOverlayFeature.formatDurationMillis(
            tracker.predictedFinishMillis(), SplitsConfig.TimeFormat.MINUTES));
        assertEquals("130.00s", DungeonSplitsOverlayFeature.formatDurationMillis(
            tracker.predictedFinishMillis(), SplitsConfig.TimeFormat.SECONDS));
    }

    @Test public void missingRemainingBestIsUnknownButPastMissingBestsNoLongerBlockPrediction() {
        config.recordPersonalBests(5, false, Map.of("Blood Clear", 20_000L, "Portal Entry", 5_000L, "Livid", 40_000L));
        tracker.startRun(0L, 5, false);
        assertEquals(-1L, tracker.predictedFinishMillis());
        event(30_000L, BLOOD);
        assertEquals(95_000L, tracker.predictedFinishMillis());
        tracker.stopRun();
        tracker.startRun(0L, 5, true);
        assertEquals(-1L, tracker.predictedFinishMillis());
    }

    @Test public void liveModeUsesCurrentRunTimeAndOnlyLaterPhaseBestsAndSwitchesImmediately() {
        seed(6, false, 10_000L);
        tracker.startRun(0L, 6, false);
        clock.set(5_000L);
        assertEquals(60_000L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        assertEquals(55_000L, tracker.predictedFinishMillis());
        clock.set(12_000L);
        assertEquals(62_000L, tracker.predictedFinishMillis());
        event(15_000L, BLOOD);
        assertEquals(55_000L, tracker.predictedFinishMillis());
        clock.set(20_000L);
        assertEquals(60_000L, tracker.predictedFinishMillis());
        assertEquals(57_000L, tracker.predictedFinishMillis(17_000L));
        config.setPredictionMode(SplitsConfig.PredictionMode.PHASE_END);
        assertEquals(65_000L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        assertEquals(60_000L, tracker.predictedFinishMillis());
    }

    @Test public void liveModeNeedsLaterBestsButDoesNotNeedAnActivePhaseBest() {
        config.recordPersonalBests(5, false, Map.of("Blood Clear", 20_000L, "Portal Entry", 5_000L, "Livid", 40_000L));
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        tracker.startRun(0L, -1, false);
        assertEquals(-1L, tracker.predictedFinishMillis());
        tracker.configureKnownFloor(5, false);
        clock.set(10_000L);
        assertEquals(75_000L, tracker.predictedFinishMillis());
        tracker.configureKnownFloor(5, true);
        assertEquals(-1L, tracker.predictedFinishMillis());
        // Once the final phase starts, there are no future PBs left to require.
        event(100_000L, "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.");
        clock.set(120_000L);
        assertEquals(120_000L, tracker.predictedFinishMillis());
        tracker.stopRun();
        assertEquals(-1L, tracker.predictedFinishMillis());
        tracker.reset();
        assertEquals(-1L, tracker.predictedFinishMillis());
    }

    @Test public void liveStormUsesOnlyTerminalsGoldorNecronAndTheAdditionalMasterSevenPhases() {
        config.recordPersonalBests(7, false, Map.of("Terminals", 20_000L, "Goldor", 5_000L, "Necron", 10_000L));
        config.recordPersonalBests(7, true, Map.of("Terminals", 30_000L, "Goldor", 6_000L, "Necron", 15_000L,
            "Relics", 3_000L, "Wither King", 1_000L, "Dragons", 20_000L)); // Stale "Wither King" key is ignored.
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        tracker.startRun(0L, 7, false);
        event(150_000L, "[BOSS] Storm: Pathetic Maxor, just like expected.");
        clock.set(160_000L);
        assertEquals(195_000L, tracker.predictedFinishMillis());
        clock.set(165_000L);
        assertEquals(200_000L, tracker.predictedFinishMillis());
        tracker.configureKnownFloor(7, true);
        assertEquals(239_000L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.PHASE_END);
        assertEquals(-1L, tracker.predictedFinishMillis()); // No Storm PB for the boundary-based mode.
    }

    @Test public void liveModeIncludesBannerWaitAndFreezesOnConfirmedCompletion() {
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        tracker.startRun(0L, 7, true);
        event(100_000L, "[BOSS] Wither King: We will decide it all, here, now.");
        clock.set(110_000L);
        assertEquals(110_000L, tracker.predictedFinishMillis());
        event(120_000L, "[BOSS] Wither King: Incredible. You did what I couldn't do myself.");
        clock.set(125_000L);
        assertEquals(125_000L, tracker.predictedFinishMillis());
        event(130_000L, "Team Score: 300 (S+)");
        clock.set(150_000L);
        assertEquals(130_000L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.PHASE_END);
        assertEquals(130_000L, tracker.predictedFinishMillis());
    }

    @Test public void predictionOffKeepsLearningAndStoredBestsForReenabling() {
        config.setEnabled(true);
        config.setTimePrediction(false);
        tracker.startRun(0L, 6, false);
        event(20_000L, BLOOD);
        tracker.stopRun();
        assertEquals(20_000L, config.personalBestMillis(6, false, "Blood Open"));
        config.setTimePrediction(true);
        assertEquals(20_000L, config.personalBestMillis(6, false, "Blood Open"));
    }

    @Test public void unknownAndLateFloorsNeverBorrowAnotherFloorsBests() {
        seed(6, false, 10_000L);
        seed(7, false, 20_000L);
        seed(7, true, 30_000L);
        tracker.startRun(0L, -1, false);
        assertEquals(-1L, tracker.predictedFinishMillis());
        event(15_000L, BLOOD);
        assertEquals(-1L, tracker.predictedFinishMillis());
        tracker.configureKnownFloor(7, false);
        assertEquals(155_000L, tracker.predictedFinishMillis());
        tracker.configureKnownFloor(7, true);
        assertEquals(285_000L, tracker.predictedFinishMillis());
        tracker.configureForFloor(7, false);
        assertEquals(285_000L, tracker.predictedFinishMillis());
        tracker.stopRun();
        tracker.startRun(0L, 6, false);
        assertEquals(60_000L, tracker.predictedFinishMillis());
    }

    @Test public void skippedBoundariesUseElapsedTotalsWithoutLearningUnknownSegments() {
        config.setEnabled(true);
        seed(6, false, 10_000L);
        tracker.startRun(0L, 6, false);
        event(3_000L, CLEAR); // Blood Open/Clear cannot be separated.
        assertEquals(43_000L, tracker.predictedFinishMillis());
        event(5_000L, SADAN);
        assertEquals(35_000L, tracker.predictedFinishMillis());
        tracker.stopRun();
        assertEquals(10_000L, config.personalBestMillis(6, false, "Blood Open"));
        assertEquals(10_000L, config.personalBestMillis(6, false, "Blood Clear"));
        assertEquals(2_000L, config.personalBestMillis(6, false, "Portal Entry"));
        assertEquals(10_000L, config.personalBestMillis(6, false, "Terracottas"));
        assertEquals(-1L, tracker.predictedFinishMillis());
    }

    @Test public void finishedRunLearnsEveryPhaseAndNextRunStartsWithTheirSum() {
        config.setEnabled(true);
        AtomicInteger writes = new AtomicInteger();
        config.onChange(writes::incrementAndGet);
        tracker.startRun(0L, 6, false);
        event(20_000L, BLOOD);
        event(50_000L, CLEAR);
        event(60_000L, SADAN);
        event(140_000L, "[BOSS] Sadan: ENOUGH!");
        event(160_000L, "[BOSS] Sadan: You did it. I understand now, you have earned my respect.");
        event(190_000L, "Defeated Sadan in 3m 10s");
        assertEquals(1, writes.get());
        assertEquals(20_000L, config.personalBestMillis(6, false, "Blood Open"));
        assertEquals(30_000L, config.personalBestMillis(6, false, "Blood Clear"));
        assertEquals(10_000L, config.personalBestMillis(6, false, "Portal Entry"));
        assertEquals(80_000L, config.personalBestMillis(6, false, "Terracottas"));
        assertEquals(20_000L, config.personalBestMillis(6, false, "Giants"));
        assertEquals(30_000L, config.personalBestMillis(6, false, "Sadan"));
        assertEquals(190_000L, tracker.predictedFinishMillis());
        event(200_000L, "Team Score: 300 (S+)");
        tracker.stopRun();
        assertEquals(190_000L, tracker.predictedFinishMillis());
        tracker.reset();
        assertEquals(-1L, tracker.predictedFinishMillis());
        tracker.startRun(0L, 6, false);
        assertEquals(190_000L, tracker.predictedFinishMillis());
        assertEquals(1, writes.get());
    }

    @Test public void lateMasterMetadataCommitsEarlyPhasesOnlyUnderMasterSeven() {
        config.setEnabled(true);
        tracker.startRun(0L, 7, false);
        event(20_000L, BLOOD);
        event(50_000L, CLEAR);
        event(60_000L, MAXOR);
        assertEquals(-1L, config.personalBestMillis(7, false, "Blood Open"));
        tracker.configureKnownFloor(7, true);
        tracker.stopRun();
        assertEquals(-1L, config.personalBestMillis(7, false, "Blood Open"));
        assertEquals(20_000L, config.personalBestMillis(7, true, "Blood Open"));
        assertEquals(30_000L, config.personalBestMillis(7, true, "Blood Clear"));
        assertEquals(10_000L, config.personalBestMillis(7, true, "Portal Entry"));
        assertEquals(-1L, config.personalBestMillis(7, true, "Maxor"));
    }

    @Test public void wipeResetAndTransferKeepOnlyAlreadyCompletedPhases() {
        for (int exit = 0; exit < 3; exit++) {
            tracker.reset();
            clock.set(0L);
            config.setEnabled(true);
            tracker.startRun(0L, exit + 1, false);
            event(20_000L, BLOOD);
            clock.set(25_000L);
            if (exit == 0) tracker.stopRun();
            else if (exit == 1) tracker.reset();
            else tracker.observeMessage("Team Score: 11 (D)", 0L);
            assertEquals(20_000L, config.personalBestMillis(exit + 1, false, "Blood Open"));
            assertEquals(-1L, config.personalBestMillis(exit + 1, false, "Blood Clear"));
        }
    }

    @Test public void scoreOnlyWipeInFinalPhaseCannotSetBossBest() {
        config.setEnabled(true);
        tracker.startRun(0L, 6, false);
        event(20_000L, BLOOD);
        event(100_000L, "[BOSS] Sadan: You did it. I understand now, you have earned my respect.");
        event(105_000L, "Team Score: 100 (D)");
        assertEquals(20_000L, config.personalBestMillis(6, false, "Blood Open"));
        assertEquals(-1L, config.personalBestMillis(6, false, "Sadan"));
        assertEquals(-1L, tracker.predictedFinishMillis());
    }

    @Test public void manualDebugDisabledTrackingAndUnknownFloorsCannotWriteRecords() {
        tracker.startRun(0L, 6, false);
        event(20_000L, BLOOD);
        tracker.stopRun();
        assertEquals(-1L, config.personalBestMillis(6, false, "Blood Open"));
        config.setEnabled(true);
        tracker.startRun(0L, 6, false);
        event(40_000L, BLOOD);
        tracker.mark("Portal Entry", 0L);
        tracker.stopRun();
        assertEquals(-1L, config.personalBestMillis(6, false, "Blood Open"));
        tracker.startRun(0L, -1, false);
        event(60_000L, BLOOD);
        tracker.stopRun();
        assertEquals(-1L, config.personalBestMillis(0, false, "Blood Open"));
        assertEquals(-1L, config.personalBestMillis(6, false, "Blood Open"));
    }

    @Test public void bossDeathPredictionFreezesThroughBannerWaitAndLateMasterModeResumesIt() {
        seed(7, false, 10_000L);
        seed(7, true, 20_000L);
        tracker.startRun(0L, 7, false);
        event(100_000L, "[BOSS] Necron: You went further than any human before, congratulations.");
        assertEquals(110_000L, tracker.predictedFinishMillis());
        event(115_000L, "[BOSS] Necron: All this, for nothing...");
        assertEquals(115_000L, tracker.predictedFinishMillis());
        clock.set(120_000L);
        assertEquals(115_000L, tracker.predictedFinishMillis());
        tracker.configureKnownFloor(7, true);
        assertEquals(155_000L, tracker.predictedFinishMillis());
        event(125_000L, "[BOSS] Wither King: You... again?"); // Dialogue inside Relics, not a boundary.
        assertEquals(155_000L, tracker.predictedFinishMillis());
        event(135_000L, "[BOSS] Wither King: We will decide it all, here, now.");
        assertEquals(155_000L, tracker.predictedFinishMillis());
        event(160_000L, "[BOSS] Wither King: Incredible. You did what I couldn't do myself.");
        assertEquals(160_000L, tracker.predictedFinishMillis());
        event(162_000L, "Team Score: 300 (S+)");
        assertEquals(162_000L, tracker.predictedFinishMillis());
    }

    @Test public void corruptHugeBestCannotOverflowIntoAValidPrediction() {
        seed(6, false, Long.MAX_VALUE);
        tracker.startRun(0L, 6, false);
        assertEquals(-1L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        assertEquals(-1L, tracker.predictedFinishMillis());
    }

    @Test public void manualEditsRefreshBothPredictionModesWithoutMovingTheRunClocks() {
        seed(6, false, 10_000L);
        tracker.startRun(0L, 6, false);
        clock.set(5_000L);
        var timings = tracker.currentTimings();
        config.setPersonalBestMillis(6, false, "Sadan", 20_000L);
        tracker.personalBestEdited(6, false, "Sadan");
        assertEquals(70_000L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        assertEquals(65_000L, tracker.predictedFinishMillis());
        config.clearPersonalBest(6, false, "Sadan");
        tracker.personalBestEdited(6, false, "Sadan");
        assertEquals(-1L, tracker.predictedFinishMillis());
        config.setPredictionMode(SplitsConfig.PredictionMode.PHASE_END);
        assertEquals(-1L, tracker.predictedFinishMillis());
        assertEquals(timings, tracker.currentTimings());
    }

    @Test public void explicitEditsWinOverBufferedMeasurementsButFutureRunsStillLearn() {
        config.setEnabled(true);
        tracker.startRun(0L, 6, false);
        event(20_000L, BLOOD);
        event(30_000L, CLEAR);
        config.setPersonalBestMillis(6, false, "Blood Open", 40_000L);
        tracker.personalBestEdited(6, false, "Blood Open");
        config.setPersonalBestMillis(6, false, "Blood Clear", 50_000L);
        config.clearPersonalBest(6, false, "Blood Clear");
        tracker.personalBestEdited(6, false, "Blood Clear");
        tracker.stopRun();
        assertEquals(40_000L, config.personalBestMillis(6, false, "Blood Open"));
        assertEquals(-1L, config.personalBestMillis(6, false, "Blood Clear"));
        tracker.startRun(0L, 6, false);
        event(35_000L, BLOOD);
        event(37_000L, CLEAR);
        // Editing another floor must not discard this run's measurements.
        config.setPersonalBestMillis(6, true, "Blood Open", 50_000L);
        tracker.personalBestEdited(6, true, "Blood Open");
        tracker.stopRun();
        assertEquals(5_000L, config.personalBestMillis(6, false, "Blood Open"));
        assertEquals(2_000L, config.personalBestMillis(6, false, "Blood Clear"));
        assertEquals(50_000L, config.personalBestMillis(6, true, "Blood Open"));
    }

    @Test public void editsBetweenScoreAndVictoryCannotBeUndoneByThePendingFinalSample() {
        for (boolean clear : new boolean[] {false, true}) {
            tracker.reset();
            clock.set(0L);
            config.setEnabled(true);
            tracker.startRun(0L, 1, false);
            event(5_000L, "[BOSS] Bonzo: Oh I'm dead!");
            event(8_000L, "Team Score: 177 (B)");
            config.setPersonalBestMillis(1, false, "Bonzo Phase 2", 20_000L);
            if (clear) config.clearPersonalBest(1, false, "Bonzo Phase 2");
            tracker.personalBestEdited(1, false, "Bonzo Phase 2");
            var timings = tracker.currentTimings();
            event(8_003L, "Defeated Bonzo in 8s");
            assertEquals(clear ? -1L : 20_000L, config.personalBestMillis(1, false, "Bonzo Phase 2"));
            assertEquals(8_000L, tracker.predictedFinishMillis());
            assertEquals(timings, tracker.currentTimings());
        }
    }
}
