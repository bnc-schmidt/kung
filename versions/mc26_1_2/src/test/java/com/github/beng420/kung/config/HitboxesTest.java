package com.github.beng420.kung.config;

import static com.github.beng420.kung.config.ConfigTestSupport.read;
import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.config.category.HitboxesConfig;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HitboxesTest extends MinecraftBootstrapTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void oldConfigsDefaultOffAndMalformedEntriesDoNotBecomeInvisibleBoxes() {
        assertFalse(read("{}").hitboxes.enabled());
        assertTrue(read("{}").hitboxes.dragonOverallBox());
        assertTrue(read("{}").hitboxes.dragonPartBoxes());
        assertTrue(read("{\"hitboxes\":null}").hitboxes.entities().isEmpty());
        assertTrue(read("{\"hitboxes\":{\"entities\":null}}").hitboxes.entities().isEmpty());
        var config = read("""
            {"hitboxes":{"entities":{"arrow":1193046,"invalid id":45,"minecraft:zombie":null}}}
            """);
        assertEquals(java.util.Map.of("minecraft:arrow", 0xFF123456), config.hitboxes.entities());
        // Entries saved with a modifier cover every variant after loading.
        assertEquals(java.util.Map.of("skyblock:skeleton_master", 0xFF000001),
            read("{\"hitboxes\":{\"entities\":{\"skyblock:healthy_skeleton_master\":1}}}").hitboxes.entities());
    }

    @Test
    public void entriesSavedWithoutLineAndSizeKeepTodaysLookAndNumbersPastTheSliderStay() {
        var old = read("{\"hitboxes\":{\"entities\":{\"minecraft:zombie\":1},\"lineThickness\":null}}").hitboxes;
        assertEquals(100, old.lineThickness("minecraft:zombie"));
        assertEquals(100, old.boxSize("minecraft:zombie"));
        var config = read("""
            {"hitboxes":{"entities":{"minecraft:zombie":1,"minecraft:arrow":2},
             "lineThickness":{"minecraft:arrow":9000},"boxSize":{"minecraft:arrow":0,"minecraft:pig":150}}}
            """).hitboxes;
        assertEquals(100, config.lineThickness("minecraft:zombie"));
        assertEquals(9000, config.lineThickness("minecraft:arrow"));
        assertEquals(1, config.boxSize("minecraft:arrow"));
        assertEquals(100, config.boxSize("minecraft:pig"));
        var row = new HitboxSettings(old).get().get(1);
        assertEquals(List.of("Line Thickness (%)", "Box Size (%)"), row.children().stream().map(SettingEntry::label).toList());
        row.children().get(0).intConsumer().accept(1);
        row.children().get(1).intConsumer().accept(999);
        assertEquals(1, old.lineThickness("minecraft:zombie"));
        assertEquals(999, old.boxSize("minecraft:zombie"));
    }

    @Test
    public void dragonOverallAndPartBoxesToggleIndependentlyAndPersistWhenReadded() {
        var path = temporary.getRoot().toPath().resolve("dragon-boxes.json");
        var config = new KungConfig(path);
        config.hitboxes.add("minecraft:ender_dragon");
        var settings = new HitboxSettings(config.hitboxes);
        var row = settings.get().get(1);
        assertEquals(List.of("Overall Box", "Body Part Boxes", "Line Thickness (%)", "Box Size (%)"),
            row.children().stream().map(SettingEntry::label).toList());
        var overall = row.children().get(0);
        var parts = row.children().get(1);
        overall.toggle().run();
        assertFalse(overall.booleanSupplier().getAsBoolean());
        assertTrue(parts.booleanSupplier().getAsBoolean());
        assertSame(row, settings.get().get(1));
        var restored = new KungConfig(path);
        restored.load();
        assertFalse(restored.hitboxes.dragonOverallBox());
        assertTrue(restored.hitboxes.dragonPartBoxes());
        parts.toggle().run();
        assertFalse(config.hitboxes.dragonOverallBox());
        assertFalse(config.hitboxes.dragonPartBoxes());
        overall.toggle().run();
        restored.load();
        assertTrue(restored.hitboxes.dragonOverallBox());
        assertFalse(restored.hitboxes.dragonPartBoxes());
        config.hitboxes.remove("minecraft:ender_dragon");
        assertEquals(1, settings.get().size());
        config.hitboxes.add("minecraft:ender_dragon");
        assertFalse(settings.get().get(1).children().get(1).booleanSupplier().getAsBoolean());
        assertFalse(config.hitboxes.enabled());
    }

    @Test
    public void everyHitboxSwitchesOffAndOnWithoutBeingDeletedAndTheStateSurvivesARestart() {
        var path = temporary.getRoot().toPath().resolve("switches.json");
        var config = new KungConfig(path);
        config.hitboxes.add("minecraft:arrow");
        config.hitboxes.add("minecraft:zombie");
        var settings = new HitboxSettings(config.hitboxes);
        var arrow = settings.get().get(1);
        var zombie = settings.get().get(2);
        var enabled = arrow.rowToggle();
        // The switch sits in the row itself, not among the expandable children.
        assertEquals("Enabled", enabled.label());
        assertEquals(List.of("Line Thickness (%)", "Box Size (%)"), arrow.children().stream().map(SettingEntry::label).toList());
        assertEquals(SettingKind.TOGGLE, enabled.kind());
        assertTrue(enabled.booleanSupplier().getAsBoolean());
        assertEquals(List.of("minecraft:arrow", "minecraft:zombie"), List.copyOf(config.hitboxes.activeEntities().keySet()));

        enabled.toggle().run();
        assertFalse(enabled.booleanSupplier().getAsBoolean());
        assertTrue(zombie.rowToggle().booleanSupplier().getAsBoolean());
        assertEquals(List.of("minecraft:zombie"), List.copyOf(config.hitboxes.activeEntities().keySet()));
        // The entry stays: still listed, same color, its rows survive and the swatch only dims.
        assertEquals(List.of("minecraft:arrow", "minecraft:zombie"), List.copyOf(config.hitboxes.entities().keySet()));
        assertSame(arrow, settings.get().get(1));
        assertEquals(0xFF153F15, arrow.intSupplier().getAsInt());
        assertEquals(HitboxesConfig.DEFAULT_COLOR, zombie.intSupplier().getAsInt());

        var restored = new KungConfig(path);
        restored.load();
        assertFalse(restored.hitboxes.active("minecraft:arrow"));
        assertTrue(restored.hitboxes.active("minecraft:zombie"));
        assertEquals(List.of("minecraft:zombie"), List.copyOf(restored.hitboxes.activeEntities().keySet()));

        // Switching back on, and deleting an off entry, both work as before.
        enabled.toggle().run();
        assertEquals(2, config.hitboxes.activeEntities().size());
        enabled.toggle().run();
        arrow.click(150, 100, 52);
        assertEquals(List.of("Add Hitbox", "Zombie"), settings.get().stream().map(SettingEntry::label).toList());
        config.hitboxes.add("minecraft:arrow");
        assertTrue(config.hitboxes.active("minecraft:arrow"));
        // An unknown id is no entry, so it cannot be switched off.
        config.hitboxes.setActive("minecraft:pig", false);
        assertEquals(2, config.hitboxes.activeEntities().size());
    }

    @Test
    public void configsSavedBeforeTheSwitchLoadAllOnAndStaleOrBrokenSwitchesAreDropped() {
        var old = read("{\"hitboxes\":{\"entities\":{\"minecraft:zombie\":1,\"minecraft:arrow\":2}}}").hitboxes;
        assertTrue(old.active("minecraft:zombie"));
        assertTrue(old.active("minecraft:arrow"));
        assertEquals(2, old.activeEntities().size());
        assertTrue(read("{\"hitboxes\":{\"entities\":{\"minecraft:zombie\":1},\"disabled\":null}}").hitboxes.active("minecraft:zombie"));
        var saved = read("""
            {"hitboxes":{"entities":{"minecraft:zombie":1,"minecraft:arrow":2},"disabled":["minecraft:arrow","minecraft:gone"]}}
            """).hitboxes;
        assertFalse(saved.active("minecraft:arrow"));
        assertTrue(saved.active("minecraft:zombie"));
        assertEquals(java.util.Map.of("minecraft:zombie", 0xFF000001), saved.activeEntities());
        // The stale id was dropped on load, so adding that entity later starts switched on.
        saved.add("minecraft:gone");
        assertTrue(saved.active("minecraft:gone"));
    }

    @Test
    public void addedTypesAndColorsPersistAndTheExistingCatalogUpdatesWithoutReopening() {
        var path = temporary.getRoot().toPath().resolve("kung.json");
        KungConfig config = new KungConfig(path);
        var feature = KungSettings.categories(config, () -> { }).stream().flatMap(category -> category.features().stream())
            .filter(entry -> entry.name().equals("Hitboxes")).findFirst().orElseThrow();
        assertEquals(List.of("Add Hitbox"), feature.settings().stream().map(SettingEntry::label).toList());
        config.hitboxes.add("minecraft:arrow");
        config.hitboxes.setColor("minecraft:arrow", 0x123456);
        config.hitboxes.add("minecraft:arrow");
        config.hitboxes.add("minecraft:ender_dragon");
        assertFalse(feature.enabled());
        assertEquals(List.of("Add Hitbox", "Arrow", "Ender Dragon"), feature.settings().stream().map(SettingEntry::label).toList());
        assertSame(feature.settings().get(1), feature.settings().get(1));
        assertEquals(0xFF123456, feature.settings().get(1).intSupplier().getAsInt());
        feature.toggle().run();

        KungConfig restored = new KungConfig(path);
        restored.load();
        assertTrue(restored.hitboxes.enabled());
        assertEquals(config.hitboxes.entities(), restored.hitboxes.entities());
        feature.settings().get(1).click(150, 100, 52);
        assertEquals(List.of("Add Hitbox", "Ender Dragon"), feature.settings().stream().map(SettingEntry::label).toList());
        restored.load();
        assertFalse(restored.hitboxes.entities().containsKey("minecraft:arrow"));
        config.hitboxes.setEnabled(false);
        assertEquals(1, config.hitboxes.entities().size());
        config.hitboxes.add(null);
        config.hitboxes.add("not an id");
        assertEquals(1, config.hitboxes.entities().size());
    }

    @Test
    public void searchCoversTheWholeRegistryByEnglishNameAndIdWithoutSelectedDuplicates() {
        var all = HitboxSettings.entityIds();
        assertEquals(BuiltInRegistries.ENTITY_TYPE.size(), all.size());
        assertTrue(all.containsAll(List.of("minecraft:arrow", "minecraft:ender_dragon", "minecraft:item", "minecraft:player")));
        assertEquals(List.of("minecraft:ender_dragon"), HitboxSettings.search(all, Set.of(), " ENDER DRAGON "));
        assertEquals(List.of("minecraft:ender_dragon"), HitboxSettings.search(all, Set.of(), "ender_dragon"));
        assertEquals(List.of("minecraft:spectral_arrow"), HitboxSettings.search(all, Set.of("minecraft:arrow"), "arrow"));
        assertEquals(List.of("minecraft:arrow"), HitboxSettings.search(all, Set.of(), "minecraft:arrow"));
        assertTrue(HitboxSettings.search(all, Set.of(), "no such entity xyz").isEmpty());
        assertEquals("Arrow (example)", HitboxSettings.name("example:arrow"));
    }

    @Test
    public void typedNamesBecomeItemAndSkyBlockEntriesAfterTheVanillaMatches() {
        var matches = HitboxSettings.search(HitboxSettings.entityIds(), Set.of(), "zombie");
        var withTyped = HitboxSettings.withTypedEntries(matches, Set.of(), "zombie");
        assertEquals(matches.getFirst(), withTyped.getFirst());
        assertEquals("skyblock:zombie", withTyped.getLast());
        assertEquals(List.of("skyblock:voidgloom_seraph"),
            HitboxSettings.withTypedEntries(List.of(), Set.of(), " Voidgloom  Seraph "));
        assertEquals(List.of(), HitboxSettings.withTypedEntries(List.of(), Set.of("skyblock:zealot"), "Zealot"));
        // A real item name also offers the held-item entry, e.g. a thrown Bonemerang's bone.
        assertEquals(List.of("item:bone", "skyblock:bone"), HitboxSettings.withTypedEntries(List.of(), Set.of(), " Bone "));
        assertEquals("Bone (item)", HitboxSettings.name("item:bone"));
        assertEquals("item:bone", com.github.beng420.kung.feature.misc.HitboxesFeature.itemId(
            new net.minecraft.world.item.ItemStack(net.minecraft.core.Holder.direct(net.minecraft.world.item.Items.BONE,
                net.minecraft.core.component.DataComponentMap.EMPTY))));
        assertEquals("Voidgloom Seraph (skyblock)", HitboxSettings.name("skyblock:voidgloom_seraph"));
        var config = new KungConfig(temporary.getRoot().toPath().resolve("skyblock.json"));
        config.hitboxes.add("skyblock:voidgloom_seraph");
        assertTrue(config.hitboxes.entities().containsKey("skyblock:voidgloom_seraph"));
    }

    @Test
    public void colorSwatchAndRemovalHaveSeparateClickTargets() {
        AtomicInteger edits = new AtomicInteger();
        AtomicInteger removals = new AtomicInteger();
        var entry = SettingEntry.colorRemove("Arrow", () -> HitboxesConfig.DEFAULT_COLOR, edits::incrementAndGet, removals::incrementAndGet);
        entry.click(90, 100, 52);
        assertEquals(0, edits.get());
        entry.click(110, 100, 52);
        assertEquals(1, edits.get());
        assertEquals(0, removals.get());
        entry.click(146, 100, 52);
        assertEquals(1, edits.get());
        assertEquals(1, removals.get());
    }

    @Test
    public void wheelAndHexSupportSaturatedColorsWhiteBlackAndRejectInvalidInput() {
        assertEquals(0xFFFF0000, HitboxEditorScreen.wheelColor(65, 0, 65, 1));
        assertEquals(0xFF00FFFF, HitboxEditorScreen.wheelColor(-65, 0, 65, 1));
        assertEquals(0xFFFFFFFF, HitboxEditorScreen.wheelColor(0, 0, 65, 1));
        assertEquals(0xFF000000, HitboxEditorScreen.wheelColor(65, 0, 65, 0));
        assertEquals(Integer.valueOf(0xFF12ABEF), HitboxEditorScreen.parseHex("#12abEF"));
        assertEquals(Integer.valueOf(0xFF000000), HitboxEditorScreen.parseHex("000000"));
        assertNull(HitboxEditorScreen.parseHex("#12345"));
        assertNull(HitboxEditorScreen.parseHex("#1234567"));
        assertNull(HitboxEditorScreen.parseHex("#xyzxyz"));
    }
}
