package com.github.beng420.kung.feature.crimson;

/**
 * When one miniboss respawns, from what the client saw of it in one lobby. A boss dies, a beacon stands, the beacon
 * goes, the boss comes back. Recorded over 11 complete cycles: the beacon stands 60.1 to 61.7 s, it goes 60.6 to
 * 62.4 s before the respawn, and the respawn comes 120.7 to 123.9 s after the beacon appeared (the DOWN message comes
 * 0.06 s earlier). A seen death (the DOWN message, or the beacon appearing in view) or a seen beacon end gives the
 * time to a second or two; a beacon seen standing gives a range; no beacon proves nothing (a lobby joined after the
 * death never shows it), so the whole wait counts. Only the boss itself or the spawning message says "spawned".
 * Times are epoch millis. Pure: the tracker feeds it, the HUD reads it.
 */
public final class MiniBossTimer {
    static final long DOWN_TO_SPAWN_MIN = 120_600;
    static final long DOWN_TO_SPAWN = 122_700;
    static final long DOWN_TO_SPAWN_MAX = 124_000;
    static final long BEACON_MAX = 61_800;
    static final long OFF_TO_SPAWN = 61_800;
    static final long OFF_TO_SPAWN_MAX = 62_600;
    /** The beacon block shows about this long after the DOWN message (one scan late, so about equal in time). */
    static final long BEACON_AFTER_DOWN = 60;
    /** A beacon that appears counts as a death only when the view had been open this long: a chunk still loading is no death. */
    static final long VIEW_SETTLE = 1_500;
    /** "Spawned" is trusted this long after the last sight of the boss or the spawning message: others may kill it. */
    static final long ALIVE_HOLD = 60_000;
    /** Past its due time the boss reads "Soon!" this long; if nothing shows it, the timer gives up. */
    static final long SOON_HOLD = 60_000;
    /** No boss and no beacon in view this long: the boss is dead. Default; see {@link MiniBoss#absentHold}. */
    static final long ABSENT_HOLD = 3_000;
    /** The dead boss's nameplate (0 health) lingers about a second after the DOWN message. */
    static final long AFTER_DOWN_IGNORE = 5_000;
    private static final long NONE = Long.MIN_VALUE;

    public enum Kind { UNKNOWN, ALIVE, SOON, KNOWN, RANGE }

    /** {@code from} to {@code to} is when it respawns; both are the same time for KNOWN. */
    public record Estimate(Kind kind, long from, long to) {
        static final Estimate UNKNOWN = new Estimate(Kind.UNKNOWN, 0, 0);
        static final Estimate ALIVE = new Estimate(Kind.ALIVE, 0, 0);
        static final Estimate SOON = new Estimate(Kind.SOON, 0, 0);
    }

    /** What the HUD writes for an estimate. */
    public record Text(String text, int color) { }

    private final long absentHold;
    private long aliveAt = NONE;
    private long deathAt = NONE;
    private long beaconFirst = NONE;
    private long beaconLast = NONE;
    private long beaconOffAt = NONE;
    private long goneBy = NONE;
    private long streakStart = NONE;
    private long deadGoneBy = NONE;
    private long emptyLast = NONE;
    // About the views, not the boss, so kept across cycles: the look before this one.
    private boolean beaconWasPresent;
    private boolean beaconWasView;
    private long viewSince = NONE;

    public MiniBossTimer() { this(ABSENT_HOLD); }

    public MiniBossTimer(long absentHold) { this.absentHold = absentHold; }

    /** The DOWN message: a new cycle starts now. */
    public void down(long now) {
        reset();
        deathAt = now;
    }

    /** The spawning message, or the boss itself in view. */
    public void spawned(long now) {
        reset();
        aliveAt = now;
    }

