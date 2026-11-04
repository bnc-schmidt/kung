package com.github.beng420.kung.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small GUI curves use a bounded number of strips with coverage at their edges. */
public final class UiShapes {
    private UiShapes() { }

    /** A 2 px frame drawn inside a 16 px item slot. */
    public static void slotFrame(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.outline(x, y, 16, 16, color);
        graphics.outline(x + 1, y + 1, 14, 14, color);
    }

    public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
        rounded(graphics, x, y, width, height, radius, radius, color);
    }

    public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                               int topRadius, int bottomRadius, int color) {
        if (width <= 0 || height <= 0) return;
        int limit = Math.min(width, height) / 2;
        topRadius = Math.clamp(topRadius, 0, limit);
        bottomRadius = Math.clamp(bottomRadius, 0, limit);
        graphics.fill(x, y + topRadius, x + width, y + height - bottomRadius, color);
        cap(graphics, x, y, width, topRadius, false, color);
        cap(graphics, x, y + height, width, bottomRadius, true, color);
    }

    private static void cap(GuiGraphicsExtractor graphics, int x, int y, int width, int radius, boolean bottom, int color) {
        for (int row = 0; row < radius; row++) {
            double distance = radius - row - 0.5;
            double edge = radius - Math.sqrt(radius * radius - distance * distance);
            int inset = (int) Math.ceil(edge);
            int atY = bottom ? y - row - 1 : y + row;
            graphics.fill(x + inset, atY, x + width - inset, atY + 1, color);
            int alpha = (int) Math.round((color >>> 24) * (inset - edge));
            int fringe = (color & 0xFFFFFF) | (alpha << 24);
            graphics.fill(x + inset - 1, atY, x + inset, atY + 1, fringe);
            graphics.fill(x + width - inset, atY, x + width - inset + 1, atY + 1, fringe);
        }
    }

    public static void shadow(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius) {
        for (int spread = 5; spread >= 1; spread--) {
            rounded(graphics, x - spread, y - spread + 2, width + spread * 2, height + spread * 2,
                radius + spread, (5 + (5 - spread) * 3) << 24);
        }
    }
}
