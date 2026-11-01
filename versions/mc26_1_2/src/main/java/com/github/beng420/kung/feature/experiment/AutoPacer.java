package com.github.beng420.kung.feature.experiment;

import java.util.random.RandomGenerator;

/**
 * Spaces automatic clicks out like a player would: each new step waits a randomized delay before it fires,
 * and a step that fires is consumed, so a click that changed nothing is retried only after another delay.
 * Full flavor only (listed in modrinthCut together with the clickers that use it).
 */
public final class AutoPacer {
    /** Even with a high ping a click is not instant. */
    public static final int MIN_WAIT_MILLIS = 20;
    private static final long NONE = Long.MIN_VALUE;
    private final RandomGenerator random;
    private long armedStep = NONE;
    private long dueMillis;

    public AutoPacer(RandomGenerator random) {
        this.random = random;
    }

    /**
     * True once when {@code step} has waited its delay: {@code delayMillis} spread by +-25 percent, doubled
     * for a {@code slowStart} step (the first click of a round, which a player needs a moment to find).
     */
    public boolean poll(long step, long nowMillis, int delayMillis, boolean slowStart) {
        if (step != armedStep) {
            armedStep = step;
            double spread = 0.75 + 0.5 * random.nextDouble();
            dueMillis = nowMillis + Math.round(delayMillis * spread * (slowStart ? 2 : 1));
        }
        if (nowMillis < dueMillis) return false;
        armedStep = NONE;
        return true;
    }

    /**
     * The wait that lets a click reach the server {@code totalMillis} after its target appeared there: the
     * round trip {@code pingMillis} already takes its part of it, down to {@link #MIN_WAIT_MILLIS}.
     */
    public static int waitFor(int totalMillis, int pingMillis) {
        return Math.max(MIN_WAIT_MILLIS, totalMillis - Math.max(0, pingMillis));
    }

    /** Forgets the armed step, so the next one waits its full delay again. */
    public void idle() {
        armedStep = NONE;
    }
}
