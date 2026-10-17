package com.github.beng420.kung.feature.garden;

import java.util.function.LongSupplier;

/**
 * The one short message the screen's popup shows, and when it goes away: three seconds after it appeared,
 * when dismissed, or when a newer message replaces it. The clock is injected (milliseconds) so expiry is
 * unit-tested.
 */
final class GreenhouseNotice {
    static final long DURATION_MILLIS = 3_000L;
    private final LongSupplier clock;
    private String text;
    private long shownAt;

    GreenhouseNotice(LongSupplier millisClock) {
        this.clock = millisClock;
    }

    /** Replaces any message; a blank one just clears. */
    void show(String message) {
        if (message == null || message.isBlank()) {
            text = null;
            return;
        }
        text = message;
        shownAt = clock.getAsLong();
    }

    void dismiss() { text = null; }

    /** The message while it is still due, else null. Asking after expiry forgets it. */
    String text() {
        if (text != null && clock.getAsLong() - shownAt >= DURATION_MILLIS) text = null;
        return text;
    }

    /** Fraction of the display time left, 1 at the start and 0 at expiry; 0 when nothing is shown. */
    float remaining() {
        if (text() == null) return 0F;
        return Math.clamp(1F - (float) (clock.getAsLong() - shownAt) / DURATION_MILLIS, 0F, 1F);
    }
}
