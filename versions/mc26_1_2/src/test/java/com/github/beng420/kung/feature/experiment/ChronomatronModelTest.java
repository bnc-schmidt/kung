package com.github.beng420.kung.feature.experiment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.experiment.ChronomatronModel.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class ChronomatronModelTest {
    private static final SlotView REMEMBER = new SlotView("minecraft:glowstone", "Remember the pattern!", false);
    private static final SlotView TIMER = new SlotView("minecraft:clock", "Timer: 9s", false);
    /** Each color sits in two cells, one above the other, like the game's High board. */
    private static final Map<String, int[]> CELLS = Map.of(
        "red", new int[] {11, 20}, "blue", new int[] {12, 21}, "lime", new int[] {13, 22});

    @Test
    public void colorsComeFromTerracottaAndStainedGlassOnly() {
        assertEquals("red", ChronomatronModel.colorOf("minecraft:red_terracotta"));
        assertEquals("light_blue", ChronomatronModel.colorOf("minecraft:light_blue_stained_glass"));
        assertNull(ChronomatronModel.colorOf("minecraft:red_stained_glass_pane"));
        assertNull(ChronomatronModel.colorOf("minecraft:white_terracotta"));
        assertNull(ChronomatronModel.colorOf("minecraft:terracotta"));
        assertNull(ChronomatronModel.colorOf("minecraft:clock"));
        assertNull(ChronomatronModel.colorOf(""));
    }

    @Test
    public void everyRoundIsTheFlashesOfThatRound() {
        ChronomatronModel model = new ChronomatronModel();
        round(model, 1, "red");
        assertEquals(Phase.PLAY, model.phase());
        assertEquals(List.of("red"), model.sequence());
        assertEquals("red", model.expectedColor());
        assertNull("Nothing follows the only color", model.followingColor());
        assertTrue(model.click("red"));
        assertEquals(Phase.END, model.phase());
        assertNull(model.expectedColor());

        round(model, 2, "red", "lime");
        assertEquals(List.of("red", "lime"), model.sequence());
        assertEquals("red", model.expectedColor());
        assertEquals("lime", model.followingColor());
        assertTrue(model.click("red"));
        assertEquals("lime", model.expectedColor());
        assertNull(model.followingColor());
        assertTrue(model.click("lime"));
        assertEquals(Phase.END, model.phase());
    }

    @Test
    public void theOrderStaysRightWhenColorsRepeat() {
        ChronomatronModel model = game("red", "lime", "red", "red", "blue");
        assertEquals("red", model.expectedColor());
        assertEquals("lime", model.followingColor());
        assertTrue(model.click("red"));
        assertEquals("lime", model.expectedColor());
        assertTrue(model.click("lime"));
        // Two reds in a row: the same color is now and after.
        assertEquals("red", model.expectedColor());
        assertEquals("red", model.followingColor());
        assertTrue(model.click("red"));
        assertEquals("red", model.expectedColor());
        assertEquals("blue", model.followingColor());
        assertTrue(model.click("red"));
        assertEquals("blue", model.expectedColor());
        assertTrue(model.click("blue"));
        assertEquals(Phase.END, model.phase());
    }

    @Test
    public void aSameColorTwiceInARowIsTwoFlashesAndTheFollowingColorIsTheSame() {
        ChronomatronModel model = game("blue", "blue");
        assertEquals(List.of("blue", "blue"), model.sequence());
        assertEquals("blue", model.expectedColor());
        assertEquals("blue", model.followingColor());
    }

    @Test
    public void aWrongClickDoesNotAdvance() {
        ChronomatronModel model = game("red", "lime");
        assertFalse(model.click("lime"));
        assertFalse(model.click(null));
        assertEquals("red", model.expectedColor());
        assertEquals(0, model.progress());
        assertTrue(model.click("red"));
        assertFalse(model.click("red"));
        assertEquals("lime", model.expectedColor());
    }

    @Test
    public void aMirroredCellLightingLaterIsTheSameFlash() {
        ChronomatronModel model = new ChronomatronModel();
        begin(model, 1);
        flash(model, 1, "red");
        assertEquals(List.of("red"), model.flashes());
    }

    @Test
    public void aDifferentColorLightingBeforeTheLastWentDarkIsItsOwnFlash() {
        ChronomatronModel model = new ChronomatronModel();
        begin(model, 2);
        List<SlotView> red = rest(REMEMBER, 2);
        red.set(11, glow("red"));
        model.update(red);
        List<SlotView> both = new ArrayList<>(red);
        both.set(12, glow("blue"));
        model.update(both);
        model.update(rest(REMEMBER, 2));
        assertEquals(List.of("red", "blue"), model.flashes());
    }

    @Test
    public void aGlintStayingOnAcrossUpdatesIsOneFlash() {
        ChronomatronModel model = new ChronomatronModel();
        begin(model, 1);
        List<SlotView> lit = rest(REMEMBER, 1);
        lit.set(11, glow("red"));
        model.update(lit);
        model.update(lit);
        model.update(lit);
        assertEquals(List.of("red"), model.flashes());
    }

    @Test
    public void onlyTheBlocksShownAtRestBeforeTheFirstFlashAreCells() {
        ChronomatronModel model = new ChronomatronModel();
        model.update(rest(REMEMBER, 1));
        assertEquals(Set.of(11, 20, 12, 21, 13, 22), model.cells());

        flash(model, 1, "red");
        List<SlotView> later = rest(REMEMBER, 1);
        later.set(31, new SlotView("minecraft:lime_stained_glass", "Lime", false));
        model.update(later);
        assertEquals(Set.of(11, 20, 12, 21, 13, 22), model.cells());
    }

    @Test
    public void aGlintOutsideTheBoardIsNotAFlash() {
        ChronomatronModel model = new ChronomatronModel();
        model.update(rest(REMEMBER, 1));
        List<SlotView> stray = rest(REMEMBER, 1);
        stray.set(31, new SlotView("minecraft:lime_terracotta", "", true));
        model.update(stray);
        assertEquals(List.of(), model.flashes());

        // The same stray glint beside a real flash cannot change its color either.
        List<SlotView> both = rest(REMEMBER, 1);
        both.set(11, glow("red"));
        both.set(31, new SlotView("minecraft:lime_terracotta", "", true));
        model.update(both);
        assertEquals(List.of("red"), model.flashes());
    }

    @Test
    public void aTimerBeforeTheLastFlashWaitsForIt() {
        ChronomatronModel model = new ChronomatronModel();
        begin(model, 2);
        flash(model, 2, "red");
        model.update(rest(TIMER, 2));
        assertEquals("One of two flashes seen: not the click phase yet", Phase.REMEMBER, model.phase());
        assertNull(model.expectedColor());

        flash(model, 2, "lime", TIMER);
        assertEquals(Phase.PLAY, model.phase());
        assertEquals(List.of("red", "lime"), model.sequence());
    }

    @Test
    public void aRoundWithTheWrongNumberOfFlashesShowsNothing() {
        ChronomatronModel model = new ChronomatronModel();
        begin(model, 1);
        flash(model, 1, "red");
        flash(model, 1, "lime");
        model.update(rest(TIMER, 1));
        assertEquals(Phase.PLAY, model.phase());
        assertNull("Two flashes in round 1 cannot be trusted", model.expectedColor());
        assertNotNull(model.problem());
        assertFalse(model.click("red"));
    }

    @Test
    public void aReplayThatDiffersFromTheLastRoundShowsNothing() {
        ChronomatronModel model = game("red");
        model.click("red");
        round(model, 2, "blue", "lime");
        assertEquals(Phase.PLAY, model.phase());
        assertNull(model.expectedColor());
        assertTrue(model.problem().contains("differs"));
    }

    @Test
    public void aTimerLeftOverFromTheRoundBeforeStartsNothing() {
        ChronomatronModel model = game("red");
        model.click("red");
        // Round 2 began but the old timer item has not been replaced yet.
        model.update(rest(TIMER, 2));
        assertEquals(Phase.REMEMBER, model.phase());
        assertNull(model.expectedColor());
        assertNull(model.problem());
    }

    @Test
    public void aHigherRoundNumberStartsTheNextRoundEvenWithoutTheRememberStatus() {
        ChronomatronModel model = game("red", "lime");
        model.click("red");
        model.update(rest(TIMER, 3));
        assertEquals(Phase.REMEMBER, model.phase());
        assertNull(model.expectedColor());
    }

    @Test
    public void joiningInTheMiddleSyncsFromTheRoundNumber() {
        ChronomatronModel model = new ChronomatronModel();
        round(model, 3, "red", "lime", "blue");
        assertEquals(List.of("red", "lime", "blue"), model.sequence());
        assertEquals("red", model.expectedColor());
    }

    @Test
    public void aFinishedGameForgetsItsSequenceAndBoard() {
        ChronomatronModel model = game("red");
        assertTrue(model.click("red"));
        model.update(rest(new SlotView("minecraft:barrier", "Experiment over", false), 1));
        assertEquals(Phase.REMEMBER, model.phase());
        assertEquals(List.of(), model.sequence());
        assertEquals(Set.of(), model.cells());
    }

    @Test
    public void colorsWithoutGlintAreNotFlashes() {
        ChronomatronModel model = new ChronomatronModel();
        List<SlotView> dark = rest(REMEMBER, 1);
        dark.set(20, new SlotView("minecraft:red_terracotta", "", false));
        model.update(dark);
        assertEquals(List.of(), model.flashes());
    }

    @Test
    public void glintsAnsweringTheLastClicksOfTheRoundBeforeAreNoFlashes() {
        // The user's trace of 2026-10-30 15:00, 11 rounds: from round 2 on, one or two glints (the last two
        // clicks, equal colors run together) came 1-2 ms after the round started, before the real flashes.
        // Rounds 5, 7 and 11 had two different ones and made the first fix go silent.
        String[] all = {"lime", "lime", "lime", "blue", "red", "blue", "lime", "lime", "lime", "red", "lime"};
        for (int rounds = 1; rounds <= all.length; rounds++) {
            ChronomatronModel model = game(java.util.Arrays.copyOf(all, rounds));
            assertEquals("round " + rounds, List.of(java.util.Arrays.copyOf(all, rounds)), model.sequence());
            assertEquals("round " + rounds, all[0], model.expectedColor());
            assertNull("round " + rounds, model.problem());
        }
    }

    @Test
    public void aRoundIsFinishedWithItsLastCorrectClick() {
        ChronomatronModel model = game("lime", "blue", "red");
        assertEquals(0, model.finishedRound());
        assertTrue(model.click("lime"));
        assertTrue(model.click("blue"));
        assertEquals("Not before the last click", 0, model.finishedRound());
        assertFalse(model.click("lime"));
        assertEquals(0, model.finishedRound());
        assertTrue(model.click("red"));
        assertEquals(3, model.finishedRound());
        // The game starts the next round a moment later.
        begin(model, 4);
        assertEquals(0, model.finishedRound());
    }

    @Test
    public void aGlintThatDoesNotFollowTheLastClicksOfTheRoundBeforeShowsNothing() {
        ChronomatronModel model = game("blue", "red");
        model.click("blue");
        model.click("red");
        begin(model, 3);
        // The round before ended on blue, red: lime cannot be an answer to those clicks.
        flash(model, 3, "lime");
        flash(model, 3, "blue");
        flash(model, 3, "red");
        flash(model, 3, "red", TIMER);
        assertNull(model.expectedColor());
        assertTrue(model.problem().contains("do not follow last round"));
    }

    @Test
    public void aSecondGlintBeforeTheFirstRoundIsOverShowsNothing() {
        // Round 1 has no round before, so there is nothing to leave over: two flashes are a miscount.
        ChronomatronModel model = new ChronomatronModel();
        begin(model, 1);
        flash(model, 1, "blue");
        flash(model, 1, "blue", TIMER);
        assertNull(model.expectedColor());
        assertNotNull(model.problem());
    }

    /** Plays rounds 1..n of {@code colors} (each round a prefix) and stops in the click phase of round n. */
    private static ChronomatronModel game(String... colors) {
        ChronomatronModel model = new ChronomatronModel();
        for (int round = 1; round <= colors.length; round++) {
            String[] flashed = java.util.Arrays.copyOf(colors, round);
            begin(model, round);
            for (String leftover : leftovers(colors, round)) {
                flash(model, round, leftover);
            }
            for (int index = 0; index < flashed.length; index++) {
                flash(model, round, flashed[index], index == flashed.length - 1 ? TIMER : REMEMBER);
            }
            if (round < colors.length) {
                for (String color : flashed) {
                    assertTrue(model.click(color));
                }
            }
        }
        return model;
    }

    /**
     * The glints the game shows as round {@code round} starts, as in the traces of 2026-10-30: answers to the
     * round before's last two clicks, in click order, equal neighbors run together.
     */
    private static List<String> leftovers(String[] colors, int round) {
        List<String> glints = new ArrayList<>();
        for (int index = Math.max(0, round - 3); index < round - 1; index++) {
            if (glints.isEmpty() || !glints.getLast().equals(colors[index])) {
                glints.add(colors[index]);
            }
        }
        return glints;
    }

    /** The game's round {@code number}: status, round item, the given flashes, then the timer. */
    private static void round(ChronomatronModel model, int number, String... flashes) {
        begin(model, number);
        for (int index = 0; index < flashes.length; index++) {
            flash(model, number, flashes[index], index == flashes.length - 1 ? TIMER : REMEMBER);
        }
    }

    private static void begin(ChronomatronModel model, int number) {
        model.update(rest(REMEMBER, number));
    }

    private static void flash(ChronomatronModel model, int round, String color) {
        flash(model, round, color, REMEMBER);
    }

    /** One flash: the first cell lights, then its twin, then both go dark (with {@code after} as status). */
    private static void flash(ChronomatronModel model, int round, String color, SlotView after) {
        int[] cells = CELLS.get(color);
        List<SlotView> lit = rest(REMEMBER, round);
        lit.set(cells[0], glow(color));
        model.update(lit);
        lit.set(cells[1], glow(color));
        model.update(lit);
        model.update(rest(after, round));
    }

    private static SlotView glow(String color) {
        return new SlotView("minecraft:" + color + "_terracotta", "", true);
    }

    /** The board at rest: two cells per color, the round item and the status item. */
    private static List<SlotView> rest(SlotView status, int round) {
        List<SlotView> slots = new ArrayList<>();
        for (int slot = 0; slot < 54; slot++) {
            slots.add(SlotView.EMPTY);
        }
        slots.set(4, new SlotView("minecraft:bookshelf", "Round: " + round, false));
        slots.set(49, status);
        CELLS.forEach((color, cells) -> {
            for (int cell : cells) {
                slots.set(cell, new SlotView("minecraft:" + color + "_stained_glass", color, false));
            }
        });
        return slots;
    }
}
