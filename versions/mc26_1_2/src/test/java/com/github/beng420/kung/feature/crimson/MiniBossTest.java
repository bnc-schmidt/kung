package com.github.beng420.kung.feature.crimson;

import static org.junit.Assert.*;

import org.junit.Test;

public final class MiniBossTest {
    @Test
    public void chatNamesFindTheBossInAnyCase() {
        assertSame(MiniBoss.BLADESOUL, MiniBoss.byName("BLADESOUL"));
        assertSame(MiniBoss.BARBARIAN_DUKE_X, MiniBoss.byName("Barbarian Duke X"));
        assertSame(MiniBoss.MAGE_OUTLAW, MiniBoss.byName("The Mage Outlaw")); // "BEWARE - The Mage Outlaw is spawning"
        assertNull(MiniBoss.byName("Kuudra"));
    }

    @Test
    public void nameplateIsTheBossButNotHisHelpers() {
        assertTrue(MiniBoss.ASHFANG.isNameplate("Ashfang 2.4M❤"));
        assertTrue(MiniBoss.ASHFANG.isNameplate("[Lv500] Ashfang 2.4M❤"));
        // The real nameplate from the first recording: two private-use icon glyphs between the tag and the name.
        assertTrue(MiniBoss.BARBARIAN_DUKE_X.isNameplate("[Lv486]  Barbarian Duke X 70M/70M❤"));
        assertTrue(MiniBoss.BLADESOUL.isNameplate("[Lv477]  Bladesoul 48.6M/50M❤"));
        assertFalse(MiniBoss.BLADESOUL.isNameplate("MINIBOSS"));
        assertFalse(MiniBoss.ASHFANG.isNameplate("Ashfang Follower 100k❤"));
        assertFalse(MiniBoss.ASHFANG.isNameplate("Ashfang Acolyte"));
        assertFalse(MiniBoss.BLADESOUL.isNameplate("Ashfang 2.4M❤"));
        assertTrue(MiniBoss.MAGE_OUTLAW.isNameplate("Mage Outlaw"));
    }

    @Test
    public void distanceIsZeroInsideAndGrowsOutside() {
        assertEquals(0, MiniBoss.BLADESOUL.distanceTo(-295, -518), 1e-9);
        assertEquals(30, MiniBoss.BLADESOUL.distanceTo(-256 + 30, -518), 1.0);
        assertTrue(MiniBoss.ASHFANG.area().contains(-485, 137, -1016));
        assertTrue(MiniBoss.BLADESOUL.area().contains(-295, 83, -518));
    }
}
