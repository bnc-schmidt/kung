package com.github.beng420.kung.config;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.MiscConfig;
import java.io.StringReader;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BowDrawThresholdSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultsNormalizationAndEmptyListsPreserveUserChoices() {
        assertEquals(List.of(3, 5, 8), ticks(KungConfig.read(new StringReader("{}")).misc));
        assertEquals(List.of(3, 5, 8), ticks(KungConfig.read(new StringReader("{\"misc\":{\"bowDrawThresholds\":null}}")).misc));
        assertEquals(List.of(), ticks(KungConfig.read(new StringReader("{\"misc\":{\"bowDrawThresholds\":[]}}")).misc));
        var config = KungConfig.read(new StringReader("""
            {"misc":{"bowDrawThresholds":[null, {"ticks":-4}, {"ticks":99}, {"ticks":7}]}}
            """));
        assertEquals(List.of(0, 99, 7), ticks(config.misc));
        // Thresholds saved before colors existed keep white lines.
        assertEquals(0xFFFFFFFF, config.misc.bowDrawThresholds().getLast().color());
        assertFalse(config.misc.bowDrawIndicatorEnabled());
        for (int index = 0; index < 30; index++) config.misc.addBowDrawThreshold();
        assertEquals(20, config.misc.bowDrawThresholds().size());
    }

    @Test
    public void listControlsEditAndRemoveTheOwnedRowAndPersistWithoutReopening() {
        var path = temporary.getRoot().toPath().resolve("bow-thresholds.json");
        var config = new KungConfig(path);
        var settings = new BowDrawThresholdSettings(config.misc);
        // Each threshold is a color row with a remove button and a tick slider below it.
        var firstColor = settings.get().get(1);
        var firstTicks = settings.get().get(2);
        var secondTicks = settings.get().get(4);
        assertEquals(SettingKind.COLOR_REMOVE, firstColor.kind());
        assertEquals(SettingKind.SLIDER, firstTicks.kind());
        assertEquals("3t", firstTicks.sliderValue());
        firstTicks.intConsumer().accept(4);
        assertEquals(List.of(4, 5, 8), ticks(config.misc));
        assertSame(firstTicks, settings.get().get(2));
        config.misc.setBowDrawThresholdColor(config.misc.bowDrawThresholds().getFirst(), 0x00FF0000);
        assertEquals(0xFFFF0000, firstColor.intSupplier().getAsInt());
        firstColor.click(99, 50, 52); // The red - at the right end removes this threshold only.
        assertEquals(List.of(5, 8), ticks(config.misc));
        secondTicks.intConsumer().accept(12); // The old control still owns the same entry after a removal.
        firstTicks.intConsumer().accept(8); // Removed native/HUD controls cannot change another entry.
        assertEquals(List.of(12, 8), ticks(config.misc));
        settings.get().getFirst().toggle().run();
        assertEquals(List.of(12, 8, 10), ticks(config.misc));
        var restored = new KungConfig(path);
        restored.load();
        assertEquals(List.of(12, 8, 10), ticks(restored.misc));
        for (var entry : List.copyOf(config.misc.bowDrawThresholds())) config.misc.removeBowDrawThreshold(entry);
        assertEquals(List.of("Add Threshold", "M7 Dragon Ticks"), settings.get().stream().map(SettingEntry::label).toList());
        restored.load();
        assertTrue(restored.misc.bowDrawThresholds().isEmpty());
    }

    @Test
    public void editorsKeepTicksFromZeroUpAndAddedRowsUseAnUnusedValue() {
        var config = new MiscConfig();
        var first = config.bowDrawThresholds().getFirst();
        config.setBowDrawThreshold(first, -20);
        assertEquals(0, first.ticks());
        config.setBowDrawThreshold(first, 99);
        assertEquals(99, first.ticks());
        config.addBowDrawThreshold();
        assertEquals(10, config.bowDrawThresholds().getLast().ticks());
        config.addBowDrawThreshold();
        assertEquals(1, config.bowDrawThresholds().getLast().ticks());
    }

    private static List<Integer> ticks(MiscConfig config) {
        return config.bowDrawThresholds().stream().map(MiscConfig.BowDrawThreshold::ticks).toList();
    }
}
