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
    private static final int ROW = 9;
    private static final String NOTE_ITEM = "minecraft:quartz_block";
    private static final String WOOL_SUFFIX = "_wool";

    /** The id of the note lit in each column, 0 while the column is dark. */
    private final long[] note = new long[COLUMNS];
    private final boolean[] clicked = new boolean[COLUMNS];
    private final int[] behindLit = new int[COLUMNS];
    private long notes;

    /**
     * Takes the item ids of slots 37-43, an empty string for an empty slot, and per column how many notes wait
     * right behind the hit row ({@link #notesBehind}). Returns the slots whose note went dark without being
     * clicked, for the trace.
     * <p>
     * The same column twice in a row keeps one quartz block lit through two steps, so the lit block alone cannot
     * tell the repeat. The note behind it stepping in does: that count falls while the block stays lit.
     */
    public List<Integer> observe(List<String> buttonItems, int... behind) {
        List<Integer> expired = new ArrayList<>();
        for (int column = 0; column < COLUMNS; column++) {
            boolean lit = column < buttonItems.size() && NOTE_ITEM.equals(buttonItems.get(column));
            int waiting = lit && column < behind.length ? behind[column] : 0;
            if (!lit) {
                if (note[column] != 0 && !clicked[column]) {
                    expired.add(FIRST_BUTTON_SLOT + column);
                }
                note[column] = 0;
            } else if (note[column] == 0) {
                note[column] = ++notes;
                clicked[column] = false;
            } else if (waiting < behindLit[column]) {
                if (!clicked[column]) {
                    expired.add(FIRST_BUTTON_SLOT + column);
                }
                note[column] = ++notes;
                clicked[column] = false;
            }
            behindLit[column] = waiting;
        }
        return expired;
    }

    /**
     * Per column, how many wool blocks (notes on their way) touch the hit row from above, from the 54 top
     * slots of the chest. A block past a gap is a later note and does not count.
     * ponytail: a column wool to the top row hides a step, add a step clock if a song needs it.
     */
    public static int[] notesBehind(List<String> topSlots) {
        int[] behind = new int[COLUMNS];
        for (int column = 0; column < COLUMNS; column++) {
            for (int slot = FIRST_BUTTON_SLOT + column - ROW; slot >= 0 && slot < topSlots.size()
                && topSlots.get(slot).endsWith(WOOL_SUFFIX); slot -= ROW) {
                behind[column]++;
            }
        }
        return behind;
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
