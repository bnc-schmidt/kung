package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.github.beng420.kung.config.category.SplitsConfig;
import org.junit.Test;

public final class SettingEntryTest {
    @Test
    public void dropdownSelectionWritesOnlyTheRequestedEnumAndKeepsCycling() {
        AtomicReference<SplitsConfig.TimeFormat> selected = new AtomicReference<>(SplitsConfig.TimeFormat.MINUTES);
        AtomicInteger writes = new AtomicInteger();
        SettingEntry choice = SettingEntry.choice("Format", SplitsConfig.TimeFormat.values(), selected::get,
            value -> { selected.set(value); writes.incrementAndGet(); }, SplitsConfig.TimeFormat::label)
            .withTooltip("Clock format").withChildren(List.of(SettingEntry.group("Details")));
        assertEquals(SplitsConfig.TimeFormat.values().length, choice.choices().size());
        assertEquals(0, writes.get());
        choice.intConsumer().accept(1);
        assertEquals(SplitsConfig.TimeFormat.values()[1], selected.get());
        assertEquals(1, writes.get());
        assertEquals(1, choice.intSupplier().getAsInt());
        assertEquals(choice.choices().get(1), choice.choiceSupplier().get());
        choice.intConsumer().accept(-1);
        choice.intConsumer().accept(99);
        assertEquals(1, writes.get());
        choice.cycleChoice().run();
        assertEquals(2, writes.get());
        assertEquals(choice.choices().get(choice.intSupplier().getAsInt()), choice.choiceSupplier().get());
    }

    @Test
    public void keybindMetadataRetainsRawEncodingSeparatelyFromItsDisplayLabel() {
        AtomicReference<String> raw = new AtomicReference<>("key:65:30");
        SettingEntry keybind = SettingEntry.keybind("Loadout 1", () -> "A", raw::get, raw::set)
            .withTooltip("Choose a key").withChildren(List.of());
        assertEquals("A", keybind.textValue());
        assertEquals("key:65:30", keybind.rawKeybindSupplier().get());
        keybind.setText("mouse:3");
        assertEquals("mouse:3", raw.get());
    }

    @Test
    public void colorRowShowsRgbHexIgnoresAlphaAndOpensItsEditorOnClick() {
        AtomicInteger color = new AtomicInteger(0x804D8DFF);
        AtomicInteger edits = new AtomicInteger();
        SettingEntry row = SettingEntry.color("To Place Color", color::get, edits::incrementAndGet);
        assertEquals(SettingKind.COLOR, row.kind());
        assertEquals("#4D8DFF", row.choiceSupplier().get());
        color.set(0xFF0A0B0C);
        assertEquals("#0A0B0C", row.choiceSupplier().get());
        assertFalse(row.expandable());
        row.click(0, 0, 50);
        assertEquals(1, edits.get());
        assertEquals("Opening the editor does not change the color", 0xFF0A0B0C, color.get());
    }

    @Test
    public void generatesTypedSettingsAndAppliesValidatedValues() {
        AtomicBoolean enabled = new AtomicBoolean();
        SettingEntry toggle = SettingEntry.toggle("Enabled", enabled::get, () -> enabled.set(!enabled.get()));
        toggle.click(0, 0, 50);
        assertTrue(enabled.get());

        AtomicInteger value = new AtomicInteger(10);
        SettingEntry stepper = SettingEntry.stepper("Scale", value::get, value::set, 10, 20, 5);
        stepper.click(49, 0, 50);
        stepper.click(49, 0, 50);
        stepper.click(49, 0, 50);
        assertEquals(20, value.get());

        AtomicReference<String> text = new AtomicReference<>("");
        SettingEntry editor = SettingEntry.text("Name", text::get, text::set);
        editor.appendText("Room");
        editor.backspaceText();
        assertEquals("Roo", text.get());

        assertFalse(editor.expandable());
        assertTrue(SettingEntry.group("Group").withChildren(List.of(editor)).expandable());
    }
}
