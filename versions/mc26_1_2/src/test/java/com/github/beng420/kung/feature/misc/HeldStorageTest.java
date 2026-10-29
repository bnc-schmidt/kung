package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import org.junit.Test;

public final class HeldStorageTest {
    @Test
    public void theSkyblockIdPicksTheStorage() {
        assertEquals(HeldStorage.BUILDERS_RULER, HeldStorage.of("BUILDERS_RULER"));
        assertEquals(HeldStorage.BASKET_OF_SEEDS, HeldStorage.of("BASKET_OF_SEEDS"));
        // The Nether Wart Basket still carries its old name in the id.
        assertEquals(HeldStorage.NETHER_WART_BASKET, HeldStorage.of("NETHER_WART_POUCH"));
        assertNull(HeldStorage.of("NETHER_WART_BASKET"));
        assertNull(HeldStorage.of(""));
        assertNull(HeldStorage.of("INFINIDIRT_WAND"));
    }

    @Test
    public void aScreenIsTheStorageByItsKnownTitleOrTheHeldItemsName() {
        assertTrue(HeldStorage.BUILDERS_RULER.opens("Builder's Ruler", "Builder's Ruler"));
        assertTrue(HeldStorage.BUILDERS_RULER.opens("§aBuilder’s Ruler", "x"));
        assertTrue(HeldStorage.NETHER_WART_BASKET.opens("Nether Wart Pouch", "Nether Wart Pouch"));
        assertTrue(HeldStorage.NETHER_WART_BASKET.opens("Nether Wart Basket", "Something Else"));
        assertFalse(HeldStorage.BASKET_OF_SEEDS.opens("SkyBlock Menu", "Basket of Seeds"));
        assertFalse(HeldStorage.BASKET_OF_SEEDS.opens("", ""));
    }
}
