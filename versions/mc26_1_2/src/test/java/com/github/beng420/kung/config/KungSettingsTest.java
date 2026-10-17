package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.KungSettings.CategoryEntry;
import com.github.beng420.kung.config.KungSettings.FeatureEntry;
import com.github.beng420.kung.config.category.DungeonConfig.DragonDebuffScope;
import com.github.beng420.kung.config.category.DungeonConfig.DragonPart;
import com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial;
import com.github.beng420.kung.config.category.SplitsConfig.PredictionMode;
import com.github.beng420.kung.config.category.SplitsConfig.TimeFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KungSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sharesCompleteCatalogWithoutOpeningScreenOrChangingDefaults() {
        Path file = temporary.getRoot().toPath().resolve("kung.json");
        var categories = KungSettings.categories(new KungConfig(file), () -> { });

        assertEquals(List.of("Dungeon", "Garden", "Hunting", "Slayer", "Util", "Debug"),
            categories.stream().map(CategoryEntry::name).toList());
        // The Modrinth build has no Blood Rush helper and no Skip NPC Dialogue.
        boolean modrinth = com.github.beng420.kung.KungBuild.MODRINTH;
        assertEquals(List.of(modrinth ? 11 : 12, 3, 1, 1, modrinth ? 13 : 14, 3),
            categories.stream().map(category -> category.features().size()).toList());
        for (var category : categories) {
            for (var feature : category.features()) {
                if (!feature.actionOnly() && feature.toggle() != null) {
                    assertFalse(feature.name() + " must remain off by default", feature.enabled());
                }
            }
        }
        assertFalse("Building either menu must not persist or enable anything", Files.exists(file));
        assertEquals(12, feature(categories, "Loadouts Auto Close").settings().stream()
            .filter(setting -> setting.kind() == SettingKind.KEYBIND).count());
        assertFalse(feature(categories, "Crypt Messages").clickable());
    }

    @Test
    public void greenhouseHologramGroupHasNoMasterToggleAndWritesThroughToTheConfig() {
        Path file = temporary.getRoot().toPath().resolve("greenhouse.json");
        KungConfig config = new KungConfig(file);
        FeatureEntry group = feature(KungSettings.categories(config, () -> { }), "Greenhouse Hologram");
        assertEquals("settings only: the hologram is user-triggered, not switched on", null, group.toggle());
        assertFalse(group.clickable());
        assertEquals(List.of("Opacity (%)", "To Place Color", "Wrong Crop Color", "Extra Crop Color",
            "Wrong State Color", "Deactivate When Complete", "Hide Models Over Wrong Soil", "Crosshair Label",
            "Toggle Key"),
            group.settings().stream().map(SettingEntry::label).toList());

        SettingEntry opacity = setting(group, "Opacity (%)");
        assertEquals(SettingKind.SLIDER, opacity.kind());
        assertEquals(5, opacity.min());
        assertEquals(100, opacity.max());
        assertEquals(30, opacity.intSupplier().getAsInt());
        assertEquals("30%", opacity.sliderValue());
        opacity.intConsumer().accept(65);
        assertEquals(0.65F, config.greenhouse.hologramOpacity(), 0.0001F);
        opacity.intConsumer().accept(500);
        assertEquals(1F, config.greenhouse.hologramOpacity(), 0F);

        SettingEntry toPlace = setting(group, "To Place Color");
        assertEquals(SettingKind.COLOR, toPlace.kind());
        assertEquals("#4D8DFF", toPlace.choiceSupplier().get());
        assertEquals("#FF3B3B", setting(group, "Wrong Crop Color").choiceSupplier().get());
        assertEquals("#FF55D8", setting(group, "Extra Crop Color").choiceSupplier().get());
        assertEquals("#FFA126", setting(group, "Wrong State Color").choiceSupplier().get());

        SettingEntry hide = setting(group, "Deactivate When Complete");
        assertTrue(hide.booleanSupplier().getAsBoolean());
        assertTrue("the tooltip says it is off for good until shown again",
            String.join(" ", hide.tooltip()).contains("until you show it again"));
        hide.toggle().run();
        assertFalse(config.greenhouse.hideWhenComplete());

        SettingEntry soil = setting(group, "Hide Models Over Wrong Soil");
        assertEquals(SettingKind.TOGGLE, soil.kind());
        assertTrue("on by default", soil.booleanSupplier().getAsBoolean());
        soil.toggle().run();
        assertFalse(config.greenhouse.hideModelsOverWrongSoil());

        SettingEntry label = setting(group, "Crosshair Label");
        assertTrue("on by default", label.booleanSupplier().getAsBoolean());
        label.toggle().run();
        assertFalse(config.greenhouse.showCrosshairLabel());

        SettingEntry key = setting(group, "Toggle Key");
        assertEquals(SettingKind.KEYBIND, key.kind());
        assertEquals("K", key.textValue());
        assertEquals("key:75:0", key.rawKeybindSupplier().get());
        key.setText("key:66:48");
        assertEquals(66, config.greenhouse.toggleKey());
        assertEquals("B", key.textValue());
        key.setText("");
        assertEquals("None", key.textValue());

        KungConfig restored = new KungConfig(file);
        restored.load();
        assertEquals(1F, restored.greenhouse.hologramOpacity(), 0F);
        assertFalse(restored.greenhouse.hideWhenComplete());
        assertFalse(restored.greenhouse.hideModelsOverWrongSoil());
        assertFalse(restored.greenhouse.showCrosshairLabel());
        assertEquals("", restored.greenhouse.toggleKeybind());
    }

    @Test
    public void bowIndicatorSharesHudPlacementAndPersistsWithoutEnablingOtherFeatures() {
        Path file = temporary.getRoot().toPath().resolve("bow.json");
        KungConfig config = new KungConfig(file);
        var entry = KungHudLayout.entries(config, new com.github.beng420.kung.feature.dungeon.DungeonStateTracker())
            .stream().filter(hud -> hud.id().equals("bow_draw_indicator")).findFirst().orElseThrow();
        assertFalse(entry.enabled());
        assertFalse(Files.exists(file));
        feature(KungSettings.categories(config, () -> { }), "Bow Draw Indicator").toggle().run();
        assertTrue(entry.enabled());
        entry.moveX(-30);
        entry.moveY(80);
        entry.scale(1.25F);
        assertEquals(new KungHudLayout.Bounds(-30, 80, 250, 51), entry.bounds());
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertTrue(restored.misc.bowDrawIndicatorEnabled());
        assertEquals(-30, restored.misc.bowDrawIndicatorX());
        assertEquals(80, restored.misc.bowDrawIndicatorY());
        assertEquals(125, restored.misc.bowDrawIndicatorScale());
        assertFalse(restored.misc.superpairsHelperEnabled());
        assertFalse(restored.dungeon.enabled());
        restored.misc.setBowDrawIndicatorScale(999);
        assertEquals(300, restored.misc.bowDrawIndicatorScale());
        restored.misc.setBowDrawIndicatorScale(0);
        assertEquals(25, restored.misc.bowDrawIndicatorScale());
    }

    @Test
    public void separateCatalogsReadAndWriteSameValidatedPersistentSettings() {
        Path file = temporary.getRoot().toPath().resolve("kung.json");
        KungConfig config = new KungConfig(file);
        var standalone = KungSettings.categories(config, () -> { });
        var nativeMenu = KungSettings.categories(config, () -> { });

        feature(nativeMenu, "Feast Progress").toggle().run();
        assertTrue(feature(standalone, "Feast Progress").enabled());
        setting(feature(nativeMenu, "Feast Progress"), "Kernel Timeout (s)").setText("500");
        assertEquals("300", setting(feature(standalone, "Feast Progress"), "Kernel Timeout (s)").textValue());
        config.feast.setKernelTimeoutSeconds(45);
        assertEquals("45", setting(feature(nativeMenu, "Feast Progress"), "Kernel Timeout (s)").textValue());

        FeatureEntry defaults = feature(KungSettings.defaults(), "Feast Progress");
        assertFalse(defaults.enabled());
        assertEquals("60", setting(defaults, "Kernel Timeout (s)").textValue());

        KungConfig restored = new KungConfig(file);
        restored.load();
        assertTrue(restored.feast.enabled());
        assertEquals(45, restored.feast.kernelTimeoutSeconds());
        assertEquals(config.feast.x(), restored.feast.x());
        assertEquals(config.feast.y(), restored.feast.y());
        assertEquals(config.feast.scale(), restored.feast.scale());
    }

    @Test
    public void choicesAndActionsKeepTheirExistingMeaningAcrossMenus() {
        KungConfig config = new KungConfig(temporary.getRoot().toPath().resolve("kung.json"));
        AtomicInteger changelogs = new AtomicInteger();
        var categories = KungSettings.categories(config, changelogs::incrementAndGet);
        FeatureEntry splits = feature(categories, "Splits Overlay");
        SettingEntry format = setting(splits, "Format");
        assertEquals(List.of("Minutes", "Seconds"), format.choices());
        format.intConsumer().accept(1);
        assertEquals(TimeFormat.SECONDS, config.splits.format());
        format.cycleChoice().run();
        assertEquals(TimeFormat.MINUTES, config.splits.format());

        SettingEntry prediction = setting(splits, "Time Prediction").children().getFirst();
        prediction.intConsumer().accept(1);
        assertEquals(PredictionMode.LIVE, config.splits.predictionMode());
        assertEquals("Live", prediction.choiceSupplier().get());
        SettingEntry source = setting(splits, "Time Prediction").children().get(1);
        assertEquals(List.of("PB", "AVG"), source.choices());
        source.intConsumer().accept(1);
        assertEquals("AVG", config.splits.predictionSource().label());
        assertFalse(config.splits.runEndChat());
        setting(splits, "Paste In Chat").toggle().run();
        assertTrue(config.splits.runEndChat());

        FeatureEntry updater = categories.getLast().features().getFirst();
        assertTrue(updater.actionOnly());
        setting(updater, "Changelogs").toggle().run();
        assertEquals(1, changelogs.get());
    }

    @Test
    public void customSoundsOpenTheirScreenAndFolderFromTheMenu() {
        Path file = temporary.getRoot().toPath().resolve("kung.json");
        KungConfig config = new KungConfig(file);
        FeatureEntry sounds = feature(KungSettings.categories(config, () -> { }), "Custom Sounds");
        assertEquals(List.of("Sound Settings", "Sound Folder"), sounds.settings().stream().map(SettingEntry::label).toList());
        assertFalse(config.misc.customSoundsEnabled());
    }

    @Test
    public void dungeonDebuffTogglesPersistIndependentlyOfMapAndEachOther() {
        Path file = temporary.getRoot().toPath().resolve("debuff.json");
        KungConfig config = new KungConfig(file);
        var catalog = KungSettings.categories(config, () -> { });
        feature(catalog, "Ice Spray Highlight").toggle().run();
        assertTrue(config.dungeon.iceSprayHighlightEnabled());
        assertFalse(config.dungeon.dragonDebuffEnabled());
        assertFalse(config.dungeon.enabled());
        feature(catalog, "Wither Dragons").toggle().run();
        config.dungeon.setDragonDebuffX(-30);
        config.dungeon.setDragonDebuffY(150);
        config.dungeon.setDragonDebuffScale(125);
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertTrue(restored.dungeon.iceSprayHighlightEnabled());
        assertTrue(restored.dungeon.dragonDebuffEnabled());
        assertFalse(restored.dungeon.enabled());
        assertEquals(-30, restored.dungeon.dragonDebuffX());
        assertEquals(150, restored.dungeon.dragonDebuffY());
        assertEquals(125, restored.dungeon.dragonDebuffScale());
        var bounds = com.github.beng420.kung.feature.dungeon.DragonDebuffHud.overlayBounds(restored.dungeon);
        assertEquals(-30, bounds.x());
        assertEquals(150, bounds.y());
        assertEquals(Math.round(410 * 1.25F), bounds.width());
        restored.dungeon.setDragonDebuffScale(999);
        assertEquals(300, restored.dungeon.dragonDebuffScale());
    }

    @Test
    public void spraySizeAndDragonScopeShareValidatedSettingsAndPersist() {
        Path file = temporary.getRoot().toPath().resolve("debuff-options.json");
        KungConfig config = new KungConfig(file);
        var catalog = KungSettings.categories(config, () -> { });
        var size = setting(feature(catalog, "Ice Spray Highlight"), "Box Size (%)");
        var scope = setting(feature(catalog, "Wither Dragons"), "Track");
        assertEquals(100, size.intSupplier().getAsInt());
        assertEquals(List.of("All Dragons", "Nearest Statue"), scope.choices());
        size.intConsumer().accept(150);
        scope.intConsumer().accept(1);
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertEquals(150, restored.dungeon.iceSprayBoxSize());
        assertEquals(DragonDebuffScope.NEAREST_STATUE, restored.dungeon.dragonDebuffScope());
        assertFalse(restored.dungeon.iceSprayHighlightEnabled());
        assertFalse(restored.dungeon.dragonDebuffEnabled());
        assertEquals(Math.round(30 * .85F), com.github.beng420.kung.feature.dungeon.DragonDebuffHud.overlayBounds(restored.dungeon).height());
        size.intConsumer().accept(900);
        assertEquals(200, config.dungeon.iceSprayBoxSize());
        size.intConsumer().accept(-1);
        assertEquals(100, config.dungeon.iceSprayBoxSize());
        var invalid = KungConfig.read(new java.io.StringReader("""
            {"dungeonMap":{"iceSprayBoxSize":999,"dragonDebuffScope":"unknown"}}
            """));
        assertEquals(200, invalid.dungeon.iceSprayBoxSize());
        assertEquals(DragonDebuffScope.ALL_DRAGONS, invalid.dungeon.dragonDebuffScope());
    }

    @Test
    public void coloredPillarsSettingsPersistIndependentlyAndFallbackToWool() {
        Path file = temporary.getRoot().toPath().resolve("pillars.json");
        KungConfig config = new KungConfig(file);
        var pillars = feature(KungSettings.categories(config, () -> { }), "Colored F7/M7 Pillars");
        var material = setting(pillars, "Material");
        assertFalse(pillars.enabled());
        assertEquals("Wool", material.choiceSupplier().get());
        assertEquals(List.of("Material"), pillars.settings().stream().map(SettingEntry::label).toList());
        var choices = List.of(PillarMaterial.choices());
        assertEquals(choices.stream().map(PillarMaterial::label).toList(), material.choices());

        pillars.toggle().run();
        for (var choice : choices) {
            material.intConsumer().accept(choices.indexOf(choice));
            KungConfig restored = new KungConfig(file);
            restored.load();
            assertTrue(restored.dungeon.coloredPillarsEnabled());
            assertEquals(choice, restored.dungeon.pillarMaterial());
            assertFalse(restored.dungeon.enabled());
        }
        pillars.toggle().run();
        config.dungeon.setPillarMaterial(null);
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertFalse(restored.dungeon.coloredPillarsEnabled());
        assertEquals(PillarMaterial.WOOL, restored.dungeon.pillarMaterial());
        for (String stored : List.of("{}", "{\"pillarMaterial\":null}", "{\"pillarMaterial\":\"unknown\"}")) {
            var loaded = KungConfig.read(new java.io.StringReader("{\"dungeonMap\":" + stored + "}"));
            assertFalse(loaded.dungeon.coloredPillarsEnabled());
            assertEquals(PillarMaterial.WOOL, loaded.dungeon.pillarMaterial());
        }
    }

    @Test
    public void witherDragonsHidesOnlyTheAimAndDiagnosticsFromOtherAccounts() {
        KungConfig config = new KungConfig(temporary.getRoot().toPath().resolve("dragon-catalog.json"));
        config.dungeon.setWitherDragonsEnabled(true);
        config.dungeon.setDevDragonDiagnosticsEnabled(true);
        var developer = KungSettings.categories(config, () -> { }, true);
        var publicCatalog = KungSettings.categories(config, () -> { }, false);
        // Same entries for everyone; Wither Dragons differs in its settings.
        for (int index = 0; index < developer.size(); index++) {
            assertEquals(developer.get(index).features().stream().map(FeatureEntry::name).toList(),
                publicCatalog.get(index).features().stream().map(FeatureEntry::name).toList());
        }
        assertEquals(List.of("Debuff Tracker", "Track", "Dragon Prio", "Mage Split", "Healer Split", "Tank Split", "Prio Unit", "Spawn Markers", "Marker", "Core Parts", "Aim Point", "Flight Paths", "Path Part", "Line Thickness (%)", "Statue Boxes", "Count Notifications", "Developer Diagnostics"),
            feature(developer, "Wither Dragons").settings().stream().map(SettingEntry::label).toList());
        assertEquals(List.of("Debuff Tracker", "Track", "Dragon Prio", "Mage Split", "Healer Split", "Tank Split", "Prio Unit", "Spawn Markers", "Marker", "Core Parts", "Flight Paths", "Path Part", "Line Thickness (%)", "Statue Boxes", "Count Notifications"),
            feature(publicCatalog, "Wither Dragons").settings().stream().map(SettingEntry::label).toList());
    }

    @Test
    public void sliderValuesTakeTheirUnitFromTheLabelAndNeverDefaultToSeconds() {
        java.util.function.BiFunction<String, Integer, String> shown = (label, value) ->
            SettingEntry.slider(label, () -> value, ignored -> { }, 0, 300, 1).sliderValue();
        assertEquals("150%", shown.apply("Box Size (%)", 150));
        assertEquals("20%", shown.apply("Low HP (%)", 20));
        assertEquals("70%", shown.apply("Volume (%)", 70));
        assertEquals("100%", shown.apply("Map Scale", 100));
        assertEquals("2.0s", shown.apply("Title Time", 20));
        assertEquals("x1.0", shown.apply("Volume", 10));
        assertEquals("x1.25", shown.apply("Pitch", 125));
        assertEquals("12", shown.apply("Anything Else", 12));
    }

    @Test
    public void preMergeConfigsKeepWhicheverDragonFeatureTheyHadOn() {
        for (String stored : List.of("{\"dragonDebuffEnabled\":true}", "{\"m7DragonHelperEnabled\":true}")) {
            var loaded = KungConfig.read(new java.io.StringReader("{\"dungeonMap\":" + stored + "}"));
            assertTrue(stored, loaded.dungeon.witherDragonsEnabled());
            assertTrue(stored, loaded.dungeon.dragonDebuffEnabled());
        }
        var fresh = KungConfig.read(new java.io.StringReader("{}"));
        assertFalse(fresh.dungeon.witherDragonsEnabled());
        assertFalse(fresh.dungeon.dragonDebuffEnabled());
        // Once toggled, the master is its own setting and the old fields no longer decide it.
        var migrated = KungConfig.read(new java.io.StringReader("{\"dungeonMap\":{\"dragonDebuffEnabled\":true,\"witherDragonsEnabled\":false}}"));
        assertFalse(migrated.dungeon.witherDragonsEnabled());
    }

    @Test
    public void witherDragonRowsOpenUnderTheSwitchTheyDependOn() {
        KungConfig config = new KungConfig(temporary.getRoot().toPath().resolve("visibility.json"));
        var dragons = feature(KungSettings.categories(config, () -> { }, true), "Wither Dragons");
        assertTrue(setting(dragons, "Track").visible());
        setting(dragons, "Debuff Tracker").toggle().run();
        assertFalse(setting(dragons, "Track").visible());
        // Spawn Markers > Marker > Core Parts; the markers start off.
        assertFalse(setting(dragons, "Marker").visible());
        setting(dragons, "Spawn Markers").toggle().run();
        assertTrue(setting(dragons, "Marker").visible());
        assertTrue(setting(dragons, "Core Parts").visible());
        config.dungeon.setDragonMarkerMode(com.github.beng420.kung.config.category.DungeonConfig.DragonMarkerMode.SKELETON);
        assertTrue(setting(dragons, "Marker").visible());
        assertFalse(setting(dragons, "Core Parts").visible());
        config.dungeon.setDragonMarkerMode(com.github.beng420.kung.config.category.DungeonConfig.DragonMarkerMode.CORE);
        setting(dragons, "Spawn Markers").toggle().run();
        assertFalse(setting(dragons, "Marker").visible());
        assertFalse(setting(dragons, "Core Parts").visible());
        assertTrue(setting(dragons, "Path Part").visible());
        setting(dragons, "Flight Paths").toggle().run();
        assertFalse(setting(dragons, "Path Part").visible());
    }

    @Test
    public void developerDragonHelperSettingsPersistIndependently() {
        Path file = temporary.getRoot().toPath().resolve("dragon-helper.json");
        KungConfig config = new KungConfig(file);
        var helper = feature(KungSettings.categories(config, () -> { }, true), "Wither Dragons");
        assertFalse(helper.enabled());
        assertEquals(List.of("Debuff Tracker", "Track", "Dragon Prio", "Mage Split", "Healer Split", "Tank Split", "Prio Unit", "Spawn Markers", "Marker", "Core Parts", "Aim Point", "Flight Paths", "Path Part", "Line Thickness (%)", "Statue Boxes", "Count Notifications", "Developer Diagnostics"),
            helper.settings().stream().map(SettingEntry::label).toList());
        assertFalse(setting(helper, "Developer Diagnostics").booleanSupplier().getAsBoolean());
        // By name, not by index: the list also holds a slider, which has no boolean supplier.
        assertFalse(setting(helper, "Spawn Markers").booleanSupplier().getAsBoolean());
        setting(helper, "Spawn Markers").toggle().run();
        for (String label : List.of("Statue Boxes", "Count Notifications")) {
            var setting = setting(helper, label);
            assertTrue(label, setting.booleanSupplier().getAsBoolean());
            setting.toggle().run();
        }
        helper.toggle().run();
        config.dungeon.setDevDragonDiagnosticsEnabled(true);
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertTrue(restored.dungeon.witherDragonsEnabled());
        assertTrue(restored.dungeon.m7DragonHelperEnabled());
        assertTrue(restored.dungeon.dragonSpawnMarkersEnabled());
        assertFalse(restored.dungeon.dragonStatueBoxesEnabled());
        assertFalse(restored.dungeon.dragonCountNotificationsEnabled());
        assertFalse(restored.dungeon.devDragonDiagnosticsEnabled());
        // The master turns the debuff tracker on with it; its own switch defaults on.
        assertTrue(restored.dungeon.dragonDebuffEnabled());
        assertFalse(restored.dungeon.enabled());
    }

    @Test
    public void dragonDefaultsAndDebuffOutputsPersist() {
        Path file = temporary.getRoot().toPath().resolve("dragon-defaults.json");
        KungConfig config = new KungConfig(file);
        assertEquals(DragonPart.BODY, config.dungeon.dragonTrailPart());
        assertEquals(100, config.dungeon.dragonLineThickness());
        config.dungeon.setDragonLineThickness(1);
        assertEquals(25, config.dungeon.dragonLineThickness());
        config.dungeon.setDragonLineThickness(999);
        assertEquals(400, config.dungeon.dragonLineThickness());
        assertTrue(config.misc.bowDrawDragonTicksEnabled());
        var tracker = setting(feature(KungSettings.categories(config, () -> { }, false), "Wither Dragons"), "Debuff Tracker");
        assertEquals(List.of("HUD", "Chat"), tracker.children().stream().map(SettingEntry::label).toList());
        tracker.children().get(1).toggle().run();
        // The HUD editor's switch only hides the HUD; turning it on brings the tracker and master with it.
        config.dungeon.setDragonDebuffHudShown(false);
        config.dungeon.setDragonDebuffTrackerEnabled(false);
        config.dungeon.setDragonDebuffHudShown(true);
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertTrue(restored.dungeon.dragonDebuffHudShown());
        assertTrue(restored.dungeon.dragonDebuffEnabled());
        assertFalse(restored.dungeon.dragonDebuffChatEnabled());
        assertEquals(400, restored.dungeon.dragonLineThickness());
    }

    @Test
    public void oldConfigsDoNotKeepTheFormerDragonMarkerDefaults() {
        // Written by 0.4.x: its defaults were saved to every config, though only the developer saw them.
        var old = KungConfig.read(new java.io.StringReader("""
            {"dungeonMap":{"dragonSpawnMarkersEnabled":true,"dragonTrailPart":"NECK","dragonCoreParts":["BOX"]}}
            """));
        assertFalse(old.dungeon.dragonSpawnMarkersEnabled());
        assertEquals(DragonPart.BODY, old.dungeon.dragonTrailPart());
        assertTrue(old.dungeon.dragonCorePart(DragonPart.BODY));
        assertFalse(old.dungeon.dragonCorePart(DragonPart.BOX));
    }

    @Test
    public void dragonCorePartsToggleIndependentlyAndSurviveAReload() {
        Path file = temporary.getRoot().toPath().resolve("core-parts.json");
        KungConfig config = new KungConfig(file);
        var parts = setting(feature(KungSettings.categories(config, () -> { }, true), "Wither Dragons"), "Core Parts").children();
        assertEquals(9, parts.size());
        // Body alone by default.
        assertFalse(parts.getFirst().booleanSupplier().getAsBoolean());
        assertTrue(parts.get(3).booleanSupplier().getAsBoolean());
        parts.get(0).toggle().run();
        parts.get(2).toggle().run();
        KungConfig restored = new KungConfig(file);
        restored.load();
        assertTrue(restored.dungeon.dragonCorePart(com.github.beng420.kung.config.category.DungeonConfig.DragonPart.BOX));
        assertTrue(restored.dungeon.dragonCorePart(com.github.beng420.kung.config.category.DungeonConfig.DragonPart.NECK));
        assertTrue(restored.dungeon.dragonCorePart(com.github.beng420.kung.config.category.DungeonConfig.DragonPart.BODY));
        assertFalse(restored.dungeon.dragonCorePart(com.github.beng420.kung.config.category.DungeonConfig.DragonPart.HEAD));
    }

    private static FeatureEntry feature(List<CategoryEntry> categories, String name) {
        return categories.stream().flatMap(category -> category.features().stream())
            .filter(feature -> feature.name().equals(name)).findFirst().orElseThrow();
    }

    private static SettingEntry setting(FeatureEntry feature, String label) {
        return feature.settings().stream().filter(setting -> setting.label().equals(label)).findFirst().orElseThrow();
    }
}
