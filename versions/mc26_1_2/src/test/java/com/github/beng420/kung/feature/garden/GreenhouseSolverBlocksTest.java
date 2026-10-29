package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSolver.Goal;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveRequest;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveResult;
import org.junit.Test;

/** A mutation planted as an ingredient (Snoozling 3x3, Noctilume 2x2) takes its whole footprint. */
public final class GreenhouseSolverBlocksTest {
    private static final long BUDGET = 30_000;
    private static final long SEED = 7;
    private static final long ITERATIONS = 600_000;

    private static SolveResult solve(Goal goal) {
        return GreenhouseSolver.solve(SolveRequest.of(goal), BUDGET, SEED, ITERATIONS, () -> false);
    }

    /** The ingredient cells named {@code crop} tile into squares of {@code side}, the way the scan groups them. */
    private static boolean wholeBlocks(GreenhouseGrid grid, String crop, int side) {
        boolean[][] taken = new boolean[GreenhouseGrid.SIZE][GreenhouseGrid.SIZE];
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell == null || cell.flag() == GreenhouseGrid.TARGET || !cell.name().equals(crop) || taken[row][column]) {
                    continue;
                }
                for (int dr = 0; dr < side; dr++) {
                    for (int dc = 0; dc < side; dc++) {
                        int r = row + dr;
                        int c = column + dc;
                        if (!GreenhouseGrid.inside(r, c) || taken[r][c]) return false;
                        GreenhouseGrid.Cell part = grid.get(r, c);
                        if (part == null || part.flag() == GreenhouseGrid.TARGET || !part.name().equals(crop)) return false;
                        taken[r][c] = true;
                    }
                }
            }
        }
        return true;
    }

    private static int cells(GreenhouseGrid grid, String crop) {
        int count = 0;
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null && cell.flag() != GreenhouseGrid.TARGET && cell.name().equals(crop)) count++;
            }
        }
        return count;
    }

    @Test
    public void plantBoyAdvanceGetsWholeSnoozlingBlocksNotSingleCells() {
        SolveResult result = solve(Goal.fixed("PlantBoy Advance", 1));

        assertEquals(1.0, result.goals().getFirst().achieved(), 1e-9);
        int snoozling = cells(result.grid(), "Snoozling");
        assertTrue("six ring cells of Snoozling need whole 3x3 blocks, got " + snoozling + " cells", snoozling >= 9);
        assertTrue("Snoozling comes in 3x3 blocks", wholeBlocks(result.grid(), "Snoozling", 3));
        assertTrue("Thunderling is a single cell", wholeBlocks(result.grid(), "Thunderling", 1));
        assertEquals("the shopping list counts plants, not cells", snoozling / 9,
            (int) result.ingredients().get("Snoozling"));
    }

    @Test
    public void aMutationThatIsItselfBlockSizedCanBeTheIngredientToo() {
        // All-in Aloe needs two ring cells of PlantBoy Advance, a 2x2 mutation.
        SolveResult result = solve(Goal.fixed("All-in Aloe", 1));

        assertEquals(1.0, result.goals().getFirst().achieved(), 1e-9);
        assertTrue("PlantBoy Advance comes in 2x2 blocks", wholeBlocks(result.grid(), "PlantBoy Advance", 2));
        assertTrue(cells(result.grid(), "PlantBoy Advance") >= 4);
    }

    @Test
    public void stoplightPetalGetsWholeNoctilumeAndSnoozlingBlocks() {
        SolveResult result = solve(Goal.fixed("Stoplight Petal", 1));

        assertEquals(1.0, result.goals().getFirst().achieved(), 1e-9);
        assertTrue("Noctilume comes in 2x2 blocks", wholeBlocks(result.grid(), "Noctilume", 2));
        assertTrue("Snoozling comes in 3x3 blocks", wholeBlocks(result.grid(), "Snoozling", 3));
        assertEquals(cells(result.grid(), "Noctilume") / 4, (int) result.ingredients().get("Noctilume"));
    }
}
