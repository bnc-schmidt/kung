package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GreenhousePaletteTest {
    private static final int BLUE = 0x4D8DFF;
    private static final int RED = 0xFF3B3B;
    private static final int PINK = 0xFF55D8;
    private static final int ORANGE = 0xFFA126;

    private static GreenhousePalette palette(float opacity) {
        return new GreenhousePalette(opacity, BLUE, RED, PINK, ORANGE);
    }

    private static int alpha(int argb) { return argb >>> 24; }

    private static int rgb(int argb) { return argb & 0xFFFFFF; }

    @Test
    public void aModelIsTintedHalfWayTowardTheToPlaceColour() {
        int tint = palette(0.30F).modelTint();

        // White moved halfway to 4D8DFF: red (255 + 77) / 2, green (255 + 141) / 2, blue 255.
        assertEquals(0xA6, tint >> 16 & 0xFF);
        assertEquals(0xC6, tint >> 8 & 0xFF);
        assertEquals(0xFF, tint & 0xFF);
        assertEquals(Math.round(0.30F * 255F), alpha(tint));
    }

    @Test
    public void theShiftFollowsTheChosenColour() {
        GreenhousePalette green = new GreenhousePalette(0.5F, 0x00FF00, RED, PINK, ORANGE);

        // Halfway from white to pure green: (128, 255, 128).
        assertEquals(0x80FF80, rgb(green.modelTint()));
        assertEquals("black moves halfway to green too", 0x008000, green.shifted(0x000000));
        assertEquals(0xFFFFFF, rgb(new GreenhousePalette(0.5F, 0xFFFFFF, RED, PINK, ORANGE).modelTint()));
    }

    @Test
    public void modelsAndFillsNeverGoBelowTheFloorWhateverTheSliderSays() {
        int floor = Math.round(GreenhousePalette.FLOOR * 255F);

        assertEquals(floor, alpha(palette(0.05F).modelTint()));
        assertEquals(floor, alpha(palette(0F).modelTint()));
        assertEquals(floor, alpha(palette(0.05F).placeFill()));
        assertEquals(floor, alpha(palette(0.05F).wrongFill()));
        assertTrue("the block shader discards under about 0.1", GreenhousePalette.FLOOR >= 0.11F);
        assertEquals(255, alpha(palette(1F).modelTint()));
        assertEquals("above the floor the slider decides", Math.round(0.5F * 255F), alpha(palette(0.5F).modelTint()));
    }

    @Test
    public void outlinesStayStrongWhateverTheSliderSays() {
        for (float opacity : new float[] {0.05F, 0.3F, 1F}) {
            GreenhousePalette palette = palette(opacity);
            for (int outline : new int[] {palette.placeOutline(), palette.wrongOutline(), palette.extraOutline(),
                palette.wrongStateOutline(), palette.unverifiedOutline()}) {
                assertTrue("alpha " + alpha(outline), alpha(outline) >= Math.round(0.7F * 255F));
            }
        }
        assertEquals(Math.round(GreenhousePalette.OUTLINE_ALPHA * 255F), alpha(palette(0.05F).placeOutline()));
    }

    @Test
    public void everyStateHasItsOwnColour() {
        GreenhousePalette palette = palette(0.3F);

        assertEquals(BLUE, rgb(palette.placeOutline()));
        assertEquals(RED, rgb(palette.wrongOutline()));
        assertEquals(PINK, rgb(palette.extraOutline()));
        assertEquals(ORANGE, rgb(palette.wrongStateOutline()));
        assertEquals(RED, rgb(palette.wrongFill()));
        assertEquals(PINK, rgb(palette.extraFill()));
        assertEquals(ORANGE, rgb(palette.wrongStateFill()));
        assertEquals(BLUE, rgb(palette.placeFill()));
        assertNotEquals(rgb(palette.unverifiedOutline()), rgb(palette.wrongStateOutline()));
        assertNotEquals("unverified is neutral, not the old orange", ORANGE, rgb(palette.unverifiedOutline()));
        assertEquals(0, GreenhousePalette.none());
    }

    @Test
    public void alertBoxesAreAFifthStrongerThanTheModelsAndCapped() {
        assertEquals(Math.round(0.3F * 1.2F * 255F), alpha(palette(0.3F).wrongFill()));
        assertEquals(255, alpha(palette(1F).wrongFill()));
        assertEquals(255, alpha(palette(0.9F).wrongFill()));
        assertTrue(alpha(palette(0.5F).wrongFill()) > alpha(palette(0.5F).placeFill()));
    }

    @Test
    public void aWhiteColourDoesNotTintAndTheAlphaOfAColourIsDropped() {
        GreenhousePalette palette = new GreenhousePalette(0.5F, 0x80123456, 0x11FF0000, PINK, ORANGE);

        assertEquals(0x123456, palette.toPlace());
        assertEquals(0xFF0000, palette.wrong());
        assertEquals(0xFF0000, rgb(palette.wrongOutline()));
    }

    @Test
    public void anAbsurdOpacityIsClamped() {
        assertEquals(255, alpha(palette(7F).modelTint()));
        assertEquals(Math.round(GreenhousePalette.FLOOR * 255F), alpha(palette(-3F).modelTint()));
        assertEquals(Math.round(0.30F * 255F), alpha(palette(Float.NaN).modelTint()));
    }

    @Test
    public void theLivePaletteFollowsTheSettingsAndTheFingerprintTellsWhenToRebuild() {
        com.github.beng420.kung.config.category.GreenhouseConfig config =
            new com.github.beng420.kung.config.category.GreenhouseConfig();
        GreenhouseHologramSettings.useConfig(() -> config);
        try {
            GreenhousePalette before = GreenhousePalette.live();
            int fingerprint = GreenhousePalette.fingerprint();
            assertEquals(0.30F, before.opacity(), 0F);
            assertEquals(0x4D8DFF, before.toPlace());
            assertEquals(fingerprint, GreenhousePalette.fingerprint());
            assertEquals(fingerprint, GreenhousePalette.fingerprint(before.opacity(), before.toPlace(), before.wrong(),
                before.extra(), before.wrongState()));

            config.setHologramOpacity(0.6F);
            assertEquals(0.6F, GreenhousePalette.live().opacity(), 0F);
            assertNotEquals("the slider changed: rebuild", fingerprint, GreenhousePalette.fingerprint());
            fingerprint = GreenhousePalette.fingerprint();

            config.setColorToPlace(0x00FF00);
            assertEquals(0x00FF00, GreenhousePalette.live().toPlace());
            assertNotEquals("a colour changed: rebuild", fingerprint, GreenhousePalette.fingerprint());
            fingerprint = GreenhousePalette.fingerprint();

            config.setColorWrong(0x112233);
            assertNotEquals(fingerprint, GreenhousePalette.fingerprint());
            fingerprint = GreenhousePalette.fingerprint();
            config.setColorExtra(0x223344);
            assertNotEquals(fingerprint, GreenhousePalette.fingerprint());
            fingerprint = GreenhousePalette.fingerprint();
            config.setColorWrongState(0x334455);
            assertNotEquals(fingerprint, GreenhousePalette.fingerprint());
            fingerprint = GreenhousePalette.fingerprint();
            config.setHideWhenComplete(false);
            assertEquals("not a colour: no rebuild", fingerprint, GreenhousePalette.fingerprint());
        } finally {
            GreenhouseHologramSettings.useConfig(null);
        }
    }

    @Test
    public void theFingerprintChangesWithAnySettingAndNotOtherwise() {
        int base = GreenhousePalette.fingerprint(0.3F, BLUE, RED, PINK, ORANGE);

        assertEquals(base, GreenhousePalette.fingerprint(0.3F, BLUE, RED, PINK, ORANGE));
        assertEquals("opaque and plain RGB are the same colour", base,
            GreenhousePalette.fingerprint(0.3F, 0xFF000000 | BLUE, RED, PINK, ORANGE));
        assertNotEquals(base, GreenhousePalette.fingerprint(0.31F, BLUE, RED, PINK, ORANGE));
        assertNotEquals(base, GreenhousePalette.fingerprint(0.3F, 0x4D8DFE, RED, PINK, ORANGE));
        assertNotEquals(base, GreenhousePalette.fingerprint(0.3F, BLUE, 0xFF3B3C, PINK, ORANGE));
        assertNotEquals(base, GreenhousePalette.fingerprint(0.3F, BLUE, RED, 0xFF55D9, ORANGE));
        assertNotEquals(base, GreenhousePalette.fingerprint(0.3F, BLUE, RED, PINK, 0xFFA127));
    }
}
