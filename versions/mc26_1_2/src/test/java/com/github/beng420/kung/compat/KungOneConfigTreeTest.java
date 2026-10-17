package com.github.beng420.kung.compat;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungSettings;
import com.github.beng420.kung.config.SettingEntry;
import com.github.beng420.kung.config.SettingKind;
import com.github.beng420.kung.config.category.SplitsConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.config.v1.Tree;
import org.polyfrost.oneconfig.api.config.v1.Visualizer;
import org.polyfrost.oneconfig.api.config.v1.backend.Backend;
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind;

public final class KungOneConfigTreeTest {
    @Test
    public void wholeCatalogUsesNativeControlsAndHasNoForeignScreenAction() {
        List<KungSettings.CategoryEntry> categories = KungSettings.defaults();
        var bridge = new KungOneConfigTree(categories, () -> { }, () -> true);
        Tree tree = bridge.tree();
        assertEquals("kung", tree.getID());
        assertNull(tree.getMetadata("on_click"));
        assertNull(tree.getMetadata(Backend.UI_ONLY_METADATA));
        assertEquals("assets/kung/icon.png", tree.getMetadata("icon_path"));
        int expected = categories.stream().mapToInt(category -> category.features().stream()
            .mapToInt(feature -> 1 + countControls(feature.settings())).sum()).sum();
        assertEquals(expected, tree.map.size());
        assertEquals(6, tree.map.values().stream().map(node -> node.getMetadata("category")).distinct().count());
        long keybinds = tree.map.values().stream().filter(node -> node.getMetadata("visualizer") == Visualizer.KeybindVisualizer.class).count();
        assertEquals(14, keybinds);
        assertEquals(Property.Display.DISABLED, property(tree, "Crypt Messages", "Enabled").getDisplay());
        assertEquals(false, property(tree, "Colored F7/M7 Pillars", "Enabled").get());
        assertArrayEquals(java.util.Arrays.stream(com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial.choices())
                .map(com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial::label).toArray(String[]::new),
            property(tree, "Colored F7/M7 Pillars", "Material").getMetadata("options"));
        assertFalse(tree.map.values().stream().anyMatch(node -> "Aim Point".equals(node.getTitle())));
        assertFalse(tree.map.values().stream().anyMatch(node -> "Developer Diagnostics".equals(node.getTitle())));
        // A row hidden by its switch is hidden in the native menu too, not merely greyed out.
        assertEquals(Property.Display.SHOWN, property(tree, "Wither Dragons", "Track").getDisplay());
        // Global OneConfig search reads searchTags, but ignores category/subcategory headings.
        assertEquals(List.of("Kung", "Garden", "6th Visitor Alarm"),
            property(tree, "6th Visitor Alarm", "Enabled").getMetadata("searchTags"));
        assertEquals(List.of("Kung", "Garden", "6th Visitor Alarm"),
            property(tree, "6th Visitor Alarm", "Volume (%)").getMetadata("searchTags"));
    }

    @Test
    public void removableColorRowsGetANativeRemoveButtonThatRespectsAvailability() {
        var removed = new AtomicInteger();
        var allowed = new AtomicBoolean(true);
        var row = SettingEntry.colorRemove("Threshold 1", () -> 0xFFFFFFFF, () -> { }, removed::incrementAndGet);
        var bridge = create(feature("Bow Draw Indicator", new AtomicBoolean(), () -> { }, List.of(row)), allowed);
        var remove = property(bridge.tree(), "Bow Draw Indicator", "Threshold 1 / Remove");
        Runnable action = remove.getMetadata("runnable");
        allowed.set(false);
        action.run();
        assertEquals(0, removed.get());
        allowed.set(true);
        action.run();
        assertEquals(1, removed.get());
    }

    @Test
    public void liveControlsUseSameBackingValuesAndProfileResetAttemptsAreIgnored() {
        AtomicBoolean enabled = new AtomicBoolean();
        AtomicBoolean writesAllowed = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger value = new AtomicInteger(14);
        var feature = feature("Feature", enabled, () -> { enabled.set(!enabled.get()); writes.incrementAndGet(); },
            List.of(SettingEntry.stepper("Number", value::get, value::set, 0, 28, 2)));
        var bridge = create(feature, writesAllowed);
        Property<?> master = property(bridge.tree(), "Feature", "Enabled");
        master.setAs(true);
        master.setAs(true);
        assertTrue(enabled.get());
        assertEquals(1, writes.get());
        enabled.set(false);
        assertEquals(false, master.get());
        Property<?> number = property(bridge.tree(), "Feature", "Number");
        number.setAs(15);
        assertEquals(16, value.get());
        number.setAs(999);
        assertEquals(28, value.get());
        writesAllowed.set(false);
        master.setAs(true);
        number.setAs(0);
        assertFalse(enabled.get());
        assertEquals(28, value.get());
        writesAllowed.set(true);
        master.setAs(true);
        assertEquals(2, writes.get());
    }

