package com.github.beng420.kung.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class UiTooltip {
    private static final int XS = 2;
    private static final int SM = 4;
    private static final int MD = 8;

    private UiTooltip() {
    }

    public static void draw(
        GuiGraphicsExtractor graphics,
        Font font,
        List<String> lines,
        int x,
        int y,
        int viewportWidth,
        int viewportHeight,
        UiTheme theme
    ) {
        draw(graphics, font, lines, x, 0, y, viewportWidth, viewportHeight, theme);
    }

    /** Odin-style info box: next to the hovered row instead of under the cursor. */
    public static void drawBeside(GuiGraphicsExtractor graphics, Font font, List<String> lines, int rowLeft, int rowRight,
                                  int y, int viewportWidth, int viewportHeight, UiTheme theme) {
        draw(graphics, font, lines, -Math.max(1, rowLeft), rowRight, y, viewportWidth, viewportHeight, theme);
    }

    private static void draw(GuiGraphicsExtractor graphics, Font font, List<String> lines, int x, int rowRight,
                             int y, int viewportWidth, int viewportHeight, UiTheme theme) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int textWidth = Math.min(260, viewportWidth - MD * 2 - 2);
        int maxLines = (viewportHeight - SM * 2 - 2) / (font.lineHeight + XS);
        if (textWidth <= 0 || maxLines <= 0) return;
        var wrapped = lines.stream().flatMap(line -> font.split(Component.literal(line), textWidth).stream())
            .limit(maxLines).toList();
        int width = wrapped.stream().mapToInt(font::width).max().orElse(0) + MD * 2;
        int height = wrapped.size() * (font.lineHeight + XS) + SM * 2;
        // Beside a row (x < 0 encodes its left edge): right of it, or left of it when the screen ends first.
        if (x < 0 && rowRight + SM + width + 1 <= viewportWidth) x = rowRight + SM;
        else if (x < 0) x = -x - SM - width;
        x = Math.clamp(x, 1, Math.max(1, viewportWidth - width - 1));
        if (y + height + 1 > viewportHeight) y -= height + MD * 2;
        y = Math.clamp(y, 1, Math.max(1, viewportHeight - height - 1));
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, theme.border());
        graphics.fill(x, y, x + width, y + height, theme.panelDark());
        for (int index = 0; index < wrapped.size(); index++) {
            graphics.text(
                font,
                wrapped.get(index),
                x + MD,
                y + SM + index * (font.lineHeight + XS),
                theme.text(),
                true
            );
        }
    }
}
