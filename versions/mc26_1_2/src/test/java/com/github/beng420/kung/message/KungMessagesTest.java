package com.github.beng420.kung.message;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.Test;

public final class KungMessagesTest {
    @Test
    public void formatsAreaAndBody() {
        assertEquals(
            "[Kung Room Sync] Connected",
            KungMessages.success(" Room Sync ", "Connected").getString()
        );
    }

    @Test
    public void supportsMessagesWithoutArea() {
        assertEquals("[Kung] Ready", KungMessages.info("Ready").getString());
    }

    @Test
    public void handlesOptionalValues() {
        assertEquals("[Kung] ", KungMessages.component(null, null, null).getString());
    }

    @Test
    public void sharedInfoColorsValuesStatesDragonsAndSprayWithoutChangingText() {
        String text = "Purple: Time: 16.20s | Arrows: 3 | Sprayed: no\nBlue dragon Ice Spray enabled";
        Component message = KungMessages.info("Debuff", text);
        assertEquals("[Kung Debuff] " + text, message.getString());
        assertEquals(0xAA55FF, colorAt(message, "Purple"));
        assertEquals(0x5599FF, colorAt(message, "Blue dragon"));
        assertEquals(0x99DDFF, colorAt(message, "Ice Spray"));
        assertEquals(0x99DDFF, colorAt(message, "Sprayed"));
        assertEquals(0xFF5555, colorAt(message, "no"));
        assertEquals(0xFFCC55, colorAt(message, "16.20s"));
        assertEquals(0x55FF55, colorAt(message, "enabled"));
        assertEquals(0xAAAAAA, colorAt(message, "Time:"));
    }

    @Test
    public void aVersionIsOneColouredValueNotJustItsLaterDigits() {
        String text = "Kung is up to date (v0.6.3, latest: v0.6.3). Update 0.6.4 is out; Time: 5.2s, 1,234 items, 2 x";
        Component message = KungMessages.info("Updater", text);
        assertEquals("[Kung Updater] " + text, message.getString());
        // Every character of "v0.6.3" has the value colour: the v, the 0 and the dots too.
        for (int start : new int[] {message.getString().indexOf("v0.6.3"), message.getString().lastIndexOf("v0.6.3")}) {
            for (int offset = 0; offset < "v0.6.3".length(); offset++) {
                assertEquals("offset " + offset, 0xFFCC55, colorAtIndex(message, start + offset));
            }
        }
        assertEquals(0xFFCC55, colorAt(message, "0.6.4"));
        assertEquals(0xAAAAAA, colorAt(message, "up to date"));
        assertEquals(0xAAAAAA, colorAt(message, "latest:"));
        // Other numbers keep their old behaviour.
        assertEquals(0xFFCC55, colorAt(message, "5.2s"));
        assertEquals(0xFFCC55, colorAt(message, "1,234"));
    }

    @Test
    public void sharedColorsPreserveCommandsPathsAndSeverity() {
        String text = "Trace saved: C:\\profiles\\Dungeons 26.1.2\\logs\\kung.log | /kung hud";
        assertEquals(text, KungMessages.detail(text).getString());
        assertEquals(0xFF5555, colorAt(KungMessages.error("Test", "enabled 42"), "enabled"));
        assertEquals(0xFFFF55, colorAt(KungMessages.warning("Test", "3"), "3"));
    }

    private static int colorAtIndex(Component component, int index) {
        List<Integer> colors = new ArrayList<>();
        component.visit((style, text) -> {
            for (int i = 0; i < text.length(); i++) colors.add(style.getColor().getValue());
            return Optional.empty();
        }, Style.EMPTY);
        return colors.get(index);
    }

    private static int colorAt(Component component, String word) {
        List<Integer> colors = new ArrayList<>();
        component.visit((style, text) -> {
            for (int i = 0; i < text.length(); i++) colors.add(style.getColor().getValue());
            return Optional.empty();
        }, Style.EMPTY);
        return colors.get(component.getString().indexOf(word));
    }
}
