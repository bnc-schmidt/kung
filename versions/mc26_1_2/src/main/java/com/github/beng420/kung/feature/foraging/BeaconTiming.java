package com.github.beng420.kung.feature.foraging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the reference row of a Moonglade Beacon menu gives away over time. Its marker steps along the row once per
 * beat: a speed-n step takes {@link BeaconTuning#stepMillis} milliseconds, and each step plays a note block sound
 * in the reference's pitch. The player's own marker steps and plays its sound too, so a reference step can only
 * be told by its sound while no step of the player's own is near. Times are System.nanoTime values.
 */
final class BeaconTiming {
    private static final long MS = 1_000_000L;
    private static final int MOVES_KEPT = 6;
    private static final int STEPS_USED = 3;
    /** A sound this close to a reference step is its sound. */
    private static final long SOUND_WINDOW = 120 * MS;
    /** A reference step with one of the player's own this close cannot be told from it by sound. */
    private static final long OWN_WINDOW = 150 * MS;
    private static final long SOUNDS_KEPT = 6_000 * MS;

    private int referenceAt = -1;
    private int ourAt = -1;
    private final ArrayDeque<Long> referenceMoves = new ArrayDeque<>();
    private final ArrayDeque<Long> ourMoves = new ArrayDeque<>();
    private final ArrayDeque<long[]> sounds = new ArrayDeque<>();
    private Long judged;
    private int referencePitch = -1;

    /** The reference marker is in this slot of its row now. The first call only learns where it is. */
    void referenceMarker(int position, long now) {
        if (referenceAt >= 0 && position != referenceAt) push(referenceMoves, now);
        referenceAt = position;
    }

    /** The same for the player's own marker. */
    void ourMarker(int position, long now) {
        if (ourAt >= 0 && position != ourAt) push(ourMoves, now);
        ourAt = position;
    }

    /** A note block sound of this pitch index arrived. */
    void sound(int pitch, long now) {
        sounds.addLast(new long[] {now, pitch});
        while (now - sounds.peekFirst()[0] > SOUNDS_KEPT) sounds.removeFirst();
    }

    /** The reference's speed index from the median of its last few steps, -1 until two steps were seen. */
    int referenceSpeed() {
        List<Long> steps = new ArrayList<>();
        Long previous = null;
        for (long move : referenceMoves) {
            if (previous != null && BeaconTuning.speedOfStep((move - previous) / MS) >= 0) steps.add((move - previous) / MS);
            previous = move;
        }
        if (steps.isEmpty()) return -1;
        List<Long> last = new ArrayList<>(steps.subList(Math.max(0, steps.size() - STEPS_USED), steps.size()));
        Collections.sort(last);
        return BeaconTuning.speedOfStep(last.get(last.size() / 2));
    }

    /** The reference's pitch index, -1 until a step without a step of the player's own near it had its sound. */
    int referencePitch() { return referencePitch; }

    /** Reads the pitch off the reference steps old enough to have their sound in; call it every tick. */
    void settle(long now) {
        for (long move : referenceMoves) {
            if (judged != null && move - judged <= 0) continue;
            if (now - move < OWN_WINDOW) break;
            judged = move;
            if (ownMoveNear(move)) continue;
            long[] nearest = null;
            for (long[] sound : sounds) {
                long gap = Math.abs(sound[0] - move);
                if (gap <= SOUND_WINDOW && (nearest == null || gap < Math.abs(nearest[0] - move))) nearest = sound;
            }
            if (nearest != null) referencePitch = (int) nearest[1];
        }
    }

    private boolean ownMoveNear(long move) {
        for (long own : ourMoves) {
            if (Math.abs(own - move) <= OWN_WINDOW) return true;
        }
        return false;
    }

    private static void push(ArrayDeque<Long> moves, long now) {
        moves.addLast(now);
        if (moves.size() > MOVES_KEPT) moves.removeFirst();
    }
}
