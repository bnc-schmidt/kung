package com.github.beng420.kung.feature.garden;

import java.util.function.LongSupplier;

/**
 * When and how far a label too wide for its box is shifted so that all of it can be read: it waits, slides to its
 * end at a slow, steady speed, waits again and slides back, forever. Pure timing on an injected millisecond
 * clock (never a frame count), so it is unit-tested; the screen draws the text at {@code left - offset} inside a
 * clip rectangle.
 *
 * <p>Two uses: {@link #always} for buttons, whose labels scroll all the time, and {@link #onHover} for list
 * rows and the like, which scroll only while the pointer is over them and start from the beginning each time.
 */
final class GreenhouseMarquee {
    /** Pause at each end of the travel. */
    static final long PAUSE_MILLIS = 800L;
    /** Travel speed in logical units per second: slow enough to read while it moves. */
    static final int SPEED = 22;
    private static final long NONE = Long.MIN_VALUE;

    private final LongSupplier clock;
    private final long epoch;
    private long hoverKey = NONE;
    private long hoverSince;
    private boolean hoveredThisFrame;

    /** @param millisClock a monotonic clock in milliseconds */
    GreenhouseMarquee(LongSupplier millisClock) {
        this.clock = millisClock;
        this.epoch = millisClock.getAsLong();
    }

    /**
     * How far to shift the text left, 0..overflow, where {@code overflow} is the text width minus the room it has.
     * Zero for a label that fits. {@code elapsedMillis} counts from the moment the scroll starts its first pause.
     */
    static int offset(int overflow, long elapsedMillis) {
        if (overflow <= 0) return 0;
        long travel = Math.max(1L, (overflow * 1000L + SPEED - 1) / SPEED);
        long cycle = 2 * (PAUSE_MILLIS + travel);
        long t = Math.floorMod(Math.max(0L, elapsedMillis), cycle);
        if (t < PAUSE_MILLIS) return 0;
        if (t < PAUSE_MILLIS + travel) return (int) (overflow * (t - PAUSE_MILLIS) / travel);
        if (t < 2 * PAUSE_MILLIS + travel) return overflow;
        return (int) (overflow * (cycle - t) / travel);
    }

    /** The shift for a button label that scrolls continuously; every such label moves in step. */
    int always(int overflow) { return offset(overflow, clock.getAsLong() - epoch); }

    /** Call once at the start of each frame, before any {@link #onHover}. */
    void beginFrame() { hoveredThisFrame = false; }

    /** Call once at the end of each frame: when nothing was hovered, the next hover starts from the beginning. */
    void endFrame() {
        if (!hoveredThisFrame) hoverKey = NONE;
    }

    /**
     * The shift for a label that scrolls only while the pointer is over it. {@code key} names the element (its
     * list and row); moving to another element, or leaving for a frame, restarts the pause.
     */
    int onHover(long key, int overflow, boolean hovered) {
        if (!hovered) return 0;
        hoveredThisFrame = true;
        long now = clock.getAsLong();
        if (hoverKey != key) {
            hoverKey = key;
            hoverSince = now;
        }
        return offset(overflow, now - hoverSince);
    }
}
