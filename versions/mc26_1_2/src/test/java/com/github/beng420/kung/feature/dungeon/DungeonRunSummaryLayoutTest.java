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
    public void namesAreBoldSoARestyledNameCannotShiftItsRow() {
        ToIntFunction<String> width = text -> text.chars().map(c -> c == 0xE000 ? 1 : 5).sum();
        Component line = DungeonRunSummaryLayout.playerLine("Bob", 0xFFFFFFFF, "4 Secrets", 80, width);
        assertTrue(line.getSiblings().get(0).getStyle().isBold());
        assertFalse(line.getSiblings().get(2).getStyle().isBold());
        // Bold adds one pixel per glyph; every row still starts its details at the same pixel.
        assertEquals(15 + 3, DungeonRunSummaryLayout.nameWidth("Bob", width));
        List<DungeonRunSummaryLayout.Row> rows = List.of(row("Bob", "1", "2", "3"), row("EternityMain", "1", "2", "3"));
        List<Component> lines = DungeonRunSummaryLayout.lines(rows, width);
        int column = DungeonRunSummaryLayout.nameColumnWidth(List.of("Bob", "EternityMain"), width);
        for (int i = 0; i < rows.size(); i++) {
            int padding = lines.get(i).getSiblings().get(1).getString().length();
            assertEquals(column, DungeonRunSummaryLayout.nameWidth(rows.get(i).name(), width) + padding);
        }
    }

    @Test
    public void soloAndTotalRoomsAreOneColumnWithThePaddingInFront() {
        ToIntFunction<String> width = text -> text.chars().map(c -> c == 0xE000 ? 1 : 6).sum();
        List<DungeonRunSummaryLayout.Row> rows = List.of(
            new DungeonRunSummaryLayout.Row("Alice", 0xFFFFFFFF, List.of(
                DungeonRunSummaryLayout.Cell.number("rooms", "2-7"), DungeonRunSummaryLayout.Cell.text(" Rooms"))),
            new DungeonRunSummaryLayout.Row("Bob", 0xFFFFFFFF, List.of(
                DungeonRunSummaryLayout.Cell.number("rooms", "7-12"), DungeonRunSummaryLayout.Cell.text(" Rooms"))));
        List<Component> lines = DungeonRunSummaryLayout.lines(rows, width);
        for (Component line : lines) {
            String text = line.getString();
            assertFalse("no gap after the dash in '" + text + "'", text.contains("- ") || text.contains("-" + DungeonRunSummaryLayout.PIXEL_SPACE));
        }
        // Alice's shorter "2-7" sits one character (6 px) further right, so " Rooms" starts at the same pixel.
        String alice = lines.get(0).getString(), bob = lines.get(1).getString();
        assertEquals(6, pixelsBefore(lines.get(0), alice.indexOf('2'), width) - pixelsBefore(lines.get(1), bob.indexOf('7'), width));
        assertEquals(pixelsBefore(lines.get(0), alice.indexOf(" Rooms"), width),
            pixelsBefore(lines.get(1), bob.indexOf(" Rooms"), width));
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
    public void numbersWithFewerDigitsShiftRightSoEveryColumnLinesUp() {
        ToIntFunction<String> width = text -> text.chars().map(c -> c == 0xE000 ? 1 : 6).sum();
        List<DungeonRunSummaryLayout.Row> rows = List.of(
            row("Alice", "12", "3", "10"), row("Bob", "5", "12", "7"), row("Cy", "?", "0", "100"));
        List<Component> lines = DungeonRunSummaryLayout.lines(rows, width);
        for (String marker : List.of(" Secrets", " Rooms", " Deaths")) {
            int firstX = -1;
            for (Component line : lines) {
                String text = line.getString();
                int x = pixelsBefore(line, text.indexOf(marker), width);
                if (firstX < 0) firstX = x;
                assertEquals("column " + marker.strip() + " in '" + text + "'", firstX, x);
            }
        }
        // Bob's one-digit secrets sit one digit (6 px) further right than Alice's two digits.
        String alice = lines.get(0).getString(), bob = lines.get(1).getString();
        assertEquals(6, pixelsBefore(lines.get(1), bob.indexOf('5'), width) - pixelsBefore(lines.get(0), alice.indexOf("12"), width));
    }

    /** Where the character at {@code end} of the line's text starts, with the name measured as drawn (bold). */
    private static int pixelsBefore(Component line, int end, ToIntFunction<String> width) {
        String text = line.getString();
        String name = line.getSiblings().get(0).getString();
        return DungeonRunSummaryLayout.nameWidth(name, width) + width.applyAsInt(text.substring(name.length(), end));
    }

    private static DungeonRunSummaryLayout.Row row(String name, String secrets, String rooms, String deaths) {
        return new DungeonRunSummaryLayout.Row(name, 0xFFFFFFFF, List.of(
            DungeonRunSummaryLayout.Cell.number("secrets", secrets), DungeonRunSummaryLayout.Cell.text("/50 Secrets | "),
            DungeonRunSummaryLayout.Cell.number("rooms", rooms), DungeonRunSummaryLayout.Cell.text(" Rooms | "),
            DungeonRunSummaryLayout.Cell.number("deaths", deaths), DungeonRunSummaryLayout.Cell.text(" Deaths")));
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
            assertEquals(column, DungeonRunSummaryLayout.nameWidth(name, width) + padding.length());
            assertTrue(padding.length() >= 8);
        }
    }
}
