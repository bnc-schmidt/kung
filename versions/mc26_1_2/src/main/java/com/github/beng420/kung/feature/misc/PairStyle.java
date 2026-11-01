package com.github.beng420.kung.feature.misc;

/**
 * The look of one kind of Superpairs card, so equal cards are found at a glance: a background color and a
 * letter. The colors are the Okabe-Ito set, chosen to stay apart for the common kinds of color blindness; the
 * letter makes each kind unique without any color at all, and the second round of colors is darker.
 */
public final class PairStyle {
    private static final int[] COLORS = {0xE69F00, 0x56B4E9, 0x009E73, 0xF0E442, 0x0072B2, 0xD55E00, 0xCC79A7};
    /** No I and O, which look like 1 and 0. */
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private PairStyle() { }

    /** Opaque ARGB background of the {@code index}-th kind seen. */
    public static int color(int index) {
        int base = COLORS[Math.floorMod(index, COLORS.length)];
        boolean darker = index / COLORS.length % 2 == 1;
        int red = base >> 16 & 0xFF;
        int green = base >> 8 & 0xFF;
        int blue = base & 0xFF;
        if (darker) {
            red = red * 6 / 10;
            green = green * 6 / 10;
            blue = blue * 6 / 10;
        }
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static String letter(int index) {
        return String.valueOf(LETTERS.charAt(Math.floorMod(index, LETTERS.length())));
    }

    /** Black or white, whichever reads better on {@link #color(int)}. */
    public static int textColor(int index) {
        int color = color(index);
        double luminance = 0.299 * (color >> 16 & 0xFF) + 0.587 * (color >> 8 & 0xFF) + 0.114 * (color & 0xFF);
        return luminance > 140 ? 0xFF000000 : 0xFFFFFFFF;
    }
}
