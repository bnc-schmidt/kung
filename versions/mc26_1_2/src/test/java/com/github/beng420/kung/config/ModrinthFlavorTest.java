package com.github.beng420.kung.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.config.KungSettings.FeatureEntry;
import com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial;
import java.io.StringReader;
import org.junit.Test;

/** Runs in both flavors; with -Pmodrinth it checks what the Modrinth jar leaves out. */
public final class ModrinthFlavorTest {
    @Test
    public void modrinthLeavesOutBloodRushMimicEspAndGlassPillars() {
        var config = KungConfig.read(new StringReader("{\"dungeonMap\":{\"pillarMaterial\":\"GLASS\",\"mimicEspEnabled\":true}}"));
        var catalog = KungSettings.categories(config, () -> { }, true);
        var features = catalog.stream().flatMap(category -> category.features().stream()).toList();
        assertEquals(!KungBuild.MODRINTH, features.stream().anyMatch(feature -> feature.name().equals("Blood rush helper")));
        assertEquals(!KungBuild.MODRINTH, features.stream().anyMatch(feature -> feature.name().equals("Skip NPC Dialogue")));
        assertEquals(!KungBuild.MODRINTH, features.stream().anyMatch(feature -> feature.name().equals("Auto Harp")));

        // The Superpairs Helper and its add-on solvers are in both jars; only the clicking is cut from the Modrinth one.
        FeatureEntry superpairs = features.stream().filter(feature -> feature.name().equals("Superpairs Helper")).findFirst().orElseThrow();
        var superpairsSettings = superpairs.settings().stream().map(SettingEntry::label).toList();
        assertEquals(true, superpairsSettings.contains("Debug"));
        // The chat hint at the max buffs round is a visual aid; only the closing is automation.
        assertEquals(true, superpairsSettings.contains("Max Buffs Round"));
        assertEquals(true, superpairsSettings.contains("Keep Cards Visible"));
        for (String visualAid : java.util.List.of("Pair Colors", "Pair Letters", "Mark Pairs", "Mark Best Click")) {
            assertEquals(visualAid, true, superpairsSettings.contains(visualAid));
        }
        for (String automation : java.util.List.of("Auto Click Main Game", "Auto Claim Pairs", "Auto Click Add-ons", "Click Delay (ms)")) {
            assertEquals(automation, !KungBuild.MODRINTH, superpairsSettings.contains(automation));
        }
        assertEquals(false, features.stream().anyMatch(feature -> feature.name().equals("Experiment Solver")));

        FeatureEntry map = features.stream().filter(feature -> feature.name().equals("Dungeon Map")).findFirst().orElseThrow();
        var mapSettings = map.settings().stream().map(SettingEntry::label).toList();
        assertEquals(!KungBuild.MODRINTH, mapSettings.contains("Mimic ESP"));
        // Entered rooms only: nothing unopened left to dim.
        assertEquals(!KungBuild.MODRINTH, mapSettings.contains("Unopened Alpha"));

        // No GitHub update check in the Modrinth jar, so no row may wait for one.
        FeatureEntry updates = catalog.stream().filter(category -> category.name().equals("Debug"))
            .findFirst().orElseThrow().features().getFirst();
        assertEquals(KungBuild.MODRINTH, updates.name().equals("Updates: Modrinth App"));
        assertEquals(!KungBuild.MODRINTH, updates.settings().stream()
            .anyMatch(setting -> setting.label().startsWith("Latest")));

        assertArrayEquals(KungBuild.MODRINTH
                ? new PillarMaterial[] {PillarMaterial.WOOL, PillarMaterial.TERRACOTTA}
                : PillarMaterial.values(),
            PillarMaterial.choices());
        // A config copied over from the full build keeps no Glass.
        assertEquals(KungBuild.MODRINTH ? PillarMaterial.WOOL : PillarMaterial.GLASS, config.dungeon.pillarMaterial());
    }
}
