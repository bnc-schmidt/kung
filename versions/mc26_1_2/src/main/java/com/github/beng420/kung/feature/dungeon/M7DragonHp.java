package com.github.beng420.kung.feature.dungeon;

import java.util.Locale;

/** Dragon HP text and colour from the health Hypixel sends in the dragon's entity data. */
final class M7DragonHp {
    /**
     * Vanilla's max-health cap. Dragons were traced at exactly this just before their real value
     * arrived, so it means "no data yet", not a dragon on 1024 HP.
     */
    private static final float PLACEHOLDER = 1024F;

    private M7DragonHp() { }

    /** False for a dead dragon, a garbage value and the placeholder. */
    static boolean known(float health) {
        return Float.isFinite(health) && health > 0 && health != PLACEHOLDER;
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
