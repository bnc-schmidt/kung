package com.github.beng420.kung.feature.misc;

/**
 * Ragnarock Axe in server ticks, all counted from the right-click: the cast, then the strength, then what is left
 * of the cooldown. A cast that is cancelled (damage, swapping away) skips the strength; the cooldown runs on.
 */
final class RagTimer {
    enum Phase { IDLE, CAST, EFFECT, COOLDOWN }

    static final int CAST_TICKS = 60;
    static final int EFFECT_TICKS = 200;
    /** The server answers a click within this many ticks; a rejection later is about another click. */
    static final int ANSWER_TICKS = 20;
    /** After an abort, a "CASTING IN" already on its way must not restart the cast. */
    static final int QUIET_TICKS = 20;

    /** Ticks since the right-click, -1 when nothing runs. */
    private int since = -1;
    /** Ticks from the right-click until the axe is ready again. */
    private int cooldown;
    private boolean casting;
    /** The actionbar showed the cast, so a later "on cooldown" answer belongs to another click. */
    private boolean confirmed;
    private int sinceAbort = QUIET_TICKS;

    Phase phase() {
        if (since < 0) return Phase.IDLE;
        if (casting && since < CAST_TICKS) return Phase.CAST;
        if (casting && since < CAST_TICKS + EFFECT_TICKS) return Phase.EFFECT;
        return since < cooldown ? Phase.COOLDOWN : Phase.IDLE;
    }

    int since() { return since; }

    int left() {
        return switch (phase()) {
            case CAST -> CAST_TICKS - since;
            case EFFECT -> CAST_TICKS + EFFECT_TICKS - since;
            case COOLDOWN -> cooldown - since;
            case IDLE -> 0;
        };
    }

    /** Length of the current phase, for the bar. */
    int total() {
        return switch (phase()) {
            case CAST -> CAST_TICKS;
            case EFFECT -> EFFECT_TICKS;
            case COOLDOWN -> Math.max(1, casting ? cooldown - CAST_TICKS - EFFECT_TICKS : cooldown);
            case IDLE -> 1;
        };
    }

    static boolean visible(Phase phase, boolean effectShown, boolean cooldownShown) {
        return switch (phase) {
            case CAST -> true;
            case EFFECT -> effectShown;
            case COOLDOWN -> cooldownShown;
            case IDLE -> false;
        };
    }

    void tick() {
        sinceAbort = Math.min(sinceAbort + 1, QUIET_TICKS);
        if (since < 0) return;
        since++;
        if (phase() == Phase.IDLE) since = -1;
    }

    /** A right-click with the axe in hand; false when a cast runs or the axe is still on cooldown by our count. */
    boolean use(int cooldownTicks) {
        if (phase() == Phase.CAST || since >= 0 && since < cooldown) return false;
        since = 0;
        cooldown = cooldownTicks;
        casting = true;
        confirmed = false;
        return true;
    }

    /** Damage or swapping away ended the running cast; true when there was one. */
    boolean abort() {
        if (phase() != Phase.CAST) return false;
        casting = false;
        sinceAbort = 0;
        return true;
    }

    /** The actionbar shows the cast: confirms the click's cast, or starts one the count missed. */
    void castingIn(int seconds, int cooldownTicks) {
        if (phase() == Phase.CAST) {
            confirmed = true;
            return;
        }
        if (sinceAbort < QUIET_TICKS) return;
        since = Math.max(0, CAST_TICKS - seconds * 20);
        cooldown = cooldownTicks;
        casting = true;
        confirmed = true;
    }

    /** The click just before did not start a cast (not enough mana); true when that start is taken back. */
    boolean rejected() {
        if (!unconfirmedStart()) return false;
        reset();
        return true;
    }

    /** "This ability is on cooldown for Ns": takes back an unconfirmed start, then adopts the server's remaining time. */
    void onCooldown(int seconds) {
        int left = seconds * 20;
        if (since < 0 || unconfirmedStart()) {
            since = 0;
            cooldown = left;
            casting = false;
        } else {
            cooldown = since + left;
        }
    }

    void reset() {
        since = -1;
        casting = false;
        confirmed = false;
        sinceAbort = QUIET_TICKS;
    }

    private boolean unconfirmedStart() { return casting && !confirmed && since >= 0 && since < ANSWER_TICKS; }
}
