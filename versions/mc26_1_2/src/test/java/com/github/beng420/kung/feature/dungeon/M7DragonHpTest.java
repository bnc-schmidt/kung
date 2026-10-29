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
