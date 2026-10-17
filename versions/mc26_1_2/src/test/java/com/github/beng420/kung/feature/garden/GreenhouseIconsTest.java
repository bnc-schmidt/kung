package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Test;

/** The icon table is plain data; the stacks built from it need a running game and are not tested here. */
public final class GreenhouseIconsTest {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{63,64}");
    private static final Pattern ITEM = Pattern.compile("minecraft:[a-z0-9_]+");

    @Test
    public void everyKnownCropHasAnIcon() {
        Map<String, GreenhouseIcons.Icon> table = GreenhouseIcons.table();

        assertEquals(GreenhouseCrops.NAMES.size(), table.size());
        for (String crop : GreenhouseCrops.NAMES) {
            GreenhouseIcons.Icon icon = table.get(crop);
            assertNotNull(crop, icon);
            assertTrue(crop, icon.head() != null || icon.item() != null);
        }
    }

    @Test
    public void headsAreTextureHashesAndItemsAreVanillaIds() {
        for (Map.Entry<String, GreenhouseIcons.Icon> entry : GreenhouseIcons.table().entrySet()) {
            GreenhouseIcons.Icon icon = entry.getValue();
            if (icon.head() != null) assertTrue(entry.getKey(), HASH.matcher(icon.head()).matches());
            if (icon.item() != null) assertTrue(entry.getKey(), ITEM.matcher(icon.item()).matches());
        }
    }

    @Test
    public void everyHeadTextureBelongsToOneCrop() {
        long heads = GreenhouseIcons.table().values().stream().filter(icon -> icon.head() != null).count();
        long distinct = GreenhouseIcons.table().values().stream().map(GreenhouseIcons.Icon::head)
            .filter(hash -> hash != null).distinct().count();

        assertEquals(56, heads);
        assertEquals(heads, distinct);
    }

    @Test
    public void vanillaCropsUseTheirItemAndHeadsKeepAStandIn() {
        Map<String, GreenhouseIcons.Icon> table = GreenhouseIcons.table();

        assertEquals("minecraft:carrot", table.get("Carrot").item());
        assertNull(table.get("Carrot").head());
        assertNotNull(table.get("Cindershade").head());
        assertNull(table.get("Cindershade").item());
        // Crops that are also vanilla items keep the item as the stand-in for their head.
        assertNotNull(table.get("Cactus").head());
        assertEquals("minecraft:cactus", table.get("Cactus").item());
    }

    @Test
    public void parseRejectsBadData() {
        assertThrows(IllegalArgumentException.class, () -> GreenhouseIcons.parse("{\"schema\":2,\"icons\":{}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseIcons.parse("{\"schema\":1,\"icons\":{\"Not a crop\":{\"item\":\"minecraft:dirt\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseIcons.parse("{\"schema\":1,\"icons\":{\"Carrot\":{}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseIcons.parse("{\"schema\":1,\"icons\":{\"Carrot\":{\"head\":\"xyz\"}}}"));
        assertThrows(IllegalArgumentException.class,
            () -> GreenhouseIcons.parse("{\"schema\":1,\"icons\":{\"Carrot\":{\"item\":\"Carrot\"}}}"));
    }
}
