package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class FrozenBlazeHudFeatureTest {
    @Test
    public void onlyAFullSetOfOneKindCounts() {
        assertEquals("Frozen Blaze", FrozenBlazeHudFeature.setName(List.of(
            "FROZEN_BLAZE_HELMET", "FROZEN_BLAZE_CHESTPLATE", "FROZEN_BLAZE_LEGGINGS", "FROZEN_BLAZE_BOOTS")));
        assertEquals("Blaze", FrozenBlazeHudFeature.setName(List.of(
            "BLAZE_HELMET", "BLAZE_CHESTPLATE", "BLAZE_LEGGINGS", "BLAZE_BOOTS")));
        assertNull(FrozenBlazeHudFeature.setName(List.of(
            "FROZEN_BLAZE_HELMET", "BLAZE_CHESTPLATE", "BLAZE_LEGGINGS", "BLAZE_BOOTS")));
        assertNull(FrozenBlazeHudFeature.setName(List.of(
            "", "FROZEN_BLAZE_CHESTPLATE", "FROZEN_BLAZE_LEGGINGS", "FROZEN_BLAZE_BOOTS")));
    }

    @Test
    public void auraNeedsWalkingAndMouseMovement() {
        int afk = FrozenBlazeHudFeature.AFK_TICKS;
        int active = 0, idle = 0;
        for (int i = 0; i < afk; i++) {
            active = FrozenBlazeHudFeature.advance(active, true);
            idle = FrozenBlazeHudFeature.advance(idle, false);
        }
        assertEquals(afk, FrozenBlazeHudFeature.idleTicks(active, idle)); // only walking
        assertEquals(afk, FrozenBlazeHudFeature.idleTicks(idle, active)); // only looking
        assertEquals(0, FrozenBlazeHudFeature.idleTicks(active, active)); // both
    }

    @Test
    public void traceAggregatesOneSecondOfSignalsAndTellsDamageTagsFromNameplates() {
        var seen = new java.util.TreeMap<String, double[]>();
        assertEquals("quiet", FrozenBlazeHudFeature.describe(seen));
        FrozenBlazeHudFeature.note(seen, "particle minecraft:flame", 3, 4.0);
        FrozenBlazeHudFeature.note(seen, "particle minecraft:flame", 2, 1.5);
        FrozenBlazeHudFeature.note(seen, "move pos", 1, 0);
        assertEquals("move pos x1 n=1 near=0.0, particle minecraft:flame x2 n=5 near=1.5", FrozenBlazeHudFeature.describe(seen));
        assertTrue(FrozenBlazeHudFeature.damageTag("§f✧1,234§f✧"));
        assertFalse(FrozenBlazeHudFeature.damageTag("[Lv100] Blaze 1,000/1,000❤"));
        assertFalse(FrozenBlazeHudFeature.damageTag("Nothing"));
    }

    @Test
    public void alarmFiresOncePerShutdownAndRearmsWhenOnAgain() {
        assertFalse(FrozenBlazeHudFeature.wentOff(true, true)); // still on
        assertTrue(FrozenBlazeHudFeature.wentOff(true, false)); // on -> off
        assertFalse(FrozenBlazeHudFeature.wentOff(false, false)); // already off, no repeat
        assertFalse(FrozenBlazeHudFeature.wentOff(false, true)); // back on
        assertTrue(FrozenBlazeHudFeature.wentOff(true, false)); // and it fires again after rearming
    }

    @Test
    public void pulsesDecideOnceSeenAndTheIdleEstimateCoversTheTimeBefore() {
        int afk = FrozenBlazeHudFeature.AFK_TICKS, timeout = FrozenBlazeHudFeature.PULSE_TIMEOUT;
        assertTrue(FrozenBlazeHudFeature.auraOn(-1, afk - 1)); // no pulse yet: estimate says on
        assertFalse(FrozenBlazeHudFeature.auraOn(-1, afk)); // and off at 30 s idle
        assertTrue(FrozenBlazeHudFeature.auraOn(0, afk)); // a pulse wins over the estimate
        assertTrue(FrozenBlazeHudFeature.auraOn(timeout, 0)); // the capture's longest gap is 40 ticks
        assertFalse(FrozenBlazeHudFeature.auraOn(timeout + 1, 0)); // silence means off, even while moving
    }

    @Test
    public void onlyAFlameBurstAtTheOwnChestIsThePulse() {
        assertTrue(FrozenBlazeHudFeature.atPlayer(0, 1, 0)); // capture: rel=(0.00,+1.00,0.00)
        assertFalse(FrozenBlazeHudFeature.atPlayer(6.2, 7.5, -1.0)); // a flame trail away from the player
        assertFalse(FrozenBlazeHudFeature.atPlayer(2.4, 1, 0.5)); // another player's burst
    }
}
