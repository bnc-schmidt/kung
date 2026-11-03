package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.waypoints.WaypointType;
import java.io.StringReader;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WaypointsSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void waypointsSitsInUtilAndIsOffByDefault() {
        var config = new KungConfig(temporary.getRoot().toPath().resolve("kung.json"));
        // Developer only: the row exists for the developer account and for nobody else.
        var util = KungSettings.categories(config, () -> { }, true).stream()
            .filter(category -> category.name().equals("Util")).findFirst().orElseThrow();
        var waypoints = util.features().stream().filter(feature -> feature.name().equals("Waypoints")).findFirst().orElseThrow();
        assertFalse(waypoints.enabled());
        assertTrue(waypoints.developerOnly());
        assertFalse(KungSettings.categories(config, () -> { }, false).stream().flatMap(category -> category.features().stream())
            .anyMatch(feature -> feature.name().equals("Waypoints")));
        assertEquals(List.of("Editor", "Default Type", "Route Line", "Line Color", "Line Thickness",
                "Activation Radius", "Allow Skipping Waypoints", "Allow Going Backwards"),
            waypoints.settings().stream().map(SettingEntry::label).toList());
    }

    @Test
    public void numbersStayInRangeAndEverythingSurvivesASaveAndLoad() {
        var file = temporary.getRoot().toPath().resolve("kung.json");
        var config = new KungConfig(file);
        config.waypoints.setActivationRadius(99);
        assertEquals(10, config.waypoints.activationRadius());
        config.waypoints.setActivationRadius(0);
        assertEquals(1, config.waypoints.activationRadius());
        config.waypoints.setLineThickness(50);
        assertEquals(6, config.waypoints.lineThickness());
        config.waypoints.setActivationRadius(4);
        config.waypoints.setDefaultType(WaypointType.OUTLINE);
        config.waypoints.setAllowGoingBackwards(false);
        config.waypoints.setLineColor(0x123456);
        config.waypoints.setEnabled(true);
        config.save();

        var reloaded = new KungConfig(file);
        reloaded.load();
        assertTrue(reloaded.waypoints.enabled());
        assertEquals(4, reloaded.waypoints.activationRadius());
        assertEquals(WaypointType.OUTLINE, reloaded.waypoints.defaultType());
        assertFalse(reloaded.waypoints.allowGoingBackwards());
        assertEquals(0xFF123456, reloaded.waypoints.lineColor());
    }

    @Test
    public void aConfigWithoutAWaypointsSectionGetsTheDefaults() {
        var config = KungConfig.read(new StringReader("{}"));
        assertFalse(config.waypoints.enabled());
        assertEquals(WaypointType.WAYPOINT, config.waypoints.defaultType());
        assertTrue(config.waypoints.routeLine());
        assertTrue(config.waypoints.allowSkipping());
        assertTrue(config.waypoints.allowGoingBackwards());
        assertEquals(2, config.waypoints.activationRadius());
    }
}
