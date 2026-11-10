package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.google.gson.Gson;
import org.junit.Test;

public final class WishAlertTest {
    private static final String ENRAGED = "⚠ Maxor is enraged! ⚠";
    private static final DungeonRunStats.DungeonClass HEALER = DungeonRunStats.DungeonClass.HEALER;
    private static final DungeonRunStats.DungeonClass TANK = DungeonRunStats.DungeonClass.TANK;

    private static DungeonConfig config(boolean enabled, boolean ignoreGates) {
        return new Gson().fromJson("{\"dungeonMap\":{\"wishAlertEnabled\":" + enabled
            + ",\"devWishAlertAnyClassEnabled\":" + ignoreGates + "}}", KungConfig.class).dungeon;
    }

    @Test public void alertsOnlyForAHealerWhoseUltimateIsActuallyReady() {
        var on = config(true, false);
        assertEquals("Maxor", WishAlert.enragedBoss(ENRAGED, on, HEALER, 0, false));
        // Level counts the cooldown down, so anything above zero means Wish is still charging.
        assertNull(WishAlert.enragedBoss(ENRAGED, on, HEALER, 1, false));
        assertNull(WishAlert.enragedBoss(ENRAGED, on, TANK, 0, false));
        assertNull(WishAlert.enragedBoss(ENRAGED, config(false, false), HEALER, 0, false));
    }

    @Test public void matchesAnyEnragedBossButNothingElse() {
        var on = config(true, false);
        assertEquals("Storm", WishAlert.enragedBoss("⚠ Storm is enraged! ⚠", on, HEALER, 0, false));
        assertNull(WishAlert.enragedBoss("[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!", on, HEALER, 0, false));
        // A chat quote of the line must not fire it.
        assertNull(WishAlert.enragedBoss("Beng114: ⚠ Maxor is enraged! ⚠", on, HEALER, 0, false));
        assertNull(WishAlert.enragedBoss(null, on, HEALER, 0, false));
    }

    @Test public void lowHealthFiresOncePerDropAndWaitsForWish() {
        var low = new WishAlert.LowHealth();
        assertNull(low.observe("[H] yrkuna 10,000❤", 20, true, 0L));
        // A drop whose repeat would only just reach the line: not yet.
        assertNull(low.observe("[H] yrkuna 6,000❤", 20, true, 1_000L));
        // Below 20% of the most HP seen, but Wish is charging: held back, not consumed.
        assertNull(low.observe("[H] yrkuna 1,900❤", 20, false, 2_000L));
        assertEquals("yrkuna", low.observe("[H] yrkuna 1,900❤", 20, true, 2_000L));
        assertNull(low.observe("[H] yrkuna 1,500❤", 20, true, 3_000L));
        // Back above the line re-arms it for the next drop.
        assertNull(low.observe("[H] yrkuna 6,000❤", 20, true, 4_000L));
        assertEquals("yrkuna", low.observe("[H] yrkuna 1,000❤", 20, true, 5_000L));
        assertNull(low.observe("[H] yrkuna DEAD", 20, true, 6_000L));
        assertNull(low.observe("[M] foo 12.5k❤", 0, true, 6_000L));
        assertEquals("foo", low.observe("[M] foo 2.4k❤", 20, true, 7_000L));
        low.reset();
        assertNull(low.observe("[M] foo 2.4k❤", 20, true, 8_000L));
    }

    @Test public void aFastDropAlertsOneUpdateBeforeTheLine() {
        var low = new WishAlert.LowHealth();
        assertNull(low.observe("[H] yrkuna 20,000❤", 20, true, 0L));
        // Lost 5,000: another 5,000 leaves 10,000, far from the 4,000 line.
        assertNull(low.observe("[H] yrkuna 15,000❤", 20, true, 1_000L));
        // Lost 7,000 and still at 8,000, but the same again would put them at 1,000: alert now, not after the death.
        assertEquals("yrkuna", low.observe("[H] yrkuna 8,000❤", 20, true, 2_000L));
        assertNull(low.observe("[H] yrkuna 8,000❤", 20, true, 3_000L));
    }

    @Test public void aDropHeldSteadyForTwoUpdatesStopsProjectingAndTheAlertRearms() {
        var low = new WishAlert.LowHealth();
        assertNull(low.observe("[H] yrkuna 20,000❤", 20, true, 0L));
        assertEquals("yrkuna", low.observe("[H] yrkuna 9,000❤", 20, true, 1_000L));
        assertNull(low.observe("[H] yrkuna 9,000❤", 20, true, 2_500L));
        // 2.5 s after the drop the old loss no longer counts, which re-arms the alert.
        assertNull(low.observe("[H] yrkuna 9,000❤", 20, true, 3_600L));
        assertEquals("yrkuna", low.observe("[H] yrkuna 2,500❤", 20, true, 4_600L));
    }

    @Test public void aRevivedTeammateWithLessHealthThanBeforeTheDeathIsNoDrop() {
        var low = new WishAlert.LowHealth();
        assertNull(low.observe("[H] yrkuna 20,000❤", 20, true, 0L));
        assertNull(low.observe("[H] yrkuna DEAD", 20, true, 1_000L));
        assertNull(low.observe("[H] yrkuna 5,000❤", 20, true, 2_000L));
    }

    @Test public void theSameHealthFromTheSecondSourceIsNoSecondDrop() {
        var low = new WishAlert.LowHealth();
        assertNull(low.observeHp("yrkuna", 20_000, 20, true, 0L));
        assertNull(low.observeHp("yrkuna", 12_000, 20, true, 1_000L));
        assertNull(low.observeHp("yrkuna", 12_000, 20, true, 1_050L));
        assertNull(low.observe("[H] yrkuna 12,000❤", 20, true, 1_100L));
    }

    @Test public void theDeveloperBypassDropsBothGatesButOnlyForTheDeveloperAccount() {
        var bypass = config(true, true);
        // Developer: any class, ultimate still charging.
        assertEquals("Maxor", WishAlert.enragedBoss(ENRAGED, bypass, TANK, 12, true));
        // Same settings copied to anyone else stay fully gated.
        assertNull(WishAlert.enragedBoss(ENRAGED, bypass, TANK, 12, false));
        assertNull(WishAlert.enragedBoss(ENRAGED, bypass, HEALER, 12, false));
        // The developer account alone is not enough while the flag is off.
        assertNull(WishAlert.enragedBoss(ENRAGED, config(true, false), TANK, 12, true));
        // The bypass never overrides the feature toggle itself.
        assertNull(WishAlert.enragedBoss(ENRAGED, config(false, true), TANK, 12, true));
    }
}
