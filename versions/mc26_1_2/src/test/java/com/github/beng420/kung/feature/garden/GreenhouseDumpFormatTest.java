package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseDumpFormat.CellRef;
import com.github.beng420.kung.feature.garden.GreenhouseDumpFormat.Texture;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public final class GreenhouseDumpFormatTest {
    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void cellIsTheFlooredOffsetFromTheCornerWithTheRawDefaultMapping() {
        // Corner (-5, 73, -5): a stand at the centre of the corner block.
        CellRef first = GreenhouseDumpFormat.cell(-4.5, -4.5, -5, -5);
        assertEquals(new CellRef(0, 0, true, 0, 0), first);

        CellRef middle = GreenhouseDumpFormat.cell(-1.5, 0.5, -5, -5);
        assertEquals(new CellRef(3, 5, true, 5, 3), middle);

        // Block edges belong to the higher cell; the last cell is 9.
        assertEquals(new CellRef(1, 0, true, 0, 1), GreenhouseDumpFormat.cell(-4.0, -5.0, -5, -5));
        assertEquals(new CellRef(9, 9, true, 9, 9), GreenhouseDumpFormat.cell(4.999, 4.999, -5, -5));
    }

    @Test
    public void positionsOutsideTheGridKeepTheirNegativeOrLargeOffsets() {
        CellRef west = GreenhouseDumpFormat.cell(-5.5, 0.0, -5, -5);
        assertEquals(-1, west.dx());
        assertEquals(5, west.dz());
        assertFalse(west.inGrid());

        CellRef beyond = GreenhouseDumpFormat.cell(5.0, 5.0, -5, -5);
        assertEquals(10, beyond.dx());
        assertFalse(beyond.inGrid());
    }

    @Test
    public void rawMappingAgreesWithTheDefaultOrientation() {
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                var offset = GreenhouseOrientation.IDENTITY.offset(row, column);
                CellRef cell = GreenhouseDumpFormat.cell(91 + offset.dx() + 0.5, 12 + offset.dz() + 0.5, 91, 12);
                assertEquals(row, cell.row());
                assertEquals(column, cell.column());
            }
        }
    }

    @Test
    public void gridAreaIsTheGridPlusOneBlockAndYSeventyToEightyTwo() {
        assertTrue(GreenhouseDumpFormat.inGridArea(-5.0, 74.0, -5.0, -5, -5));
        assertTrue(GreenhouseDumpFormat.inGridArea(-6.0, 70.0, -6.0, -5, -5));
        assertTrue(GreenhouseDumpFormat.inGridArea(6.0, 82.0, 6.0, -5, -5));
        assertFalse(GreenhouseDumpFormat.inGridArea(-6.1, 74.0, 0.0, -5, -5));
        assertFalse(GreenhouseDumpFormat.inGridArea(0.0, 74.0, 6.1, -5, -5));
        assertFalse(GreenhouseDumpFormat.inGridArea(0.0, 69.9, 0.0, -5, -5));
        assertFalse(GreenhouseDumpFormat.inGridArea(0.0, 82.1, 0.0, -5, -5));
    }

    @Test
    public void textureValueGivesTheUrlAndTheHashAtItsEnd() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123def456\"}}}";

        Optional<Texture> texture = GreenhouseDumpFormat.texture(base64(json));

        assertTrue(texture.isPresent());
        assertEquals("http://textures.minecraft.net/texture/abc123def456", texture.get().url());
        assertEquals("abc123def456", texture.get().hash());
        assertEquals(json, texture.get().json());
    }

    @Test
    public void textureValueToleratesSpacesUnquotedKeysAndPaddingGaps() {
        // Hand-made heads are not always valid JSON (unquoted keys) and values arrive with a trailing newline.
        String loose = "{textures:{SKIN:{url:\"https://textures.minecraft.net/texture/ff00\"}}}";
        assertEquals("ff00", GreenhouseDumpFormat.texture(base64(loose) + "\n").orElseThrow().hash());
        String spaced = "{ \"textures\" : { \"SKIN\" : { \"url\" : \"http://x/texture/9a\" , \"metadata\":{} } } }";
        assertEquals("9a", GreenhouseDumpFormat.texture(base64(spaced)).orElseThrow().hash());
    }

    @Test
    public void junkTextureValuesAreEmptyAndNeverThrow() {
        assertTrue(GreenhouseDumpFormat.texture(null).isEmpty());
        assertTrue(GreenhouseDumpFormat.texture("").isEmpty());
        assertTrue(GreenhouseDumpFormat.texture("   ").isEmpty());
        assertTrue(GreenhouseDumpFormat.texture("not base64 !!!").isEmpty());
        assertTrue(GreenhouseDumpFormat.texture(base64("{\"textures\":{}}")).isEmpty());
        assertTrue(GreenhouseDumpFormat.texture(base64("{\"url\":\"http://x/texture/\"}")).isEmpty());
        assertTrue(GreenhouseDumpFormat.texture("A").isEmpty());
    }

    @Test
    public void quoteIsOneCleanLine() {
        assertEquals("\"Plain\"", GreenhouseDumpFormat.quote("Plain"));
        assertEquals("\"Gold Carpenter\"", GreenhouseDumpFormat.quote("§6Gold §lCarpenter"));
        assertEquals("\"a b c\"", GreenhouseDumpFormat.quote("a\nb\tc"));
        assertEquals("\"say \\\"hi\\\"\"", GreenhouseDumpFormat.quote("say \"hi\""));
        assertEquals("-", GreenhouseDumpFormat.quote(null));
        assertTrue(GreenhouseDumpFormat.quote("x".repeat(5000)).length() < 500);
    }

    @Test
    public void countsListTheBiggestFirst() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:bat", 1);
        counts.put("minecraft:armor_stand", 130);
        counts.put("minecraft:allay", 1);

        assertEquals("minecraft:armor_stand=130 minecraft:allay=1 minecraft:bat=1", GreenhouseDumpFormat.counts(counts));
        assertEquals("-", GreenhouseDumpFormat.counts(Map.of()));
    }

    @Test
    public void bodyEndsWithTheChecklistEvenWhenCut() {
        GreenhouseDumpFormat.Body small = new GreenhouseDumpFormat.Body();
        small.line("one");
        small.line("two");
        String text = small.finish();
        assertTrue(text.startsWith("one\ntwo\n"));
        assertTrue(text.contains("CHECKLIST"));
        assertFalse(small.truncated());

        GreenhouseDumpFormat.Body big = new GreenhouseDumpFormat.Body();
        String filler = "x".repeat(990);
        for (int i = 0; i < 1000; i++) big.line(filler);
        String cut = big.finish();
        assertTrue(big.truncated());
        assertTrue(cut.contains("truncated"));
        assertTrue(cut.length() <= GreenhouseDumpFormat.MAX_CHARS);
        assertTrue(cut.trim().endsWith(GreenhouseDumpFormat.checklist().getLast()));
    }

    @Test
    public void checklistNamesTheOpenQuestions() {
        String all = String.join("\n", GreenhouseDumpFormat.checklist());
        for (String topic : new String[] {"Stand position", "Head names", "armor stands", "Soil", "Pets and pests",
            "Orientation"}) {
            assertTrue(topic, all.contains(topic));
        }
    }
}
