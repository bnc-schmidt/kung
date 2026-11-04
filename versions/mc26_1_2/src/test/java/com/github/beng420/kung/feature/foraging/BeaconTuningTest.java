package com.github.beng420.kung.feature.foraging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class BeaconTuningTest {
    @Test
    public void clicksTakeTheShorterWayRoundTheRing() {
        // 13 colors: white (0) to orange (1) is one left click, and red (12) is one right click from white.
        assertEquals(1, BeaconTuning.clicks(1, 0, 13));
        assertEquals(-1, BeaconTuning.clicks(12, 0, 13));
        assertEquals(6, BeaconTuning.clicks(6, 0, 13));
        assertEquals("seven left clicks are six right clicks the other way", -6, BeaconTuning.clicks(7, 0, 13));
        assertEquals(0, BeaconTuning.clicks(5, 5, 13));
    }

    @Test
    public void aLongWayRoundIsShownAsTheShortWayTheOtherDirection() {
        // Ours is orange (1), the reference white (0): 12 left clicks or 1 right click.
        assertEquals(-1, BeaconTuning.clicks(0, 1, 13));
        // 9 left clicks are 4 right clicks; 8 left are 5 right; 7 left are 6 right.
        assertEquals(-4, BeaconTuning.clicks(9, 0, 13));
        assertEquals(-5, BeaconTuning.clicks(8, 0, 13));
        assertEquals(-6, BeaconTuning.clicks(7, 0, 13));
        // And the same the other way: 12 right clicks are 1 left click.
        assertEquals(1, BeaconTuning.clicks(1, 0, 13));
        assertEquals(4, BeaconTuning.clicks(0, 9, 13));
    }

    @Test
    public void everyPairOnEveryRingIsReachedWithinHalfTheRing() {
        for (int size : new int[] {13, 5, 3}) {
            for (int reference = 0; reference < size; reference++) {
                for (int ours = 0; ours < size; ours++) {
                    int clicks = BeaconTuning.clicks(reference, ours, size);
                    assertEquals(reference, Math.floorMod(ours + clicks, size));
                    assertTrue(Math.abs(clicks) <= size / 2);
                }
            }
        }
    }

    @Test
    public void aPaneNamesItsColor() {
        assertEquals(0, BeaconTuning.colorOfPane("white_stained_glass_pane"));
        assertEquals(3, BeaconTuning.colorOfPane("light_blue_stained_glass_pane"));
        assertEquals(12, BeaconTuning.colorOfPane("red_stained_glass_pane"));
        assertEquals(-1, BeaconTuning.colorOfPane("red_stained_glass"));
        assertEquals(-1, BeaconTuning.colorOfPane("black_stained_glass_pane"));
        assertEquals(-1, BeaconTuning.colorOfPane("barrier"));
    }

    @Test
    public void everyColorNameMatchesItsPaneIndex() {
        for (int index = 0; index < BeaconTuning.COLORS.size(); index++) {
            String name = BeaconTuning.COLORS.get(index);
            assertEquals(index, BeaconTuning.colorOfPane(name + "_stained_glass_pane"));
            assertEquals(index, BeaconTuning.colorOfName(name.replace('_', ' ').toUpperCase()));
        }
        assertEquals(3, BeaconTuning.colorOfName("Light Blue"));
        assertEquals(-1, BeaconTuning.colorOfName("Black"));
    }

    @Test
    public void speedAndPitchNamesBecomeIndexes() {
        assertEquals(0, BeaconTuning.speedOfName("1"));
        assertEquals(4, BeaconTuning.speedOfName(" 5 "));
        assertEquals(-1, BeaconTuning.speedOfName("0"));
        assertEquals(-1, BeaconTuning.speedOfName("6"));
        assertEquals(-1, BeaconTuning.speedOfName("fast"));
        assertEquals(0, BeaconTuning.pitchOfName("Low"));
        assertEquals(1, BeaconTuning.pitchOfName("Normal"));
        assertEquals(2, BeaconTuning.pitchOfName("High"));
        assertEquals(-1, BeaconTuning.pitchOfName("Shrill"));
    }

    @Test
    public void theCurrentValueIsReadFromTheLoreByKind() {
        List<String> lore = List.of("", "Current color: Light Blue", "Current speed: 3", "Click to change!");
        assertEquals("Light Blue", BeaconTuning.current(lore, "color"));
        assertEquals("3", BeaconTuning.current(lore, "speed"));
        assertNull(BeaconTuning.current(lore, "pitch"));
        assertNull(BeaconTuning.current(List.of(), "color"));
    }

    @Test
    public void aSpeedStepTakes500MillisecondsLessPerSpeed() {
        // Measured in game: the player's marker at speed 1 stepped every 2800 ms, at speed 2 every 2300 ms.
        assertEquals(2800, BeaconTuning.stepMillis(0));
        assertEquals(2300, BeaconTuning.stepMillis(1));
        assertEquals(1800, BeaconTuning.stepMillis(2));
        assertEquals(800, BeaconTuning.stepMillis(4));
        for (int speed = 0; speed < BeaconTuning.SPEEDS; speed++) {
            assertEquals(speed, BeaconTuning.speedOfStep(BeaconTuning.stepMillis(speed)));
            assertEquals(speed, BeaconTuning.speedOfStep(BeaconTuning.stepMillis(speed) + 200));
        }
        assertEquals(-1, BeaconTuning.speedOfStep(3_400));
        assertEquals(-1, BeaconTuning.speedOfStep(400));
    }

    @Test
    public void aNoteBlockPitchNamesItsLevel() {
        assertEquals(0, BeaconTuning.pitchOfSound(0.0952381f));
        assertEquals(1, BeaconTuning.pitchOfSound(0.7936508f));
        assertEquals(2, BeaconTuning.pitchOfSound(1.4920635f));
        assertEquals(-1, BeaconTuning.pitchOfSound(1.0f));
        assertEquals(-1, BeaconTuning.pitchOfSound(2.0f));
    }
}
