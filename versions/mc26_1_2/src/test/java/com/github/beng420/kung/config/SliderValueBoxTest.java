package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** What the value box beside a slider shows and accepts; the box and the knob are the screen's. */
public final class SliderValueBoxTest {
    private final AtomicInteger stored = new AtomicInteger();

    private SettingEntry slider(String label, int value, int min, int max) {
        stored.set(value);
        return SettingEntry.slider(label, stored::get, stored::set, min, max, 1);
    }

    @Test
    public void aNumberTypedPastTheSlidersEndsIsTheValue() {
        SettingEntry delay = slider("Warn Within (s)", 30, 0, 300);
        assertEquals("30", delay.sliderNumber());
        assertTrue(delay.setSliderNumber("600"));
        assertEquals(600, stored.get());
        assertEquals("600", delay.sliderNumber());
        assertTrue(delay.setSliderNumber("0"));
        assertEquals(0, stored.get());
    }

    @Test
    public void tenthsAndHundredthsAreTypedInTheUnitTheSliderShows() {
        SettingEntry title = slider("Title Time", 35, 1, 50);
        assertEquals("3.5", title.sliderNumber());
        assertEquals("3.5s", title.sliderValue());
        assertTrue(title.setSliderNumber("7"));
        assertEquals(70, stored.get());
        assertTrue(title.setSliderNumber("4.25"));
        assertEquals(43, stored.get());

        SettingEntry pitch = slider("Cactus Pitch", 135, 50, 200);
        assertEquals("1.35", pitch.sliderNumber());
        assertTrue(pitch.setSliderNumber("0.5"));
        assertEquals(50, stored.get());

        SettingEntry volume = slider("Volume", 15, 0, 50);
        assertEquals("1.5", volume.sliderNumber());
        // A percentage is typed as the percentage, whatever else the label says.
        SettingEntry percent = slider("Volume (%)", 80, 1, 100);
        assertEquals("80", percent.sliderNumber());
        assertTrue(percent.setSliderNumber("250"));
        assertEquals(250, stored.get());
    }

    @Test
    public void textThatIsNoNumberLeavesTheValueAlone() {
        SettingEntry delay = slider("Delay (s)", 30, 0, 300);
        for (String text : new String[] {"", ".", "abc", "-1", "1e3", null}) {
            assertFalse(String.valueOf(text), delay.setSliderNumber(text));
        }
        assertEquals(30, stored.get());
        // A number too big for an int once it is in tenths stops at the int limit; the setting clamps it from there.
        SettingEntry title = slider("Title Time", 35, 1, 50);
        assertTrue(title.setSliderNumber("999999999"));
        assertEquals(Integer.MAX_VALUE, stored.get());
    }

    @Test
    public void theFieldTakesDigitsAndAtMostTwoDecimals() {
        for (String text : new String[] {"", "12", "12.", "12.3", "12.34", ".5", "123456789"}) {
            assertTrue(text, SettingEntry.acceptsNumberInput(text));
        }
        for (String text : new String[] {"12.345", "1234567890", "-1", "1e3", "a", "1,5", " 1"}) {
            assertFalse(text, SettingEntry.acceptsNumberInput(text));
        }
    }
}
