package com.github.beng420.kung.feature.garden;

/**
 * The hologram's colours and opacities as plain arithmetic, after the player's settings (opacity slider and four
 * colours) the way Litematica draws a schematic: what is still to place is tinted toward blue and outlined in blue,
 * a wrong crop is red, a crop where the layout has none is pink and a block in the wrong state (farmland where dirt
 * belongs and the other way round) is orange. Models and fills never go below {@link #FLOOR}, because the block
 * shader discards fragments under about 0.1; outlines keep their strength whatever the slider says.
 *
 * <p>The record holds the settings of one rebuild; {@link #live()} reads them, {@link #fingerprint()} says cheaply
 * whether they changed since.
 */
record GreenhousePalette(float opacity, int toPlace, int wrong, int extra, int wrongState) {
    /** Lowest alpha of any model or fill, whatever the slider says. */
    static final float FLOOR = 0.12F;
    /** Outlines: at least 0.7 so they stay visible when the models are almost gone. */
    static final float OUTLINE_ALPHA = 0.85F;
    /** How far a model's colour moves toward the "to place" colour. */
    static final float BLUE_SHIFT = 0.5F;
    /** Wrong, extra and wrong-state boxes are a little stronger than the models. */
    static final float ALERT_FACTOR = 1.2F;
    /** Something unrecognised in a layout cell: neither right nor wrong, a neutral outline. */
    static final int UNVERIFIED_RGB = 0xC8C8C8;
    private static final int RGB = 0xFFFFFF;

    GreenhousePalette {
        opacity = Float.isNaN(opacity) ? 0.30F : Math.clamp(opacity, 0F, 1F);
        toPlace &= RGB;
        wrong &= RGB;
        extra &= RGB;
        wrongState &= RGB;
    }

    /** The current settings. */
    static GreenhousePalette live() {
        return new GreenhousePalette(GreenhouseHologramSettings.opacity(), GreenhouseHologramSettings.colorToPlace(),
            GreenhouseHologramSettings.colorWrong(), GreenhouseHologramSettings.colorExtra(),
            GreenhouseHologramSettings.colorWrongState());
    }

    /** A cheap number that changes when any of the current settings does; no allocation, so it can run every frame. */
    static int fingerprint() {
        return fingerprint(GreenhouseHologramSettings.opacity(), GreenhouseHologramSettings.colorToPlace(),
            GreenhouseHologramSettings.colorWrong(), GreenhouseHologramSettings.colorExtra(),
            GreenhouseHologramSettings.colorWrongState());
    }

    static int fingerprint(float opacity, int toPlace, int wrong, int extra, int wrongState) {
        int hash = Float.floatToIntBits(opacity);
        hash = hash * 31 + (toPlace & RGB);
        hash = hash * 31 + (wrong & RGB);
        hash = hash * 31 + (extra & RGB);
        return hash * 31 + (wrongState & RGB);
    }

    /** The alpha (0..1) of a model: the slider, never under the floor. */
    float modelAlpha() { return Math.clamp(Math.max(opacity, FLOOR), 0F, 1F); }

    /** The colour a model is multiplied with: white moved toward the "to place" colour, at the model alpha. */
    int modelTint() { return argb(modelAlpha(), shifted(RGB)); }

    /** {@code rgb} moved toward the "to place" colour by {@link #BLUE_SHIFT}. */
    int shifted(int rgb) {
        int red = mix(rgb >> 16 & 0xFF, toPlace >> 16 & 0xFF);
        int green = mix(rgb >> 8 & 0xFF, toPlace >> 8 & 0xFF);
        int blue = mix(rgb & 0xFF, toPlace & 0xFF);
        return red << 16 | green << 8 | blue;
    }

    /** A fill in {@code rgb}: alpha = slider x factor, never under the floor. */
    int fill(int rgb, float factor) {
        return argb(Math.clamp(Math.max(opacity * factor, FLOOR), 0F, 1F), rgb);
    }

    /** An outline in {@code rgb}: strong whatever the slider says. */
    int outline(int rgb) { return argb(OUTLINE_ALPHA, rgb); }

    /** Fully transparent: a box that is only an outline, or only a fill. */
    static int none() { return 0; }

    /** The translucent block or slab of something still to place. */
    int placeFill() { return fill(toPlace, 1F); }

    int placeOutline() { return outline(toPlace); }

    int wrongFill() { return fill(wrong, ALERT_FACTOR); }

    int wrongOutline() { return outline(wrong); }

    int extraFill() { return fill(extra, ALERT_FACTOR); }

    int extraOutline() { return outline(extra); }

    int wrongStateFill() { return fill(wrongState, ALERT_FACTOR); }

    int wrongStateOutline() { return outline(wrongState); }

    int unverifiedOutline() { return outline(UNVERIFIED_RGB); }

    private static int mix(int from, int to) {
        return Math.round(from + (to - from) * BLUE_SHIFT);
    }

    private static int argb(float alpha, int rgb) {
        return Math.round(Math.clamp(alpha, 0F, 1F) * 255F) << 24 | rgb & RGB;
    }
}