    @Test
    public void enumsAndNestedDependenciesFollowValidatedSettingsWithoutCycling() {
        AtomicBoolean prediction = new AtomicBoolean(true);
        AtomicReference<SplitsConfig.PredictionMode> selected = new AtomicReference<>(SplitsConfig.PredictionMode.PHASE_END);
        AtomicInteger writes = new AtomicInteger();
        var choice = SettingEntry.choice("Update", SplitsConfig.PredictionMode.values(), selected::get,
            value -> { selected.set(value); writes.incrementAndGet(); }, SplitsConfig.PredictionMode::label);
        var bridge = create(feature("Splits", new AtomicBoolean(), () -> { }, List.of(
            SettingEntry.toggle("Prediction", prediction::get, () -> prediction.set(!prediction.get())).withChildren(List.of(choice))
        )), new AtomicBoolean(true));
        Property<?> nativeChoice = property(bridge.tree(), "Splits", "Prediction / Update");
        assertArrayEquals(new String[] {"Phase End", "Live"}, nativeChoice.getMetadata("options"));
        nativeChoice.setAs(1);
        assertEquals(SplitsConfig.PredictionMode.LIVE, selected.get());
        assertEquals(1, writes.get());
        property(bridge.tree(), "Splits", "Prediction").setAs(false);
        assertEquals(Property.Display.DISABLED, nativeChoice.getDisplay());
        property(bridge.tree(), "Splits", "Prediction").setAs(true);
        assertEquals(Property.Display.SHOWN, nativeChoice.getDisplay());
    }

    @Test
    public void numericUnitsAndDefaultsMatchKungRatherThanRawStorageUnits() {
        var defaults = new KungOneConfigTree(KungSettings.defaults(), () -> { }, () -> true);
        Tree tree = defaults.tree();
        assertEquals(70, property(tree, "6th Visitor Alarm", "Volume (%)").get());
        if (!com.github.beng420.kung.KungBuild.MODRINTH) {
            assertEquals(0.5, (Double) property(tree, "Blood rush helper", "Title Time (s)").get(), 0.00001);
        }
        var live = new KungOneConfigTree(KungSettings.defaults(), () -> { }, () -> true);
        live.applyDefaults(defaults);
        assertEquals(false, property(live.tree(), "Feast Progress", "Enabled").getMetadata("default"));
        assertEquals("60", property(live.tree(), "Feast Progress", "Kernel Timeout (s)").getMetadata("default"));
    }

    @Test
    public void nativeBackendReplacesOldLauncherCardWithoutReadingOrWritingAnotherConfig() {
        AtomicInteger saves = new AtomicInteger();
        AtomicBoolean allowed = new AtomicBoolean(true);
        var bridge = new KungOneConfigTree(KungSettings.defaults(), saves::incrementAndGet, allowed::get);
        var backend = new NoDiskBackend();
        var oldCard = Tree.tree("kung");
        oldCard.addMetadata(Backend.UI_ONLY_METADATA, true);
        oldCard.addMetadata("on_click", (Runnable) () -> fail("Old Kung overlay callback must not survive"));
        backend.register(oldCard);
        Tree registered = backend.register(bridge.tree()).get();
        assertSame(bridge.tree(), registered);
        assertNull(registered.getMetadata("on_click"));
        assertTrue(backend.save(registered));
        assertEquals(1, saves.get());
        allowed.set(false);
        backend.save(registered);
        assertEquals(1, saves.get());
    }

