package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class GreenhouseGridTest {
    private static GreenhouseGrid sample() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        grid.set(0, 9, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.TARGET));
        grid.set(2, 3, new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS));
        return grid;
    }

    @Test
    public void rotatingRightMovesTheTopLeftToTheTopRight() {
        GreenhouseGrid rotated = sample().rotatedRight();

        assertEquals("Wheat Seeds", rotated.get(0, 9).name());
        assertEquals("Carrot", rotated.get(9, 9).name());
        assertEquals("Sunflower", rotated.get(3, 7).name());
        assertEquals(3, rotated.filled());
        assertNull(rotated.get(0, 0));
    }

    @Test
    public void rotatingLeftMovesTheTopLeftToTheBottomLeft() {
        GreenhouseGrid rotated = sample().rotatedLeft();

        assertEquals("Wheat Seeds", rotated.get(9, 0).name());
        assertEquals("Carrot", rotated.get(0, 0).name());
        assertEquals("Sunflower", rotated.get(6, 2).name());
    }

    @Test
    public void rotationsAreInverseAndFourTurnsAreIdentity() {
        GreenhouseGrid grid = sample();

        assertEquals(grid, grid.rotatedRight().rotatedLeft());
        assertEquals(grid, grid.rotatedRight().rotatedRight().rotatedRight().rotatedRight());
        assertEquals(grid.rotatedRight().rotatedRight(), grid.rotatedLeft().rotatedLeft());
    }

    @Test
    public void mirrorFlipsLeftAndRightAndKeepsFlags() {
        GreenhouseGrid grid = sample();
        GreenhouseGrid mirrored = grid.mirrored();

        assertEquals(new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT), mirrored.get(0, 9));
        assertEquals(new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.TARGET), mirrored.get(0, 0));
        assertEquals(new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS), mirrored.get(2, 6));
        assertEquals(grid, mirrored.mirrored());
    }

    @Test
    public void transformsLeaveTheirSourceUntouched() {
        GreenhouseGrid grid = sample();
        GreenhouseGrid copy = sample();

        assertNotSame(grid, grid.rotatedRight());
        grid.rotatedLeft();
        grid.mirrored();
        grid.cleared();
        assertEquals(copy, grid);
    }

    @Test
    public void clearedIsEmpty() {
        assertEquals(0, sample().cleared().filled());
        assertEquals(new GreenhouseGrid(), sample().cleared());
    }

    @Test
    public void cellsTakeTheSitesSpellingOfKnownCrops() {
        assertEquals("Do-not-eat-shroom", new GreenhouseGrid.Cell("do not  eat shroom", 1).name());
        assertEquals("All-in Aloe", new GreenhouseGrid.Cell("ALL IN ALOE", 1).name());
        assertEquals("Mystery Crop", new GreenhouseGrid.Cell("Mystery Crop", 1).name());
    }

    @Test
    public void cropSearchIgnoresCaseAndSeparators() {
        assertEquals(GreenhouseCrops.NAMES.size(), GreenhouseCrops.search("").size());
        assertEquals(List.of("Wheat Seeds"), GreenhouseCrops.search("WHEAT"));
        assertEquals(List.of("Do-not-eat-shroom"), GreenhouseCrops.search("not eat"));
        assertEquals(List.of(), GreenhouseCrops.search("zzz"));
        assertEquals(List.of("Jerryseed", "Fertilized Jerryseed"), GreenhouseCrops.search("jerryseed"));
    }

    @Test
    public void knownCropListHasSixtyFiveDistinctNames() {
        assertEquals(65, GreenhouseCrops.NAMES.size());
        assertEquals(65, GreenhouseCrops.NAMES.stream().map(GreenhouseCrops::normalize).distinct().count());
    }

    @Test
    public void abbreviationNeverThrowsAndSeparatorOnlyNamesGetAPlaceholder() {
        String[] names = {"-", "_", "--", "---", "- -", " _ ", "", " ", "\t", "a", "-a", "a-", "-a-b-", "\u00c4pfel",
            "\ud83c\udf3e\ud83c\udf3e\ud83c\udf3e\ud83c\udf3e", "\u4f5c\u7269 \u8c46", "x".repeat(10_000),
            "x-".repeat(5_000), "-".repeat(10_000)};
        for (String name : names) {
            String abbreviation = GreenhouseCrops.abbreviation(name);
            assertTrue("at most three characters for [" + name.length() + "]", abbreviation.codePointCount(0,
                abbreviation.length()) <= 3);
        }
        assertEquals("?", GreenhouseCrops.abbreviation("-"));
        assertEquals("?", GreenhouseCrops.abbreviation("_"));
        assertEquals("?", GreenhouseCrops.abbreviation("---"));
        assertEquals("?", GreenhouseCrops.abbreviation(" - "));
        assertEquals("", GreenhouseCrops.abbreviation(""));
        assertEquals("A", GreenhouseCrops.abbreviation("-a"));
        assertEquals("\u00c4pf", GreenhouseCrops.abbreviation("\u00c4pfel"));
        // A surrogate pair is kept whole.
        assertEquals("\ud83c\udf3e\ud83c\udf3e\ud83c\udf3e", GreenhouseCrops.abbreviation("\ud83c\udf3e".repeat(4)));
    }

    @Test
    public void abbreviationsFitACell() {
        assertEquals("WS", GreenhouseCrops.abbreviation("Wheat Seeds"));
        assertEquals("DNE", GreenhouseCrops.abbreviation("Do-not-eat-shroom"));
        assertEquals("Car", GreenhouseCrops.abbreviation("Carrot"));
        assertEquals("Fi", GreenhouseCrops.abbreviation("Fi"));
        assertEquals("", GreenhouseCrops.abbreviation(""));
    }
}
