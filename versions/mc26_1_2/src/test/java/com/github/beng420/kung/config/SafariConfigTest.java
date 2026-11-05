package com.github.beng420.kung.config;

import java.nio.file.Files;
import org.junit.Test;
import static com.github.beng420.kung.config.ConfigTestSupport.read;
import static org.junit.Assert.*;

public class SafariConfigTest {
    @Test
    public void missingAndNullCategoriesDefaultOffAndInvalidScalesAreClamped() {
        assertFalse(read("{}").safari.enabled());
        assertFalse(read("{\"safari\":null}").safari.enabled());
        assertEquals(75, read("{}").safari.scale());
        assertEquals(25, read("{\"safari\":{\"scale\":-5}}").safari.scale());
        assertEquals(300, read("{\"safari\":{\"scale\":10000}}").safari.scale());
    }

    @Test
    public void panelIsOnAndOnlyTheCurrentZoneOpensUnlessSaved() {
        assertTrue(read("{}").safari.background());
        assertFalse(read("{}").safari.allZones());
        assertTrue(read("{\"safari\":{\"scale\":80}}").safari.background());
        assertFalse(read("{\"safari\":{\"background\":false,\"allZones\":true}}").safari.background());
        assertTrue(read("{\"safari\":{\"background\":false,\"allZones\":true}}").safari.allZones());
    }

    @Test
    public void categorySettersPersistWithoutChangingFeastOrExistingHudPositions() throws Exception {
        var path = Files.createTempDirectory("kung-safari-config").resolve("kung.json");
        Files.writeString(path, """
            {"feast":{"enabled":true,"x":123,"y":74},"dungeonMap":{"x":91},
             "safari":{"x":24,"y":89,"scale":85}}
            """);
        KungConfig config = new KungConfig(path);
        config.load();
        config.safari.setEnabled(true);
        config.safari.setX(66);
        config.safari.setScale(105);
        KungConfig restored = new KungConfig(path);
        restored.load();
        assertTrue(restored.safari.enabled());
        assertEquals(66, restored.safari.x());
        assertEquals(89, restored.safari.y());
        assertEquals(105, restored.safari.scale());
        assertTrue(restored.feast.enabled());
        assertEquals(123, restored.feast.x());
        assertEquals(74, restored.feast.y());
        assertEquals(91, restored.dungeon.x());
    }
}
