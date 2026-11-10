package com.github.beng420.kung.feature.hunting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.Test;

public final class AttributeLevelTest {
    private static final List<String> ENABLED = List.of("Mana Pool", "", "Enabled: Yes", "Click to disable!");

    @Test
    public void titleIsAttributeMenuWithOrWithoutAPagePrefix() {
        assertTrue(AttributeLevel.isMenuTitle("Attribute Menu"));
        assertTrue(AttributeLevel.isMenuTitle("(1/3) Attribute Menu"));
        assertTrue(AttributeLevel.isMenuTitle("(11/13) Attribute Menu "));
        assertFalse(AttributeLevel.isMenuTitle("Hunting Box"));
        assertFalse(AttributeLevel.isMenuTitle("(1/3) Hunting Box"));
        assertFalse(AttributeLevel.isMenuTitle("Attribute Menu Transfer"));
    }

    @Test
    public void levelIsTheRomanNumeralAtTheEndOfTheName() {
        assertEquals(OptionalInt.of(9), AttributeLevel.of("Berry Eater IX", ENABLED));
        assertEquals(OptionalInt.of(1), AttributeLevel.of("Essence of Ice I", ENABLED));
        assertEquals(OptionalInt.of(4), AttributeLevel.of("Mana Pool IV", ENABLED));
        assertEquals(OptionalInt.of(6), AttributeLevel.of("Mana Pool VI", ENABLED));
        assertEquals(OptionalInt.of(8), AttributeLevel.of("Mana Pool VIII", ENABLED));
        assertEquals(OptionalInt.of(10), AttributeLevel.of("Mana Pool X", ENABLED));
        assertEquals(OptionalInt.of(10), AttributeLevel.of(" Mana Pool X ", ENABLED));
    }

    @Test
    public void everyLevelFromOneToTenReadsBack() {
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        for (int level = 1; level <= 10; level++) {
            assertEquals(OptionalInt.of(level), AttributeLevel.of("Dominance " + numerals[level - 1], ENABLED));
        }
    }

    @Test
    public void anAttributeWithoutANumeralIsLevelZero() {
        List<String> locked = List.of("Nature Elemental", "Syphon 1 shard to unlock!");
        assertEquals(OptionalInt.of(0), AttributeLevel.of("Nature Elemental", locked));
        assertEquals(OptionalInt.of(0), AttributeLevel.of("Mana Pool", ENABLED));
    }

    @Test
    public void anAttributeIsToldByItsEnabledOrSyphonLine() {
        assertTrue(AttributeLevel.of("Berry Eater V", List.of("Enabled: No")).isPresent());
        assertTrue(AttributeLevel.of("Berry Eater V", List.of("Syphon 3 shards to level up!")).isPresent());
        assertTrue(AttributeLevel.of("Berry Eater V", List.of("Syphon 1 shard to unlock!")).isPresent());
    }

    @Test
    public void fillerAndButtonItemsAreNotAttributes() {
        assertEquals(OptionalInt.empty(), AttributeLevel.of(" ", List.of()));
        assertEquals(OptionalInt.empty(), AttributeLevel.of("Go Back", List.of("To Hunting Box")));
        assertEquals(OptionalInt.empty(), AttributeLevel.of("Advanced Mode", List.of("Advanced Mode unlocked at 30")));
        // A name with a numeral alone is no proof: the lore decides.
        assertEquals(OptionalInt.empty(), AttributeLevel.of("Hunting Level XXV", List.of("Reach level 25.")));
    }

    @Test
    public void theNumberShowsBelowTenOrAlwaysWhenNotLimited() {
        for (int level = 0; level < 10; level++) {
            assertTrue(AttributeLevel.showsNumber(level, true));
            assertTrue(AttributeLevel.showsNumber(level, false));
        }
        assertFalse(AttributeLevel.showsNumber(10, true));
        assertTrue(AttributeLevel.showsNumber(10, false));
    }

    @Test
    public void theSlotIsRedBelowTenOnly() {
        for (int level = 0; level < 10; level++) {
            assertTrue(AttributeLevel.isRed(level));
        }
        assertFalse(AttributeLevel.isRed(10));
    }
}
