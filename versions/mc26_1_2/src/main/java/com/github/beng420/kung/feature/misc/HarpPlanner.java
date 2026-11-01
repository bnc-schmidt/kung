package com.github.beng420.kung.feature.misc;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells which buttons of Melody's Harp to click: the bottom row of the chest (slots 37-43) shows a quartz block
 * in the column whose note has arrived. Each note is clicked once, however many ticks it stays lit. Pure logic,
 * fed one reading per client tick. Full flavor only (listed in modrinthCut).
 */
public final class HarpPlanner {
    public static final int FIRST_BUTTON_SLOT = 37;
    public static final int COLUMNS = 7;
    private static final String NOTE_ITEM = "minecraft:quartz_block";

    /** The id of the note lit in each column, 0 while the column is dark. */
    private final long[] note = new long[COLUMNS];
    private final boolean[] clicked = new boolean[COLUMNS];
    private long notes;

    /**
     * Takes the item ids of slots 37-43, an empty string for an empty slot. Returns the slots whose note went
     * dark without being clicked, for the trace.
     */
    public List<Integer> observe(List<String> buttonItems) {
        List<Integer> expired = new ArrayList<>();
        for (int column = 0; column < COLUMNS; column++) {
            boolean lit = column < buttonItems.size() && NOTE_ITEM.equals(buttonItems.get(column));
            if (!lit) {
                if (note[column] != 0 && !clicked[column]) {
                    expired.add(FIRST_BUTTON_SLOT + column);
                }
                note[column] = 0;
            } else if (note[column] == 0) {
                note[column] = ++notes;
                clicked[column] = false;
            }
        }
        return expired;
    }

    /** The lit buttons whose note was not clicked yet, left to right. */
    public List<Integer> targets() {
        List<Integer> slots = new ArrayList<>();
        for (int column = 0; column < COLUMNS; column++) {
            if (note[column] != 0 && !clicked[column]) {
                slots.add(FIRST_BUTTON_SLOT + column);
            }
        }
        return slots;
    }

    /** The running number of the note lit at {@code slot}: a new step for the pacer, 0 when it is dark. */
    public long note(int slot) {
        return note[slot - FIRST_BUTTON_SLOT];
    }

    public void markClicked(int slot) {
        clicked[slot - FIRST_BUTTON_SLOT] = true;
    }
}
