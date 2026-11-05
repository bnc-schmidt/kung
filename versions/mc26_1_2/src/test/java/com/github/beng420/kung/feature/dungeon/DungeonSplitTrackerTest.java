package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.util.ServerTickSequence;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class DungeonSplitTrackerTest {
    @Test
    public void additionalBundlePingsBeforeACompleteStallCannotPrepayItsLostTime() {
        AtomicLong clock = new AtomicLong();
        var diagnostics = new ArrayList<String>();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get, diagnostics::add);
        tracker.startRun(0L, 7, true);
        ServerTickSequence sequence = new ServerTickSequence();
        for (int tick = 1; tick <= 60; tick++) {
            clock.set(tick * 50L);
            if (sequence.accept(-tick, false)) tracker.serverTick(clock.get());
            if (sequence.accept(1000 + tick, true)) tracker.serverTick(clock.get());
        }
        clock.set(6_000L); // No server progress for a full three seconds.
        assertEquals(3_000L, tracker.currentTotalServerDurationMillis());
        assertEquals(-1L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        tracker.mark("Blood Clear", 0L);
        assertEquals(3_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("phaseTicks=60 maxAppliedGapMs=3000")
            && line.contains("phaseStartTicks=0 totalTicks=60 boundary=\"manual:Blood Clear\"")));
    }

    @Test
    public void aKnownStreamCanStallFromTheStartOfTheRun() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.serverTick(clock.get()); // Observed in this instance's start room.
        tracker.startRun(0L, 7, true);
        clock.set(3_000L);
        tracker.mark("Blood Clear", 0L);
        assertEquals(0L, tracker.completedSplits().getFirst().serverSplitDurationMillis());
        assertEquals(3_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        tracker.reset();
        tracker.startRun(0L, 7, true);
        assertEquals(-1L, tracker.currentTotalServerDurationMillis());
    }

    @Test
    public void sixtySecondsAtTenTpsLosesThirtySecondsIncludingDeliveryBatches() {
        for (int batchSize : new int[] {1, 10, 100, 600}) {
            AtomicLong clock = new AtomicLong();
            DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
            ServerTickSequence sequence = new ServerTickSequence();
            tracker.startRun(0L, 7, true);
            for (int endTick = batchSize; endTick <= 600; endTick += batchSize) {
                clock.set(endTick * 100L);
                for (int tick = endTick - batchSize + 1; tick <= endTick; tick++) {
                    for (int id : new int[] {-tick, 0, -tick}) {
                        if (sequence.accept(id, false)) tracker.serverTick(clock.get());
                    }
                }
                assertEquals(-1L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
            }
            tracker.mark("Blood Clear", 0L);
            assertEquals(60_000L, tracker.completedSplits().getFirst().splitDurationMillis());
            assertEquals(30_000L, tracker.completedSplits().getFirst().serverSplitDurationMillis());
            assertEquals(30_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        }
    }

    @Test
    public void resumedPacketsAfterAStallOnlyCreditTheTicksThatActuallyArrive() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        advanceTicks(tracker, clock, 20, 1_000L);
        clock.set(4_000L);
        assertEquals(1_000L, tracker.currentTotalServerDurationMillis());
        clock.set(5_000L);
        for (int tick = 0; tick < 20; tick++) tracker.serverTick(clock.get());
        tracker.mark("Blood Clear", 0L);
        assertEquals(2_000L, tracker.completedSplits().getFirst().serverSplitDurationMillis());
        assertEquals(3_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        var settled = tracker.completedSplits().getFirst();
        for (int tick = 0; tick < 60; tick++) tracker.serverTick(clock.get());
        clock.set(8_000L);
        tracker.mark("Portal Entry", 0L);
        assertEquals(settled, tracker.completedSplits().getFirst());
        assertEquals(3_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void healthyTwentyTpsRemainsLosslessWhenDeliveryIsDelayedForThreeSeconds() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        advanceTicks(tracker, clock, 20, 1_000L);
        clock.set(4_000L);
        assertEquals(1_000L, tracker.currentTotalServerDurationMillis());
        for (int tick = 0; tick < 60; tick++) tracker.serverTick(clock.get());
        advanceTicks(tracker, clock, 1120, 60_000L);
        tracker.mark("Blood Clear", 0L);
        assertEquals(60_000L, tracker.completedSplits().getFirst().serverSplitDurationMillis());
        assertEquals(0L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void clockQueriesCannotChangeTheResultOrConsumePendingLoss() {
        for (boolean sample : new boolean[] {false, true}) {
            AtomicLong clock = new AtomicLong();
            DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
            tracker.startRun(0L, 7, true);
            for (int tick = 1; tick <= 60; tick++) {
                clock.set(tick * 50L);
                tracker.serverTick(clock.get());
                if (sample) tracker.currentTimings();
            }
            clock.set(6_000L);
            tracker.mark("Blood Clear", 0L);
            assertEquals(3_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        }
    }

    @Test
    public void zeroPingsAndRepeatedTicksCannotInflateHealthyOrSlowSplits() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        ServerTickSequence sequence = new ServerTickSequence();
        tracker.startRun(0L, 7, true);
        for (int tick = 1; tick <= 300; tick++) {
            // First phase: 200 ticks in 10 s; second phase: 100 ticks in 10 s.
            clock.set(tick <= 200 ? tick * 50L : 10_000L + (tick - 200) * 100L);
            for (int id : new int[] {-tick, 0, -tick, 0}) {
                if (sequence.accept(id, false)) tracker.serverTick(clock.get());
            }
            if (tick == 200) tracker.mark("Blood Clear", 0L);
        }
        tracker.mark("Portal Entry", 0L);
        var open = tracker.completedSplits().getFirst();
        var clear = tracker.completedSplits().getLast();
        assertEquals(10_000L, open.serverSplitDurationMillis());
        assertEquals(5_000L, clear.serverSplitDurationMillis());
        assertEquals(15_000L, clear.serverTotalDurationMillis());
        assertEquals(5_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void extraBundlePingsAreRejectedBeforeClockMeasurement() {
        AtomicLong clock = new AtomicLong();
        var diagnostics = new ArrayList<String>();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get, diagnostics::add);
        tracker.startRun(0L, 7, false);
        ServerTickSequence sequence = new ServerTickSequence();
        for (int tick = 1; tick <= 723; tick++) {
            clock.set(tick * 50L);
            if (sequence.accept(-tick, false)) tracker.serverTick(clock.get());
            if (tick <= 117 && sequence.accept(1000 + tick, true)) tracker.serverTick(clock.get());
        }
        var live = tracker.currentTimings();
        assertEquals(36_150L, live.splitMillis());
        assertEquals(36_150L, live.serverSplitMillis());
        assertEquals(live.totalMillis(), live.serverTotalMillis());
        tracker.observeMessage("The BLOOD DOOR has been opened!", 0L);
        var bloodOpen = tracker.completedSplits().getFirst();
        assertEquals(live.serverSplitMillis(), bloodOpen.serverSplitDurationMillis());
        assertEquals(live.serverTotalMillis(), bloodOpen.serverTotalDurationMillis());
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("phaseStartTicks=0 totalTicks=723")));
    }

    @Test
    public void acceptedTicksStayInTheirPhaseEvenWhenItsNominalTickTimeExceedsWallTime() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        advanceTicks(tracker, clock, 300, 10_000L);
        tracker.mark("Blood Clear", 0L);
        advanceTicks(tracker, clock, 100, 20_000L);
        tracker.mark("Portal Entry", 0L);
        assertEquals(15_000L, tracker.completedSplits().getFirst().serverSplitDurationMillis());
        assertEquals(5_000L, tracker.completedSplits().getLast().serverSplitDurationMillis());
        assertEquals(20_000L, tracker.currentTotalServerDurationMillis());
        assertEquals(0L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void catchUpInNextPhaseChangesTotalLossOnlyWhenThatPhaseSettles() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        advanceTicks(tracker, clock, 100, 10_000L);
        tracker.mark("Blood Clear", 0L);
        var open = tracker.completedSplits().getFirst();
        advanceTicks(tracker, clock, 300, 20_000L);
        var live = tracker.currentTimings();
        assertEquals(15_000L, live.serverSplitMillis());
        assertEquals(20_000L, live.serverTotalMillis());
        assertEquals(5_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        tracker.mark("Portal Entry", 0L);
        var clear = tracker.completedSplits().getLast();
        assertEquals(15_000L, clear.serverSplitDurationMillis());
        assertEquals(open, tracker.completedSplits().getFirst());
        assertEquals(0L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void delayedTickBatchRecoversWithinItsPhaseWithoutFabricatingLag() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        for (int batch = 1; batch <= 100; batch++) {
            clock.set(batch * 100L);
            tracker.serverTick(clock.get());
            tracker.serverTick(clock.get());
        }
        tracker.mark("Blood Clear", 0L);
        assertEquals(10_000L, tracker.completedSplits().getFirst().serverSplitDurationMillis());
        assertEquals(0L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void wipeFreezesCountedClocksAndNextRunHasNoTickCredit() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        advanceTicks(tracker, clock, 300, 10_000L);
        tracker.observeMessage("Team Score: 11 (D)", 0L);
        assertEquals(15_000L, tracker.stoppedCurrentSplit().serverSplitDurationMillis());
        assertEquals(15_000L, tracker.currentTotalServerDurationMillis());
        clock.set(30_000L);
        tracker.serverTick(clock.get());
        assertEquals(15_000L, tracker.currentTotalServerDurationMillis());
        tracker.startRun(0L, 1, false);
        assertEquals(-1L, tracker.currentTotalServerDurationMillis());
        advanceTicks(tracker, clock, 100, 40_000L);
        tracker.stopRun();
        assertEquals(5_000L, tracker.currentTotalServerDurationMillis());
        assertEquals(5_000L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    private static void advanceTicks(DungeonSplitTracker tracker, AtomicLong clock, int ticks, long endMillis) {
        long start = clock.get();
        for (int tick = 1; tick <= ticks; tick++) {
            clock.set(start + (endMillis - start) * tick / ticks);
            tracker.serverTick(clock.get());
            var timing = tracker.currentTimings();
            assertEquals(0L, timing.serverSplitMillis() % 50L);
            assertEquals(0L, timing.serverTotalMillis() % 50L);
        }
    }

    @Test
    public void missingTickStreamDoesNotLabelAllElapsedTimeAsLag() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        clock.set(10_000L);
        tracker.mark("Blood Clear", 0L);
        var bloodOpen = tracker.completedSplits().getLast();
        assertEquals(-1L, bloodOpen.serverSplitDurationMillis());
        assertEquals(-1L, bloodOpen.serverTotalDurationMillis());
        assertEquals("", DungeonSplitsOverlayFeature.lostTimeSuffix(bloodOpen.splitDurationMillis(), bloodOpen.serverSplitDurationMillis()));
        assertEquals(-1L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
        tracker.stopRun();
        assertEquals(-1L, DungeonSplitsOverlayFeature.settledTotalLostTimeMillis(tracker));
    }

    @Test
    public void maxorDialogueAtTwentyTpsAddsEqualWallAndServerTimeWithoutLoss() {
        AtomicLong clock = new AtomicLong();
        var diagnostics = new ArrayList<String>();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get, diagnostics::add);
        tracker.startRun(0L, 7, true);
        tracker.mark("Maxor", 0L);
        for (int tick = 1; tick <= 200; tick++) {
            clock.set(tick * 50L);
            tracker.serverTick(clock.get());
            if (tick == 100) {
                assertFalse(tracker.observeMessage("[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!", 0L));
                assertEquals("Maxor", tracker.currentSplitName());
            }
        }
        assertTrue(tracker.observeMessage("[BOSS] Storm: Pathetic Maxor, just like expected.", 0L));
        var maxor = tracker.completedSplits().getLast();
        assertEquals("Maxor", maxor.name());
        assertEquals(10_000L, maxor.splitDurationMillis());
        assertEquals(10_000L, maxor.serverSplitDurationMillis());
        assertEquals("", DungeonSplitsOverlayFeature.lostTimeSuffix(maxor.splitDurationMillis(), maxor.serverSplitDurationMillis()));
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("phase-end name=Maxor wallMs=10000 serverMs=10000")
            && line.contains("phaseTicks=200 maxAppliedGapMs=50")));
        clock.set(20_000L);
        assertEquals(maxor, tracker.completedSplits().getLast());
    }

    @Test
    public void aSlowTickStreamDuringDialogueStillCountsMeasuredLoss() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        tracker.mark("Maxor", 0L);
        for (int tick = 1; tick <= 100; tick++) {
            clock.set(tick * 100L);
            tracker.serverTick(clock.get());
        }
        tracker.observeMessage("[BOSS] Storm: Pathetic Maxor, just like expected.", 0L);
        var maxor = tracker.completedSplits().getLast();
        assertEquals("-5.0s", DungeonSplitsOverlayFeature.lostTimeSuffix(maxor.splitDurationMillis(), maxor.serverSplitDurationMillis()));
    }

    @Test
    public void startRoomFloorMetadataSurvivesTheTimerResetWhenCountdownHasNoFloor() {
        AtomicLong clock = new AtomicLong(1_000L);
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.configureForFloor(1, false);
        assertTrue(tracker.hasKnownFloor());
        assertFalse(tracker.started());
        assertTrue(Arrays.asList(tracker.splitNames()).contains("Bonzo Phase 1"));
        clock.set(20_000L);
        tracker.startRun(0L, 0, false);
        assertTrue(tracker.running());
        assertTrue(Arrays.asList(tracker.splitNames()).contains("Bonzo Phase 1"));
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Maxor"));
        assertEquals(0L, tracker.currentTotalDurationMillis());
    }

    @Test
    public void preparedFloorCanBeCorrectedWithoutCarryingMetadataFromAnEndedRun() {
        DungeonSplitTracker tracker = new DungeonSplitTracker();
        tracker.configureForFloor(7, true);
        tracker.configureForFloor(7, false);
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Dragons"));
        tracker.startRun(0L, 7, true);
        tracker.stopRun();
        tracker.startRun(0L, 0, false);
        assertFalse(tracker.hasKnownFloor());
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Dragons"));
        tracker.configureForFloor(1, false);
        tracker.reset();
        assertFalse(tracker.hasKnownFloor());
        assertFalse(tracker.started());
    }

    @Test
    public void followsBloodAndPortalMessages() {
        DungeonSplitTracker tracker = new DungeonSplitTracker();
        tracker.startRun(0L, 7, false);

        assertTrue(tracker.observeMessage("\u00a7cThe BLOOD DOOR has been opened!", 1L));
        assertEquals("Blood Clear", tracker.currentSplitName());
        assertFalse(tracker.observeMessage("The BLOOD DOOR has been opened!", 2L));
        assertTrue(tracker.observeMessage("[BOSS] The Watcher: You have proven yourself. You may pass.", 3L));
        assertEquals("Portal Entry", tracker.currentSplitName());
    }

    @Test
    public void finishesFromTheServerCompletionMessage() {
        DungeonSplitTracker tracker = new DungeonSplitTracker();
        tracker.startRun(0L, 7, false);

        assertTrue(tracker.observeMessage("S Defeated Necron in 05m 42s", 1L));
        assertFalse(tracker.running());
        assertTrue(tracker.completedSplits().isEmpty());
        assertEquals("Blood Open", tracker.stoppedCurrentSplit().name());
    }

    @Test
    public void soloWipeKeepsTheExactMeasuredBloodProgressAndFrozenMasterLayout() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        advanceTicks(tracker, clock, 1100, 55_000L);
        clock.set(57_600L);
        // Actual trace: a wipe before Blood prints the result score without any boss banner.
        assertTrue(tracker.observeMessage("Team Score: 11 (D)", 0L));
        var stopped = tracker.stoppedCurrentSplit();
        assertEquals("Blood Open", stopped.name());
        assertEquals(57_600L, stopped.splitDurationMillis());
        assertEquals(57_600L, stopped.totalDurationMillis());
        assertEquals(55_000L, stopped.serverSplitDurationMillis());
        assertEquals(55_000L, stopped.serverTotalDurationMillis());
        assertTrue(tracker.completedSplits().isEmpty());
        assertFalse(tracker.hasCurrentSplit());
        assertTrue(tracker.started());
        clock.set(120_000L);
        tracker.serverTick(0L);
        tracker.stopRun();
        tracker.configureForFloor(1, false);
        tracker.configureKnownFloor(0, false);
        assertFalse(tracker.observeMessage("Team Score: 11 (D)", 1L));
        assertFalse(tracker.observeMessage("[BOSS] Wither King: We will decide it all, here, now.", 1L));
        assertEquals(stopped, tracker.stoppedCurrentSplit());
        assertEquals(57_600L, tracker.currentTotalDurationMillis());
        assertEquals(55_000L, tracker.currentTotalServerDurationMillis());
        assertTrue(Arrays.asList(tracker.splitNames()).contains("Dragons"));
    }

    @Test
    public void endingMidPhasePreservesCompletedPhasesAndKeepsTheInterruptedSpanSeparate() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 1, false);
        clock.set(1_000L);
        tracker.observeMessage("The BLOOD DOOR has been opened!", 0L);
        var bloodOpen = tracker.completedSplits().getFirst();
        clock.set(5_000L);
        tracker.observeMessage("Team Score: 25 (D)", 0L);
        assertEquals(1, tracker.completedSplits().size());
        assertEquals(bloodOpen, tracker.completedSplits().getFirst());
        assertEquals("Blood Clear", tracker.stoppedCurrentSplit().name());
        assertEquals(4_000L, tracker.stoppedCurrentSplit().splitDurationMillis());
        assertEquals(5_000L, tracker.stoppedCurrentSplit().totalDurationMillis());
        tracker.configureKnownFloor(7, true);
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Dragons"));
        tracker.startRun(0L, 7, true);
        assertNull(tracker.stoppedCurrentSplit());
        assertTrue(tracker.completedSplits().isEmpty());
        assertTrue(Arrays.asList(tracker.splitNames()).contains("Dragons"));
    }

    @Test
    public void actualFinalPhaseCompletesWithoutAnInterruptedSnapshotAndRejectsRewardMetadata() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 1, false);
        clock.set(5_000L);
        tracker.observeMessage("[BOSS] Bonzo: Oh I'm dead!", 0L);
        clock.set(8_000L);
        tracker.observeMessage("Defeated Bonzo in 8s", 0L);
        assertEquals("Bonzo Phase 2", tracker.completedSplits().getLast().name());
        assertEquals(3_000L, tracker.completedSplits().getLast().splitDurationMillis());
        assertNull(tracker.stoppedCurrentSplit());
        tracker.configureForFloor(7, true);
        tracker.configureKnownFloor(0, false);
        assertTrue(Arrays.asList(tracker.splitNames()).contains("Bonzo Phase 2"));
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Dragons"));
        clock.set(20_000L);
        tracker.serverTick(0L);
        tracker.stopRun();
        assertEquals(8_000L, tracker.currentTotalDurationMillis());
        assertEquals(3_000L, tracker.completedSplits().getLast().splitDurationMillis());
    }

    @Test
    public void masterSevenContinuesThroughRelicsDialogueAndDragonsUntilCompletion() {
        AtomicLong clock = new AtomicLong(1_000L);
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        String[][] messages = {
            {"The BLOOD DOOR has been opened!", "Blood Clear"},
            {"[BOSS] The Watcher: You have proven yourself. You may pass.", "Portal Entry"},
            {"[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", "Maxor"},
            {"[BOSS] Storm: Pathetic Maxor, just like expected.", "Storm"},
            {"[BOSS] Goldor: Who dares trespass into my domain?", "Terminals"},
            {"The Core entrance is opening!", "Goldor"},
            {"[BOSS] Necron: You went further than any human before, congratulations.", "Necron"},
            {"[BOSS] Necron: All this, for nothing...", "Relics"},
            {"[BOSS] Wither King: We will decide it all, here, now.", "Dragons"}
        };
        for (String[] event : messages) {
            clock.addAndGet(2_000L);
            tracker.serverTick(clock.get());
            assertTrue(event[0], tracker.observeMessage(event[0], 0L));
            assertEquals(event[1], tracker.currentSplitName());
            assertTrue(tracker.hasCurrentSplit());
            assertTrue("Only the completion banner ends the run", tracker.running());
        }
        clock.addAndGet(2_000L);
        tracker.serverTick(clock.get());
        assertTrue(tracker.observeMessage("[BOSS] Wither King: Incredible. You did what I couldn't do myself.", 0L));
        assertFalse(tracker.hasCurrentSplit());
        assertTrue(tracker.running());
        assertEquals("Dragons", tracker.completedSplits().getLast().name());
        assertEquals(20_000L, tracker.completedSplits().getLast().totalDurationMillis());
        assertFalse(tracker.observeMessage("[BOSS] Wither King: Incredible. You did what I couldn't do myself.", 0L));
        clock.addAndGet(2_000L);
        assertTrue(tracker.observeMessage("\u00a7r\u00a7c☠ Defeated The Wither King in 06m 42s (NEW RECORD!)", 0L));
        assertFalse(tracker.running());
        assertEquals(10, tracker.completedSplits().size());
        assertEquals(22_000L, tracker.currentTotalDurationMillis());
        assertEquals(500L, tracker.currentTotalServerDurationMillis());
        for (DungeonSplitTracker.CompletedSplit split : tracker.completedSplits()) {
            assertEquals(2_000L, split.splitDurationMillis());
        }
        clock.addAndGet(30_000L);
        tracker.serverTick(clock.get());
        assertEquals(22_000L, tracker.currentTotalDurationMillis());
        assertEquals(500L, tracker.currentTotalServerDurationMillis());
    }

    @Test
    public void repeatedAndOutOfOrderChatCannotRewindOrResetTheTimer() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        assertTrue(tracker.observeMessage("The BLOOD DOOR has been opened!", 0L));
        clock.addAndGet(5_000L);
        assertFalse(tracker.observeMessage("The BLOOD DOOR has been opened!", 100L));
        assertFalse(tracker.observeMessage("Starting in 1 second.", 100L));
        assertTrue(tracker.observeMessage("[BOSS] The Watcher: You have proven yourself. You may pass.", 101L));
        assertFalse(tracker.observeMessage("The BLOOD DOOR has been opened!", 102L));
        assertEquals("Portal Entry", tracker.currentSplitName());
        assertEquals(2, tracker.completedSplits().size());
        assertEquals(5_000L, tracker.currentTotalDurationMillis());
    }

    @Test
    public void delayedFloorMetadataAndMasterSevenDialoguePreserveProgress() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 0, false);
        assertTrue(tracker.observeMessage("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 1L));
        assertEquals("Maxor", tracker.currentSplitName());
        clock.set(5_000L);
        tracker.observeMessage("[BOSS] Necron: All this, for nothing...", 2L);
        assertFalse(tracker.hasCurrentSplit());
        clock.set(8_000L);
        // Wither King dialogue only reveals Master mode; Relics already began at Necron's death.
        assertFalse(tracker.observeMessage("[BOSS] Wither King: You... again?", 3L));
        assertEquals("Relics", tracker.currentSplitName());
        assertTrue(tracker.hasCurrentSplit());
        clock.set(11_000L);
        assertTrue(tracker.observeMessage("[BOSS] Wither King: We will decide it all, here, now.", 4L));
        assertEquals("Dragons", tracker.currentSplitName());
        assertEquals("Relics", tracker.completedSplits().getLast().name());
        assertEquals(6_000L, tracker.completedSplits().getLast().splitDurationMillis());
        tracker.configureForFloor(0, false);
        tracker.configureForFloor(7, false);
        assertTrue(Arrays.asList(tracker.splitNames()).contains("Dragons"));
        assertEquals("Dragons", tracker.currentSplitName());
    }

    @Test
    public void missingMessagesDoNotFabricateAccuratePhaseTimes() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, false);
        clock.set(3_000L);
        tracker.observeMessage("[BOSS] The Watcher: You have proven yourself. You may pass.", 1L);
        assertEquals(-1L, tracker.completedSplits().getFirst().splitDurationMillis());
        assertEquals(3_000L, tracker.completedSplits().getFirst().totalDurationMillis());
        clock.set(4_000L);
        tracker.observeMessage("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 2L);
        assertEquals(1_000L, tracker.completedSplits().getLast().splitDurationMillis());
    }

    @Test
    public void normalSevenEndsNecronAtCombatDeathWithoutAddingAVictoryPhase() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, false);
        clock.set(10_000L);
        tracker.observeMessage("[BOSS] Necron: You went further than any human before, congratulations.", 0L);
        clock.set(15_000L);
        tracker.serverTick(clock.get());
        tracker.observeMessage("[BOSS] Necron: All this, for nothing...", 1L);
        assertEquals("Necron", tracker.completedSplits().getLast().name());
        assertEquals(5_000L, tracker.completedSplits().getLast().splitDurationMillis());
        assertEquals(50L, tracker.completedSplits().getLast().serverSplitDurationMillis());
        assertEquals(15_000L, tracker.completedSplits().getLast().totalDurationMillis());
        assertFalse(tracker.hasCurrentSplit());
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Relics"));
        assertTrue(tracker.running());
        assertFalse(tracker.observeMessage("[BOSS] Necron: All this, for nothing...", 1L));
        assertFalse(tracker.observeMessage("[BOSS] Necron: You went further than any human before, congratulations.", 1L));
        clock.set(18_000L);
        tracker.serverTick(clock.get());
        assertTrue(tracker.observeMessage("Defeated Necron in 6m 1s", 2L));
        assertFalse(tracker.running());
        assertEquals(18_000L, tracker.currentTotalDurationMillis());
        assertEquals(100L, tracker.currentTotalServerDurationMillis());
        assertEquals(2, tracker.completedSplits().size());
        assertEquals(5_000L, tracker.completedSplits().getLast().splitDurationMillis());
    }

    @Test
    public void lateMasterMetadataStartsRelicsFromNecronsDeath() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, false);
        tracker.observeMessage("[BOSS] Necron: You went further than any human before, congratulations.", 0L);
        clock.set(5_000L);
        tracker.observeMessage("[BOSS] Necron: All this, for nothing...", 1L);
        clock.set(7_000L);
        tracker.configureForFloor(7, true);
        assertTrue(tracker.hasCurrentSplit());
        assertEquals("Relics", tracker.currentSplitName());
        assertEquals(2_000L, tracker.currentSplitDurationMillis());
        assertFalse(tracker.observeMessage("[BOSS] Necron: All this, for nothing...", 2L));
        assertEquals(2, tracker.completedSplits().size());
    }

    private static final String RELIC_PICKUP = "Tdogminifig picked the Corrupted Orange Relic!";
    private static final String NECRON_DEATH = "[BOSS] Necron: All this, for nothing...";
    private static final String KING_AGAIN = "[BOSS] Wither King: You... again?";
    private static final String KING_DECIDES = "[BOSS] Wither King: We will decide it all, here, now.";

    /** Master Seven in Necron; Necron began at 5s and the clock is at 5s. */
    private static DungeonSplitTracker necronTracker(AtomicLong clock, SplitsConfig config) {
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config);
        tracker.startRun(0L, 7, true);
        for (String message : new String[] {
            "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", "[BOSS] Storm: Pathetic Maxor, just like expected.",
            "[BOSS] Goldor: Who dares trespass into my domain?", "The Core entrance is opening!",
            "[BOSS] Necron: You went further than any human before, congratulations."}) {
            clock.addAndGet(1_000L);
            tracker.observeMessage(message, 0L);
        }
        assertEquals("Necron", tracker.currentSplitName());
        return tracker;
    }

    /** The last two completed splits are Necron then Relics, both measured (never -1). */
    private static void assertNecronAndRelics(DungeonSplitTracker tracker, long necron, long relics) {
        var completed = tracker.completedSplits();
        var necronSplit = completed.get(completed.size() - 2);
        var relicsSplit = completed.getLast();
        assertEquals("Necron", necronSplit.name());
        assertEquals(necron, necronSplit.splitDurationMillis());
        assertEquals("Relics", relicsSplit.name());
        assertEquals(relics, relicsSplit.splitDurationMillis());
    }

    @Test
    public void relicPickupStartsRelicsAndLaterPickupsChangeNothing() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = necronTracker(clock, new SplitsConfig());
        clock.addAndGet(4_000L);
        assertTrue(tracker.observeMessage(RELIC_PICKUP, 0L));
        assertEquals("Relics", tracker.currentSplitName());
        assertEquals("Necron", tracker.completedSplits().getLast().name());
        assertEquals(4_000L, tracker.completedSplits().getLast().splitDurationMillis());
        clock.addAndGet(1_000L);
        assertFalse(tracker.observeMessage("[MVP+] Ben picked the Corrupted Red Relic!", 0L));
        assertFalse(tracker.observeMessage("Party > Ben: x picked the Corrupted Red Relic!", 0L));
        assertEquals("Relics", tracker.currentSplitName());
        clock.addAndGet(2_000L);
        tracker.dragonSpawned();
        assertEquals("Dragons", tracker.currentSplitName());
        assertEquals(3_000L, tracker.completedSplits().getLast().splitDurationMillis());
    }

    @Test
    public void relicPickupCannotMoveANormalSevenRun() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, false);
        tracker.observeMessage("[BOSS] Necron: You went further than any human before, congratulations.", 0L);
        assertFalse(tracker.observeMessage(RELIC_PICKUP, 0L));
        assertEquals("Necron", tracker.currentSplitName());
    }

    @Test
    public void everyRelicsStartBoundaryLeavesNecronAndRelicsMeasured() {
        for (String start : new String[] {NECRON_DEATH, RELIC_PICKUP, KING_AGAIN}) {
            AtomicLong clock = new AtomicLong();
            DungeonSplitTracker tracker = necronTracker(clock, new SplitsConfig());
            clock.addAndGet(4_000L);
            assertTrue(start, tracker.observeMessage(start, 0L));
            assertEquals(start, "Relics", tracker.currentSplitName());
            clock.addAndGet(6_000L);
            assertTrue(start, tracker.observeMessage(KING_DECIDES, 0L));
            assertEquals(start, "Dragons", tracker.currentSplitName());
            assertNecronAndRelics(tracker, 4_000L, 6_000L);
            // Later Wither King lines are plain dialogue, never a boundary.
            assertFalse(tracker.observeMessage(KING_AGAIN, 0L));
            assertFalse(tracker.observeMessage(
                "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you.", 0L));
            assertEquals("Dragons", tracker.currentSplitName());
        }
    }

    @Test
    public void firstDragonSpawnStartsDragonsFromRelicsWithoutMarkingTheRunManual() {
        SplitsConfig config = new SplitsConfig();
        config.setEnabled(true);
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = necronTracker(clock, config);
        clock.addAndGet(4_000L);
        tracker.observeMessage(RELIC_PICKUP, 0L);
        clock.addAndGet(3_000L);
        tracker.dragonSpawned();
        assertEquals("Dragons", tracker.currentSplitName());
        assertNecronAndRelics(tracker, 4_000L, 3_000L);
        int completed = tracker.completedSplits().size();
        clock.addAndGet(1_400L);
        // The chat line trails the spawn and the other dragons spawn later: all of that is a no-op now.
        assertFalse(tracker.observeMessage(KING_DECIDES, 0L));
        tracker.dragonSpawned();
        assertEquals(completed, tracker.completedSplits().size());
        clock.addAndGet(18_600L);
        tracker.observeMessage("[BOSS] Wither King: Incredible. You did what I couldn't do myself.", 0L);
        clock.addAndGet(2_000L);
        tracker.observeMessage("Team Score: 300 (S+)", 0L);
        // mark()/markIfCurrent() would have made this a manual run, which never saves personal bests.
        assertEquals(4_000L, config.personalBestMillis(7, true, "Necron"));
        assertEquals(3_000L, config.personalBestMillis(7, true, "Relics"));
        assertEquals(20_000L, config.personalBestMillis(7, true, "Dragons"));
    }

    @Test
    public void dragonSpawnIsNoOpOutsideRelicsAndNecron() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker idle = new DungeonSplitTracker(clock::get);
        idle.dragonSpawned();
        assertFalse(idle.started());
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        tracker.dragonSpawned();
        assertEquals("Blood Open", tracker.currentSplitName());
        tracker.observeMessage("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 0L);
        tracker.dragonSpawned();
        assertEquals("Maxor", tracker.currentSplitName());
        DungeonSplitTracker normal = new DungeonSplitTracker(clock::get);
        normal.startRun(0L, 7, false);
        normal.observeMessage("[BOSS] Necron: You went further than any human before, congratulations.", 0L);
        normal.dragonSpawned();
        assertEquals("Necron", normal.currentSplitName());
    }

    @Test
    public void dragonsNeverFollowNecronWithAnUnknownRelicsSpan() {
        for (boolean spawn : new boolean[] {false, true}) {
            AtomicLong clock = new AtomicLong();
            DungeonSplitTracker tracker = necronTracker(clock, new SplitsConfig());
            clock.addAndGet(9_000L);
            if (spawn) tracker.dragonSpawned();
            else assertTrue(tracker.observeMessage(KING_DECIDES, 0L));
            assertEquals("Dragons", tracker.currentSplitName());
            assertNecronAndRelics(tracker, 9_000L, 0L);
        }
    }

    @Test
    public void necronEndWindowCoversNecronAndRelicsOnly() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = necronTracker(clock, new SplitsConfig());
        assertTrue(tracker.inNecronEndWindow());
        tracker.observeMessage(RELIC_PICKUP, 0L);
        assertTrue(tracker.inNecronEndWindow());
        tracker.dragonSpawned();
        assertFalse(tracker.inNecronEndWindow());
        DungeonSplitTracker earlier = new DungeonSplitTracker(clock::get);
        earlier.startRun(0L, 7, true);
        assertFalse(earlier.inNecronEndWindow());
    }

    @Test
    public void allFloorLayoutsContainOnlyNamedRunPhases() {
        for (int floor = 1; floor <= 7; floor++) {
            for (boolean master : new boolean[] {false, true}) {
                DungeonSplitTracker tracker = new DungeonSplitTracker();
                tracker.startRun(0L, floor, master);
                assertFalse(Arrays.asList(tracker.splitNames()).contains("Victory"));
            }
        }
        assertFalse(Arrays.asList(DungeonSplitTracker.defaultSplitNames()).contains("Victory"));
    }

    @Test
    public void lowerFloorsNameTheActualFinalBossPhase() {
        DungeonSplitTracker tracker = new DungeonSplitTracker();
        tracker.startRun(0L, 6, true);
        tracker.observeMessage("[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!", 1L);
        assertEquals("Terracottas", tracker.currentSplitName());
        tracker.observeMessage("[BOSS] Sadan: ENOUGH!", 2L);
        assertEquals("Giants", tracker.currentSplitName());
        tracker.observeMessage("[BOSS] Sadan: You did it. I understand now, you have earned my respect.", 3L);
        assertEquals("Sadan", tracker.currentSplitName());
        assertTrue(tracker.running());
    }

    @Test
    public void abortFreezesTimeAndNewRunResetsMasterModeAndResults() {
        AtomicLong clock = new AtomicLong();
        DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get);
        tracker.startRun(0L, 7, true);
        clock.set(10_000L);
        tracker.stopRun();
        clock.set(20_000L);
        assertEquals(10_000L, tracker.currentTotalDurationMillis());
        assertTrue(tracker.completedSplits().isEmpty());
        tracker.startRun(100L, 7, false);
        assertFalse(Arrays.asList(tracker.splitNames()).contains("Dragons"));
        assertEquals(0L, tracker.currentTotalDurationMillis());
    }

    @Test
    public void splitFormattingKeepsMillisecondsAndMarksMissingValues() {
        assertEquals("--", DungeonSplitsOverlayFeature.formatDurationMillis(-1L, SplitsConfig.TimeFormat.MINUTES));
        assertEquals("0.00s", DungeonSplitsOverlayFeature.formatDurationMillis(0L, SplitsConfig.TimeFormat.MINUTES));
        assertEquals("1.03s", DungeonSplitsOverlayFeature.formatDurationMillis(1_039L, SplitsConfig.TimeFormat.MINUTES));
        assertEquals("2m 5.67s", DungeonSplitsOverlayFeature.formatDurationMillis(125_678L, SplitsConfig.TimeFormat.MINUTES));
    }
}
