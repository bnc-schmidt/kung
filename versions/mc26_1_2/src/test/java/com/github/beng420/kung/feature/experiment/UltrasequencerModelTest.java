package com.github.beng420.kung.feature.experiment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.experiment.UltrasequencerModel.Phase;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class UltrasequencerModelTest {
    private static final SlotView REMEMBER = new SlotView("minecraft:glowstone", "Remember the pattern!", false);
    private static final SlotView TIMER = new SlotView("minecraft:clock", "Timer: 12s", false);

    @Test
    public void clicksTheNumbersInOrderWhereverTheyWereShown() {
        UltrasequencerModel model = new UltrasequencerModel();
        model.update(shown(REMEMBER, 10, 2, 12, 1, 14, 3));
        assertNull("Nothing to click while the numbers are shown", model.nextSlot());

        model.update(shown(TIMER));
        assertEquals(Phase.PLAY, model.phase());
        assertEquals(Integer.valueOf(12), model.nextSlot());
        assertEquals(List.of(12, 10, 14), model.upcomingSlots(3));
        assertEquals(1, model.numberAt(12));

        assertTrue(model.click(12));
        assertEquals(List.of(10, 14), model.upcomingSlots(3));
        assertTrue(model.click(10));
        assertEquals(2, model.progress());
        assertTrue(model.click(14));
        assertEquals(Phase.END, model.phase());
        assertNull(model.nextSlot());
    }

    @Test
    public void aWrongClickDoesNotAdvance() {
        UltrasequencerModel model = playing(10, 2, 12, 1);
        assertFalse(model.click(10));
        assertEquals(Integer.valueOf(12), model.nextSlot());
        assertEquals(0, model.progress());
    }

    @Test
    public void numbersShownOverSeveralUpdatesAreAllKept() {
        UltrasequencerModel model = new UltrasequencerModel();
        model.update(shown(REMEMBER, 12, 1));
        model.update(shown(REMEMBER, 12, 1, 10, 2));
        model.update(shown(REMEMBER, 10, 2, 14, 3));
        model.update(shown(TIMER));
        assertEquals(List.of(12, 10, 14), model.upcomingSlots(5));
    }

    @Test
    public void aNumberMovingToAnotherSlotKeepsOnlyItsLatestSlot() {
        UltrasequencerModel model = new UltrasequencerModel();
        model.update(shown(REMEMBER, 12, 1, 10, 2));
        model.update(shown(REMEMBER, 13, 1, 10, 2));
        model.update(shown(TIMER));
        assertEquals(List.of(13, 10), model.upcomingSlots(5));
    }

    @Test
    public void theNextRoundStartsFromScratch() {
        UltrasequencerModel model = playing(10, 2, 12, 1);
        model.click(12);
        model.click(10);
        assertEquals(Phase.END, model.phase());

        model.update(shown(REMEMBER, 20, 1, 21, 2, 22, 3));
        assertEquals(Phase.REMEMBER, model.phase());
        model.update(shown(TIMER));
        assertEquals(List.of(20, 21, 22), model.upcomingSlots(5));
    }

    @Test
    public void numbersWithAGapShowNothing() {
        UltrasequencerModel model = new UltrasequencerModel();
        // 3 never showed up: the order after 2 would be a guess.
        model.update(shown(REMEMBER, 12, 1, 10, 2, 14, 4));
        model.update(shown(TIMER));
        assertEquals(Phase.PLAY, model.phase());
        assertFalse(model.usable());
        assertNull(model.nextSlot());
        assertEquals(List.of(), model.upcomingSlots(3));
        assertFalse(model.pending(12));
        assertFalse(model.click(12));
    }

    @Test
    public void numbersNotStartingAtOneShowNothing() {
        UltrasequencerModel model = playing(10, 2, 12, 3);
        assertFalse(model.usable());
        assertNull(model.nextSlot());
    }

    @Test
    public void onlyNumbersStillToClickArePending() {
        UltrasequencerModel model = playing(10, 2, 12, 1, 14, 3);
        assertTrue(model.pending(12));
        assertTrue(model.pending(10));
        assertTrue(model.pending(14));
        assertFalse("Not a numbered slot", model.pending(20));
        model.click(12);
        assertFalse("Clicked already", model.pending(12));
        assertTrue(model.pending(10));
        model.click(10);
        model.click(14);
        assertFalse(model.pending(14));
    }

    @Test
    public void aRoundIsFinishedWithItsLastNumberAndCountedByTheRoundItemOrBySelf() {
        UltrasequencerModel model = playing(10, 2, 12, 1);
        assertEquals(0, model.finishedRound());
        model.click(12);
        assertEquals(0, model.finishedRound());
        model.click(10);
        assertEquals("Counted by the model without a round item", 1, model.finishedRound());

        model.update(shown(REMEMBER, 20, 1));
        model.update(shown(TIMER));
        model.click(20);
        assertEquals(2, model.finishedRound());

        List<SlotView> remember = shown(REMEMBER, 20, 1);
        remember.set(4, new SlotView("minecraft:bookshelf", "Round: 7", false));
        model.update(remember);
        List<SlotView> timer = shown(TIMER);
        timer.set(4, new SlotView("minecraft:bookshelf", "Round: 7", false));
        model.update(timer);
        model.click(20);
        assertEquals("The round item wins", 7, model.finishedRound());
    }

    @Test
    public void aTimerWithoutNumbersStartsNothing() {
        UltrasequencerModel model = new UltrasequencerModel();
        model.update(shown(TIMER));
        assertEquals(Phase.REMEMBER, model.phase());
        assertEquals(List.of(), model.upcomingSlots(3));
    }

    @Test
    public void nonNumbersAndSlotsOutsideThePlayAreaAreIgnored() {
        UltrasequencerModel model = new UltrasequencerModel();
        List<SlotView> slots = shown(REMEMBER, 12, 1);
        slots.set(11, new SlotView("minecraft:black_stained_glass_pane", " ", false));
        slots.set(13, new SlotView("minecraft:lime_dye", "Lime", false));
        slots.set(3, new SlotView("minecraft:lime_dye", "7", false));
        slots.set(50, new SlotView("minecraft:lime_dye", "8", false));
        model.update(slots);
        model.update(shown(TIMER));
        assertEquals(List.of(12), model.upcomingSlots(5));
    }

    @Test
    public void aFinishedGameStartsOverAfterANonRoundStatus() {
        UltrasequencerModel model = playing(10, 1);
        model.click(10);
        model.update(shown(new SlotView("minecraft:barrier", "Experiment over", false)));
        assertEquals(Phase.REMEMBER, model.phase());
        assertEquals(List.of(), model.upcomingSlots(3));
    }

    private static UltrasequencerModel playing(int... slotNumberPairs) {
        UltrasequencerModel model = new UltrasequencerModel();
        model.update(shown(REMEMBER, slotNumberPairs));
        model.update(shown(TIMER));
        return model;
    }

    /** A menu whose status slot is {@code status} and whose listed slots show the given numbers. */
    private static List<SlotView> shown(SlotView status, int... slotNumberPairs) {
        List<SlotView> slots = new ArrayList<>();
        for (int slot = 0; slot < 54; slot++) {
            slots.add(slot == 49 ? status : SlotView.EMPTY);
        }
        for (int index = 0; index < slotNumberPairs.length; index += 2) {
            slots.set(slotNumberPairs[index], new SlotView("minecraft:lime_dye",
                String.valueOf(slotNumberPairs[index + 1]), false));
        }
        return slots;
    }
}
