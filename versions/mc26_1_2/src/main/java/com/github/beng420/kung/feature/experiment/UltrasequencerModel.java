package com.github.beng420.kung.feature.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows one Ultrasequencer game from menu snapshots. While the game says "Remember the pattern!" it shows
 * numbered items; once the timer starts they are hidden and must be clicked in number order. The numbers are
 * only trusted when they run 1, 2, 3 ... without a gap; otherwise the model shows nothing instead of a wrong
 * order. Pure logic: the caller builds a {@link SlotView} list (slots 0-53) after every menu change.
 */
public final class UltrasequencerModel {
    public enum Phase { REMEMBER, PLAY, END }

    static final int STATUS_SLOT = 49;
    private static final int FIRST_SLOT = 9;
    private static final int LAST_SLOT = 44;
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");
    private static final Pattern ROUND = Pattern.compile("Round: (\\d+)");

    private final TreeMap<Integer, Integer> slotByNumber = new TreeMap<>();
    private Phase phase = Phase.REMEMBER;
    private boolean usable;
    private int next = 1;
    /** Rounds clicked through to the end since this model began. */
    private int rounds;
    /** The number on the round item if the game shows one in slot 4, else -1. */
    private int round = -1;

    public void update(List<SlotView> slots) {
        if (slots.size() <= STATUS_SLOT) return;
        Matcher roundItem = ROUND.matcher(slots.get(ChronomatronModel.ROUND_SLOT).name());
        if (roundItem.matches()) round = Integer.parseInt(roundItem.group(1));
        String status = slots.get(STATUS_SLOT).name();
        if (status.equals(ChronomatronModel.REMEMBER_NAME)) {
            if (phase != Phase.REMEMBER) newRound();
            collect(slots);
        } else if (status.startsWith(ChronomatronModel.TIMER)) {
            if (phase == Phase.REMEMBER && !slotByNumber.isEmpty()) {
                phase = Phase.PLAY;
                next = 1;
                usable = contiguous();
            }
        } else if (phase == Phase.END) {
            newRound();
        }
    }

    /** Numbers can appear over several updates, so every shown number is kept until the round is over. */
    private void collect(List<SlotView> slots) {
        for (int slot = FIRST_SLOT; slot <= LAST_SLOT; slot++) {
            String name = slots.get(slot).name();
            if (!NUMBER.matcher(name).matches()) continue;
            int shownAt = slot;
            slotByNumber.values().removeIf(known -> known == shownAt);
            slotByNumber.put(Integer.parseInt(name), slot);
        }
    }

    private boolean contiguous() {
        return slotByNumber.firstKey() == 1 && slotByNumber.lastKey() == slotByNumber.size();
    }

    /** Counts a click on {@code slot}; true when it was the expected one. */
    public boolean click(int slot) {
        Integer expected = nextSlot();
        if (expected == null || expected != slot) return false;
        next++;
        if (!slotByNumber.containsKey(next)) {
            phase = Phase.END;
            rounds++;
        }
        return true;
    }

    /** The number of the round the player just clicked through to its end, or 0 while one is under way. */
    public int finishedRound() {
        return phase == Phase.END ? (round > 0 ? round : rounds) : 0;
    }

    /** The slot to click now, or null outside the click phase or when the numbers are not trusted. */
    public Integer nextSlot() {
        return phase == Phase.PLAY && usable ? slotByNumber.get(next) : null;
    }

    /** The next {@code count} slots to click, in order, starting with {@link #nextSlot()}. */
    public List<Integer> upcomingSlots(int count) {
        List<Integer> slots = new ArrayList<>();
        for (int number = next; nextSlot() != null && slots.size() < count && slotByNumber.containsKey(number); number++) {
            slots.add(slotByNumber.get(number));
        }
        return slots;
    }

    /** The number shown in a slot during the memorize phase, or 0. */
    public int numberAt(int slot) {
        for (Map.Entry<Integer, Integer> entry : slotByNumber.entrySet()) {
            if (entry.getValue() == slot) return entry.getKey();
        }
        return 0;
    }

    /** True for a slot whose number is still to be clicked this round. */
    public boolean pending(int slot) {
        return phase == Phase.PLAY && usable && numberAt(slot) >= next;
    }

    /** The numbers learned this round, in order, as {@code slot=number}; for the trace. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        slotByNumber.forEach((number, slot) -> text.append(text.isEmpty() ? "" : " ").append(slot).append('=').append(number));
        return text.toString();
    }

    public Phase phase() {
        return phase;
    }

    public boolean usable() {
        return usable;
    }

    /** Clicks made this round. */
    public int progress() {
        return next - 1;
    }

    private void newRound() {
        slotByNumber.clear();
        phase = Phase.REMEMBER;
        usable = false;
        next = 1;
    }
}
