package com.github.beng420.kung.config;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.SplitsConfig;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SplitPersonalBestsConfigTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void realConfigSaveAndReloadKeepFloorModeAndPhaseSeparate() throws Exception {
        var path = temporary.getRoot().toPath().resolve("kung.json");
        KungConfig config = new KungConfig(path);
        config.load();
        config.splits.setX(321);
        config.splits.setScale(150);
        config.splits.setTimePrediction(false);
        config.splits.setRunEndChat(true);
        config.splits.setPredictionMode(SplitsConfig.PredictionMode.LIVE);
        config.splits.recordRun(6, false, Map.of("Blood Open", 40_000L, "Blood Clear", 20_000L), Map.of());
        config.splits.recordRun(7, false, Map.of("Blood Open", 35_000L), Map.of());
        config.splits.recordRun(7, true, Map.of("Blood Open", 25_000L), Map.of());
        config.splits.recordRun(6, true, Map.of("Blood Open", 30_000L), Map.of());
        config.splits.recordRun(0, false, Map.of("Blood Open", 50_000L), Map.of());
        KungConfig restored = new KungConfig(path);
        restored.load();
        assertEquals(40_000L, restored.splits.personalBestMillis(6, false, "Blood Open"));
        assertEquals(20_000L, restored.splits.personalBestMillis(6, false, "Blood Clear"));
        assertEquals(35_000L, restored.splits.personalBestMillis(7, false, "Blood Open"));
        assertEquals(25_000L, restored.splits.personalBestMillis(7, true, "Blood Open"));
        assertEquals(30_000L, restored.splits.personalBestMillis(6, true, "Blood Open"));
        assertEquals(50_000L, restored.splits.personalBestMillis(0, false, "Blood Open"));
        assertEquals(-1L, restored.splits.personalBestMillis(1, false, "Blood Open"));
        assertEquals(-1L, restored.splits.personalBestMillis(7, true, "Blood Clear"));
        assertEquals(321, restored.splits.x());
        assertEquals(150, restored.splits.scale());
        assertFalse(restored.splits.timePrediction());
        assertTrue(restored.splits.runEndChat());
        assertEquals(SplitsConfig.PredictionMode.LIVE, restored.splits.predictionMode());
        assertFalse(restored.splits.enabled());
        assertTrue(Files.readString(path).contains("\"M7\""));
    }

    @Test public void onlyStrictImprovementsSaveOncePerBatch() {
        SplitsConfig config = new SplitsConfig();
        AtomicInteger writes = new AtomicInteger();
        config.onChange(writes::incrementAndGet);
        config.recordRun(6, false, Map.of("Blood Open", 30_000L, "Blood Clear", 20_000L), Map.of());
        config.recordRun(6, false, Map.of("Blood Open", 31_000L, "Blood Clear", 20_000L), Map.of());
        assertEquals(1, writes.get());
        config.recordRun(6, false, Map.of("Blood Open", 25_000L, "Blood Clear", 22_000L), Map.of());
        assertEquals(2, writes.get());
        assertEquals(25_000L, config.personalBestMillis(6, false, "Blood Open"));
        assertEquals(20_000L, config.personalBestMillis(6, false, "Blood Clear"));
    }

    @Test public void manualSaveAndClearPersistImmediatelyWithoutAClosingSave() throws Exception {
        var path = temporary.getRoot().toPath().resolve("edited-pbs.json");
        KungConfig config = new KungConfig(path);
        config.load();
        config.splits.recordRun(7, false, Map.of("Blood Open", 1_234L, "Necron", 12_345L), Map.of());
        config.splits.recordRun(7, true, Map.of("Blood Open", 2_345L), Map.of());
        config.splits.setPersonalBestMillis(7, false, "Blood Open", 83_456L);
        KungConfig restored = new KungConfig(path);
        restored.load();
        assertEquals(83_456L, restored.splits.personalBestMillis(7, false, "Blood Open"));
        assertEquals(2_345L, restored.splits.personalBestMillis(7, true, "Blood Open"));
        config.splits.clearPersonalBest(7, false, "Blood Open");
        restored.load();
        assertEquals(-1L, restored.splits.personalBestMillis(7, false, "Blood Open"));
        assertEquals(12_345L, restored.splits.personalBestMillis(7, false, "Necron"));
        config.splits.clearPersonalBest(7, false, "Necron");
        assertFalse(Files.readString(path).contains("\"F7\""));
        assertTrue(Files.readString(path).contains("\"M7\""));
        config.splits.setPersonalBestMillis(7, false, "Blood Open", 1L);
        restored.load();
        assertEquals(1L, restored.splits.personalBestMillis(7, false, "Blood Open"));
        assertFalse(restored.splits.enabled());
    }

    @Test public void invalidEditsAndRepeatedClearsCannotWriteOrCreateZeroRecords() {
        SplitsConfig config = new SplitsConfig();
        AtomicInteger writes = new AtomicInteger();
        config.onChange(writes::incrementAndGet);
        config.setPersonalBestMillis(-1, false, "Blood Open", 1L);
        config.setPersonalBestMillis(8, true, "Blood Open", 1L);
        config.setPersonalBestMillis(0, false, "Blood Open", 0L);
        config.setPersonalBestMillis(0, false, "Blood Open", -1L);
        config.setPersonalBestMillis(0, false, null, 1L);
        config.setPersonalBestMillis(0, false, " ", 1L);
        config.clearPersonalBest(0, false, "Blood Open");
        assertEquals(0, writes.get());
        config.setPersonalBestMillis(0, false, "Blood Open", 1L);
        config.setPersonalBestMillis(0, false, "Blood Open", 1L);
        assertEquals(1, writes.get());
        config.clearPersonalBest(0, false, "Blood Open");
        config.clearPersonalBest(0, false, "Blood Open");
        assertEquals(2, writes.get());
        assertEquals(-1L, config.personalBestMillis(0, false, "Blood Open"));
    }

    @Test public void missingOrUnknownPredictionSettingsPreservePhaseEndDefaults() {
        for (String json : new String[] {"{}", "{\"splitsOverlay\":{\"predictionMode\":null}}",
            "{\"splitsOverlay\":{\"predictionMode\":\"unknown\"}}"}) {
            var config = KungConfig.read(new StringReader(json)).splits;
            assertTrue(config.timePrediction());
            assertFalse(config.runEndChat());
            assertEquals(SplitsConfig.PredictionMode.PHASE_END, config.predictionMode());
            assertFalse(config.enabled());
        }
    }

    @Test public void oldNullAndInvalidMeasurementsDoNotBecomeRecords() {
        for (String json : new String[] {"{}", "{\"splitsOverlay\":{\"personalBests\":null}}",
            "{\"splitsOverlay\":{\"personalBests\":{\"F6\":null}}}",
            "{\"splitsOverlay\":{\"personalBests\":{\"F6\":{\"Blood Open\":null,\"Blood Clear\":0}}}}"}) {
            var config = KungConfig.read(new StringReader(json)).splits;
            assertEquals(-1L, config.personalBestMillis(6, false, "Blood Open"));
            assertEquals(-1L, config.personalBestMillis(6, false, "Blood Clear"));
            config.recordRun(-1, false, Map.of("Blood Open", 1L), Map.of());
            config.recordRun(8, true, Map.of("Blood Open", 1L), Map.of());
            config.recordRun(6, false, Map.of("Blood Open", -1L, "Blood Clear", 0L), Map.of());
            assertEquals(-1L, config.personalBestMillis(6, false, "Blood Open"));
            assertEquals(-1L, config.personalBestMillis(6, false, "Blood Clear"));
            config.recordRun(6, false, Map.of("Blood Open", 40_000L), Map.of());
            assertEquals(40_000L, config.personalBestMillis(6, false, "Blood Open"));
        }
    }
}
