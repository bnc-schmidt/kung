package com.github.beng420.kung.feature.experiment;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Stakes menu of an add-on, where each tier's lore lists the rewards by rounds ("Chain of N:" for
 * Chronomatron, "Series of N:" for Ultrasequencer). The last such line is the best reward, so the round it
 * names is the one after which the buffs are maxed. The line format is the one SkyHanni matches; Kung has no
 * trace of this lore yet.
 */
public final class StakesLore {
    /** The tier is a practice mode without rewards. */
    public static final int PRACTICE = -1;
    private static final Pattern ROUNDS = Pattern.compile("(?:Chain|Series) of (\\d+):");

    private StakesLore() { }

    /** The rounds of the best reward, 0 when the lore names none, {@link #PRACTICE} for a practice tier. */
    public static int roundsNeeded(List<String> lore) {
        int found = 0;
        for (String line : lore) {
            if (line.contains("Practice mode has no rewards")) {
                return PRACTICE;
            }
            Matcher matcher = ROUNDS.matcher(line);
            while (matcher.find()) {
                found = Integer.parseInt(matcher.group(1));
            }
        }
        return found;
    }

    /**
     * The round after which the buffs of {@code game} are maxed, 0 when unknown. Ultrasequencer needs one round
     * less than its lore says, a Hypixel quirk SkyHanni works around the same way.
     */
    public static int maxBuffsRound(String game, int roundsNeeded) {
        if (roundsNeeded <= 0) {
            return 0;
        }
        return game.equals("Ultrasequencer") ? Math.max(1, roundsNeeded - 1) : roundsNeeded;
    }
}
