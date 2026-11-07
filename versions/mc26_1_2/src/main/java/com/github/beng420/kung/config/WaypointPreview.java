package com.github.beng420.kung.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A small block that shows what a waypoint draw type looks like: a filled box, the edges of the box, or both, and the
 * beam above a Waypoint type. Drawn with 1-pixel rectangles in an {@link #SIZE} square, so it stays sharp at any menu scale.
 */
final class WaypointPreview {
    static final int SIZE = 18;
    private static final int ICON = 8;
    private static final int FACE = 8;
    private static final int DEPTH = 3;
    private static final int FRONT_ALPHA = 0xB0;
    private static final int TOP_ALPHA = 0x70;
    private static final int SIDE_ALPHA = 0x90;

    private WaypointPreview() { }

    /** Draws the block with its top left corner at (x, y). {@code rgb} is 0xRRGGBB. */
    static void draw(GuiGraphicsExtractor graphics, int x, int y, int rgb, boolean fill, boolean outline, boolean beam) {
        int fx = x + 1;
        int fy = y + SIZE - FACE - 1;
        int solid = 0xFF000000 | rgb;
        if (beam) graphics.fill(fx + 3, y, fx + 5, fy - DEPTH, fill ? alpha(rgb, FRONT_ALPHA) : solid);
        if (fill) {
            // Oblique cube: the top and the right face are the front face pushed back by DEPTH.
            for (int step = 0; step < DEPTH; step++) {
                graphics.fill(fx + step + 1, fy - step - 1, fx + step + 1 + FACE, fy - step, alpha(rgb, TOP_ALPHA));
                graphics.fill(fx + FACE + step, fy - step - 1, fx + FACE + step + 1, fy + FACE - step - 1, alpha(rgb, SIDE_ALPHA));
            }
            graphics.fill(fx, fy, fx + FACE, fy + FACE, alpha(rgb, FRONT_ALPHA));
        }
        if (outline) {
            graphics.fill(fx, fy, fx + FACE, fy + 1, solid);
            graphics.fill(fx, fy + FACE - 1, fx + FACE, fy + FACE, solid);
            graphics.fill(fx, fy, fx + 1, fy + FACE, solid);
            graphics.fill(fx + FACE - 1, fy, fx + FACE, fy + FACE, solid);
            graphics.fill(fx + DEPTH, fy - DEPTH, fx + DEPTH + FACE, fy - DEPTH + 1, solid);
            graphics.fill(fx + DEPTH + FACE - 1, fy - DEPTH, fx + DEPTH + FACE, fy + FACE - DEPTH, solid);
            for (int step = 1; step <= DEPTH; step++) {
                graphics.fill(fx + step, fy - step, fx + step + 1, fy - step + 1, solid);
                graphics.fill(fx + FACE - 1 + step, fy - step, fx + FACE + step, fy - step + 1, solid);
                graphics.fill(fx + FACE - 1 + step, fy + FACE - 1 - step, fx + FACE + step, fy + FACE - step, solid);
            }
        }
    }

    /** The small square the HUD shows at a waypoint behind blocks: a dark frame around the color. */
    static void drawIcon(GuiGraphicsExtractor graphics, int x, int y, int rgb) {
        int left = x + (SIZE - ICON) / 2;
        int top = y + (SIZE - ICON) / 2;
        graphics.fill(left, top, left + ICON, top + ICON, 0x99000000);
        graphics.fill(left + 1, top + 1, left + ICON - 1, top + ICON - 1, 0xFF000000 | rgb);
    }

    private static int alpha(int rgb, int alpha) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }
}
