package com.github.beng420.kung.feature.foraging;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic and the lore of the Moonglade Beacon menus. Each of color, speed and pitch is a ring of values
 * that a left click advances and a right click steps back, so the clicks between two values are the shorter way
 * round: positive for left clicks, negative for right clicks.
 */
public final class BeaconTuning {
    /** The colors in the order the color button cycles; each is also the stained glass pane that shows it. */
    static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "cyan", "purple", "blue", "brown", "green", "red");
    static final int SPEEDS = 5;
    /** Low, Normal, High. */
    static final int PITCHES = 3;
    private static final List<String> PITCH_NAMES = List.of("low", "normal", "high");
    /** The note block pitches of Low, Normal and High, as the server sends them. */
    private static final float[] PITCH_SOUNDS = {0.0952381f, 0.7936508f, 1.4920635f};
    private static final float PITCH_TOLERANCE = 0.01f;
    private static final String PANE_SUFFIX = "_stained_glass_pane";
    private static final Pattern CURRENT = Pattern.compile("^Current (color|speed|pitch): (.+)$");

    private BeaconTuning() { }

    /** The left clicks (positive) or right clicks (negative) that turn {@code ours} into {@code reference}. */
    static int clicks(int reference, int ours, int size) {
        int forward = Math.floorMod(reference - ours, size);
        return forward > size / 2 ? forward - size : forward;
    }

    /** How long a marker takes per step at a speed index, in milliseconds: 2800 at speed 1, 500 less per speed. */
    static long stepMillis(int speed) {
        return 2800 - 500L * speed;
    }

    /** The speed index whose step is nearest to {@code millis}, -1 when it is further than half a speed from all five. */
    static int speedOfStep(long millis) {
        int speed = (int) Math.round((2800 - millis) / 500.0);
        return speed >= 0 && speed < SPEEDS ? speed : -1;
    }

    /** The pitch index of a note block sound's pitch, -1 for any other value. */
    static int pitchOfSound(float pitch) {
        for (int index = 0; index < PITCH_SOUNDS.length; index++) {
            if (Math.abs(PITCH_SOUNDS[index] - pitch) < PITCH_TOLERANCE) return index;
        }
        return -1;
    }

    /** The color index of a stained glass pane by its item id path, -1 for any other item. */
    static int colorOfPane(String itemPath) {
        return itemPath.endsWith(PANE_SUFFIX)
            ? COLORS.indexOf(itemPath.substring(0, itemPath.length() - PANE_SUFFIX.length())) : -1;
    }

    /** The color index of a name such as "Light Blue", -1 for an unknown one. */
    static int colorOfName(String name) {
        return COLORS.indexOf(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    /** The speed index (0 for speed 1) of a number such as "3", -1 when it is none of the five. */
    static int speedOfName(String name) {
        try {
            int speed = Integer.parseInt(name.trim());
            return speed >= 1 && speed <= SPEEDS ? speed - 1 : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** The pitch index of "Low", "Normal" or "High", -1 for anything else. */
    static int pitchOfName(String name) {
        return PITCH_NAMES.indexOf(name.trim().toLowerCase(Locale.ROOT));
    }

    /** The value after "Current color: ", "Current speed: " or "Current pitch: " in a button's lore, null if absent. */
    static String current(List<String> lore, String kind) {
        for (String line : lore) {
            Matcher matcher = CURRENT.matcher(line.trim());
            if (matcher.matches() && matcher.group(1).equals(kind)) return matcher.group(2);
        }
        return null;
    }
}
