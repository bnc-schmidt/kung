package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.misc.SuperpairsPlanner.Pick;
import com.github.beng420.kung.feature.misc.SuperpairsPlanner.Prompt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.Test;

public final class SuperpairsPlannerTest {
    private static final Set<Integer> NONE_CLAIMED = Set.of();

    @Test
    public void afterAnInstantFindTheKnownPrioritySingleIsTheTarget() {
        SuperpairsBoard board = board();
        reveal(board, 10, "Titanic Experience Bottle");
        reveal(board, 11, "Growth VII");
        reveal(board, 12, "150k Enchanting Exp");
        reveal(board, 13, "150k Enchanting Exp");

        assertEquals("The Titanic is worth more than a T7", List.of(10),
            SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.INSTANT, -1, false, 5).slots());
        assertEquals(List.of(11),
            SuperpairsPlanner.next(board, Set.of(10), Prompt.INSTANT, -1, false, 5).slots());
    }

    @Test
    public void withoutAPrioritySingleAnyClickIsFineAfterAnInstantFind() {
        SuperpairsBoard board = board();
        reveal(board, 10, "150k Enchanting Exp");
        reveal(board, 11, "Growth VI");
        assertEquals(Pick.NONE, SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.INSTANT, -1, false, 5));
        // A Titanic that is already a known pair is claimed the normal way, not with the free click.
        reveal(board, 12, "Titanic Experience Bottle");
        reveal(board, 13, "Titanic Experience Bottle");
        assertEquals(Pick.NONE, SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.INSTANT, -1, false, 5));
    }

    @Test
    public void betweenTheTwoClicksTheTwinOfTheRevealedCardIsTheTarget() {
        SuperpairsBoard board = board();
        reveal(board, 10, "Growth VII");
        reveal(board, 20, "Growth VII");
        reveal(board, 21, "5k Enchanting Exp");
        assertEquals(List.of(20), SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 10, false, 5).slots());
        assertEquals(List.of(10), SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 20, false, 5).slots());
        assertEquals("The twin of a card seen once is not known yet", Pick.NONE,
            SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 21, false, 5));
        assertEquals("A claimed twin is gone", Pick.NONE,
            SuperpairsPlanner.next(board, Set.of(20), Prompt.SECOND, 10, false, 5));
    }

    @Test
    public void whileCardsAreUnseenOnlyAPriorityTwinIsWorthTheSecondClick() {
        SuperpairsBoard board = board();
        reveal(board, 10, "34k Enchanting Exp");
        reveal(board, 20, "34k Enchanting Exp");
        reveal(board, 11, "Titanic Experience Bottle");
        reveal(board, 21, "Titanic Experience Bottle");
        assertEquals("An ordinary twin waits: the second click should reveal something", Pick.NONE,
            SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 10, false, 9));
        assertEquals(List.of(20), SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 10, true, 9).slots());
        assertEquals(List.of(21), SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 11, false, 9).slots());
    }

    @Test
    public void claimingStartsWithTheMostValuablePairOnceNothingUnseenIsLeft() {
        SuperpairsBoard board = board();
        reveal(board, 10, "150k Enchanting Exp");
        reveal(board, 11, "150k Enchanting Exp");
        reveal(board, 12, "Growth VII");
        reveal(board, 13, "Growth VII");
        reveal(board, 14, "Titanic Experience Bottle");
        reveal(board, 15, "Titanic Experience Bottle");
        reveal(board, 16, "Sharpness VI");
        reveal(board, 19, "Sharpness VI");

        assertEquals("Still hunting unseen pairs", Pick.NONE,
            SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.ANY, -1, false, 8));
        Set<Integer> claimed = new HashSet<>();
        for (List<Integer> expected : List.of(List.of(14, 15), List.of(12, 13), List.of(16, 19), List.of(10, 11))) {
            Pick pick = SuperpairsPlanner.next(board, claimed, Prompt.ANY, -1, true, 8);
            assertEquals(expected, pick.slots());
            claimed.addAll(pick.slots());
        }
        assertEquals(Pick.NONE, SuperpairsPlanner.next(board, claimed, Prompt.ANY, -1, true, 8));
    }

    @Test
    public void noPairIsStartedWithoutTwoClicksLeft() {
        SuperpairsBoard board = board();
        reveal(board, 10, "Growth VII");
        reveal(board, 11, "Growth VII");
        assertEquals(List.of(10, 11), SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.ANY, -1, true, 2).slots());
        assertEquals(Pick.NONE, SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.ANY, -1, true, 1));
        assertEquals(Pick.NONE, SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.SECOND, 10, true, 0));
        assertEquals("Unknown remaining clicks do not stop it", List.of(10, 11),
            SuperpairsPlanner.next(board, NONE_CLAIMED, Prompt.ANY, -1, true, -1).slots());
    }

    @Test
    public void claimableSlotsAreThePairsWithBothCardsKnownAndNotTaken() {
        SuperpairsBoard board = board();
        reveal(board, 10, "Growth VII");
        reveal(board, 20, "Growth VII");
        reveal(board, 11, "34k Enchanting Exp");
        reveal(board, 12, "5k Enchanting Exp");
        reveal(board, 21, "5k Enchanting Exp");
        assertEquals(Set.of(10, 20, 12, 21), SuperpairsPlanner.claimableSlots(board, NONE_CLAIMED));
        assertEquals("A taken pair is no longer claimable", Set.of(12, 21),
            SuperpairsPlanner.claimableSlots(board, Set.of(10, 20)));
        assertEquals(Set.of(), SuperpairsPlanner.claimableSlots(new SuperpairsBoard(), NONE_CLAIMED));
    }

    @Test
    public void theHiddenButtonPromptsMapToThePrompts() {
        assertEquals(Prompt.ANY, Prompt.of("Click any button!"));
        assertEquals(Prompt.SECOND, Prompt.of("§aClick a second button!"));
        assertEquals(Prompt.INSTANT, Prompt.of("Next button is instantly rewarded!"));
        assertEquals("? means the server is busy", Prompt.UNKNOWN, Prompt.of("?"));
        assertEquals(Prompt.UNKNOWN, Prompt.of(null));
    }

    @Test
    public void kindsKeepTheNumberTheyWereFirstSeenWith() {
        SuperpairsBoard board = board();
        reveal(board, 10, "5k Enchanting Exp");
        reveal(board, 11, "Growth VII");
        reveal(board, 12, "5k Enchanting Exp");
        assertEquals(0, board.legendIndexAt(10));
        assertEquals(1, board.legendIndexAt(11));
        assertEquals(0, board.legendIndexAt(12));
        assertEquals(-1, board.legendIndexAt(13));
        assertEquals(List.of(List.of(10, 12), List.of(11)), List.copyOf(board.slotsByKind().values()));
        board.reset();
        assertTrue(board.slotsByKind().isEmpty());
        assertFalse(board.labelAt(10) != null);
    }

    private static SuperpairsBoard board() {
        SuperpairsBoard board = new SuperpairsBoard();
        IntStream.range(0, 54).filter(slot -> SuperpairsBoard.isBoardSlot(54, slot))
            .forEach(slot -> board.observe(54, slot, "cyan_stained_glass", "?", 1));
        return board;
    }

    private static void reveal(SuperpairsBoard board, int slot, String label) {
        board.observe(54, slot, label.contains("Bottle") ? "player_head" : label.contains("Exp") ? "lime_dye" : "enchanted_book",
            label, 1);
    }
}
