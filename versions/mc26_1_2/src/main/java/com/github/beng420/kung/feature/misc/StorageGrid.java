package com.github.beng420.kung.feature.misc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A held item's storage as its screen shows it: per slot an item key and a count, in reading order.
 * Hypixel takes blocks from the first slot on, so every use drains the top-left stacks first.
 */
final class StorageGrid {
    static final int COLUMNS = 9;
    /** The wand sits in the Builder's Ruler storage like a block that never runs out. */
    static final String INFINIDIRT_WAND = "INFINIDIRT_WAND";
    private static final Pattern BUILT = Pattern.compile("^You built (\\d[\\d,]*) blocks?!");
    private final String[] keys;
    private final int[] counts;

    StorageGrid(String[] keys, int[] counts) {
        if (keys.length != counts.length) throw new IllegalArgumentException("keys and counts differ in length");
        this.keys = keys.clone();
        this.counts = counts.clone();
    }

    int size() { return counts.length; }

    String key(int slot) { return keys[slot]; }

    int count(int slot) { return counts[slot]; }

    int total() {
        int total = 0;
        for (int count : counts) total += count;
        return total;
    }

    /** The block count of a "You built 54 blocks!" chat line, or -1 for any other line. Section-sign codes are ignored. */
    static int builtBlocks(String text) {
        Matcher matcher = BUILT.matcher(text.replaceAll("§.", "").replace((char) 0xA0, ' ').strip());
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    /** The first slot that holds anything, or -1 for an empty storage. */
    int firstFilled() {
        for (int slot = 0; slot < counts.length; slot++) if (counts[slot] > 0) return slot;
        return -1;
    }

    int lastFilled() {
        for (int slot = counts.length - 1; slot >= 0; slot--) if (counts[slot] > 0) return slot;
        return -1;
    }

    /**
     * One use took {@code used} blocks of the first stack's item. They come from that stack and, when it runs
     * short, from the next stacks of the same item; other items in between stay as they are. The wand takes
     * nothing. A count that no stack can cover is cut off, never driven below zero: the next screen open corrects it.
     *
     * @return how many blocks were taken from the grid
     */
    int consume(int used) {
        int first = firstFilled();
        if (used <= 0 || first < 0 || INFINIDIRT_WAND.equals(keys[first])) return 0;
        String item = keys[first];
        int left = used;
        for (int slot = first; slot < counts.length && left > 0; slot++) {
            if (!item.equals(keys[slot])) continue;
            int taken = Math.min(counts[slot], left);
            counts[slot] -= taken;
            left -= taken;
        }
        return used - left;
    }
}
