package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.CellKey;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.MatchRenderPlan;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DungeonScoreReadinessTest {
    private DungeonConfig previousConfig;
    private static final CellKey SPECIAL = new CellKey(3, 0);

    @Before public void isolateConfig() {
        previousConfig = KungConfig.get().dungeon;
        KungConfig.get().dungeon = new DungeonConfig();
    }

    @After public void restoreConfig() {
        KungConfig.get().dungeon = previousConfig;
    }

    private static DungeonRunStats readySecrets() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 34/36", null);
        stats.observeTabLine(null, "Opened Rooms: 35", null);
        stats.observeTabLine(null, "Crypts: 5", null);
        stats.observeStatLine(null, "Secrets: 35/40");
        stats.observeTabLine(null, "Secrets Found: 87.5%", null);
        stats.observeMessage(null, "[BOSS] The Watcher: You have proven yourself. You may pass.", 100L);
        return stats;
    }

    @Test public void discoveredPuzzleKeepsItsRoomAndTenSkillPointsOutstanding() {
        var stats = readySecrets();
        var open = plan(RoomType.PUZZLE, false, false);
        assertEquals(285, stats.score(open, 40));
        stats.observeTabLine(null, "Puzzles: (1)", null);
        stats.observeTabLine(null, "Water Board: [✦]", null);
        assertEquals(285, stats.score(open, 40)); // Map and tab are the same outstanding puzzle.
        stats.observeTabLine(null, "Water Board: [✔]", null);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        assertEquals(300, stats.score(open, 40)); // Tab can finish the puzzle before the map refresh.
        assertEquals(300, stats.score(plan(RoomType.PUZZLE, true, true), 40));
    }

    @Test public void tabTracksOpenPuzzlesEvenWhenTheirMapTypeIsNotKnown() {
        var stats = readySecrets();
        stats.observeTabLine(null, "Completed Rooms: 36/36", null);
        stats.observeTabLine(null, "Puzzles: (1)", null);
        stats.observeTabLine(null, "Blaze: [✦]", null);
        assertEquals(290, stats.score(plan(RoomType.UNKNOWN, true, true), 40));
        stats.observeTabLine(null, "Blaze: [✦]", null);
        stats.observeTabLine(null, "Team Deaths: 0", null);
        assertEquals(290, stats.score(plan(RoomType.UNKNOWN, true, true), 40));
        stats.observeTabLine(null, "Blaze: [✓]", null);
        assertEquals(300, stats.score(plan(RoomType.UNKNOWN, true, true), 40));
    }

    @Test public void allSecretsCannotHideTheOutstandingPuzzlePenalty() {
        var stats = readySecrets();
        stats.observeStatLine(null, "Secrets: 40/40");
        stats.observeTabLine(null, "Secrets Found: 100.0%", null);
        stats.observeTabLine(null, "Puzzles: (1)", null);
        stats.observeTabLine(null, "Water Board: [✦]", null);
        // Previously 302: all secrets and bonuses crossed 300 without the puzzle's ten-point penalty.
        assertEquals(292, stats.score(plan(RoomType.PUZZLE, false, false), 40));
        stats.observeTabLine(null, "Water Board: [✔]", null);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        assertEquals(307, stats.score(plan(RoomType.PUZZLE, true, true), 40));
    }

    @Test public void visitingYellowTrapOrNormalNeverCompletesTheirClear() {
        for (var type : List.of(RoomType.YELLOW, RoomType.TRAP, RoomType.NORMAL)) {
            var stats = readySecrets();
            assertEquals(type.name(), 295, stats.score(plan(type, false, false), 40));
            stats.observeTabLine(null, "Completed Rooms: 35/36", null);
            assertEquals(type.name(), 300, stats.score(plan(type, true, false), 40));
        }
    }

    @Test public void bloodAndBossProjectionCannotCoverAnOpenOrdinaryRoom() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 34/36", null);
        stats.observeTabLine(null, "Crypts: 5", null);
        stats.observeTabLine(null, "Secrets Found: 87.5%", null);
        assertEquals(295, stats.score(plan(RoomType.TRAP, false, false), 40));
        assertEquals(300, stats.score(plan(RoomType.TRAP, true, false), 40));
    }

    @Test public void aHighServerClearPercentageCannotBypassAnOpenPuzzle() {
        var stats = readySecrets();
        stats.observeScoreboardLine(null, "§aCleared: §f100% §7(300)");
        assertEquals(100.0, stats.clearedPercent(), 0.0);
        assertEquals(285, stats.score(plan(RoomType.PUZZLE, false, false), 40));
        stats.observeScoreboardLine(null, "Party > Alice: Cleared: 1% (100)");
        assertEquals(100.0, stats.clearedPercent(), 0.0);
    }

    @Test public void failedPuzzleIsChargedOnceAndDoesNotBecomeSolvedByARedMapCross() {
        var stats = readySecrets();
        stats.observeTabLine(null, "Puzzles: (1)", null);
        stats.observeTabLine(null, "Blaze: [✖] (Alice)", null);
        stats.observeTabLine(null, "Blaze: [✖] (Alice)", null);
        assertEquals(285, stats.score(plan(RoomType.PUZZLE, true, false), 40));
        stats.observeStatLine(null, "Team Score: 287 (S)");
        assertEquals(287, stats.score(plan(RoomType.PUZZLE, true, false), 40));
    }

    @Test public void roundedSidebarDenominatorDoesNotTreatTheScannedGridAsTheServerTotal() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 34", null);
        stats.observeTabLine(null, "Opened Rooms: 34", null);
        stats.observeTabLine(null, "Secrets Found: 87.5%", null);
        stats.observeTabLine(null, "Crypts: 5", null);
        stats.observeScoreboardLine(null, "Cleared: 97% (295)");
        stats.observeMessage(null, "[BOSS] The Watcher: You have proven yourself. You may pass.", 100L);
        assertEquals(300, stats.score(null, 40)); // 34/35 plus the boss, not an invented 36-cell total.
    }

    @Test public void completedRoomCounterAloneDoesNotInventAFullDungeon() {
        var stats = new DungeonRunStats();
        stats.observeTabLine(null, "Completed Rooms: 3", null);
        stats.observeTabLine(null, "Secrets Found: 87.5%", null);
        stats.observeTabLine(null, "Crypts: 5", null);
        assertTrue(stats.score(null, 40) < 300);
    }

    @Test public void mapFallbackCreditsWhiteClearedCellsIncludingEveryCellOfLargeRooms() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Secrets Found: 87.5%", null);
        stats.observeTabLine(null, "Crypts: 5", null);
        var base = plan(RoomType.NORMAL, true, false);
        var owners = new HashMap<>(base.roomOwners());
        for (int x = 0; x < 4; x++) owners.put(new CellKey(x, 1), "large-room");
        var large = new MatchRenderPlan(base.matches(), base.predictedRooms(), base.matchedRoomCells(), base.internalDoors(),
            base.externalDoors(), base.roomTypes(), Map.copyOf(owners), base.hints(), base.remoteRoomCells(),
            base.remoteRoomProgress(), base.visitedRooms(), base.clearedRooms(), base.completedRooms(),
            base.openedSpecialDoorCells(), base.fairyEntranceDoor(), base.bloodRushPath(), base.mapPuzzleNames());
        assertEquals(300, stats.score(large, 40));
        assertEquals(300, stats.score(large, 40));
        stats.reset();
        assertTrue(stats.score(null, 40) < 300);
    }

    /** M4 run 4 of 2026-11-01: 1 found showed 2.4% (41 or 42 secrets), 2 found showed 4.9% (only 41). */
    @Test public void secretTotalStaysInsideWhatTheShownPercentagesAllow() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.observeTabLine(null, "Secrets Found: 1", null);
        stats.observeTabLine(null, "Secrets Found: 2.4%", null);
        assertEquals(41, stats.bestSecretsAvailable(41)); // the room catalogue picks within the range
        assertEquals(42, stats.bestSecretsAvailable(42));
        assertEquals(42, stats.bestSecretsAvailable(0));
        stats.observeTabLine(null, "Secrets Found: 2", null);
        stats.observeTabLine(null, "Secrets Found: 4.9%", null);
        assertEquals(41, stats.bestSecretsAvailable(42)); // the catalogue is outside the range now
        assertEquals(41, stats.bestSecretsAvailable(0)); // the old vote ended 42-41 and kept the larger 42
    }

    /** M4 run 3 of 2026-11-01 really had 42: 1 found at 2.4%, 3 found at 7.1%, 34 found at 81.0%. */
    @Test public void laterReadingsOverrideAWrongRoomCatalogueTotal() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.observeTabLine(null, "Secrets Found: 1", null);
        stats.observeTabLine(null, "Secrets Found: 2.4%", null);
        assertEquals(41, stats.bestSecretsAvailable(41));
        stats.observeTabLine(null, "Secrets Found: 3", null);
        stats.observeTabLine(null, "Secrets Found: 7.1%", null);
        assertEquals(42, stats.bestSecretsAvailable(41));
        stats.observeTabLine(null, "Secrets Found: 34", null);
        stats.observeTabLine(null, "Secrets Found: 81.0%", null);
        assertEquals(42, stats.bestSecretsAvailable(41));
    }

    @Test public void aSecretReadingThatDisagreesOnlyCountsOnceItLasts() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.beginObservation(0L);
        stats.observeTabLine(null, "Secrets Found: 10", null);
        stats.observeTabLine(null, "Secrets Found: 25.0%", null);
        assertEquals(40, stats.bestSecretsAvailable(0));
        stats.beginObservation(10L);
        stats.observeTabLine(null, "Secrets Found: 27.0%", null); // found 10 fits 37 now: a tab update half applied
        assertEquals(40, stats.bestSecretsAvailable(0));
        stats.beginObservation(30L);
        stats.observeTabLine(null, "Secrets Found: 27.0%", null);
        assertEquals(37, stats.bestSecretsAvailable(0));
    }

    /** M4 run 4 of 2026-11-01 01:42:56-01:43:06: "Cleared: 100% (265)" was the score of five seconds earlier. */
    @Test public void staleSidebarScoreCannotPullTheClearedDisplayBelowTheEstimate() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.observeSkyblockerBonus(DungeonBonusContribution.PRINCE);
        stats.observeSkyblockerBonus(DungeonBonusContribution.BAT);
        stats.beginObservation(100L);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        stats.observeTabLine(null, "Crypts: 5", null);
        stats.observeTabLine(null, "Secrets Found: 33", null);
        stats.observeTabLine(null, "Secrets Found: 80.5%", null);
        stats.observeScoreboardLine(null, "Cleared: 97% (265)");
        stats.beginObservation(200L);
        stats.observeTabLine(null, "Completed Rooms: 36/36", null);
        stats.observeScoreboardLine(null, "Cleared: 100% (265)");
        // 100 + 60 + 32 + 100 + 7. The server shows its 10-second-old 265 for another few seconds.
        assertEquals(299, stats.score(null, 41));
        stats.beginObservation(400L);
        stats.observeScoreboardLine(null, "Cleared: 100% (298)"); // refreshed after the last room: the server wins
        assertEquals(298, stats.score(null, 41));
        stats.observeScoreboardLine(null, "Cleared: 100% (301)");
        assertEquals(301, stats.score(null, 41));
    }

    /** M4 run 3: the score and "Cleared: 100%" arrived in the same refresh, 301 against our 299. */
    @Test public void sidebarScoreThatArrivesWithTheLastRoomIsFresh() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.beginObservation(100L);
        stats.observeTabLine(null, "Completed Rooms: 35/36", null);
        stats.observeScoreboardLine(null, "Cleared: 97% (262)");
        stats.beginObservation(300L);
        stats.observeScoreboardLine(null, "Cleared: 100% (298)"); // lower than the estimate, fresh from the server
        stats.observeTabLine(null, "Completed Rooms: 36/36", null);
        assertEquals(298, stats.score(null, 42));
    }

    /**
     * M4 run 4 of 2026-11-01: sidebar refreshes at 90% (228) and 97% (258). {@code unseenBonus} adds the points of
     * players whose Bat/Prince reports Kung never gets; a negative value leaves the sidebar scores out.
     */
    private static DungeonRunStats runWithSidebarRefreshes(int unseenBonus) {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.observeTabLine(null, "Puzzles: (1)", null);
        stats.observeTabLine(null, "Blaze: [✦]", null);
        stats.observeTabLine(null, "Crypts: 3", null);
        stats.observeTabLine(null, "Secrets Found: 21", null);
        stats.observeTabLine(null, "Secrets Found: 51.2%", null);
        stats.observeScoreboardLine(null, "Cleared: 90%" + sidebarScore(228, unseenBonus));
        stats.observeTabLine(null, "Blaze: [✔]", null);
        stats.observeTabLine(null, "Crypts: 4", null);
        stats.observeTabLine(null, "Secrets Found: 23", null);
        stats.observeTabLine(null, "Secrets Found: 56.1%", null);
        stats.observeScoreboardLine(null, "Cleared: 97%" + sidebarScore(258, unseenBonus));
        return stats;
    }

    private static String sidebarScore(int score, int unseenBonus) {
        return unseenBonus < 0 ? "" : " (" + (score + unseenBonus) + ")";
    }

    @Test public void sidebarScoreThatMatchesOurBonusInfersNothing() {
        // Both refreshes sit 2 below the fitted running score, the usual rounding: no hidden bonus.
        assertEquals(runWithSidebarRefreshes(-1).score(null, 41), runWithSidebarRefreshes(0).score(null, 41));
    }

    @Test public void sidebarScoreAboveOurBonusReadingAfterReadingIsBonusWeNeverHeardOf() {
        int plain = runWithSidebarRefreshes(-1).score(null, 41);
        // Six points of someone else's Bat and Prince: both refreshes sit 4 over the fit, kept conservatively.
        assertEquals(plain + 4, runWithSidebarRefreshes(6).score(null, 41));
        assertEquals(plain + 12, runWithSidebarRefreshes(30).score(null, 41)); // capped
    }

    @Test public void aLaterBonusReportIsAbsorbedByWhatWeInferredNotAddedTwice() {
        var stats = runWithSidebarRefreshes(6);
        int inferred = stats.score(null, 41);
        stats.observeSkyblockerBonus(DungeonBonusContribution.BAT);
        assertEquals(inferred, stats.score(null, 41));
    }

    @Test public void anInferredBonusEndsWithThePuzzleStateItWasMeasuredIn() {
        var stats = runWithSidebarRefreshes(6);
        var plain = runWithSidebarRefreshes(-1);
        for (var run : List.of(stats, plain)) {
            run.observeTabLine(null, "Puzzles: (2)", null);
            run.observeTabLine(null, "Water Board: [✦]", null);
        }
        assertEquals(plain.score(null, 41), stats.score(null, 41));
    }

    @Test public void aSingleHighSidebarReadingIsNotEnough() {
        var stats = new DungeonRunStats();
        stats.configureForFloor(4, true);
        stats.observeTabLine(null, "Crypts: 3", null);
        stats.observeTabLine(null, "Secrets Found: 21", null);
        stats.observeTabLine(null, "Secrets Found: 51.2%", null);
        stats.observeScoreboardLine(null, "Cleared: 90% (240)");
        var plain = new DungeonRunStats();
        plain.configureForFloor(4, true);
        plain.observeTabLine(null, "Crypts: 3", null);
        plain.observeTabLine(null, "Secrets Found: 21", null);
        plain.observeTabLine(null, "Secrets Found: 51.2%", null);
        plain.observeScoreboardLine(null, "Cleared: 90%");
        assertEquals(plain.score(null, 41), stats.score(null, 41));
    }

    /** Floor 1 run of 2026-10-03 19:40-19:41: three deaths, tab and sidebar as logged by score-calc. */
    private static DungeonRunStats floorOneRun(int completedRooms, double clearedPercent, int secrets, double secretsPercent, int crypts) {
        var stats = new DungeonRunStats();
        stats.configureForFloor(1, false);
        stats.observeTabLine(null, "Completed Rooms: " + completedRooms, null);
        stats.observeTabLine(null, "Opened Rooms: " + completedRooms, null);
        stats.observeTabLine(null, "Secrets Found: " + secrets, null);
        stats.observeTabLine(null, "Secrets Found: " + secretsPercent + "%", null);
        stats.observeTabLine(null, "Crypts: " + crypts, null);
        stats.observeTabLine(null, "Team Deaths: 3", null);
        stats.observeMessage(null, "[BOSS] The Watcher: You have proven yourself. You may pass.", 100L);
        stats.observeScoreboardLine(null, "Cleared: " + clearedPercent + "%");
        return stats;
    }

    @Test public void serverCompletedRoomsAreNeverCappedBelowByUnfinishedMapCells() {
        var map = planWithOpenRooms(2); // the map kept two clear cells open for the whole run
        // 19:40:57, 17/20: Blood done, boss forecast. Matches the logged score=258 (skill 87, explore 71).
        assertEquals(258, floorOneRun(17, 85.0, 4, 12.9, 0).score(map, 31));
        // 19:40:58, 19/20: the old cap (20 - 2 open cells = 18) was below the 19 the server had counted.
        assertTrue(floorOneRun(19, 95.0, 4, 12.9, 0).score(map, 31) > 258);
        // 19:41:20, 20/20 at boss entry: reported 263 = 20+72-5 + 54+21 + 100 + 1 instead of the full room credit.
        // Estimate = 20+80-5 + 60+21 + 100 + 1; Kung assumes a Legendary Spirit pet (first death costs 1, not 2).
        assertEquals(277, floorOneRun(20, 100.0, 5, 16.1, 1).score(map, 31));
    }

    @Test public void sidebarScoreIsAuthoritativeOnceEveryRoomIsCredited() {
        var map = planWithOpenRooms(2);
        var stats = floorOneRun(20, 100.0, 5, 16.1, 1);
        // Hypixel: 20+80-6 (no Spirit pet, three deaths cost 2 each) + 60+21 + 100 + 1 = 276.
        stats.observeScoreboardLine(null, "§aCleared: §f100% §7(276)");
        assertEquals(276, stats.score(map, 31));
        stats.observeScoreboardLine(null, "Party > Alice: Cleared: 100% (999)");
        assertEquals(276, stats.score(map, 31));
        stats.observeStatLine(null, "Team Score: 280 (S)"); // the final result still wins
        assertEquals(280, stats.score(map, 31));
    }

    @Test public void sidebarScoreIsIgnoredWhileBloodOrBossCreditIsStillForecast() {
        var stats = floorOneRun(19, 95.0, 4, 12.9, 0);
        stats.observeScoreboardLine(null, "Cleared: 95% (264)"); // counts finished rooms only
        // Forecast 20/20 rooms: 20+80-5 + 60+17 + 100, not the sidebar's 264.
        assertEquals(272, stats.score(planWithOpenRooms(0), 31));
    }

    /** 22 map cells: Start, Blood, {@code open} unfinished ordinary cells and the rest cleared. */
    private static MatchRenderPlan planWithOpenRooms(int open) {
        Map<CellKey, String> owners = new HashMap<>();
        Map<CellKey, RoomType> types = new HashMap<>();
        Set<CellKey> cleared = new HashSet<>();
        for (int i = 0; i < 22; i++) {
            var cell = new CellKey(i % 6, i / 6);
            owners.put(cell, "room:" + i);
            types.put(cell, i == 0 ? RoomType.START : i == 1 ? RoomType.BLOOD : RoomType.NORMAL);
            if (i > 0 && i < 22 - open) cleared.add(cell);
        }
        return new MatchRenderPlan(List.of(), List.of(), Set.of(), Set.of(), Map.of(), Map.copyOf(types), Map.copyOf(owners),
            Map.of(), Set.of(), Map.of(), Set.copyOf(owners.keySet()), Set.copyOf(cleared), Set.of(),
            Set.of(), null, List.of(), Map.of());
    }

    private static MatchRenderPlan plan(RoomType specialType, boolean specialCleared, boolean specialCompleted) {
        Map<CellKey, String> owners = new HashMap<>();
        Map<CellKey, RoomType> types = new HashMap<>();
        Set<CellKey> cleared = new HashSet<>();
        Set<CellKey> completed = new HashSet<>();
        for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
            var cell = new CellKey(x, z);
            owners.put(cell, "room:" + x + "," + z);
            types.put(cell, RoomType.NORMAL);
            cleared.add(cell);
        }
        types.put(new CellKey(0, 0), RoomType.START);
        types.put(new CellKey(1, 0), RoomType.FAIRY);
        types.put(new CellKey(2, 0), RoomType.BLOOD);
        // The server automatically credits special entrance cells without ordinary checkmarks.
        cleared.remove(new CellKey(0, 0));
        cleared.remove(new CellKey(1, 0));
        types.put(SPECIAL, specialType);
        if (!specialCleared) cleared.remove(SPECIAL);
        if (specialCompleted) completed.add(SPECIAL);
        return new MatchRenderPlan(List.of(), List.of(), Set.of(), Set.of(), Map.of(), Map.copyOf(types), Map.copyOf(owners),
            Map.of(), Set.of(), Map.of(), Set.copyOf(owners.keySet()), Set.copyOf(cleared), Set.copyOf(completed),
            Set.of(), null, List.of(), Map.of());
    }
}
