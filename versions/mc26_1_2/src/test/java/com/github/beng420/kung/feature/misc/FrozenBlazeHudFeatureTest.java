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
        assertTrue(FrozenBlazeHudFeature.auraOn(0, afk)); // a fresh pulse wins over the estimate
        assertTrue(FrozenBlazeHudFeature.auraOn(timeout, afk)); // for a few seconds
        assertFalse(FrozenBlazeHudFeature.auraOn(timeout + 1, afk)); // then the idle estimate is back in charge
        // Silence is not off: the aura pulses only now and then (gaps of 1060 ticks in a capture while it was on).
        assertTrue(FrozenBlazeHudFeature.auraOn(1060, 0));
        assertTrue(FrozenBlazeHudFeature.auraOn(timeout + 1, afk - 1));
    }

    @Test
    public void aFlickerDoesNotSetOffTheAlarmButARealRunOutDoes() {
        int min = FrozenBlazeHudFeature.ALARM_MIN_ON_TICKS;
        assertFalse(FrozenBlazeHudFeature.shouldAlarm(true, false, min - 1)); // on for a moment only
        assertTrue(FrozenBlazeHudFeature.shouldAlarm(true, false, min));
        assertFalse(FrozenBlazeHudFeature.shouldAlarm(true, true, 1000)); // still on
        assertFalse(FrozenBlazeHudFeature.shouldAlarm(false, false, 1000)); // already off
    }

    @Test
    public void aWalkingPlayerStillMatchesItsPulseWhichTheServerPlacesOneRoundTripBehind() {
        // Sprinting at 0.28 blocks per tick for 30 ticks; the trail keeps the last 20 positions.
        double[] trail = new double[0];
        for (int tick = 0; tick < 30; tick++) {
            trail = FrozenBlazeHudFeature.pushed(trail, tick * 0.28, 64, 0, FrozenBlazeHudFeature.TRAIL_TICKS);
        }
        assertEquals(FrozenBlazeHudFeature.TRAIL_TICKS * 3, trail.length);
        double nowX = 29 * 0.28;
        // 156 ms ping is about 3 ticks of walking: 0.84 blocks behind, outside the standing-still match.
        assertFalse(FrozenBlazeHudFeature.atPlayer(-0.84, 1, 0));
        assertTrue(FrozenBlazeHudFeature.atPlayerRecently(trail, nowX - 0.84, 65, 0));
        assertTrue(FrozenBlazeHudFeature.atPlayerRecently(trail, nowX, 65.4, 0.3)); // jumping a little
        // Positions older than the trail, other players and flame trails stay out.
        assertFalse(FrozenBlazeHudFeature.atPlayerRecently(trail, 0, 65, 0));
        assertFalse(FrozenBlazeHudFeature.atPlayerRecently(trail, nowX + 3, 65, 0));
        assertFalse(FrozenBlazeHudFeature.atPlayerRecently(trail, nowX, 72, 0));
        assertFalse(FrozenBlazeHudFeature.atPlayerRecently(new double[0], 0, 1, 0));
    }

    @Test
    public void onlyAFlameBurstAtTheOwnChestIsThePulse() {
        assertTrue(FrozenBlazeHudFeature.atPlayer(0, 1, 0)); // capture: rel=(0.00,+1.00,0.00)
        assertFalse(FrozenBlazeHudFeature.atPlayer(6.2, 7.5, -1.0)); // a flame trail away from the player
        assertFalse(FrozenBlazeHudFeature.atPlayer(2.4, 1, 0.5)); // another player's burst
    }
}
