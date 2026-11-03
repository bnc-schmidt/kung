package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class MiningSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void gourmandAlertSitsInMiningWithACountdownSliderOfTenSecondsAndIsOffByDefault() {
        var config = new KungConfig(temporary.getRoot().toPath().resolve("kung.json"));
        var mining = KungSettings.categories(config, () -> { }).stream()
            .filter(category -> category.name().equals("Mining")).findFirst().orElseThrow();
        var alert = mining.features().get(0);
        assertEquals("Gourmand Alert", alert.name());
        assertFalse(alert.enabled());
        assertEquals(List.of("Countdown (s)", "Volume (%)"), alert.settings().stream().map(SettingEntry::label).toList());
        var slider = alert.settings().get(0);
        assertEquals(SettingKind.SLIDER, slider.kind());
        assertEquals(10, config.mining.gourmandCountdownSeconds());
        assertFalse("the countdown slider has a tooltip", slider.tooltip().isEmpty());
    }

    @Test
    public void countdownAndVolumeStayInRangeAndSurviveASaveAndLoad() {
        var file = temporary.getRoot().toPath().resolve("kung.json");
        var config = new KungConfig(file);
        config.mining.setGourmandCountdownSeconds(500);
        assertEquals(30, config.mining.gourmandCountdownSeconds());
        config.mining.setGourmandCountdownSeconds(-4);
        assertEquals(3, config.mining.gourmandCountdownSeconds());
        config.mining.setGourmandCountdownSeconds(15);
        config.mining.setGourmandVolume(0);
        assertEquals(1, config.mining.gourmandVolume());
        config.mining.setGourmandAlertEnabled(true);
        config.save();

        var reloaded = new KungConfig(file);
        reloaded.load();
        assertTrue(reloaded.mining.gourmandAlertEnabled());
        assertEquals(15, reloaded.mining.gourmandCountdownSeconds());
    }

    @Test
    public void aConfigWithoutAMiningSectionGetsTheDefaults() {
        var config = KungConfig.read(new StringReader("{}"));
        assertFalse(config.mining.gourmandAlertEnabled());
        assertEquals(10, config.mining.gourmandCountdownSeconds());
        assertEquals(70, config.mining.gourmandVolume());
    }
}
