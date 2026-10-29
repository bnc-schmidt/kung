package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import java.util.function.BiPredicate;
import org.junit.Test;

public final class BasketRowTest {
    /** Soil along z = 0 for every x in [from, to), nothing anywhere else. */
    private static BiPredicate<Integer, Integer> soil(int from, int to) {
        return (x, z) -> z == 0 && x >= from && x < to;
    }

    @Test
    public void aRowCountsTheSoilFromTheStartToItsEnd() {
        assertEquals(10, BasketRow.length(-100, 0, 1, 0, soil(-100, -90)));
        assertEquals(4, BasketRow.length(-100, 0, 1, 0, soil(-100, -96)));
    }

    @Test
    public void aRowStartingOnAnythingButSoilIsEmpty() {
        assertEquals(0, BasketRow.length(0, 0, 1, 0, soil(5, 20)));
    }

    @Test
    public void anObstacleEndsTheRowAndNothingBehindItCounts() {
        BiPredicate<Integer, Integer> blocked = (x, z) -> z == 0 && x >= -100 && x < -50 && x != -95;
        assertEquals(5, BasketRow.length(-100, 0, 1, 0, blocked));
    }

    @Test
    public void aRowWalksInAnyDirection() {
        BiPredicate<Integer, Integer> column = (x, z) -> x == -100 && z <= -80 && z > -90;
        assertEquals(10, BasketRow.length(-100, -80, 0, -1, column));
        BiPredicate<Integer, Integer> west = (x, z) -> z == -80 && x <= -100 && x > -105;
        assertEquals(5, BasketRow.length(-100, -80, -1, 0, west));
    }

    @Test
    public void aRowStopsAtThePlotEdge() {
        // Plot edges lie at -240, -144, -48, 48, 144, 240: a row from -100 east ends at -48.
        assertEquals(52, BasketRow.length(-100, 0, 1, 0, soil(-300, 300)));
        // Walking west the edge is at -144, the last block of the plot is -144.
        assertEquals(45, BasketRow.length(-100, 0, -1, 0, soil(-300, 300)));
    }

    @Test
    public void aRowFromThePlotCornerIsAtMostTheWholePlot() {
        assertEquals(96, BasketRow.length(-144, 0, 1, 0, soil(-300, 300)));
        assertEquals(96, BasketRow.length(47, 0, -1, 0, soil(-300, 300)));
    }

    @Test
    public void outsideTheGardenARowIsOnlyCappedAtNinetySix() {
        assertEquals(96, BasketRow.length(300, 0, 1, 0, soil(0, 1000)));
        assertEquals(96, BasketRow.length(-500, 0, 1, 0, soil(-1000, 1000)));
    }

    @Test
    public void aRowLeavingTheGardenStopsAtItsBorder() {
        assertEquals(10, BasketRow.length(230, 0, 1, 0, soil(0, 1000)));
    }

    @Test
    public void theBasketsPlantOnTheirOwnSoil() {
        assertTrue(HeldStorage.BASKET_OF_SEEDS.plantsOn("minecraft:farmland"));
        assertFalse(HeldStorage.BASKET_OF_SEEDS.plantsOn("minecraft:soul_sand"));
        assertTrue(HeldStorage.NETHER_WART_BASKET.plantsOn("minecraft:soul_sand"));
        assertFalse(HeldStorage.NETHER_WART_BASKET.plantsOn("minecraft:farmland"));
        assertFalse(HeldStorage.BUILDERS_RULER.plantsRows());
        assertTrue(HeldStorage.BASKET_OF_SEEDS.plantsRows());
        assertTrue(HeldStorage.NETHER_WART_BASKET.plantsRows());
    }
}
