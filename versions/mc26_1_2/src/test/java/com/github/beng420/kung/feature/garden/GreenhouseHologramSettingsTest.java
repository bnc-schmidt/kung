package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.category.GreenhouseConfig;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.input.KeyEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class GreenhouseHologramSettingsTest {
    private GreenhouseConfig config;

    @Before
    public void useAStandaloneConfig() {
        config = new GreenhouseConfig();
        GreenhouseHologramSettings.useConfig(() -> config);
    }

    @After
    public void restoreTheLiveConfig() {
        GreenhouseHologramSettings.useConfig(null);
        GreenhouseHologramSettings.setToggleHandler(null);
    }

    @Test
    public void gettersFollowTheDefaultsBeforeAnythingIsLoaded() {
        GreenhouseHologramSettings.useConfig(null);
        assertEquals(0.30F, GreenhouseHologramSettings.opacity(), 0F);
        assertEquals(0xFF4D8DFF, GreenhouseHologramSettings.colorToPlace());
        assertEquals(0xFFFF3B3B, GreenhouseHologramSettings.colorWrong());
        assertEquals(0xFFFF55D8, GreenhouseHologramSettings.colorExtra());
        assertEquals(0xFFFFA126, GreenhouseHologramSettings.colorWrongState());
        assertTrue(GreenhouseHologramSettings.hideWhenComplete());
        assertEquals(75, GreenhouseHologramSettings.toggleKey());
    }

    @Test
    public void gettersReadTheLiveValuesOnEveryCall() {
        config.setHologramOpacity(0.6F);
        config.setColorToPlace(0x010203);
        config.setColorWrong(0x040506);
        config.setColorExtra(0x070809);
        config.setColorWrongState(0x0A0B0C);
        config.setHideWhenComplete(false);
        config.setToggleKeybind("key:71:34");
        assertEquals(0.6F, GreenhouseHologramSettings.opacity(), 0F);
        assertEquals(0xFF010203, GreenhouseHologramSettings.colorToPlace());
        assertEquals(0xFF040506, GreenhouseHologramSettings.colorWrong());
        assertEquals(0xFF070809, GreenhouseHologramSettings.colorExtra());
        assertEquals(0xFF0A0B0C, GreenhouseHologramSettings.colorWrongState());
        assertFalse(GreenhouseHologramSettings.hideWhenComplete());
        assertEquals(71, GreenhouseHologramSettings.toggleKey());
    }

    @Test
    public void withOpacityScalesTheAlphaAndDropsTheIncomingOne() {
        config.setHologramOpacity(1F);
        assertEquals(0xFF4D8DFF, GreenhouseHologramSettings.withOpacity(0x4D8DFF, 1F));
        assertEquals(0xFF4D8DFF, GreenhouseHologramSettings.withOpacity(0x004D8DFF, 1F));
        assertEquals("The incoming alpha is not mixed in", 0x804D8DFF,
            GreenhouseHologramSettings.withOpacity(0x334D8DFF, 0.5F));
        assertEquals("A factor above one cannot pass full alpha", 0xFF4D8DFF,
            GreenhouseHologramSettings.withOpacity(0x4D8DFF, 4F));
        assertEquals(0x004D8DFF, GreenhouseHologramSettings.withOpacity(0x4D8DFF, 0F));
        assertEquals(0x004D8DFF, GreenhouseHologramSettings.withOpacity(0x4D8DFF, -2F));

        config.setHologramOpacity(0.30F);
        assertEquals(0x4D4D8DFF, GreenhouseHologramSettings.withOpacity(0x4D8DFF, 1F));
        assertEquals(0x264D8DFF, GreenhouseHologramSettings.withOpacity(0x4D8DFF, 0.5F));
        config.setHologramOpacity(0.05F);
        assertEquals(0x0D4D8DFF, GreenhouseHologramSettings.withOpacity(0x4D8DFF, 1F));
    }

    @Test
    public void theToggleKeyCallsTheHandlerOnlyForItsOwnKey() {
        AtomicInteger toggles = new AtomicInteger();
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(75, 37, 0));
        assertEquals("No handler set yet: nothing happens and nothing throws", 0, toggles.get());

        GreenhouseHologramSettings.setToggleHandler(toggles::incrementAndGet);
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(75, 37, 0));
        assertEquals("K is the default", 1, toggles.get());
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(76, 38, 0));
        GreenhouseHologramSettings.onMousePress(0);
        assertEquals(1, toggles.get());

        config.setToggleKeybind("key:66:48");
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(75, 37, 0));
        assertEquals("A rebound key replaces K", 1, toggles.get());
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(66, 48, 0));
        assertEquals(2, toggles.get());

        config.setToggleKeybind("");
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(66, 48, 0));
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(75, 37, 0));
        assertEquals("Unbound means no key toggles", 2, toggles.get());
    }

    @Test
    public void aMouseButtonCanBeTheToggleToo() {
        AtomicInteger toggles = new AtomicInteger();
        GreenhouseHologramSettings.setToggleHandler(toggles::incrementAndGet);
        config.setToggleKeybind("mouse:3");
        GreenhouseHologramSettings.onMousePress(3);
        assertEquals(1, toggles.get());
        GreenhouseHologramSettings.onMousePress(4);
        GreenhouseHologramSettings.onKeyPress(new KeyEvent(75, 37, 0));
        assertEquals(1, toggles.get());
        GreenhouseHologramSettings.setToggleHandler(null);
        GreenhouseHologramSettings.onMousePress(3);
        assertEquals(1, toggles.get());
    }
}
