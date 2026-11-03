package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import org.junit.Test;

public final class M7DragonHpTest {
    @Test public void deadPlaceholderAndGarbageHealthAreNotShown() {
        assertFalse(M7DragonHp.known(0F));
        assertFalse(M7DragonHp.known(-5F));
        assertFalse(M7DragonHp.known(1024F));
        assertFalse(M7DragonHp.known(Float.NaN));
        assertFalse(M7DragonHp.known(Float.POSITIVE_INFINITY));
        assertTrue(M7DragonHp.known(1F));
        assertTrue(M7DragonHp.known(1025F));
        assertTrue(M7DragonHp.known(6.040881E8F));
    }

    @Test public void formatsEachMagnitudeAndNeverPrintsAThousandOfTheSmallerUnit() {
        assertEquals("604.09m", M7DragonHp.format(6.040881E8F));
        assertEquals("997.69m", M7DragonHp.format(9.9769E8F));
        assertEquals("1.02b", M7DragonHp.format(1.02E9F));
        assertEquals("1.00m", M7DragonHp.format(1_000_000F));
        assertEquals("999.95m", M7DragonHp.format(999_950_000F));
        assertEquals("1.00b", M7DragonHp.format(999_996_000F));
        assertEquals("1.00m", M7DragonHp.format(999_960F));
        assertEquals("999.9k", M7DragonHp.format(999_940F));
        assertEquals("7.5k", M7DragonHp.format(7_500F));
        assertEquals("1.0k", M7DragonHp.format(1_000F));
        assertEquals("999", M7DragonHp.format(999.9F));
        assertEquals("5", M7DragonHp.format(5F));
    }

    @Test public void serverHealthStaysShownWhileTheDragonsOwnFieldIsClamped() {
        // EnderDragon.aiStep clamps the live field to max health every client tick (here 1024).
        // Before the fix the text read that field, so it vanished after every tick until the next packet.
        assertTrue(M7DragonHp.visible(6.040881E8F, 6.040881E8F));
        assertTrue(M7DragonHp.visible(6.040881E8F, 1024F));
        assertTrue(M7DragonHp.visible(6.040881E8F, 200F));
        // The same server value gives the same text on every frame, so the digits only move with real HP changes.
        assertEquals(M7DragonHp.format(6.040881E8F), M7DragonHp.format(6.040881E8F));
    }

    @Test public void deadDragonAndMissingServerValueHideTheText() {
        assertFalse(M7DragonHp.visible(6.040881E8F, 0F));
        assertFalse(M7DragonHp.visible(6.040881E8F, Float.NaN));
        assertFalse(M7DragonHp.visible(1024F, 1024F));
        assertFalse(M7DragonHp.visible(0F, 1024F));
    }

    @Test public void textGrowsWhenNearAndStaysReadableWhenFar() {
        assertEquals(4F, M7DragonHp.textScale(5.0, 100), 1e-6F);
        assertEquals(4F, M7DragonHp.textScale(0.0, 100), 1e-6F);
        assertEquals(2F, M7DragonHp.textScale(40.0, 100), 1e-6F);
        assertEquals(1.5F, M7DragonHp.textScale(53.3, 100), 0.01F);
        assertEquals(1.5F, M7DragonHp.textScale(200.0, 100), 1e-6F);
        float previous = Float.MAX_VALUE;
        for (double distance = 1; distance <= 120; distance += 1) {
            float scale = M7DragonHp.textScale(distance, 100);
            assertTrue(scale <= previous);
            assertTrue(scale >= 1.5F && scale <= 4F);
            previous = scale;
        }
    }

    @Test public void sizeSettingScalesTheDistanceSize() {
        assertEquals(3F, M7DragonHp.textScale(200.0, 200), 1e-6F);
        assertEquals(0.75F, M7DragonHp.textScale(200.0, 50), 1e-6F);
    }

    @Test public void colourFollowsTheShareOfTheHighestHealthSeen() {
        assertEquals(0x55FF55, M7DragonHp.color(900F, 900F));
        assertEquals(0x55FF55, M7DragonHp.color(675F, 900F));
        assertEquals(0xFFFF55, M7DragonHp.color(674F, 900F));
        assertEquals(0xFFFF55, M7DragonHp.color(450F, 900F));
        assertEquals(0xFFAA00, M7DragonHp.color(449F, 900F));
        assertEquals(0xFFAA00, M7DragonHp.color(225F, 900F));
        assertEquals(0xFF5555, M7DragonHp.color(224F, 900F));
        // No peak yet: nothing to compare against, so the lowest tier rather than a false green.
        assertEquals(0xFF5555, M7DragonHp.color(100F, 0F));
    }
}
