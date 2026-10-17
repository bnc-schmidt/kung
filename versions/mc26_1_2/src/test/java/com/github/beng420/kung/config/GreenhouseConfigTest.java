package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.config.category.GreenhouseConfig;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GreenhouseConfigTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultsMatchTheSpecAndOldFilesWithoutTheCategoryLoadWithThem() {
        assertDefaults(new GreenhouseConfig());
        assertDefaults(read("{}").greenhouse);
        KungConfig old = read("{\"feast\":{\"enabled\":true,\"x\":123}}");
        assertDefaults(old.greenhouse);
        assertTrue("Other categories stay as read", old.feast.enabled());
        assertEquals(123, old.feast.x());
        assertDefaults(read("{\"greenhouse\":null}").greenhouse);
        assertDefaults(read("{\"greenhouse\":\"junk\"}").greenhouse);
        assertDefaults(read("{\"greenhouse\":{}}").greenhouse);
    }

    @Test
    public void everySettingSurvivesASaveAndLoad() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("kung.json");
        KungConfig config = new KungConfig(file);
        config.greenhouse.setHologramOpacityPercent(55);
        config.greenhouse.setColorToPlace(0x112233);
        config.greenhouse.setColorWrong(0x445566);
        config.greenhouse.setColorExtra(0x778899);
        config.greenhouse.setColorWrongState(0xAABBCC);
        config.greenhouse.setHideWhenComplete(false);
        config.greenhouse.setToggleKeybind("key:66:48");

        KungConfig restored = new KungConfig(file);
        restored.load();
        GreenhouseConfig loaded = restored.greenhouse;
        assertEquals(0.55F, loaded.hologramOpacity(), 0.0001F);
        assertEquals(55, loaded.hologramOpacityPercent());
        assertEquals(0xFF112233, loaded.colorToPlace());
        assertEquals(0xFF445566, loaded.colorWrong());
        assertEquals(0xFF778899, loaded.colorExtra());
        assertEquals(0xFFAABBCC, loaded.colorWrongState());
        assertEquals(false, loaded.hideWhenComplete());
        assertEquals("key:66:48", loaded.toggleKeybind());
        assertEquals(66, loaded.toggleKey());
        assertTrue("The file keeps colors as plain RGB", Files.readString(file).contains("\"colorToPlace\": " + 0x112233));
    }

    @Test
    public void opacityIsClampedWhenSetAndWhenRead() {
        GreenhouseConfig config = new GreenhouseConfig();
        config.setHologramOpacity(5F);
        assertEquals(1F, config.hologramOpacity(), 0F);
        config.setHologramOpacity(-1F);
        assertEquals(0.05F, config.hologramOpacity(), 0F);
        config.setHologramOpacity(Float.NaN);
        assertEquals(0.30F, config.hologramOpacity(), 0F);
        config.setHologramOpacityPercent(0);
        assertEquals(5, config.hologramOpacityPercent());
        config.setHologramOpacityPercent(250);
        assertEquals(100, config.hologramOpacityPercent());

        assertEquals(1F, read("{\"greenhouse\":{\"hologramOpacity\":9}}").greenhouse.hologramOpacity(), 0F);
        assertEquals(0.05F, read("{\"greenhouse\":{\"hologramOpacity\":0.001}}").greenhouse.hologramOpacity(), 0F);
        assertEquals(0.05F, read("{\"greenhouse\":{\"hologramOpacity\":-3}}").greenhouse.hologramOpacity(), 0F);
    }

    @Test
    public void colorsKeepOnlyRgbAndAlwaysComeBackOpaque() {
        GreenhouseConfig config = new GreenhouseConfig();
        config.setColorWrong(0x80123456);
        assertEquals(0xFF123456, config.colorWrong());
        config.setColorExtra(0);
        assertEquals(0xFF000000, config.colorExtra());
        assertEquals(0xFFFFFFFF, read("{\"greenhouse\":{\"colorWrongState\":-1}}").greenhouse.colorWrongState());
    }

    @Test
    public void corruptValuesFallBackAloneAndNeverCostTheRestOfTheFile() {
        KungConfig config = read("""
            {"feast":{"enabled":true,"x":77},
             "greenhouse":{"hologramOpacity":"abc","colorToPlace":true,"colorWrong":[1],
                           "colorExtra":{"a":1},"hideWhenComplete":"yes","toggleKeybind":5,
                           "colorWrongState":65280}}
            """);
        assertEquals(0.30F, config.greenhouse.hologramOpacity(), 0F);
        assertEquals(GreenhouseConfig.DEFAULT_COLOR_TO_PLACE | 0xFF000000, config.greenhouse.colorToPlace());
        assertEquals(GreenhouseConfig.DEFAULT_COLOR_WRONG | 0xFF000000, config.greenhouse.colorWrong());
        assertEquals(GreenhouseConfig.DEFAULT_COLOR_EXTRA | 0xFF000000, config.greenhouse.colorExtra());
        assertTrue(config.greenhouse.hideWhenComplete());
        assertEquals("key:75:0", config.greenhouse.toggleKeybind());
        assertEquals(0xFF00FF00, config.greenhouse.colorWrongState());
        assertTrue(config.feast.enabled());
        assertEquals(77, config.feast.x());
    }

    @Test
    public void toggleKeybindAcceptsKeysMouseAndUnboundButNothingMalformed() {
        assertEquals("key:75:0", read("{\"greenhouse\":{\"toggleKeybind\":\"banana\"}}").greenhouse.toggleKeybind());
        assertEquals("key:75:0", read("{\"greenhouse\":{\"toggleKeybind\":null}}").greenhouse.toggleKeybind());
        GreenhouseConfig unbound = read("{\"greenhouse\":{\"toggleKeybind\":\"\"}}").greenhouse;
        assertEquals("", unbound.toggleKeybind());
        assertEquals(-1, unbound.toggleKey());

        GreenhouseConfig config = new GreenhouseConfig();
        assertEquals(75, config.toggleKey());
        config.setToggleKeybind("banana");
        config.setToggleKeybind(null);
        config.setToggleKeybind("key:");
        assertEquals("key:75:0", config.toggleKeybind());
        config.setToggleKeybind("mouse:3");
        assertEquals("mouse:3", config.toggleKeybind());
        assertEquals("A mouse button has no key code", -1, config.toggleKey());
        config.setToggleKeybind("key:-1:55");
        assertEquals("An unknown key has no key code either", -1, config.toggleKey());
        config.setToggleKeybind("");
        assertEquals("", config.toggleKeybind());
    }

    private static void assertDefaults(GreenhouseConfig config) {
        assertEquals(0.30F, config.hologramOpacity(), 0F);
        assertEquals(30, config.hologramOpacityPercent());
        assertEquals(0xFF4D8DFF, config.colorToPlace());
        assertEquals(0xFFFF3B3B, config.colorWrong());
        assertEquals(0xFFFF55D8, config.colorExtra());
        assertEquals(0xFFFFA126, config.colorWrongState());
        assertTrue("Hiding a finished hologram is on by default", config.hideWhenComplete());
        assertEquals("key:75:0", config.toggleKeybind());
        assertEquals("K", 75, config.toggleKey());
    }

    private static KungConfig read(String json) {
        return KungConfig.read(new StringReader(json));
    }
}
