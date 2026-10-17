package com.github.beng420.kung.ui;

import static org.junit.Assert.assertEquals;

import com.mojang.blaze3d.font.GlyphInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import org.junit.Test;

public final class UiTextFieldTest {
    /** Every glyph is six units wide: enough for the box to measure and scroll its text. */
    private static Font font() {
        BakedGlyph glyph = (BakedGlyph) java.lang.reflect.Proxy.newProxyInstance(
            BakedGlyph.class.getClassLoader(), new Class<?>[] {BakedGlyph.class},
            (proxy, method, args) -> GlyphInfo.simple(6F));
        GlyphSource source = new GlyphSource() {
            @Override public BakedGlyph getGlyph(int codepoint) { return glyph; }
            @Override public BakedGlyph getRandomGlyph(net.minecraft.util.RandomSource random, int codepoint) { return glyph; }
        };
        return new Font(new Font.Provider() {
            @Override public GlyphSource glyphs(FontDescription description) { return source; }
            @Override public EffectGlyph effect() { return null; }
        }) { };
    }

    @Test
    public void aFieldKeepsAPastedLinkInsteadOfCuttingItAtVanillasThirtyTwoCharacters() {
        UiTextField field = new UiTextField(font(), Component.literal("link"));
        String link = "https://skymutations.eu/greenhouse?layout=" + "N".repeat(3000);

        field.setValue(link);

        assertEquals(link, field.value());
    }

    /** The text and hint position EditBox cached the last time it ran its private position update. */
    private static int[] textPosition(UiTextField field) throws ReflectiveOperationException {
        java.lang.reflect.Field controlField = UiTextField.class.getDeclaredField("control");
        controlField.setAccessible(true);
        Object control = controlField.get(field);
        int[] position = new int[2];
        String[] names = {"textX", "textY"};
        for (int i = 0; i < names.length; i++) {
            java.lang.reflect.Field f = net.minecraft.client.gui.components.EditBox.class.getDeclaredField(names[i]);
            f.setAccessible(true);
            position[i] = f.getInt(control);
        }
        return position;
    }

    @Test
    public void textAndHintSitInTheFinalBoundsFromTheFirstFrame() throws ReflectiveOperationException {
        UiTextField field = new UiTextField(font(), Component.literal("search")).hint("Search");

        field.setBounds(10, 100, 120, 18);

        // 4 units in from the left border; vertically centred for an 8 unit line: y + (height - 8) / 2.
        assertEquals(14, textPosition(field)[0]);
        assertEquals(105, textPosition(field)[1]);
        field.setBounds(30, 200, 90, 24);
        assertEquals(34, textPosition(field)[0]);
        assertEquals(208, textPosition(field)[1]);
    }

    @Test
    public void explicitLimitsStillApply() {
        UiTextField field = new UiTextField(font(), Component.literal("name")).maxLength(16);

        field.setValue("abcdefghijklmnopqrstuvwxyz");

        assertEquals("abcdefghijklmnop", field.value());
    }
}
