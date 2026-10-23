package com.github.beng420.kung.update;

/**
 * Polling uses monotonic time; opening menus or changing worlds does not restart the interval.
 * GitHub allows 60 unauthenticated API requests per hour per IP, shared with release notes; the former
 * 30-second poll used them all and then failed for the rest of the hour. A failed check waits a full
 * interval too, so a rate limit is never retried into.
 */
final class KungUpdateCheckSchedule {
    static final long INTERVAL_MILLIS = 30L * 60L * 1000L;
    /** The settings button may ask GitHub right away, but not in a row: that is how the hourly limit gets used up. */
    static final long MANUAL_COOLDOWN_MILLIS = 15_000L;
    private boolean started;
    private long lastStarted;
    private boolean manualStarted;
    private long lastManual;

    boolean manualAllowed(long nowMillis) {
        return !manualStarted || nowMillis - lastManual >= MANUAL_COOLDOWN_MILLIS;
    }

    /** A manual check counts as the automatic one too: the next poll comes a full interval later. */
    void manualStarted(long nowMillis) {
        manualStarted = true;
        lastManual = nowMillis;
        started(nowMillis);
    }

    boolean due(long nowMillis) {
        return !started || nowMillis - lastStarted >= INTERVAL_MILLIS;
    }

    void started(long nowMillis) {
        started = true;
        lastStarted = nowMillis;
    }
}
