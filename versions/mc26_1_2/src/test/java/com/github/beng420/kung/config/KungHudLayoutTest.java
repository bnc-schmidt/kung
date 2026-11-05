package com.github.beng420.kung.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KungHudLayoutTest {
    @Test
    public void draggingPreservesContentOriginOffsetAndAllowsNegativePositions() {
        FakeHud hud = new FakeHud();
        var entry = hud.entry();
        assertEquals(new KungHudLayout.Bounds(3, 3, 100, 50), entry.bounds());

        entry.moveX(100.3F);
        entry.moveY(70.6F);
        assertEquals(105, hud.x);
        assertEquals(76, hud.y);
        assertEquals(100, entry.bounds().x());
        assertEquals(71, entry.bounds().y());

        entry.moveX(-100.6F);
        entry.moveY(-20.4F);
        assertEquals(-96, hud.x);
        assertEquals(-15, hud.y);
        assertEquals(-101, entry.bounds().x());
        assertEquals(-20, entry.bounds().y());
    }

    @Test
    public void draggingUsesFreshBoundsAfterScaleOrTextMarginChanges() {
        FakeHud hud = new FakeHud();
        var entry = hud.entry();
        entry.scale(1.75F);
        assertEquals(8, hud.x);
        assertEquals(8, hud.y);
        assertEquals(175, entry.bounds().width());
        assertEquals(1.75F, entry.scale(), 0F);
        entry.moveX(100F);
        assertEquals(109, hud.x);
        assertEquals(100, entry.bounds().x());

        hud.margin = 8;
        entry.moveX(100F);
        assertEquals(114, hud.x);
        assertEquals(100, entry.bounds().x());
    }

    @Test
    public void scaleRoundsToPercentAndEnforcesSupportedRange() {
        FakeHud hud = new FakeHud();
        var entry = hud.entry();
        entry.scale(1.756F);
        assertEquals(176, hud.percent);
        entry.scale(0.01F);
        assertEquals(25, hud.percent);
        entry.scale(7F);
        assertEquals(300, hud.percent);
        entry.scale(Float.MAX_VALUE);
        assertEquals(300, hud.percent);
        entry.scale(-1F);
        assertEquals(25, hud.percent);
    }

    @Test
    public void invalidInputsAndUnchangedNativeValuesDoNotPersist() {
        FakeHud hud = new FakeHud();
        var entry = hud.entry();
        for (float invalid : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            entry.moveX(invalid);
            entry.moveY(invalid);
            entry.scale(invalid);
        }
        entry.moveX(entry.bounds().x());
        entry.moveY(entry.bounds().y());
        entry.scale(entry.scale());
        entry.enabled(false);
        assertEquals(0, hud.writes);
        assertEquals(8, hud.x);
        assertEquals(8, hud.y);
        assertEquals(100, hud.percent);
    }

    @Test
    public void independentEditorsShareEnablePositionAndScaleState() {
        FakeHud hud = new FakeHud();
        var standalone = hud.entry();
        var nativeEditor = hud.entry();
        assertFalse(standalone.enabled());
        nativeEditor.enabled(true);
        assertTrue(standalone.enabled());
        nativeEditor.moveX(25F);
        standalone.scale(0.5F);
        assertEquals(standalone.bounds(), nativeEditor.bounds());
        assertEquals(0.5F, nativeEditor.scale(), 0F);
        standalone.enabled(false);
        assertFalse(nativeEditor.enabled());
        assertEquals(4, hud.writes);
    }

    private static final class FakeHud {
        int x = 8;
        int y = 8;
        int percent = 100;
        int margin = 5;
        boolean enabled;
        int writes;

        KungHudLayout.Entry entry() {
            return new KungHudLayout.Entry("example", "Example", () -> {
                float scale = percent / 100.0F;
                return new KungHudLayout.Bounds(Math.round(x - margin * scale), Math.round(y - 5 * scale),
                    Math.round(100 * scale), Math.round(50 * scale));
            }, () -> x, () -> y, value -> { x = value; writes++; }, value -> { y = value; writes++; },
                () -> percent, value -> { percent = value; writes++; }, () -> enabled,
                value -> { enabled = value; writes++; }, null);
        }
    }
}
