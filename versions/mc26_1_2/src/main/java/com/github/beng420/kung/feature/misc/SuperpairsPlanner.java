package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks the next click worth making once cards are known, best card first. Pure logic.
 *
 * <ul>
 * <li>After an Instant Find, the next click is rewarded on its own, so a known single of a priority kind
 * (Titanic bottle, T7 enchantment) is the target: its hidden partner is found for free. With none, any click
 * does.
 * <li>Between the two clicks of a turn, the twin of the card just revealed, if it is known.
 * <li>At the start of a turn, once nothing unseen is left to find: the most valuable pair whose two cards are
 * both known, first slot first.
 * </ul>
 */
public final class SuperpairsPlanner {
    /** What the hidden buttons say the game is waiting for. */
    public enum Prompt {
        UNKNOWN, ANY, SECOND, INSTANT;

        public static Prompt of(String label) {
            String name = HypixelLocation.stripFormatting(label).trim().toLowerCase(java.util.Locale.ROOT);
            name = name.endsWith("!") ? name.substring(0, name.length() - 1) : name;
            return switch (name) {
                case "click any button" -> ANY;
                case "click a second button" -> SECOND;
                case "next button is instantly rewarded" -> INSTANT;
                default -> UNKNOWN;
            };
        }
    }

    /** The slots to click, in order; empty when no click is worth recommending. */
    public record Pick(List<Integer> slots, String reason) {
        public static final Pick NONE = new Pick(List.of(), "");
    }

    private SuperpairsPlanner() { }

    /**
     * @param claimed      slots whose pair is already taken
     * @param firstSlot    the card revealed by the first click of this turn, or -1
     * @param revealDone   no completely unseen pair is left, so claiming is all that remains
     * @param remainingClicks the clicks the game still allows, or -1 when unknown
     */
    public static Pick next(SuperpairsBoard board, Set<Integer> claimed, Prompt prompt, int firstSlot,
                            boolean revealDone, int remainingClicks) {
        if (remainingClicks == 0) return Pick.NONE;
        Map<String, List<Integer>> kinds = board.slotsByKind();
        switch (prompt) {
            case INSTANT -> {
                return best(board, kinds, claimed, 1, true, "instant find: a known single of a priority card");
            }
            case SECOND -> {
                for (Map.Entry<String, List<Integer>> kind : kinds.entrySet()) {
                    // While cards are still unseen, the second click reveals one; only a priority twin is worth it.
                    if (kind.getValue().contains(firstSlot)
                        && (revealDone || SuperpairsValue.isPriority(board.labelOfKind(kind.getKey())))) {
                        for (int slot : kind.getValue()) {
                            if (slot != firstSlot && !claimed.contains(slot)) {
                                return new Pick(List.of(slot), "twin of the card just revealed");
                            }
                        }
                    }
                }
                return Pick.NONE;
            }
            case ANY -> {
                if (!revealDone || remainingClicks == 1) return Pick.NONE;
                return best(board, kinds, claimed, 2, false, "best known pair");
            }
            default -> {
                return Pick.NONE;
            }
        }
    }

    /** The slots of every pair whose cards are both known and not taken yet: the pairs that can be clicked. */
    public static Set<Integer> claimableSlots(SuperpairsBoard board, Set<Integer> claimed) {
        Set<Integer> slots = new java.util.TreeSet<>();
        for (List<Integer> kind : board.slotsByKind().values()) {
            List<Integer> open = kind.stream().filter(slot -> !claimed.contains(slot)).toList();
            if (open.size() >= 2) {
                slots.addAll(open);
            }
        }
        return slots;
    }

    /**
     * The best kind of card with exactly {@code count} unclaimed known cards (a single, or a pair), ranked by
     * value. {@code priorityOnly} limits it to Titanic bottles and T7 enchantments.
     */
    private static Pick best(SuperpairsBoard board, Map<String, List<Integer>> kinds, Set<Integer> claimed,
                             int count, boolean priorityOnly, String reason) {
        List<List<Integer>> candidates = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : kinds.entrySet()) {
            List<Integer> open = entry.getValue().stream().filter(slot -> !claimed.contains(slot)).toList();
            String label = board.labelOfKind(entry.getKey());
            boolean fits = count == 1 ? open.size() % 2 == 1 : open.size() >= 2;
            if (fits && label != null && (!priorityOnly || SuperpairsValue.isPriority(label))) {
                candidates.add(open);
            }
        }
        Comparator<List<Integer>> byValue = Comparator.comparingInt(
            (List<Integer> open) -> SuperpairsValue.value(board.labelAt(open.getFirst()))).reversed();
        return candidates.stream().min(byValue.thenComparing(open -> open.getFirst()))
            .map(open -> new Pick(count == 1 ? List.of(open.getLast()) : List.of(open.get(0), open.get(1)), reason))
            .orElse(Pick.NONE);
    }
}
