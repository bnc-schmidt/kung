package com.github.beng420.kung.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The flat rounded button of the menu screens: label centered, lit while hovered and enabled. */
public final class UiButton {
    private UiButton() { }

    public static void draw(GuiGraphicsExtractor graphics, Font font, UiTheme theme, UiBounds bounds, String label,
                            int mouseX, int mouseY, boolean enabled, int color) {
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
            enabled && bounds.contains(mouseX, mouseY) ? theme.controlHover() : theme.control());
        graphics.text(font, label, bounds.x() + (bounds.width() - font.width(label)) / 2,
            bounds.y() + (bounds.height() - 8) / 2, enabled ? color : theme.muted(), false);
    }
}
