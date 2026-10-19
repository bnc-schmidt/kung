package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.message.KungMessages;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** Pads measured names in chat pixels, independently of proportional or resource-pack fonts. */
final class DungeonRunSummaryLayout {
    static final String PIXEL_SPACE = "\uE000";
    static final FontDescription.Resource SPACING_FONT =
        new FontDescription.Resource(Identifier.fromNamespaceAndPath("kung", "chat_spacing"));
    private static final int COLUMN_GAP = 8;
    private static final Style TEXT_STYLE = Style.EMPTY.withFont(FontDescription.DEFAULT).withBold(false);
    private static final Style SPACING_STYLE = Style.EMPTY.withFont(SPACING_FONT).withBold(false);

    private DungeonRunSummaryLayout() { }

    static int nameColumnWidth(List<String> names, ToIntFunction<String> width) {
        return names.stream().mapToInt(width).max().orElse(0) + COLUMN_GAP;
    }

    /** One piece of a player's details; a {@code column} marks a number that lines up right-aligned across players. */
    record Cell(String text, String column) {
        static Cell text(String text) { return new Cell(text, null); }
        static Cell number(String column, String text) { return new Cell(text, column); }
    }

    record Row(String name, int nameColor, List<Cell> cells) { }

    /** One line per row, names and every number column measured in chat pixels over all rows. */
    static List<Component> lines(List<Row> rows, ToIntFunction<String> width) {
        int nameColumn = nameColumnWidth(rows.stream().map(Row::name).toList(), width);
        Map<String, Integer> columnWidths = new HashMap<>();
        for (Row row : rows) {
            for (Cell cell : row.cells()) {
                if (cell.column() != null) columnWidths.merge(cell.column(), width.applyAsInt(cell.text()), Math::max);
            }
        }
        return rows.stream().map(row -> {
            MutableComponent details = Component.empty();
            for (Cell cell : row.cells()) {
                if (cell.column() != null) {
                    int padding = columnWidths.get(cell.column()) - width.applyAsInt(cell.text());
                    if (padding > 0) details.append(spacing(padding));
                }
                details.append(KungMessages.highlight(cell.text()).withStyle(TEXT_STYLE));
            }
            return playerLine(row.name(), row.nameColor(), details, nameColumn, width);
        }).toList();
    }

    /** The name takes {@code nameColor} (class colour); details use the shared report highlighting. */
    static Component playerLine(String name, int nameColor, String details, int nameColumnWidth,
                                ToIntFunction<String> width) {
        return playerLine(name, nameColor, KungMessages.highlight(details).withStyle(TEXT_STYLE), nameColumnWidth, width);
    }

    private static Component playerLine(String name, int nameColor, Component details, int nameColumnWidth,
                                        ToIntFunction<String> width) {
        int padding = Math.max(COLUMN_GAP, nameColumnWidth - width.applyAsInt(name));
        return Component.empty()
            .append(Component.literal(name).setStyle(TEXT_STYLE.withColor(nameColor & 0xFFFFFF)))
            .append(spacing(padding))
            .append(details);
    }

    private static Component spacing(int pixels) {
        return Component.literal(PIXEL_SPACE.repeat(pixels)).setStyle(SPACING_STYLE);
    }
}
