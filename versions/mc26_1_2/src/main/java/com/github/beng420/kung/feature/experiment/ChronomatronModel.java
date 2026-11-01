package com.github.beng420.kung.feature.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows one Chronomatron game from menu snapshots. Every round the game flashes the whole sequence, one
 * glowing block after the other, and the player then clicks it back. The model writes down each flash of the
 * round and only trusts the list when it checks out: its length must be the number on the round item, and it
 * must start with the previous round's sequence. A list that does not check out shows nothing at all, so the
 * solver is either right or silent. Pure logic: the caller builds a {@link SlotView} list (slots 0-53) after
 * every menu change.
 */
public final class ChronomatronModel {
    public enum Phase { REMEMBER, PLAY, END }

    static final int ROUND_SLOT = 4;
    static final int STATUS_SLOT = 49;
    static final String TIMER = "Timer: ";
    static final String REMEMBER_NAME = "Remember the pattern!";
    private static final Pattern ROUND = Pattern.compile("Round: (\\d+)");
    private static final List<String> COLORS = List.of(
        "red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "pink");

    /** The colors flashed so far this round, in order. */
    private final List<String> flashes = new ArrayList<>();
    /** The glinting cells right now, with their colors. */
    private final Map<Integer, String> glowing = new TreeMap<>();
    /** The slots showing a color at rest before the first flash; nothing else is ever flashed or clicked. */
    private final Map<Integer, String> cells = new TreeMap<>();
    private boolean cellsKnown;
    private Phase phase = Phase.REMEMBER;
    /** The checked sequence of the current round; empty until the timer started and the flashes checked out. */
    private List<String> sequence = List.of();
    /** The checked sequence of the round before. */
    private List<String> previous = List.of();
    private String problem;
    /** The number on the round item, or -1 while it is not shown. */
    private int round = -1;
    private int next;

    /** The game's color of a terracotta or stained glass block, or null for anything else. */
    public static String colorOf(String itemId) {
        if (itemId == null) return null;
        String path = itemId.substring(itemId.indexOf(':') + 1);
        for (String suffix : new String[] {"_terracotta", "_stained_glass"}) {
            if (path.endsWith(suffix)) {
                String color = path.substring(0, path.length() - suffix.length());
                return COLORS.contains(color) ? color : null;
            }
        }
        return null;
    }

    public void update(List<SlotView> slots) {
        if (slots.size() <= STATUS_SLOT) return;
        String status = slots.get(STATUS_SLOT).name();
        observeRound(slots.get(ROUND_SLOT).name());
        if (status.equals(REMEMBER_NAME)) {
            if (phase != Phase.REMEMBER) newRound();
        } else if (!status.startsWith(TIMER) && phase == Phase.END) {
            reset();
            return;
        }
        if (phase == Phase.REMEMBER) {
            flash(slots);
            if (status.startsWith(TIMER)) startPlay();
        }
    }

    /** A higher round number means the next round began, even if its "Remember" status was missed. */
    private void observeRound(String name) {
        Matcher matcher = ROUND.matcher(name);
        if (!matcher.matches()) return;
        int number = Integer.parseInt(matcher.group(1));
        if (round >= 0 && number > round && phase != Phase.REMEMBER) newRound();
        round = number;
    }

    private void flash(List<SlotView> slots) {
        Map<Integer, String> now = new TreeMap<>();
        for (int slot = 0; slot < STATUS_SLOT; slot++) {
            SlotView view = slots.get(slot);
            String color = colorOf(view.item());
            if (color == null) continue;
            if (!view.glint()) {
                if (!cellsKnown) cells.put(slot, color);
            } else if (cells.containsKey(slot)) {
                // A cell keeps the color it had at rest, whatever it shows while it flashes or after a click.
                now.put(slot, cells.get(slot));
            }
        }
        // A color shows in more than one cell. A cell lighting up starts a new flash unless its color
        // already glows (its twin cell), so back-to-back flashes of different colors both count.
        for (Map.Entry<Integer, String> entry : now.entrySet()) {
            if (!glowing.containsKey(entry.getKey()) && !glowing.containsValue(entry.getValue())) {
                flashes.add(entry.getValue());
                cellsKnown = true;
                glowing.put(entry.getKey(), entry.getValue());
            }
        }
        glowing.clear();
        glowing.putAll(now);
    }

    /** The timer began: from here the flashes of this round are the sequence, if they check out. */
    private void startPlay() {
        // Still flashing, or the timer left over from the round before: not this round's click phase yet.
        if (flashes.isEmpty() || round > 0 && flashes.size() < round) return;
        problem = null;
        List<String> resolved = resolve();
        sequence = resolved == null ? List.of() : resolved;
        phase = Phase.PLAY;
        next = 0;
    }

    /** The round's sequence out of the flashes, or null with {@link #problem} set when they do not check out. */
    private List<String> resolve() {
        int expected = round > 0 ? round : previous.isEmpty() ? -1 : previous.size() + 1;
        List<String> list = flashes;
        if (expected > 0 && flashes.size() < expected) {
            problem = "flashes " + flashes.size() + " but round " + expected;
            return null;
        }
        if (expected > 0 && flashes.size() > expected) {
            // From round 2 on, the game answers the last clicks of the round before with a glint on their
            // color as the new round starts (seen 1-2 ms after the round item changes, 0.6-0.8 s before the
            // first real flash; two different colors give two glints, equal ones run into one). The real
            // flashes are always the last N.
            List<String> leftovers = flashes.subList(0, flashes.size() - expected);
            if (expected < 2 || !followsClicks(leftovers)) {
                problem = "glints " + leftovers + " before the flashes do not follow last round " + previous;
                return null;
            }
            list = flashes.subList(leftovers.size(), flashes.size());
        }
        if (!previous.isEmpty() && (list.size() <= previous.size() || !list.subList(0, previous.size()).equals(previous))) {
            problem = "replay " + list + " differs from last round " + previous;
            return null;
        }
        return List.copyOf(list);
    }

    /**
     * Whether the glints at the start of a round can be the answers to the last clicks of the round before: its
     * last m colors with equal neighbors run together. Without a known round before, a few of any colors fit.
     */
    private boolean followsClicks(List<String> leftovers) {
        if (previous.isEmpty()) return leftovers.size() <= 4;
        for (int m = 1; m <= previous.size(); m++) {
            List<String> tail = new ArrayList<>();
            for (String color : previous.subList(previous.size() - m, previous.size())) {
                if (tail.isEmpty() || !tail.getLast().equals(color)) tail.add(color);
            }
            if (tail.equals(leftovers)) return true;
        }
        return false;
    }

    /** Counts a click on {@code slot}; true when it was a cell of the expected color. */
    public boolean clickSlot(int slot) {
        return click(cells.get(slot));
    }

    /** Counts a click on a cell of {@code color}; true when it was the expected one. */
    public boolean click(String color) {
        if (expectedColor() == null || !expectedColor().equals(color)) return false;
        next++;
        if (next >= sequence.size()) phase = Phase.END;
        return true;
    }

    /** The color to click now, or null outside the click phase or when the sequence is not trusted. */
    public String expectedColor() {
        return phase == Phase.PLAY && next < sequence.size() ? sequence.get(next) : null;
    }

    /** The color to click after {@link #expectedColor()}, or null when that is the last one. */
    public String followingColor() {
        return expectedColor() != null && next + 1 < sequence.size() ? sequence.get(next + 1) : null;
    }

    /** The slots that can flash and be clicked: those showing a color at rest before the first flash. */
    public Set<Integer> cells() {
        return Collections.unmodifiableSet(cells.keySet());
    }

    /** The color of a cell, or null for any other slot. */
    public String colorAt(int slot) {
        return cells.get(slot);
    }

    /** The checked sequence of this round, empty while it is not known or not trusted. */
    public List<String> sequence() {
        return sequence;
    }

    /** The glints seen so far this round, in order; from round 2 on the first few answer the round before's last clicks. */
    public List<String> flashes() {
        return List.copyOf(flashes);
    }

    /** Why the click phase has no sequence, or null. */
    public String problem() {
        return problem;
    }

    public int round() {
        return round;
    }

    /** The number of the round the player just clicked through to its end, or 0 while one is under way. */
    public int finishedRound() {
        return phase == Phase.END ? Math.max(round, 0) : 0;
    }

    public Phase phase() {
        return phase;
    }

    /** Clicks made this round. */
    public int progress() {
        return next;
    }

    private void newRound() {
        if (!sequence.isEmpty()) previous = sequence;
        else if (problem != null) previous = List.of();
        sequence = List.of();
        problem = null;
        flashes.clear();
        glowing.clear();
        phase = Phase.REMEMBER;
        next = 0;
    }

    private void reset() {
        sequence = List.of();
        problem = null;
        previous = List.of();
        cells.clear();
        cellsKnown = false;
        round = -1;
        newRound();
    }
}