    @Test
    public void mouseKeyboardAndClearingKeepKungInputOwnershipAndExistingScanCodes() {
        AtomicReference<String> raw = new AtomicReference<>("key:65:30");
        var setting = SettingEntry.keybind("Loadout", () -> "A", raw::get, raw::set);
        var bridge = create(feature("Loadouts", new AtomicBoolean(), () -> { }, List.of(setting)), new AtomicBoolean(true));
        Property<?> property = property(bridge.tree(), "Loadouts", "Loadout");
        assertEquals(true, property.getMetadata("singleKey"));
        assertEquals(true, property.getMetadata("oc_no_mc_mirror"));
        OneConfigKeybind initial = (OneConfigKeybind) property.get();
        assertSame(initial, property.get());
        assertArrayEquals(new int[] {65}, initial.getKeyCodes());
        assertFalse(initial.getAction().invoke(true));
        property.setAs(new OneConfigKeybind(new int[] {65}, new int[0], (byte) 0, 0, pressed -> false));
        assertEquals("key:65:30", raw.get());
        property.setAs(new OneConfigKeybind(new int[0], new int[] {3}, (byte) 0, 0, pressed -> false));
        assertEquals("mouse:3", raw.get());
        raw.set("key:66:48");
        assertArrayEquals(new int[] {66}, ((OneConfigKeybind) property.get()).getKeyCodes());
        property.setAs(new OneConfigKeybind(new int[] {67}, new int[0], (byte) 0, 0, pressed -> false));
        assertEquals("key:67:0", raw.get());
        property.setAs(new OneConfigKeybind(new int[0], new int[0], (byte) 0, 0, pressed -> false));
        assertEquals("", raw.get());
    }

    @Test
    public void flatteningRetainsSimilarlyNamedFileGroupsAndActionButtons() {
        AtomicInteger actions = new AtomicInteger();
        var first = SettingEntry.group("Arrow: a-b.wav").withChildren(List.of(SettingEntry.button("Test", "Play", actions::incrementAndGet)));
        var second = SettingEntry.group("Arrow: a_b.wav").withChildren(List.of(SettingEntry.button("Test", "Play", actions::incrementAndGet)));
        var bridge = create(feature("Custom Sounds", new AtomicBoolean(), () -> { }, List.of(first, second)), new AtomicBoolean(true));
        assertEquals(3, bridge.tree().map.size());
        Property<?> button = property(bridge.tree(), "Custom Sounds", "Arrow: a-b.wav / Test");
        assertEquals(Visualizer.ButtonVisualizer.class, button.getMetadata("visualizer"));
        ((Runnable) button.getMetadata("runnable")).run();
        assertEquals(1, actions.get());
    }

    @Test
    public void colorRowsBecomeButtonsThatShowTheirHexAndOpenTheSharedPopup() {
        AtomicInteger color = new AtomicInteger(0xFF4D8DFF);
        AtomicInteger edits = new AtomicInteger();
        var row = SettingEntry.color("To Place Color", color::get, edits::incrementAndGet);
        var bridge = create(feature("Greenhouse Hologram", new AtomicBoolean(), () -> { }, List.of(row)), new AtomicBoolean(true));
        Property<?> button = property(bridge.tree(), "Greenhouse Hologram", "To Place Color");
        assertEquals(Visualizer.ButtonVisualizer.class, button.getMetadata("visualizer"));
        assertEquals("#4D8DFF", button.getMetadata("text"));
        ((Runnable) button.getMetadata("runnable")).run();
        assertEquals(1, edits.get());
        color.set(0xFF112233);
        bridge.refresh();
        assertEquals("#112233", button.getMetadata("text"));
    }

    private static int countControls(List<SettingEntry> settings) {
        return settings.stream().mapToInt(setting -> (setting.kind() == SettingKind.GROUP ? 0
            : setting.kind() == SettingKind.COLOR_REMOVE ? 2 : 1) + countControls(setting.children())).sum();
    }

    private static KungOneConfigTree create(KungSettings.FeatureEntry feature, AtomicBoolean allowed) {
        return new KungOneConfigTree(List.of(new KungSettings.CategoryEntry("Category", List.of(feature))), () -> { }, allowed::get);
    }

    private static KungSettings.FeatureEntry feature(String name, AtomicBoolean enabled, Runnable toggle, List<SettingEntry> settings) {
        return new KungSettings.FeatureEntry(() -> name, enabled::get, () -> true, toggle, settings, false, List.of(), false);
    }

    private static Property<?> property(Tree tree, String feature, String title) {
        return tree.map.values().stream().filter(node -> title.equals(node.getTitle()) && feature.equals(node.getMetadata("subcategory")))
            .map(node -> (Property<?>) node).findFirst().orElseThrow(() -> new AssertionError(feature + " / " + title));
    }

    private static final class NoDiskBackend extends Backend {
        @Override protected Tree load0(String id) { throw new AssertionError("OneConfig must not load over Kung settings"); }
        @Override protected boolean save0(Tree tree) { throw new AssertionError("OneConfig must not save a separate Kung config"); }
        @Override protected boolean delete0(Tree tree) { throw new AssertionError("OneConfig must not delete Kung settings"); }
        @Override protected boolean corrupt0(Tree tree) { throw new AssertionError("OneConfig must not back up Kung settings"); }
    }
}
