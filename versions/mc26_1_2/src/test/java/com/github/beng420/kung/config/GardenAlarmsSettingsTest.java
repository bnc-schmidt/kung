package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GardenAlarmsSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void gardenAlarmsHoldThreeToggleChildrenWithTooltipsAndTheOldFeatureIsGone() {
        var config = new KungConfig(temporary.getRoot().toPath().resolve("kung.json"));
        var features = KungSettings.categories(config, () -> { }).stream()
            .flatMap(category -> category.features().stream()).toList();
        assertFalse(features.stream().anyMatch(feature -> feature.name().equals("6th Visitor Alarm")));
        var alarms = features.stream().filter(feature -> feature.name().equals("Garden Alarms")).findFirst().orElseThrow();
        assertFalse("master toggle is off by default", alarms.enabled());
        assertEquals(List.of("Max Visitor Alarm", "No FF Buff Alarm", "No Spray Alarm", "Mute Key", "Volume (%)"),
            alarms.settings().stream().map(SettingEntry::label).toList());
        for (String label : List.of("Max Visitor Alarm", "No FF Buff Alarm", "No Spray Alarm")) {
            var setting = alarms.settings().stream().filter(entry -> entry.label().equals(label)).findFirst().orElseThrow();
            assertEquals(label, SettingKind.TOGGLE, setting.kind());
            assertFalse(label + " is off by default", setting.booleanSupplier().getAsBoolean());
            assertFalse(label + " has a tooltip", setting.tooltip().isEmpty());
            setting.toggle().run();
            assertTrue(label + " switches on", setting.booleanSupplier().getAsBoolean());
        }
        var mute = alarms.settings().stream().filter(entry -> entry.label().equals("Mute Key")).findFirst().orElseThrow();
        assertEquals(SettingKind.KEYBIND, mute.kind());
        assertEquals("unbound by default", "", mute.rawKeybindSupplier().get());
        assertFalse(mute.tooltip().isEmpty());
        mute.textConsumer().accept("key:77:50");
        assertEquals("key:77:50", config.gardenAlarms.muteKeybind());
        assertTrue(config.gardenAlarms.maxVisitor() && config.gardenAlarms.noFfBuff() && config.gardenAlarms.noSpray());
    }
}
