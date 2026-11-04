package com.github.beng420.kung.feature.foraging;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The times are from a real solve: speed 3 for the reference, the player's own markers at speed 1 and then 2. */
public final class BeaconTimingTest {
    private static final long MS = 1_000_000L;
    /** The reference marker stepped at these times (ms), as the slot packets showed it. */
    private static final long[] REFERENCE_STEPS = {1732, 3543, 5383, 7110, 8937, 10709, 12539, 14273};
    private static final long[] OUR_STEPS = {2811, 5600, 8433, 11210, 13524};

    /** Feeds the steps and the sounds in time order, settling every 50 ms like the client tick. */
    private static BeaconTiming run(int referencePitch, int ourPitch, long[] referenceSteps, long[] ourSteps) {
        BeaconTiming timing = new BeaconTiming();
        timing.referenceMarker(6, 0);
        timing.ourMarker(0, 0);
        int reference = 0;
        int ours = 0;
        for (long at = 0; at <= 16_000; at += 5) {
            while (reference < referenceSteps.length && referenceSteps[reference] <= at) {
                timing.sound(referencePitch, (referenceSteps[reference] - 20) * MS);
                timing.referenceMarker(10 + reference + 1, referenceSteps[reference] * MS);
                reference++;
            }
            while (ours < ourSteps.length && ourSteps[ours] <= at) {
                timing.sound(ourPitch, (ourSteps[ours] - 20) * MS);
                timing.ourMarker(28 + ours + 1, ourSteps[ours] * MS);
                ours++;
            }
            if (at % 50 == 0) timing.settle(at * MS);
        }
        return timing;
    }

    @Test
    public void theReferenceSpeedIsReadFromItsStepLength() {
        BeaconTiming timing = run(0, 0, REFERENCE_STEPS, OUR_STEPS);
        assertEquals("steps of about 1790 ms are speed 3", 2, timing.referenceSpeed());
    }

    @Test
    public void everySpeedIsToldFromItsOwnStepLength() {
        for (int speed = 0; speed < BeaconTuning.SPEEDS; speed++) {
            BeaconTiming timing = new BeaconTiming();
            long step = BeaconTuning.stepMillis(speed);
            timing.referenceMarker(10, 0);
            for (int move = 1; move <= 3; move++) timing.referenceMarker(10 + move, (move * step + (move % 2) * 60) * MS);
            assertEquals("speed index " + speed, speed, timing.referenceSpeed());
        }
    }

    @Test
    public void oneStepIsNotEnoughToTellTheSpeed() {
        BeaconTiming timing = new BeaconTiming();
        timing.referenceMarker(16, 0);
        assertEquals(-1, timing.referenceSpeed());
        timing.referenceMarker(10, 1_732 * MS);
        assertEquals("the first step has no step before it to measure", -1, timing.referenceSpeed());
        timing.referenceMarker(11, 3_543 * MS);
        assertEquals(2, timing.referenceSpeed());
    }

    @Test
    public void aStepLongerThanAnySpeedIsIgnored() {
        BeaconTiming timing = new BeaconTiming();
        timing.referenceMarker(10, 0);
        timing.referenceMarker(11, 9_000 * MS);
        timing.referenceMarker(12, 18_000 * MS);
        assertEquals("a pause is no speed", -1, timing.referenceSpeed());
    }

    @Test
    public void theReferencePitchIsTheOneOfItsOwnSoundsAndNotTheOnesOfThePlayersMarker() {
        // The reference plays High (2), the player's marker Low (0): only the sounds at the reference steps count.
        assertEquals(2, run(2, 0, REFERENCE_STEPS, OUR_STEPS).referencePitch());
        // And the other way round: the reference is Low, the player High.
        assertEquals(0, run(0, 2, REFERENCE_STEPS, OUR_STEPS).referencePitch());
    }

    @Test
    public void theReferencePitchIsUnknownWithoutAnySound() {
        BeaconTiming timing = new BeaconTiming();
        timing.referenceMarker(10, 0);
        timing.referenceMarker(11, 1_800 * MS);
        timing.settle(2_000 * MS);
        assertEquals(-1, timing.referencePitch());
    }

    @Test
    public void aReferenceStepRightBesideTheOwnOneWaitsForTheNextStep() {
        BeaconTiming timing = new BeaconTiming();
        timing.referenceMarker(10, 0);
        timing.ourMarker(28, 0);
        // Both markers step 40 ms apart: whichever sound this is, it cannot be told to be the reference's.
        timing.sound(0, 1_990 * MS);
        timing.referenceMarker(11, 2_000 * MS);
        timing.ourMarker(29, 2_040 * MS);
        timing.settle(2_300 * MS);
        assertEquals(-1, timing.referencePitch());
        // The next reference step is alone: its sound is the reference's.
        timing.sound(1, 3_780 * MS);
        timing.referenceMarker(12, 3_800 * MS);
        timing.settle(4_000 * MS);
        assertEquals(1, timing.referencePitch());
    }
}
