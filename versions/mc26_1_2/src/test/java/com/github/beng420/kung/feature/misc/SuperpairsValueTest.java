package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class SuperpairsValueTest {
    @Test
    public void titanicBottlesAndLevelSevenEnchantmentsArePriorityCards() {
        assertTrue(SuperpairsValue.isPriority("Titanic Experience Bottle"));
        assertTrue(SuperpairsValue.isPriority("Titanic Experience Bottle x2"));
        assertTrue(SuperpairsValue.isPriority("Growth VII"));
        assertTrue(SuperpairsValue.isPriority("Big Brain X"));
        assertFalse(SuperpairsValue.isPriority("Growth VI"));
        assertFalse(SuperpairsValue.isPriority("Grand Experience Bottle"));
        assertFalse(SuperpairsValue.isPriority("150k Enchanting Exp"));
    }

    @Test
    public void romanLevelsAreRead() {
        assertEquals(7, SuperpairsValue.enchantLevel("Growth VII"));
        assertEquals(4, SuperpairsValue.enchantLevel("Sharpness IV"));
        assertEquals(9, SuperpairsValue.enchantLevel("Power IX"));
        assertEquals(6, SuperpairsValue.enchantLevel("Vampirism VI x2"));
        assertEquals(0, SuperpairsValue.enchantLevel("Titanic Experience Bottle"));
        assertEquals(0, SuperpairsValue.enchantLevel("34k Enchanting Exp"));
        assertEquals(0, SuperpairsValue.enchantLevel("Instant Find"));
    }

    @Test
    public void theOrderIsTitanicThenT7ThenOtherEnchantmentsThenBottlesThenXpByAmount() {
        String[] best = {"Titanic Experience Bottle", "Growth VII", "Growth VI", "Sharpness V", "Grand Experience Bottle",
            "Experience Bottle", "150k Enchanting Exp", "34k Enchanting Exp", "5k Enchanting Exp", "Gained +3 Clicks"};
        for (int index = 1; index < best.length; index++) {
            assertTrue(best[index - 1] + " > " + best[index],
                SuperpairsValue.value(best[index - 1]) > SuperpairsValue.value(best[index]));
        }
        assertTrue(SuperpairsValue.value("Growth VIII") > SuperpairsValue.value("Growth VII"));
        assertEquals("Plain and million amounts", SuperpairsValue.value("1.5m Enchanting Exp"), 1_500);
        assertEquals(SuperpairsValue.value("5,000 Enchanting Exp"), SuperpairsValue.value("5k Enchanting Exp"));
    }

    @Test
    public void everyKindOfAFullBoardLooksDifferentWithAndWithoutColor() {
        Set<String> withColor = new HashSet<>();
        Set<String> letters = new HashSet<>();
        for (int kind = 0; kind < 14; kind++) {
            withColor.add(PairStyle.color(kind) + PairStyle.letter(kind));
            letters.add(PairStyle.letter(kind));
        }
        assertEquals(14, withColor.size());
        assertEquals("Letters alone tell all kinds apart", 14, letters.size());
        Set<Integer> colors = new HashSet<>();
        for (int kind = 0; kind < 14; kind++) {
            colors.add(PairStyle.color(kind));
        }
        assertEquals("The second round of colors is darker, so colors alone stay apart too", 14, colors.size());
        assertNotEquals(PairStyle.color(0), PairStyle.color(7));
    }

    @Test
    public void textStaysReadableOnEveryColor() {
        for (int kind = 0; kind < 14; kind++) {
            int color = PairStyle.color(kind);
            double luminance = 0.299 * (color >> 16 & 0xFF) + 0.587 * (color >> 8 & 0xFF) + 0.114 * (color & 0xFF);
            assertEquals(luminance > 140 ? 0xFF000000 : 0xFFFFFFFF, PairStyle.textColor(kind));
            assertEquals("Opaque", 0xFF, color >>> 24);
        }
    }
}
