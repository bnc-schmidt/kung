package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class SuperpairsAutoRevealTest {
    @Test
    public void unseenSlotsAreTheBoardSlotsNoCardWasSeenIn() {
        SuperpairsBoard board = fullBoard();
        assertEquals(28, board.unseenSlots().size());
        assertEquals(10, (int) board.unseenSlots().getFirst());

        board.observe(54, 10, "magenta_dye", "144k Enchanting Exp", 1);
        board.observe(54, 11, "diamond", "Instant Find", 1);
        // A card turning back into a button prompt does not make its slot unseen again.
        board.observe(54, 10, "cyan_stained_glass", "Click any button!", 1);
        List<Integer> unseen = board.unseenSlots();
        assertEquals(26, unseen.size());
        assertFalse(unseen.contains(10));
        assertFalse(unseen.contains(11));
        assertEquals(12, (int) unseen.getFirst());
    }

    @Test
    public void unseenSlotsOfAnEmptyBoardAreEmpty() {
        assertEquals(List.of(), new SuperpairsBoard().unseenSlots());
    }

    @Test
    public void onlyTheClickPromptsAreAccepted() {
        for (String prompt : new String[] {"Click any button!", "§aClick a second button!", "Next button is instantly rewarded!",
            "click any button"}) {
            assertTrue(prompt, SuperpairsAutoReveal.ready(prompt, SuperpairsAutoReveal.SETTLE_MILLIS));
        }
        for (String prompt : new String[] {"?", "", null, "Instant Find", "Click any button now"}) {
            assertFalse(String.valueOf(prompt), SuperpairsAutoReveal.ready(prompt, SuperpairsAutoReveal.SETTLE_MILLIS));
        }
    }

    @Test
    public void clicksWaitForTheBoardToSettleAndForAPromptThatTakesClicks() {
        assertFalse("Slot updates are still coming in", SuperpairsAutoReveal.ready("Click any button!", 100));
        assertTrue(SuperpairsAutoReveal.ready("Click any button!", 150));
        assertTrue(SuperpairsAutoReveal.ready("Click a second button!", 400));
        assertFalse("An unknown prompt waits", SuperpairsAutoReveal.ready("?", 2_999));
        assertTrue("A prompt nobody has traced does not stall the helper for good", SuperpairsAutoReveal.ready("?", 3_000));
    }

    @Test
    public void onlyUnseenSlotsStillShowingAHiddenCardAreTargets() {
        SuperpairsBoard board = fullBoard();
        board.observe(54, 10, "magenta_dye", "144k Enchanting Exp", 1);
        // Slot 11 is unseen but showing a card face up right now; slot 12 is hidden.
        List<Integer> targets = SuperpairsAutoReveal.targets(board, slot -> slot == 11 ? null : "Click any button!");
        assertFalse(targets.contains(10));
        assertFalse(targets.contains(11));
        assertTrue(targets.contains(12));
        assertEquals(26, targets.size());
    }

    private static SuperpairsBoard fullBoard() {
        SuperpairsBoard board = new SuperpairsBoard();
        for (int slot = 0; slot < 54; slot++) {
            if (SuperpairsBoard.isBoardSlot(54, slot)) {
                board.observe(54, slot, "cyan_stained_glass", "?", 1);
            }
        }
        return board;
    }
}
