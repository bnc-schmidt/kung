package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class DungeonBestRunTest extends MinecraftBootstrapTest {
    private final AtomicLong clock = new AtomicLong();
    private final SplitsConfig config = new SplitsConfig();
    private final List<DungeonSplitMessages.Notice> sent = new ArrayList<>();
    private final DungeonSplitTracker tracker = new DungeonSplitTracker(clock::get, ignored -> { }, config,
        ignored -> { }, sent::addAll);

    private void after(long millis, String message) {
        after(tracker, millis, message);
    }

    private void after(DungeonSplitTracker run, long millis, String message) {
        clock.addAndGet(millis);
        run.observeMessage(message, 0L);
    }

    /** A finished F5 run: Blood Open, Blood Clear, Portal Entry and Livid take the given times. */
    private void lividRun(long bloodOpen, long bloodClear, long portal, long boss) {
        lividRun(tracker, bloodOpen, bloodClear, portal, boss);
    }

    private void lividRun(DungeonSplitTracker run, long bloodOpen, long bloodClear, long portal, long boss) {
        run.startRun(0L, 5, false);
        after(run, bloodOpen, "The BLOOD DOOR has been opened!");
        after(run, bloodClear, "[BOSS] The Watcher: You have proven yourself. You may pass.");
        after(run, portal, "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.");
        after(run, boss, "Defeated Livid in 1m 30s");
    }

    private List<String> sentTexts() {
        return sent.stream().map(DungeonSplitMessages.Notice::text).toList();
    }

    @Test
    public void theFirstFinishedRunBecomesTheBestRunAndIsAnnounced() {
        config.setEnabled(true);
        lividRun(10_000L, 30_000L, 10_000L, 40_000L);
        var best = config.bestRun(5, false);
        assertEquals(10_000L, (long) best.get("Blood Open"));
        assertEquals(40_000L, (long) best.get("Livid"));
        assertEquals(50_000L, (long) best.get(SplitsConfig.BEST_RUN_BOSS_ENTRY));
        assertEquals(90_000L, (long) best.get(SplitsConfig.BEST_RUN_TOTAL));
        assertEquals(1, sent.size());
        assertTrue(sentTexts().getFirst(), sentTexts().getFirst().contains("new PB run recorded: 1m 30.00s (first recorded run)"));
    }

    @Test
    public void aSlowerRunKeepsTheBestRunAndSaysNothing() {
        config.setEnabled(true);
        lividRun(10_000L, 30_000L, 10_000L, 40_000L);
        sent.clear();
        lividRun(11_000L, 30_000L, 10_000L, 40_000L);
        assertEquals(90_000L, config.bestRunMillis(5, false));
        assertEquals(List.of(), sent);
    }

    @Test
    public void aFasterRunReplacesItAndTheFinishedRunStillComparesWithTheOneItBeat() {
        config.setEnabled(true);
        lividRun(10_000L, 30_000L, 10_000L, 40_000L);
        sent.clear();
        lividRun(9_000L, 28_000L, 10_000L, 33_000L);
        assertEquals(80_000L, config.bestRunMillis(5, false));
        assertTrue(sentTexts().getFirst(), sentTexts().getFirst().contains("1m 20.00s (Previous: 1m 30.00s)"));
        assertEquals(90_000L, (long) tracker.pbRun().get(SplitsConfig.BEST_RUN_TOTAL));
        tracker.startRun(0L, 5, false);
        assertEquals(80_000L, (long) tracker.pbRun().get(SplitsConfig.BEST_RUN_TOTAL));
    }

    @Test
    public void theSessionScopeComparesWithTheFastestRunSinceTheGameStarted() {
        var earlierGame = new SplitsConfig();
        earlierGame.setEnabled(true);
        lividRun(new DungeonSplitTracker(clock::get, ignored -> { }, earlierGame, ignored -> { }, sent::addAll),
            10_000L, 30_000L, 10_000L, 40_000L);
        assertEquals(90_000L, earlierGame.bestRunMillis(5, false));

        // A new game: what is saved comes back, the session's runs do not.
        var gson = new Gson();
        var game = gson.fromJson(gson.toJson(earlierGame), SplitsConfig.class);
        game.setPbScope(SplitsConfig.PbScope.SESSION);
        var run = new DungeonSplitTracker(clock::get, ignored -> { }, game, ignored -> { }, sent::addAll);
        assertEquals(90_000L, game.bestRunMillis(5, false));
        assertEquals(Map.of(), game.comparedRun(5, false));

        // Slower than the saved best, but the first of the session: it becomes the session's best, silently.
        sent.clear();
        lividRun(run, 10_000L, 30_000L, 10_000L, 50_000L);
        assertEquals(List.of(), sent);
        assertEquals(90_000L, game.bestRunMillis(5, false));
        assertEquals(100_000L, (long) game.comparedRun(5, false).get(SplitsConfig.BEST_RUN_TOTAL));
        assertEquals("nothing came before it in the session", Map.of(), run.pbRun());

        // Faster than the session's best: it compares with the one it beat.
        lividRun(run, 10_000L, 30_000L, 10_000L, 45_000L);
        assertEquals(100_000L, (long) run.pbRun().get(SplitsConfig.BEST_RUN_TOTAL));
        run.startRun(0L, 5, false);
        assertEquals(95_000L, (long) run.pbRun().get(SplitsConfig.BEST_RUN_TOTAL));

        // All Time shows the saved one again, and the session's runs never touched it.
        game.setPbScope(SplitsConfig.PbScope.ALL_TIME);
        assertEquals(90_000L, (long) run.pbRun().get(SplitsConfig.BEST_RUN_TOTAL));
        assertEquals(90_000L, game.bestRunMillis(5, false));
    }

    @Test
    public void theBestRunIsKeptPerFloorAndModeAndCanBeCleared() {
        config.setEnabled(true);
        lividRun(10_000L, 30_000L, 10_000L, 40_000L);
        assertEquals(-1L, config.bestRunMillis(5, true));
        assertEquals(-1L, config.bestRunMillis(6, false));
        config.clearBestRun(5, false);
        assertEquals(-1L, config.bestRunMillis(5, false));
        assertEquals(java.util.Map.of(), config.bestRun(5, false));
    }

    @Test
    public void withTheSettingOnEveryPastedLineEndsWithTheBestRunsTime() {
        config.setEnabled(true);
        config.setRunEndChat(true);
        lividRun(10_000L, 30_000L, 10_000L, 40_000L);
        sent.clear();
        config.setIncludePbRun(true);
        lividRun(12_000L, 30_000L, 10_000L, 50_000L);
        List<String> lines = sentTexts();
        assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Blood Open: 12.00s") && line.endsWith(" | 10.00s (+2.0s)")));
        assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Boss Entry: ") && line.endsWith(" | 50.00s (+2.0s)")));
        assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Total: ") && line.endsWith(" | 1m 30.00s (+12.0s)")));
    }

    @Test
    public void withTheSettingOffThePasteHasNoBestRunColumn() {
        config.setEnabled(true);
        config.setRunEndChat(true);
        lividRun(10_000L, 30_000L, 10_000L, 40_000L);
        sent.clear();
        lividRun(12_000L, 30_000L, 10_000L, 50_000L);
        assertFalse(sentTexts().toString(), sentTexts().stream().anyMatch(line -> line.contains(" | ")));
    }

    @Test
    public void aFasterSplitReadsMinusAndASlowerOnePlus() {
        assertEquals("+1.2s", DungeonSplitsOverlayFeature.formatDeltaMillis(1_240L));
        assertEquals("-0.4s", DungeonSplitsOverlayFeature.formatDeltaMillis(-440L));
        assertEquals("+1m 2.3s", DungeonSplitsOverlayFeature.formatDeltaMillis(62_300L));
        assertEquals("-12.0s", DungeonSplitsOverlayFeature.formatDeltaMillis(-12_000L));
        assertEquals("0.0s", DungeonSplitsOverlayFeature.formatDeltaMillis(30L));
    }

    @Test
    public void theDeltaBlendsFromGreenThroughYellowToRedByItsShareOfTheBestRunsTime() {
        // A tenth of the 10 s best time or more is the full color.
        assertEquals(0xFF55FF55, DungeonSplitsOverlayFeature.deltaColor(-5_000L, 10_000L));
        assertEquals(0xFF55FF55, DungeonSplitsOverlayFeature.deltaColor(-1_000L, 10_000L));
        assertEquals(0xFFFF5555, DungeonSplitsOverlayFeature.deltaColor(1_000L, 10_000L));
        assertEquals(0xFFFF5555, DungeonSplitsOverlayFeature.deltaColor(9_000L, 10_000L));
        // Halfway to the full loss: halfway from yellow to red. The same loss on a long row is milder.
        assertEquals(0xFFFFAA55, DungeonSplitsOverlayFeature.deltaColor(500L, 10_000L));
        assertEquals(0xFFFFEE55, DungeonSplitsOverlayFeature.deltaColor(1_000L, 100_000L));
        assertEquals(0xFFAAFF55, DungeonSplitsOverlayFeature.deltaColor(-500L, 10_000L));
        // Under 0.05 s it is "0.0s" in the muted color.
        assertEquals(DungeonSplitsOverlayFeature.MUTED, DungeonSplitsOverlayFeature.deltaColor(30L, 10_000L));
    }

    @Test
    public void aRunningPhaseShowsADeltaOnlyOnceItIsSlowerThanTheBestRun() {
        long none = DungeonSplitsOverlayFeature.NO_DELTA;
        assertEquals(none, DungeonSplitsOverlayFeature.deltaMillis(8_000L, 10_000L, false));
        assertEquals(500L, DungeonSplitsOverlayFeature.deltaMillis(10_500L, 10_000L, false));
        assertEquals(-2_000L, DungeonSplitsOverlayFeature.deltaMillis(8_000L, 10_000L, true));
        assertEquals(none, DungeonSplitsOverlayFeature.deltaMillis(-1L, 10_000L, true));
        assertEquals(none, DungeonSplitsOverlayFeature.deltaMillis(8_000L, -1L, true));
    }
}
