package com.github.beng420.kung.feature.garden;

import java.util.function.LongSupplier;

/**
 * A latch for a per-frame callback that threw: tripped, it holds the callback off for a fixed time and then re-arms,
 * so one transient error does not disable the feature until the game restarts. The clock is a monotonic nanosecond
 * counter ({@code System::nanoTime}); tests pass their own. Client thread only.
 */
final class GreenhouseBackoff {
    private final long delayNanos;
    private final LongSupplier clock;
    private boolean tripped;
    private long retryAt;

    GreenhouseBackoff(long delayNanos, LongSupplier clock) {
        this.delayNanos = delayNanos;
        this.clock = clock;
    }

    /** Whether the callback may run now: always until tripped, then again once the delay has passed. */
    boolean ready() {
        // The difference, not a comparison: nanoTime values may wrap.
        if (tripped && clock.getAsLong() - retryAt >= 0) tripped = false;
        return !tripped;
    }

    /** The callback failed: hold it off for the delay from now (a failure on the retry restarts the wait). */
    void trip() {
        tripped = true;
        retryAt = clock.getAsLong() + delayNanos;
    }
}
