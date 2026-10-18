package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.TextColor;
import org.junit.Test;

public final class DungeonRunSummaryLayoutTest {
    @Test
    public void proportionalNamesStartTheirSecretColumnAtExactlyTheSamePixel() {
        List<String> names = List.of("iiiiiiii", "WWWW", "Alice");
        ToIntFunction<String> width = text -> text.chars().map(c -> c == 'i' ? 2 : c == 'W' ? 8 : 5).sum();
        assertAligned(names, width);
        // Simulate a font resource reload with different advances, including an odd-pixel gap.
        assertAligned(names, text -> text.chars().map(c -> c == 'i' ? 3 : c == 'W' ? 11 : 7).sum());
    }

    @Test
    public void customPaddingFontNeverLeaksIntoNamesOrStats() {
        Component line = DungeonRunSummaryLayout.playerLine(
            "Alice", DungeonRunStats.DungeonClass.MAGE.argb(), "12 Secrets | 2-4 Rooms | P", 40, ignored -> 25);
        assertEquals("Alice", line.getSiblings().get(0).getString());
        assertEquals(FontDescription.DEFAULT, line.getSiblings().get(0).getStyle().getFont());
        assertEquals(DungeonRunSummaryLayout.SPACING_FONT, line.getSiblings().get(1).getStyle().getFont());
        assertFalse(line.getSiblings().get(1).getStyle().isBold());
        assertEquals(FontDescription.DEFAULT, line.getSiblings().get(2).getStyle().getFont());
        assertEquals("12 Secrets | 2-4 Rooms | P", line.getSiblings().get(2).getString());
        assertFalse(line.getSiblings().get(2).getStyle().isBold());
    }

    @Test
    public void nameCarriesTheClassColourAndDetailsAreHighlightedNotWhite() {
        Component line = DungeonRunSummaryLayout.playerLine(
            "Alice", DungeonRunStats.DungeonClass.MAGE.argb(), "12/52 Secrets | 2-4 Rooms | 0 Deaths", 40,
            ignored -> 25);
        assertEquals(TextColor.fromRgb(0x62DFFF), line.getSiblings().get(0).getStyle().getColor());
        Component details = line.getSiblings().get(2);
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), details.getStyle().getColor());
        assertEquals(TextColor.fromRgb(0xFFCC55), details.getSiblings().get(1).getStyle().getColor());
        assertEquals(TextColor.fromRgb(0x555555), details.getSiblings().get(5).getStyle().getColor());

        Component unknown = DungeonRunSummaryLayout.playerLine(
            "Bob", DungeonRunStats.DungeonClass.UNKNOWN.argb(), "?/52 Secrets", 40, ignored -> 25);
        assertEquals(TextColor.fromRgb(0xE9EDF2), unknown.getSiblings().get(0).getStyle().getColor());
    }

    @Test
    public void spacingResourceDefinesTheExactOnePixelAdvance() throws Exception {
        try (var stream = DungeonRunSummaryLayoutTest.class.getResourceAsStream("/assets/kung/font/chat_spacing.json")) {
            assertNotNull(stream);
            var resource = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var provider = resource.getAsJsonArray("providers").get(0).getAsJsonObject();
            assertEquals("space", provider.get("type").getAsString());
            assertEquals(1, provider.getAsJsonObject("advances").get(DungeonRunSummaryLayout.PIXEL_SPACE).getAsInt());
        }
    }

    private static void assertAligned(List<String> names, ToIntFunction<String> width) {
        int column = DungeonRunSummaryLayout.nameColumnWidth(names, width);
        for (String name : names) {
            Component line = DungeonRunSummaryLayout.playerLine(name, 0xFFFFFFFF, "4 Secrets", column, width);
            String padding = line.getSiblings().get(1).getString();
            assertTrue(padding.codePoints().allMatch(c -> c == 0xE000));
            assertEquals(column, width.applyAsInt(name) + padding.length());
            assertTrue(padding.length() >= 8);
        }
    }
}
