package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.message.KungMessages;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
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

    /** The name takes {@code nameColor} (class colour); details use the shared report highlighting. */
    static Component playerLine(String name, int nameColor, String details, int nameColumnWidth,
                                ToIntFunction<String> width) {
        int padding = Math.max(COLUMN_GAP, nameColumnWidth - width.applyAsInt(name));
        return Component.empty()
            .append(Component.literal(name).setStyle(TEXT_STYLE.withColor(nameColor & 0xFFFFFF)))
            .append(Component.literal(PIXEL_SPACE.repeat(padding)).setStyle(SPACING_STYLE))
            .append(KungMessages.highlight(details).withStyle(TEXT_STYLE));
    }
}
