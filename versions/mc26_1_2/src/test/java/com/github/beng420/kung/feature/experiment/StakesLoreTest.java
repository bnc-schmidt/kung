package com.github.beng420.kung.feature.experiment;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class StakesLoreTest {
    @Test
    public void theLastRewardLineNamesTheRoundsForTheBestReward() {
        List<String> lore = List.of("Fill the board.", "Chain of 3: +1 click", "Chain of 6: +2 clicks", "Chain of 9: +3 clicks", "");
        assertEquals(9, StakesLore.roundsNeeded(lore));
        assertEquals(6, StakesLore.roundsNeeded(List.of("Series of 4: +1 click", "Series of 6: +3 clicks")));
    }

    @Test
    public void practiceAndEmptyLoreHaveNoRounds() {
        assertEquals(StakesLore.PRACTICE, StakesLore.roundsNeeded(List.of("Practice mode has no rewards.", "Chain of 9: +3")));
        assertEquals(0, StakesLore.roundsNeeded(List.of("Enchanting level too low!")));
        assertEquals(0, StakesLore.roundsNeeded(List.of()));
    }

    @Test
    public void ultrasequencerNeedsOneRoundLessThanItsLoreSays() {
        // In the 2026-10-30 15:33 trace SkyHanni alerted after round 5 of a Metaphysical Ultrasequencer.
        assertEquals(9, StakesLore.maxBuffsRound("Chronomatron", 9));
        assertEquals(5, StakesLore.maxBuffsRound("Ultrasequencer", 6));
        assertEquals(1, StakesLore.maxBuffsRound("Ultrasequencer", 1));
        assertEquals(0, StakesLore.maxBuffsRound("Chronomatron", StakesLore.PRACTICE));
        assertEquals(0, StakesLore.maxBuffsRound("Ultrasequencer", 0));
    }
}
