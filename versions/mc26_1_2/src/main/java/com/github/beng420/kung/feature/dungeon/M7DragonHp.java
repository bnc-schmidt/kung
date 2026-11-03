package com.github.beng420.kung.feature.dungeon;

import java.util.Locale;

/** Dragon HP text and colour from the health Hypixel sends in the dragon's entity data. */
final class M7DragonHp {
    /**
     * Vanilla's max-health cap. Dragons were traced at exactly this just before their real value
     * arrived, so it means "no data yet", not a dragon on 1024 HP.
     */
    private static final float PLACEHOLDER = 1024F;

    /** The text is 80 / distance times the font size: 4x up close, 1.5x from ~53 blocks, the far end of the arena. */
    private static final double REFERENCE_DISTANCE = 80.0;
    private static final float NEAR_SCALE = 4F;
    private static final float FAR_SCALE = 1.5F;

    private M7DragonHp() { }

    /** False for a dead dragon, a garbage value and the placeholder. */
    static boolean known(float health) {
        return Float.isFinite(health) && health > 0 && health != PLACEHOLDER;
    }

    /**
     * Whether to draw the server's last health. The dragon's own field cannot decide that: its
     * client tick clamps it to max health (EnderDragon.aiStep calls setHealth(getHealth()), which
     * clamps), so only the dead state, a live value of 0, is read from it.
     */
    static boolean visible(float serverHealth, float liveHealth) {
        return known(serverHealth) && liveHealth > 0;
    }

    /** Text size on screen: bigger when near, never below FAR_SCALE or above NEAR_SCALE, times the Text Size setting. */
    static float textScale(double distance, int sizePercent) {
        float byDistance = (float) Math.clamp(REFERENCE_DISTANCE / Math.max(distance, 1.0), FAR_SCALE, NEAR_SCALE);
        return byDistance * sizePercent / 100F;
    }

    /** 604.09m, 1.02b, 7.5k: two decimals above a million, since the last stretch is what matters. */
    static String format(float health) {
        // The limits sit just below 1000 of the smaller unit, so rounding never prints 1000.00m.
        if (health >= 999_995_000F) return String.format(Locale.ROOT, "%.2fb", health / 1e9F);
        if (health >= 999_950F) return String.format(Locale.ROOT, "%.2fm", health / 1e6F);
        if (health >= 1e3F) return String.format(Locale.ROOT, "%.1fk", health / 1e3F);
        return Long.toString((long) health);
    }

    /**
     * RGB by how much of the highest health seen is left: green from 75%, yellow from 50%, gold from 25%, red below.
     * Dragons spawn with different totals, so the share beats Odin's fixed 750m/500m/250m steps.
     */
    static int color(float health, float peak) {
        float share = peak > 0 ? health / peak : 0F;
        if (share >= 0.75F) return 0x55FF55;
        if (share >= 0.50F) return 0xFFFF55;
        if (share >= 0.25F) return 0xFFAA00;
        return 0xFF5555;
    }
}
