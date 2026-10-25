package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.config.category.SplitsConfig.TimeFormat;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonStats;
import com.github.beng420.kung.util.CatacombsAverageCalculator.FloorStats;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DungeonPbTitleTest {
    private static final String F7 = "☠ Defeated Maxor, Storm, Goldor, and Necron in 06m 31s";

    @Test
    public void readsTheRunTimeAndTheRecordMarker() {
        var plain = DungeonLifecycleSignals.parseFloorFinish(F7);
        assertEquals(391_000L, plain.millis());
        assertFalse(plain.newRecord());
        assertEquals("Maxor, Storm, Goldor, and Necron", plain.boss());
        assertTrue(DungeonLifecycleSignals.parseFloorFinish(F7 + " (NEW RECORD!)").newRecord());
        assertEquals(3_723_000L, DungeonLifecycleSignals.parseFloorFinish("Defeated Sadan in 1h 02m 03s").millis());
        assertEquals(45_000L, DungeonLifecycleSignals.parseFloorFinish("§a☠ Defeated The Watcher in 00m 45s (NEW RECORD!)").millis());
        assertNull(DungeonLifecycleSignals.parseFloorFinish("Team Score: 301 (S+)"));
        assertNull(DungeonLifecycleSignals.parseFloorFinish("Defeated Sadan in a while"));
        assertNull(DungeonLifecycleSignals.parseFloorFinish(null));
    }

    @Test
    public void aNewRecordIsRememberedOnlyForTheFloorOfTheRun() {
        var settings = new SplitsConfig();
        DungeonPbTitle.floorFinish(F7, 7, false, settings); // no marker: nothing learned
        assertEquals(-1L, settings.floorRecordMillis(7, false));
        DungeonPbTitle.floorFinish(F7 + " (NEW RECORD!)", 6, false, settings); // Necron line on F6: not this run's boss
        assertEquals(-1L, settings.floorRecordMillis(6, false));
        DungeonPbTitle.floorFinish(F7 + " (NEW RECORD!)", -1, false, settings); // floor unknown
        DungeonPbTitle.floorFinish(F7 + " (NEW RECORD!)", 7, false, settings);
        assertEquals(391_000L, settings.floorRecordMillis(7, false));
        assertEquals(-1L, settings.floorRecordMillis(7, true));
        // A faster record replaces it, a slower time (the API's S and S+ tiers differ) never does.
        DungeonPbTitle.floorFinish("☠ Defeated Maxor, Storm, Goldor, and Necron in 06m 13s (NEW RECORD!)", 7, false, settings);
        assertEquals(373_000L, settings.floorRecordMillis(7, false));
        DungeonPbTitle.floorFinish(F7 + " (NEW RECORD!)", 7, false, settings);
        assertEquals(373_000L, settings.floorRecordMillis(7, false));
    }

    @Test
    public void theSubtitleNamesTheSectionAndShowsOldAndNewTime() {
        assertEquals("Blood Open: 27.37s → 25.93s",
            DungeonPbTitle.subtitle("Blood Open", 27_370L, 25_930L, TimeFormat.MINUTES).getString());
        assertEquals("F7: 6m 31.00s → 6m 13.00s",
            DungeonPbTitle.subtitle(DungeonPbTitle.floorLabel(7, false), 391_000L, 373_000L, TimeFormat.MINUTES).getString());
        assertEquals("M7: 6m 13.00s", DungeonPbTitle.subtitle("M7", -1L, 373_000L, TimeFormat.MINUTES).getString());
        assertEquals("Entrance", DungeonPbTitle.floorLabel(0, false));
    }

    @Test
    public void everyPbPathIsQuietWhenTheToggleIsOff() {
        var settings = new SplitsConfig();
        settings.setPbTitle(false);
        var first = new DungeonSplitTracker.PhaseMessage(7, false, "Maxor", 30_000L, -1L, true, TimeFormat.MINUTES);
        var better = new DungeonSplitTracker.PhaseMessage(7, false, "Maxor", 29_000L, 30_000L, true, TimeFormat.MINUTES);
        // With the toggle on and no game running there is no screen to show it on, and nothing may break.
        DungeonPbTitle.phase(better, settings);
        settings.setPbTitle(true);
        DungeonPbTitle.phase(first, settings);
        DungeonPbTitle.phase(better, settings);
        DungeonPbTitle.floorFinish(F7 + " (NEW RECORD!)", 7, false, settings);
        assertEquals(391_000L, settings.floorRecordMillis(7, false));
    }

    @Test
    public void theApiGivesTheFasterOfTheSAndSPlusTimes() {
        var catacombs = new FloorStats(Map.of(), Map.of(), Map.of("7", 373_000, "1", 90_000), Map.of("7", 380_000, "2", 100_000));
        var master = new FloorStats(Map.of(), Map.of(), Map.of("7", 400_000), Map.of());
        List<DungeonPbTitle.Record> records = DungeonPbTitle.recordsFrom(
            new DungeonStats(true, null, 0, 0, 0, catacombs, master, 0));
        assertEquals(List.of(
            new DungeonPbTitle.Record(1, false, 90_000L),
            new DungeonPbTitle.Record(2, false, 100_000L),
            new DungeonPbTitle.Record(7, false, 373_000L),
            new DungeonPbTitle.Record(7, true, 400_000L)), records);
        assertTrue(DungeonPbTitle.recordsFrom(new DungeonStats(false, null, 0, 0, 0, catacombs, master, 0)).isEmpty());
        assertTrue(DungeonPbTitle.recordsFrom(null).isEmpty());
    }
}