    /**
     * One look at the boss's area. The beacon view is open when the beacon would show (within about 80 blocks, the area's
     * chunks loaded); the mob view when the player stands at the spawn spot, close enough to see the whole fight. With a
     * view closed, "none" proves nothing; a boss seen is alive wherever it was seen.
     */
    public void observe(long now, boolean beaconView, boolean beacon, boolean mobView, boolean mob) {
        boolean beaconSeen = beaconView && beacon;
        // The beacon appearing in a view that was open and empty is the death itself, seen to a tenth of a second:
        // this is the way to know it when no DOWN message came (standing by while others kill the boss).
        if (beaconSeen && beaconWasView && !beaconWasPresent && viewSince != NONE
            && now - viewSince >= VIEW_SETTLE && (deathAt == NONE || now - deathAt > 2_000)) {
            down(now - BEACON_AFTER_DOWN);
        }
        // A beacon stands only over a dead boss, so a nameplate beside one is the dying one; the same goes for the
        // seconds after the DOWN message.
        if (mob && !beaconSeen && (deathAt == NONE || now - deathAt >= AFTER_DOWN_IGNORE)) {
            spawned(now);
        } else if (beaconSeen) {
            boolean oldCycle = aliveAt != NONE || beaconOffAt != NONE || goneBy != NONE || deadGoneBy != NONE
                || deathAt != NONE && now - deathAt > BEACON_MAX + 2_000;
            if (oldCycle) reset(); // a beacon after the last cycle ended: the boss was killed again unseen
            if (beaconFirst == NONE) beaconFirst = now;
            beaconLast = now;
        } else if (beaconView && beaconLast != NONE && goneBy == NONE) {
            goneBy = now;
            // Seen standing at the look before and gone now: the end itself, not a beacon missed while away.
            if (beaconWasPresent) beaconOffAt = now;
        }
        if (beaconView && !beaconWasView) viewSince = now;
        if (!beaconView) viewSince = NONE;
        beaconWasView = beaconView;
        beaconWasPresent = beaconSeen;
        if (mobView && !mob && beaconView && !beacon && beaconLast == NONE && deathAt == NONE && aliveAt == NONE) {
            if (streakStart == NONE) streakStart = now;
            emptyLast = now;
            if (deadGoneBy == NONE && now - streakStart >= absentHold) deadGoneBy = streakStart;
        } else {
            streakStart = NONE;
        }
    }

    public Estimate estimate(long now) {
        if (aliveAt != NONE) return now - aliveAt <= ALIVE_HOLD ? Estimate.ALIVE : Estimate.UNKNOWN;
        Estimate due;
        if (deathAt != NONE) {
            due = new Estimate(Kind.KNOWN, deathAt + DOWN_TO_SPAWN, deathAt + DOWN_TO_SPAWN);
        } else if (beaconOffAt != NONE) {
            due = new Estimate(Kind.KNOWN, beaconOffAt + OFF_TO_SPAWN, beaconOffAt + OFF_TO_SPAWN);
        } else if (beaconLast != NONE) {
            // Standing at the last look: it died no more than BEACON_MAX before, so it is back no sooner than that.
            long from = beaconLast + DOWN_TO_SPAWN_MIN - BEACON_MAX;
            long to = beaconFirst + DOWN_TO_SPAWN_MAX;
            if (goneBy != NONE) to = Math.min(to, goneBy + OFF_TO_SPAWN_MAX);
            due = new Estimate(Kind.RANGE, Math.min(from, to), to);
        } else if (deadGoneBy != NONE) {
            // No beacon seen proves nothing: 12 recorded joins at a dead boss waited 6 to 116 s, evenly, 5 of them over a
            // minute, so only the longest wait counts. A beacon seen standing is what gives the minute.
            long to = deadGoneBy + DOWN_TO_SPAWN_MAX;
            due = new Estimate(Kind.RANGE, Math.min(emptyLast, to), to);
        } else {
            return Estimate.UNKNOWN;
        }
        if (now <= due.to()) return due;
        return now - due.to() <= SOON_HOLD ? Estimate.SOON : Estimate.UNKNOWN;
    }

    public Text text(long now) {
        Estimate estimate = estimate(now);
        return switch (estimate.kind()) {
            case UNKNOWN -> new Text("Unknown", 0xFF5555);
            case ALIVE -> new Text("Spawned", 0x55FF55);
            case SOON -> new Text("Soon!", 0xFFAA00);
            case KNOWN -> new Text(clock(estimate.to() - now), estimate.to() - now <= 10_000 ? 0xFFAA00 : 0xFFFF55);
            case RANGE -> new Text("~" + (estimate.from() <= now ? "Now" : clock(estimate.from() - now)) + " - "
                + clock(estimate.to() - now), 0xFFFF55);
        };
    }

    /** "1m 12s" or "45s", counting a started second as a whole one. */
    static String clock(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        return seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s" : seconds + "s";
    }

    private void reset() {
        aliveAt = deathAt = beaconFirst = beaconLast = beaconOffAt = goneBy = streakStart = deadGoneBy = emptyLast = NONE;
    }
}
