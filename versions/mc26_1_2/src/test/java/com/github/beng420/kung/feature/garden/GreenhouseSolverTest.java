package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSolver.Goal;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.GoalResult;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveRequest;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.Test;

public final class GreenhouseSolverTest {
    private static final int SIZE = GreenhouseGrid.SIZE;
    private static final double EPS = 1e-9;
    /** Iteration cap that ends every run here long before the time budget, so results repeat exactly. */
    private static final long ITERATIONS = 120_000;
    /** Enough annealing rounds to reach the proven optimum of the one-ingredient cases reliably. */
    private static final long LONG_ITERATIONS = 600_000;
    private static final long BUDGET = 20_000;
    private static final long SEED = 7;

    private static SolveResult solve(SolveRequest request) {
        return GreenhouseSolver.solve(request, BUDGET, SEED, ITERATIONS, () -> false);
    }

    private static SolveResult solveLong(SolveRequest request) {
        return GreenhouseSolver.solve(request, BUDGET, SEED, LONG_ITERATIONS, () -> false);
    }

    private static SolveResult solve(boolean[] unlocked, Goal... goals) {
        return solve(new SolveRequest(List.of(goals), unlocked));
    }

    private static boolean[] region(int row, int column, int rows, int columns) {
        boolean[] open = new boolean[SIZE * SIZE];
        for (int r = row; r < row + rows; r++) {
            for (int c = column; c < column + columns; c++) open[r * SIZE + c] = true;
        }
        return open;
    }

    private static GoalResult goal(SolveResult result, String mutation) {
        return result.goals().stream().filter(g -> g.mutation().equals(mutation)).findFirst().orElseThrow();
    }

    // ----- independent evaluator: written from the rules, sharing nothing with the solver -----

    private static final List<GreenhouseMutations.Mutation> SUPPORTED = GreenhouseMutations.all().stream()
        .filter(GreenhouseMutations.Mutation::supported).toList();

    /** Expected count and number of candidate sites (sites whose ring meets the recipe) per mutation. */
    private record Outcome(Map<String, Double> expected, Map<String, Integer> sites, int shared) { }

    private static Map<String, Double> reference(String[] cropAt, boolean[] unlocked) {
        return outcome(cropAt, unlocked).expected();
    }

    /** Counts for crops at {@code cropAt} (null = no crop); a cell without one is empty. */
    private static Outcome outcome(String[] cropAt, boolean[] unlocked) {
        Map<String, Double> out = new HashMap<>();
        Map<String, Integer> candidates = new HashMap<>();
        int[] shared = new int[1];
        boolean[] claimed = new boolean[SIZE * SIZE];
        for (int pass = 0; pass < 2; pass++) {
            for (int row = 0; row < SIZE; row++) {
                for (int column = 0; column < SIZE; column++) {
                    for (int[] shape : new int[][] {{2, 2}, {3, 3}, {1, 1}}) {
                        // Sized footprints first, so a cell they take is not also a 1x1 site.
                        if ((shape[0] == 1) != (pass == 1)) continue;
                        site(cropAt, unlocked, claimed, out, candidates, shared, row, column, shape[0], shape[1]);
                    }
                }
            }
        }
        return new Outcome(out, candidates, shared[0]);
    }

    private static void site(String[] cropAt, boolean[] unlocked, boolean[] claimed, Map<String, Double> out,
                             Map<String, Integer> candidates, int[] shared, int row, int column, int width,
                             int height) {
        if (row + height > SIZE || column + width > SIZE) return;
        for (int r = row; r < row + height; r++) {
            for (int c = column; c < column + width; c++) {
                int p = r * SIZE + c;
                if (!unlocked[p] || cropAt[p] != null || claimed[p]) return;
            }
        }
        Map<String, Integer> ring = new HashMap<>();
        int ringCrops = 0;
        for (int r = row - 1; r <= row + height; r++) {
            for (int c = column - 1; c <= column + width; c++) {
                boolean inFootprint = r >= row && r < row + height && c >= column && c < column + width;
                if (inFootprint || r < 0 || c < 0 || r >= SIZE || c >= SIZE) continue;
                String crop = unlocked[r * SIZE + c] ? cropAt[r * SIZE + c] : null;
                if (crop != null) {
                    ring.merge(crop, 1, Integer::sum);
                    ringCrops++;
                }
            }
        }
        List<GreenhouseMutations.Mutation> hits = new ArrayList<>();
        for (GreenhouseMutations.Mutation mutation : SUPPORTED) {
            if (mutation.width() != width || mutation.height() != height) continue;
            boolean met = mutation.emptyRing() ? ringCrops == 0 : true;
            for (Map.Entry<String, Integer> need : mutation.needs().entrySet()) {
                if (ring.getOrDefault(need.getKey(), 0) < need.getValue()) met = false;
            }
            if (met) hits.add(mutation);
        }
        if (hits.isEmpty()) return;
        if (hits.size() > 1) shared[0]++;
        double chances = hits.stream().mapToInt(GreenhouseMutations.Mutation::chancePct).sum();
        for (GreenhouseMutations.Mutation hit : hits) {
            out.merge(hit.name(), hit.chancePct() / chances, Double::sum);
            candidates.merge(hit.name(), 1, Integer::sum);
        }
        if (width * height > 1) {
            for (int r = row; r < row + height; r++) {
                for (int c = column; c < column + width; c++) claimed[r * SIZE + c] = true;
            }
        }
    }

    private static Outcome outcome(GreenhouseGrid grid, boolean[] unlocked) {
        String[] cropAt = new String[SIZE * SIZE];
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null && cell.flag() == GreenhouseGrid.INGREDIENT) cropAt[row * SIZE + column] = cell.name();
            }
        }
        return outcome(cropAt, unlocked);
    }

    private static Map<String, Double> reference(GreenhouseGrid grid, boolean[] unlocked) {
        return outcome(grid, unlocked).expected();
    }

    // ----- tests -----

    @Test
    public void matchesTheExhaustiveOptimumOnSmallRegions() {
        for (boolean[] open : List.of(region(3, 3, 4, 4), region(2, 1, 3, 5), region(0, 0, 2, 7))) {
            int[] cells = IntStream.range(0, SIZE * SIZE).filter(p -> open[p]).toArray();
            double best = 0;
            String[] cropAt = new String[SIZE * SIZE];
            for (int bits = 0; bits < 1 << cells.length; bits++) {
                for (int i = 0; i < cells.length; i++) cropAt[cells[i]] = (bits >> i & 1) != 0 ? "Wheat Seeds" : null;
                best = Math.max(best, reference(cropAt, open).getOrDefault("Dustgrain", 0.0));
            }

            SolveResult result = solve(open, Goal.maximize("Dustgrain"));

            assertEquals(best, goal(result, "Dustgrain").achieved(), EPS);
            assertTrue(best > 0);
        }
    }

    @Test
    public void fixedQuantityIsMetExactlyAndTheRestGoesToTheMaximizeGoal() {
        SolveResult result = solve(null, Goal.fixed("Cindershade", 3), Goal.maximize("Dustgrain"));

        assertEquals(3.0, goal(result, "Cindershade").achieved(), EPS);
        assertEquals(3, goal(result, "Cindershade").sites());
        assertEquals("", goal(result, "Cindershade").note());
        assertTrue(goal(result, "Dustgrain").achieved() >= 50);
        assertEquals(3, result.targets().stream().filter(t -> t.mutation().equals("Cindershade")).count());
        assertTrue(result.soils().containsAll(List.of("Soul Sand", "Farmland")));
        assertTrue(result.ingredients().containsKey("Wheat Seeds"));
        assertTrue(result.ingredients().containsKey("Ashwreath"));
        assertEquals(0, result.ambiguousTargets());
    }

    @Test
    public void anUnreachableQuantityReturnsTheBestLayoutAndSaysSo() {
        SolveResult unreachable = solve(null, Goal.fixed("Dustgrain", 1000));
        SolveResult maximize = solve(null, Goal.maximize("Dustgrain"));

        GoalResult goal = goal(unreachable, "Dustgrain");
        assertEquals(1000, goal.requested());
        assertTrue(goal.achieved() > 40);
        assertEquals(goal(maximize, "Dustgrain").achieved(), goal.achieved(), EPS);
        assertEquals(maximize.grid(), unreachable.grid());
        assertTrue(goal.note(), goal.note().startsWith("Best layout found has "));
        assertTrue(goal.note().endsWith(" of 1000 requested"));
    }

    @Test
    public void fixedGoalsOutrankTheMaximizeGoalExactly() {
        // 3x4 plot, two ingredients: wheat feeds Dustgrain, cocoa feeds Choconut, and a cell with two of each
        // is a coin flip between them. Compare with the exhaustive lexicographic optimum for two caps: no
        // excess Choconut sites, then the most Choconut up to the cap, then the most Dustgrain.
        boolean[] open = region(4, 3, 3, 4);
        int[] cells = IntStream.range(0, SIZE * SIZE).filter(p -> open[p]).toArray();
        int[] caps = {1000, 2};
        double[][] best = new double[caps.length][];
        String[] cropAt = new String[SIZE * SIZE];
        int states = 1;
        for (int i = 0; i < cells.length; i++) states *= 3;
        for (int state = 0; state < states; state++) {
            int rest = state;
            for (int cell : cells) {
                cropAt[cell] = new String[] {null, "Wheat Seeds", "Cocoa Beans"}[rest % 3];
                rest /= 3;
            }
            Outcome counts = outcome(cropAt, open);
            double dustgrain = counts.expected().getOrDefault("Dustgrain", 0.0);
            for (int c = 0; c < caps.length; c++) {
                int excess = Math.max(0, counts.sites().getOrDefault("Choconut", 0) - caps[c]);
                double[] value = {-excess, Math.min(caps[c], counts.expected().getOrDefault("Choconut", 0.0)), dustgrain};
                if (best[c] == null || lexicographicallyBetter(value, best[c])) best[c] = value;
            }
        }

        for (int c = 0; c < caps.length; c++) {
            SolveResult result = solve(open, Goal.fixed("Choconut", caps[c]), Goal.maximize("Dustgrain"));

            GoalResult choconut = goal(result, "Choconut");
            assertEquals("cap " + caps[c], best[c][0], -Math.max(0, choconut.sites() - caps[c]), EPS);
            assertEquals("cap " + caps[c], best[c][1], Math.min(caps[c], choconut.achieved()), EPS);
            assertEquals("cap " + caps[c], best[c][2], goal(result, "Dustgrain").achieved(), EPS);
        }
    }

    private static boolean lexicographicallyBetter(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > EPS) return a[i] > b[i];
        }
        return false;
    }

    @Test
    public void aFixedGoalIsOptimalUnderItsSiteLimit() {
        // Dustgrain on a 3x5 plot with limits 1, 2, 3 and 5 sites: the exhaustive best expected count among
        // layouts with at most that many Dustgrain sites, which is also what the solver must report.
        boolean[] open = region(2, 2, 3, 5);
        int[] cells = IntStream.range(0, SIZE * SIZE).filter(p -> open[p]).toArray();
        int[] limits = {1, 2, 3, 5};
        double[] best = new double[limits.length];
        String[] cropAt = new String[SIZE * SIZE];
        for (int bits = 0; bits < 1 << cells.length; bits++) {
            for (int i = 0; i < cells.length; i++) cropAt[cells[i]] = (bits >> i & 1) != 0 ? "Wheat Seeds" : null;
            Outcome counts = outcome(cropAt, open);
            int sites = counts.sites().getOrDefault("Dustgrain", 0);
            for (int l = 0; l < limits.length; l++) {
                if (sites <= limits[l]) best[l] = Math.max(best[l], counts.expected().getOrDefault("Dustgrain", 0.0));
            }
        }

        for (int l = 0; l < limits.length; l++) {
            GoalResult result = goal(solve(open, Goal.fixed("Dustgrain", limits[l])), "Dustgrain");

            assertTrue("limit " + limits[l], result.sites() <= limits[l]);
            assertEquals("limit " + limits[l], best[l], result.achieved(), EPS);
        }
    }

    @Test
    public void aFixedGoalNeverGetsMoreCandidateSitesThanRequested() {
        // Glasscorn's own ingredients cannot create Zombud sites, but the leftover space is tempting: Zombud
        // fixed at 2 used to end with four or five sites (2.27 expected) because sites are shared with Witherbloom.
        for (long seed = 1; seed <= 4; seed++) {
            SolveResult result = GreenhouseSolver.solve(
                SolveRequest.of(Goal.fixed("Zombud", 2), Goal.maximize("Glasscorn")), BUDGET, seed, ITERATIONS,
                () -> false);

            GoalResult zombud = goal(result, "Zombud");
            assertEquals("seed " + seed, 2, zombud.sites());
            assertEquals("seed " + seed, 2 * 25.0 / 55.0, zombud.achieved(), EPS);
            assertTrue(zombud.note(), zombud.note().startsWith("Capped at 2 candidate sites"));
            assertTrue("seed " + seed, goal(result, "Glasscorn").achieved() >= 6);
            assertEquals(zombud.sites(), (int) outcome(result.grid(), region(0, 0, 10, 10)).sites().get("Zombud"));
        }
    }

    @Test
    public void aMaximizeGoalThatContainsAFixedRecipeIsHeldToTheLimit() {
        // Every Zombud site is also a Witherbloom site (four Dead Plants), so with Witherbloom fixed at 2 the
        // plan spends both on plain Witherbloom sites and leaves no room for Zombud.
        SolveResult result = solve(null, Goal.fixed("Witherbloom", 2), Goal.maximize("Zombud"));

        assertEquals(2, goal(result, "Witherbloom").sites());
        assertEquals(2.0, goal(result, "Witherbloom").achieved(), EPS);
        assertEquals(0, goal(result, "Zombud").sites());
        assertEquals("", goal(result, "Witherbloom").note());
    }

    @Test
    public void aFixedGoalOfZeroForbidsItsSites() {
        SolveResult result = solve(null, Goal.fixed("Zombud", 0), Goal.maximize("Witherbloom"));

        assertEquals(0, goal(result, "Zombud").sites());
        assertTrue(goal(result, "Witherbloom").achieved() >= 40);
    }

    @Test
    public void aSharedRecipeOfALowerGoalCapsTheHigherOne() {
        // Each Zombud site is also a Witherbloom site. With Witherbloom fixed at 1, a second Zombud site would be
        // an excess site, which outranks everything, so the plan keeps one site that both goals count.
        SolveResult result = solve(null, Goal.fixed("Zombud", 2), Goal.fixed("Witherbloom", 1));

        assertEquals(1, goal(result, "Zombud").sites());
        assertEquals(1, goal(result, "Witherbloom").sites());
        assertEquals(25.0 / 55.0, goal(result, "Zombud").achieved(), EPS);
        assertTrue(goal(result, "Zombud").note(), goal(result, "Zombud").note().startsWith("Best layout found has 0.45 of 2"));
    }

    @Test
    public void aFixedLonelilyIsLimitedWithFillerCrops() {
        SolveResult result = solve(region(0, 0, 4, 4), Goal.fixed("Lonelily", 3));

        assertEquals(3, goal(result, "Lonelily").sites());
        assertEquals(3.0, goal(result, "Lonelily").achieved(), EPS);
        assertEquals(List.of("Wheat Seeds"), List.copyOf(result.ingredients().keySet()));
    }

    @Test
    public void sameSeedGivesTheSameResult() {
        SolveRequest request = SolveRequest.of(Goal.fixed("Snoozling", 2), Goal.fixed("Cindershade", 2),
            Goal.maximize("Dustgrain"));

        SolveResult first = solve(request);
        SolveResult second = solve(request);

        assertEquals(first.grid(), second.grid());
        assertEquals(first.goals(), second.goals());
        assertEquals(first.targets(), second.targets());
        assertEquals(first.ingredients(), second.ingredients());
    }

    @Test
    public void lockedCellsStayEmptyAndNeverCountAsCrops() {
        boolean[] open = new boolean[SIZE * SIZE];
        Arrays.fill(open, true);
        // The diagonal is locked, which still leaves 5x5 blocks (a footprint and its ring) above and below it.
        for (int p = 0; p < open.length; p++) if (p / SIZE == p % SIZE) open[p] = false;

        SolveResult result = solve(open, Goal.fixed("Snoozling", 1), Goal.maximize("Dustgrain"));

        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                if (!open[row * SIZE + column]) assertNull(row + "," + column, result.grid().get(row, column));
            }
        }
        assertEquals(1.0, goal(result, "Snoozling").achieved(), EPS);
        Map<String, Double> counted = reference(result.grid(), open);
        assertEquals(counted.get("Dustgrain"), goal(result, "Dustgrain").achieved(), EPS);
    }

    @Test
    public void unsupportedAndUnknownGoalsGetAStatusInsteadOfAnException() {
        SolveResult result = solve(null, Goal.maximize("Godseed"), Goal.maximize("Nope"), Goal.fixed("Dustgrain", 4));

        GoalResult godseed = goal(result, "Godseed");
        assertFalse(godseed.supported());
        assertEquals(0, godseed.achieved(), EPS);
        assertTrue(godseed.note(), godseed.note().startsWith("Unsupported"));
        assertFalse(goal(result, "Nope").supported());
        assertEquals("Unknown mutation", goal(result, "Nope").note());
        assertEquals(4.0, goal(result, "Dustgrain").achieved(), EPS);

        SolveResult onlyUnsupported = solve(null, Goal.maximize("Shellfruit"));
        assertEquals(0, onlyUnsupported.grid().filled());
        assertTrue(onlyUnsupported.notes().toString(), onlyUnsupported.notes().contains("No supported goal to plan"));
        assertTrue(solve(new SolveRequest(List.of(), null)).notes().contains("No goals given"));
    }

    @Test
    public void theGridRoundTripsThroughTheLinkAndRescoresToTheReportedCounts() {
        SolveRequest request = SolveRequest.of(Goal.fixed("Cindershade", 3), Goal.fixed("Snoozling", 1),
            Goal.maximize("Choconut"));

        SolveResult result = solve(request);

        GreenhouseGrid decoded = GreenhouseLayoutCodec.decode(GreenhouseLayoutCodec.encode(result.grid())).orElseThrow();
        assertEquals(result.grid(), decoded);
        boolean[] open = request.unlocked();
        Map<String, Double> counted = reference(decoded, open);
        for (GoalResult goal : result.goals()) {
            assertEquals(goal.mutation(), counted.getOrDefault(goal.mutation(), 0.0), goal.achieved(), EPS);
        }
        // Every flagged target cell belongs to a reported target, and no crop sits on one.
        int targetCells = 0;
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                GreenhouseGrid.Cell cell = decoded.get(row, column);
                if (cell != null && cell.flag() == GreenhouseGrid.TARGET) targetCells++;
            }
        }
        assertEquals(result.targets().stream().mapToInt(t -> t.width() * t.height()).sum(), targetCells);
        assertEquals(result.ingredients().values().stream().mapToInt(Integer::intValue).sum() + targetCells,
            decoded.filled());
    }

    @Test
    public void sizedMutationsGetWholeFootprintsAndTheirFullRing() {
        SolveResult result = solve(null, Goal.maximize("Snoozling"));

        assertEquals(4.0, goal(result, "Snoozling").achieved(), EPS);
        assertEquals(4, result.targets().size());
        for (GreenhouseSolver.Target target : result.targets()) {
            assertEquals(3, target.width());
            assertEquals(3, target.height());
            for (int dr = 0; dr < 3; dr++) {
                for (int dc = 0; dc < 3; dc++) {
                    GreenhouseGrid.Cell cell = result.grid().get(target.row() + dr, target.column() + dc);
                    assertEquals(new GreenhouseGrid.Cell("Snoozling", GreenhouseGrid.TARGET), cell);
                }
            }
        }
        assertEquals(4 * 9, result.grid().filled() - result.ingredients().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of("Farmland"), result.soils());
    }

    @Test
    public void aSharedSiteSplitsByTheSpawnChancesOfItsCandidates() {
        // Zombud's ring (4 Dead Plants, 2 Fleshtrap, 2 Cindershade) also meets Witherbloom's four Dead Plants.
        // Zombud spawns at 25, Witherbloom at 30: the site is worth 25/55 and 30/55, not one half each.
        SolveResult result = solve(region(0, 0, 3, 3), Goal.maximize("Zombud"));

        assertEquals(25.0 / 55.0, goal(result, "Zombud").achieved(), EPS);
        assertEquals(1, result.ambiguousTargets());
        assertEquals(2, result.targets().get(0).candidates());
        assertEquals(30.0 / 55.0, reference(result.grid(), region(0, 0, 3, 3)).get("Witherbloom"), EPS);
    }


    @Test
    public void randomRequestsRescoreToTheReportedCountsAndRespectTheMask() {
        java.util.Random random = new java.util.Random(42);
        for (int round = 0; round < 12; round++) {
            List<Goal> goals = new ArrayList<>();
            boolean maximizing = false;
            for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) {
                String name = SUPPORTED.get(random.nextInt(SUPPORTED.size())).name();
                boolean maximize = !maximizing && random.nextInt(3) == 0;
                maximizing |= maximize;
                goals.add(maximize ? Goal.maximize(name) : Goal.fixed(name, 1 + random.nextInt(6)));
            }
            boolean[] open = new boolean[SIZE * SIZE];
            double locked = round % 3 == 0 ? 0 : random.nextDouble() * 0.35;
            for (int p = 0; p < open.length; p++) open[p] = random.nextDouble() >= locked;

            SolveResult result = GreenhouseSolver.solve(new SolveRequest(goals, open), BUDGET, round, 15_000, () -> false);

            Outcome counted = outcome(result.grid(), open);
            for (GoalResult goal : result.goals()) {
                assertEquals(goals.toString(), counted.expected().getOrDefault(goal.mutation(), 0.0), goal.achieved(), EPS);
                if (goal.supported()) {
                    assertEquals(goals.toString(), (int) counted.sites().getOrDefault(goal.mutation(), 0), goal.sites());
                }
            }
            for (int p = 0; p < open.length; p++) {
                if (!open[p]) assertNull(result.grid().get(p / SIZE, p % SIZE));
            }
        }
    }

    @Test
    public void aPriorityPenaltyPicksTheOtherInterchangeableIngredient() {
        // Both maximize goals share a tier, so wheat (Dustgrain) and cocoa (Choconut) layouts tie on counts.
        List<Goal> goals = List.of(Goal.maximize("Dustgrain"), Goal.maximize("Choconut"));
        double plain = total(solveLong(new SolveRequest(goals, null)));

        SolveResult noCocoa = solveLong(new SolveRequest(goals, null).withPriorities(Map.of("Cocoa Beans", 100)));
        SolveResult noWheat = solveLong(new SolveRequest(goals, null).withPriorities(Map.of("wheat seeds", 100)));

        assertEquals(plain, total(noCocoa), EPS);
        assertEquals(plain, total(noWheat), EPS);
        assertEquals(72.0, plain, EPS);
        assertFalse(noCocoa.ingredients().containsKey("Cocoa Beans"));
        assertTrue(noCocoa.ingredients().containsKey("Wheat Seeds"));
        assertFalse(noWheat.ingredients().containsKey("Wheat Seeds"));
        assertTrue(noWheat.ingredients().containsKey("Cocoa Beans"));
    }

    @Test
    public void prioritiesNeverChangeWhatTheGoalsAchieve() {
        Map<String, Integer> everything = new HashMap<>();
        for (String crop : GreenhouseCrops.NAMES) everything.put(crop, 100);

        // Proven optima: 72 (exact DP), 16 (interior packing) and 4 (footprint packing).
        assertEquals(72.0, goal(solveLong(SolveRequest.of(Goal.maximize("Dustgrain")).withPriorities(everything)),
            "Dustgrain").achieved(), EPS);
        assertEquals(16.0, goal(solveLong(SolveRequest.of(Goal.maximize("Cindershade"))
            .withPriorities(Map.of("Ashwreath", 100, "Witherbloom", 3))), "Cindershade").achieved(), EPS);
        assertEquals(4.0, goal(solveLong(SolveRequest.of(Goal.maximize("Snoozling")).withPriorities(everything)),
            "Snoozling").achieved(), EPS);

        // A fixed goal stays exact, and the maximize goal below it stays in its usual 55-57 range.
        SolveRequest mixed = SolveRequest.of(Goal.fixed("Cindershade", 3), Goal.maximize("Dustgrain"));
        SolveResult plain = solveLong(mixed);
        SolveResult penalized = solveLong(mixed.withPriorities(Map.of("Wheat Seeds", 100, "Ashwreath", 60)));
        assertEquals(3.0, goal(penalized, "Cindershade").achieved(), EPS);
        assertTrue(goal(penalized, "Dustgrain").achieved() >= 54);
        assertTrue(goal(plain, "Dustgrain").achieved() >= 54);
    }

    @Test
    public void unknownPriorityCropsAreIgnoredWithANote() {
        SolveRequest request = SolveRequest.of(Goal.fixed("Dustgrain", 4))
            .withPriorities(Map.of("Not a crop", 5, "wheat seeds", 20, "Carrot", 7, "Wheat Seeds ", 20));

        SolveResult result = solve(request);

        assertEquals(4.0, goal(result, "Dustgrain").achieved(), EPS);
        assertEquals(List.of("Unknown crop in priorities, ignored: Not a crop"), result.notes());
    }

    @Test
    public void outOfRangePrioritiesAreClampedWithANote() {
        SolveResult result = solve(SolveRequest.of(Goal.fixed("Dustgrain", 4))
            .withPriorities(Map.of("Wheat Seeds", 900)));

        assertEquals(List.of("Priority for Wheat Seeds limited to 100"), result.notes());
        assertEquals(4.0, goal(result, "Dustgrain").achieved(), EPS);
    }

    @Test
    public void solveAsyncCompletesOnAnotherThread() throws Exception {
        CompletableFuture<SolveResult> future = GreenhouseSolver.solveAsync(
            SolveRequest.of(Goal.fixed("Dustgrain", 6)), 300, 1, () -> false);

        SolveResult result = future.get(10, TimeUnit.SECONDS);

        assertEquals(6.0, goal(result, "Dustgrain").achieved(), EPS);
        assertEquals(6, goal(result, "Dustgrain").sites());
        assertTrue(GreenhouseSolver.solveAsync(SolveRequest.of(Goal.maximize("Dustgrain")), 60_000, 1, () -> true)
            .get(10, TimeUnit.SECONDS).notes().stream()
            .anyMatch(n -> n.startsWith("Search stopped early")));
    }

    @Test
    public void anErrorOnTheWorkerCompletesTheFutureInsteadOfLeavingItPending() throws Exception {
        CompletableFuture<SolveResult> future = GreenhouseSolver.solveAsync(
            SolveRequest.of(Goal.fixed("Dustgrain", 6)), 300, 1, () -> { throw new StackOverflowError(); });

        try {
            future.get(10, TimeUnit.SECONDS);
            throw new AssertionError("expected the failure to reach the future");
        } catch (ExecutionException failure) {
            assertTrue(failure.getCause() instanceof StackOverflowError);
        }
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    public void theWorkerIsADaemonThatYieldsToTheRenderThread() throws Exception {
        AtomicInteger priority = new AtomicInteger();
        AtomicBoolean daemon = new AtomicBoolean();

        GreenhouseSolver.solveAsync(SolveRequest.of(Goal.fixed("Dustgrain", 6)), 300, 1, () -> {
            priority.set(Thread.currentThread().getPriority());
            daemon.set(Thread.currentThread().isDaemon());
            return true;
        }).get(10, TimeUnit.SECONDS);

        assertEquals(Thread.MIN_PRIORITY, priority.get());
        assertTrue(daemon.get());
    }

    @Test
    public void failureTextFallsBackToTheClassWhenThereIsNoMessage() {
        assertEquals("StackOverflowError", GreenhouseSolver.describe(new StackOverflowError()));
        assertEquals("boom", GreenhouseSolver.describe(new IllegalStateException("boom")));
        assertEquals("OutOfMemoryError", GreenhouseSolver.describe(new CompletionException(new OutOfMemoryError(" "))));
        assertEquals("IllegalStateException", GreenhouseSolver.describe(new IllegalStateException()));
    }

    private static double total(SolveResult result) {
        return result.goals().stream().mapToDouble(GoalResult::achieved).sum();
    }

    /** Moves a fixed step per reading, so runs against it are deterministic and cost no real waiting. */
    private static final class FakeClock implements LongSupplier {
        private final long stepNanos;
        private long now;

        FakeClock(long stepNanos) { this.stepNanos = stepNanos; }

        @Override public long getAsLong() { return now += stepNanos; }

        long now() { return now; }
    }

    private static GreenhouseSolver.Options stagnating(LongSupplier clock, long maxIterations,
                                                       GreenhouseSearch.Observer observer) {
        return new GreenhouseSolver.Options(maxIterations, () -> false, clock, true, observer);
    }

    @Test
    public void aRunThatStoppedImprovingEndsEarlyWithANote() {
        // One clock reading is 1 ms and a reading comes every 128 iterations, so a 600 s budget is far away.
        // Cindershade (16 targets) is found by every fresh restart, which is what the rule waits for.
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.maximize("Cindershade")), 600_000, SEED,
            stagnating(new FakeClock(1_000_000), Long.MAX_VALUE, null));

        assertTrue(result.stoppedEarly());
        assertTrue(result.notes().toString(), result.notes().stream().anyMatch(n -> n.startsWith("Stopped early: no improvement for ")));
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() >= GreenhouseSearch.MIN_RUNTIME_NANOS / 1_000_000);
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() < 300_000);
        assertEquals(16.0, goal(result, "Cindershade").achieved(), EPS);
    }

    @Test
    public void aRunWhoseRestartsDisagreeKeepsGoingUntilTheBudget() {
        // The mixed case's last improvement is a rare find; restarts rarely agree, so the rule must not stop it.
        long budgetMillis = 12_000;
        SolveResult result = GreenhouseSolver.solve(
            SolveRequest.of(Goal.fixed("Cindershade", 3), Goal.maximize("Dustgrain")), budgetMillis, SEED,
            stagnating(new FakeClock(1_000_000), Long.MAX_VALUE, null));

        assertFalse(result.stoppedEarly());
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() >= budgetMillis * 9 / 10);
    }

    @Test
    public void stagnationStillFiresWhenCostsAreOn() {
        // Cost and priority points are tie-breakers: a cheaper typing must neither reset the idle clock nor keep
        // the restarts from agreeing on the plan. With the price in the plan this run never stopped (99 s of fake
        // time, 13 s of real time, no stop); without it, it stops at about 15 s of fake time.
        SolveRequest request = SolveRequest.of(Goal.maximize("Cindershade"))
            .withCosts(Map.of("Ashwreath", 500.0, "Witherbloom", 100.0))
            .withPriorities(Map.of("Ashwreath", 40));

        SolveResult result = GreenhouseSolver.solve(request, 100_000, SEED,
            stagnating(new FakeClock(1_000_000), Long.MAX_VALUE, null));

        assertTrue(result.stoppedEarly());
        assertTrue(result.notes().toString(), result.notes().stream().anyMatch(n -> n.startsWith("Stopped early: no improvement for ")));
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() >= GreenhouseSearch.MIN_RUNTIME_NANOS / 1_000_000);
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() < 50_000);
        assertEquals(16, goal(result, "Cindershade").sites());
        assertTrue(result.totalCost() > 0);
    }

    /** Every crop gets a price, so the exact re-typing has real work around all the sites of six goals. */
    private static SolveRequest pricedSixGoals() {
        Map<String, Double> costs = new HashMap<>();
        for (String crop : GreenhouseCrops.NAMES) costs.put(crop, 10.0 + Math.abs(crop.hashCode() % 500));
        return new SolveRequest(List.of(Goal.fixed("Cindershade", 2), Goal.fixed("Zombud", 2), Goal.fixed("Snoozling", 1),
            Goal.fixed("Coalroot", 3), Goal.fixed("Dustgrain", 5), Goal.maximize("Glasscorn")), null).withCosts(costs);
    }

    @Test
    public void stopReturnsPromptlyEvenWhileTheExactTypingIsBusy() throws Exception {
        // Exact re-typing used to run to its node limit after the flag was set (seconds with six goals).
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<SolveResult> future = GreenhouseSolver.solveAsync(pricedSixGoals(), 60_000, 1, stop::get);

        Thread.sleep(1_500);
        long stopped = System.nanoTime();
        stop.set(true);
        SolveResult result = future.get(20, TimeUnit.SECONDS);

        long latencyMillis = (System.nanoTime() - stopped) / 1_000_000;
        assertTrue("stop latency " + latencyMillis + " ms", latencyMillis < 800);
        assertTrue(result.notes().toString(), result.notes().stream().anyMatch(n -> n.startsWith("Search stopped early")));
        assertNotNull(result.grid());
    }

    @Test
    public void aRunEndsNearItsBudgetEvenWhenTheExactTypingIsBusy() {
        long started = System.nanoTime();
        GreenhouseSolver.solve(pricedSixGoals(), 2_000, 1);

        long overshootMillis = (System.nanoTime() - started) / 1_000_000 - 2_000;
        assertTrue("overshoot " + overshootMillis + " ms", overshootMillis < 300);
    }

    @Test
    public void stagnationNeverFiresBeforeTheMinimumRuntime() {
        // A run of 400000 iterations on this clock lasts a few milliseconds, however long it has been idle.
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.maximize("Dustgrain")), 600_000, SEED,
            stagnating(new FakeClock(10_000), 400_000, null));

        assertFalse(result.stoppedEarly());
        assertTrue(result.notes().toString(), result.notes().stream().noneMatch(n -> n.startsWith("Stopped early")));
        assertTrue(goal(result, "Dustgrain").achieved() > 60);
    }

    @Test
    public void theHardBudgetStillCapsARunThatMayStopOnStagnation() {
        // The budget is shorter than the minimum runtime, so only the deadline can end this run.
        long budgetMillis = GreenhouseSearch.MIN_RUNTIME_NANOS / 1_000_000 / 2;
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.maximize("Dustgrain")), budgetMillis, SEED,
            stagnating(new FakeClock(1_000_000), Long.MAX_VALUE, null));

        assertFalse(result.stoppedEarly());
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() <= budgetMillis);
        assertTrue("elapsed " + result.elapsedMillis(), result.elapsedMillis() >= budgetMillis * 9 / 10);
    }

    @Test
    public void everyPublicOverloadRunsTheWholeBudgetByDefault() {
        assertFalse(GreenhouseSolver.Options.standard(Long.MAX_VALUE, () -> false, null).stopOnStagnation());
        assertFalse(GreenhouseSolver.Options.standard(ITERATIONS, () -> false, null).stopOnStagnation());
    }

    @Test
    public void anIterationCapRunDoesNotStopEarlyEither() {
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.maximize("Dustgrain")), 600_000, SEED,
            ITERATIONS, () -> false);

        assertFalse(result.stoppedEarly());
    }

    @Test
    public void improvementsAreReportedStrictlyAndInOrder() {
        List<Long> times = new ArrayList<>();
        List<long[]> keys = new ArrayList<>();
        List<Long> boundaries = new ArrayList<>();
        GreenhouseSearch.Observer observer = new GreenhouseSearch.Observer() {
            @Override public void improved(long elapsedNanos, long[] key) {
                times.add(elapsedNanos);
                keys.add(key);
            }

            @Override public void roundEnded(long elapsedNanos, boolean fresh, long[] roundKey) { boundaries.add(elapsedNanos); }
        };

        GreenhouseSolver.solve(SolveRequest.of(Goal.fixed("Cindershade", 3), Goal.maximize("Dustgrain")), 600_000, SEED,
            new GreenhouseSolver.Options(ITERATIONS, () -> false, new FakeClock(1_000_000), false, observer));

        assertFalse(keys.isEmpty());
        assertFalse(boundaries.isEmpty());
        for (int i = 1; i < keys.size(); i++) {
            assertTrue(times.get(i) >= times.get(i - 1));
            int at = 0;
            while (at < keys.get(i).length && keys.get(i)[at] == keys.get(i - 1)[at]) at++;
            assertTrue("improvement " + i + " is not strictly better", at < keys.get(i).length && keys.get(i)[at] > keys.get(i - 1)[at]);
        }
        for (int i = 1; i < boundaries.size(); i++) assertTrue(boundaries.get(i) > boundaries.get(i - 1));
    }

    // ----- costs -----

    @Test
    public void theCheaperOfTwoEquallyGoodLayoutsWinsExhaustively() {
        // 3x4 plot, wheat (cost 1) and cocoa (cost 5) feed two maximize goals in one tier. Among the layouts with
        // the best expected total, then the fewest shared sites, the solver must take the cheapest.
        boolean[] open = region(4, 3, 3, 4);
        int[] cells = IntStream.range(0, SIZE * SIZE).filter(p -> open[p]).toArray();
        double[] best = null;
        String[] cropAt = new String[SIZE * SIZE];
        int states = 1;
        for (int i = 0; i < cells.length; i++) states *= 3;
        for (int state = 0; state < states; state++) {
            int rest = state;
            double cost = 0;
            for (int cell : cells) {
                int kind = rest % 3;
                rest /= 3;
                cropAt[cell] = new String[] {null, "Wheat Seeds", "Cocoa Beans"}[kind];
                cost += new double[] {0, 1, 5}[kind];
            }
            Outcome counts = outcome(cropAt, open);
            double[] value = {counts.expected().getOrDefault("Dustgrain", 0.0) + counts.expected().getOrDefault("Choconut", 0.0),
                -counts.shared(), -cost};
            if (best == null || lexicographicallyBetter(value, best)) best = value;
        }

        SolveResult result = solve(new SolveRequest(List.of(Goal.maximize("Dustgrain"), Goal.maximize("Choconut")), open)
            .withCosts(Map.of("Wheat Seeds", 1.0, "Cocoa Beans", 5.0)));

        assertEquals(best[0], total(result), EPS);
        assertEquals(-best[1], result.ambiguousTargets(), EPS);
        assertEquals(-best[2], result.totalCost(), EPS);
    }

    @Test
    public void costsNeverLowerWhatTheGoalsAchieve() {
        // Proven optima again, with a cost on every crop the goals use.
        assertEquals(72.0, goal(solveLong(SolveRequest.of(Goal.maximize("Dustgrain"))
            .withCosts(Map.of("Wheat Seeds", 1e9))), "Dustgrain").achieved(), EPS);
        assertEquals(16.0, goal(solveLong(SolveRequest.of(Goal.maximize("Cindershade"))
            .withCosts(Map.of("Ashwreath", 5000.0, "Witherbloom", 1.0))), "Cindershade").achieved(), EPS);
        assertEquals(4.0, goal(solveLong(SolveRequest.of(Goal.maximize("Snoozling"))
            .withCosts(Map.of("Creambloom", 1e6, "Dustgrain", 7.0, "Thornshade", 300.0))), "Snoozling").achieved(), EPS);

        SolveRequest mixed = SolveRequest.of(Goal.fixed("Cindershade", 3), Goal.maximize("Dustgrain"))
            .withCosts(Map.of("Wheat Seeds", 50.0, "Ashwreath", 100.0));
        SolveResult result = solveLong(mixed);
        assertEquals(3.0, goal(result, "Cindershade").achieved(), EPS);
        assertEquals(3, goal(result, "Cindershade").sites());
        assertTrue(goal(result, "Dustgrain").achieved() >= 54);
    }

    @Test
    public void anExpensiveIngredientIsSharedBetweenRings() {
        // A 5x5 plot holds four Zombud sites at most. Fleshtrap (1000 each) can be shared between neighbouring
        // rings, Dead Plants (free) cannot be saved on, so the plan should use few Fleshtrap cells, each next to
        // two or more sites, instead of eight single-use ones.
        boolean[] open = region(2, 2, 5, 5);
        SolveRequest request = new SolveRequest(List.of(Goal.maximize("Zombud")), open)
            .withCosts(Map.of("Fleshtrap", 1000.0, "Cindershade", 300.0, "Dead Plants", 0.0));

        SolveResult result = solveLong(request);

        assertEquals(4, goal(result, "Zombud").sites());
        int fleshtraps = result.ingredients().get("Fleshtrap");
        assertTrue("fleshtraps " + fleshtraps, fleshtraps <= 4);
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                GreenhouseGrid.Cell cell = result.grid().get(row, column);
                if (cell == null || !cell.name().equals("Fleshtrap")) continue;
                int fed = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        GreenhouseGrid.Cell near = GreenhouseGrid.inside(row + dr, column + dc)
                            ? result.grid().get(row + dr, column + dc) : null;
                        if (near != null && near.flag() == GreenhouseGrid.TARGET) fed++;
                    }
                }
                assertTrue("fleshtrap at " + row + "," + column + " feeds " + fed, fed >= 2);
            }
        }
        // And it is cheaper than the same plot planned without costs, which counts crops only.
        SolveResult plain = solveLong(new SolveRequest(List.of(Goal.maximize("Zombud")), open));
        double plainCost = plain.ingredients().getOrDefault("Fleshtrap", 0) * 1000.0
            + plain.ingredients().getOrDefault("Cindershade", 0) * 300.0;
        assertTrue(result.totalCost() + " vs " + plainCost, result.totalCost() <= plainCost);
        assertEquals(fleshtraps * 1000.0 + result.ingredients().get("Cindershade") * 300.0, result.totalCost(), EPS);
    }

    @Test
    public void theResultReportsTheUnitCostsAndTheirTotal() {
        SolveResult result = solve(SolveRequest.of(Goal.fixed("Cindershade", 2))
            .withCosts(Map.of("ashwreath", 12.5, "Witherbloom", 0.25)));

        double total = 0;
        for (Map.Entry<String, Integer> entry : result.ingredients().entrySet()) {
            total += entry.getValue() * result.unitCosts().get(entry.getKey());
        }
        assertEquals(12.5, result.unitCosts().get("Ashwreath"), EPS);
        assertEquals(0.25, result.unitCosts().get("Witherbloom"), EPS);
        assertEquals(total, result.totalCost(), EPS);
        assertTrue(result.totalCost() > 0);
        assertEquals(0.0, solve(SolveRequest.of(Goal.fixed("Cindershade", 2))).totalCost(), EPS);
    }

    @Test
    public void unknownAndOutOfRangeCostsAreReportedAndClamped() {
        SolveResult result = solve(SolveRequest.of(Goal.fixed("Dustgrain", 4)).withCosts(
            Map.of("Not a crop", 5.0, "Wheat Seeds", -3.0, "Carrot", 9.0)));

        assertEquals(4.0, goal(result, "Dustgrain").achieved(), EPS);
        assertTrue(result.notes().toString(), result.notes().contains("Unknown crop in costs, ignored: Not a crop"));
        assertTrue(result.notes().toString(), result.notes().contains("Cost for Wheat Seeds limited to 0"));
        assertEquals(0.0, result.unitCosts().get("Wheat Seeds"), EPS);

        SolveResult huge = solve(SolveRequest.of(Goal.fixed("Dustgrain", 4)).withCosts(
            Map.of("Wheat Seeds", Double.POSITIVE_INFINITY)));
        assertEquals(GreenhouseSolver.COST_CAP, huge.unitCosts().get("Wheat Seeds"), 1.0);
        assertEquals(4.0, goal(huge, "Dustgrain").achieved(), EPS);
        SolveResult nan = solve(SolveRequest.of(Goal.fixed("Dustgrain", 4)).withCosts(Map.of("Wheat Seeds", Double.NaN)));
        assertEquals(0.0, nan.unitCosts().get("Wheat Seeds"), EPS);
    }

    @Test
    public void aLatticeOfSitesIsTypedAtItsProvenCheapestCost() {
        // 7x7 plot: nine Zombud sites fit only on the one lattice, whose ring union is 40 cells. An exhaustive
        // branch and bound (outside this suite) proves 8 Fleshtrap + 8 Cindershade, 10400, the cheapest typing.
        boolean[] open = region(0, 0, 7, 7);
        Map<String, Double> prices = Map.of("Fleshtrap", 1000.0, "Cindershade", 300.0, "Dead Plants", 0.0);

        SolveResult result = solveLong(new SolveRequest(List.of(Goal.maximize("Zombud")), open).withCosts(prices));

        assertEquals(9, goal(result, "Zombud").sites());
        assertEquals(40, result.ingredients().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(10400.0, result.totalCost(), EPS);
        assertEquals(8, (int) result.ingredients().get("Fleshtrap"));
        assertEquals(8, (int) result.ingredients().get("Cindershade"));
    }

    @Test
    public void theFullPlotKeepsItsProvenZombudSiteCropAndCostFigures() {
        // 16 sites is the packing maximum on 10x10 and 65 crops the smallest ring union among the 281571
        // arrangements of 16 (checked by enumeration outside the suite). On the aligned lattice the cheapest
        // typing at Fleshtrap 1000 / Cindershade 300 / Dead Plants 0 costs 13900 (proven there).
        SolveRequest plain = SolveRequest.of(Goal.maximize("Zombud"));
        SolveResult unpriced = solveLong(plain);
        assertEquals(16, goal(unpriced, "Zombud").sites());
        assertEquals(65, unpriced.ingredients().values().stream().mapToInt(Integer::intValue).sum());

        SolveResult priced = solveLong(plain.withCosts(
            Map.of("Fleshtrap", 1000.0, "Cindershade", 300.0, "Dead Plants", 0.0)));
        assertEquals(16, goal(priced, "Zombud").sites());
        assertTrue("cost " + priced.totalCost(), priced.totalCost() <= 13900.0 + EPS);
        assertEquals(65, priced.ingredients().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    public void retypingNeverReturnsAnAssignmentThatIsNotBetterThanTheCurrentOne() {
        // The 7x7 Zombud lattice typed at its proven cheapest cost (10400), plus two Fleshtrap far from every ring.
        // Those two are not re-typed, so their price must not be counted as room to improve: nothing is better.
        String[] rows = {"DDDDDDD", "D.F.F.D", "FCCDCFC", "D.D.D.D", "DDFDFDD", "D.D.D.D", "FCCDCFC"};
        byte[] layout = new byte[SIZE * SIZE];
        Arrays.fill(layout, GreenhouseSearch.EMPTY);
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < 7; column++) {
                char letter = rows[row].charAt(column);
                if (letter != '.') layout[row * SIZE + column] = (byte) "DFC".indexOf(letter);
            }
        }
        layout[8 * SIZE + 8] = 1;
        layout[9 * SIZE + 9] = 1;
        GreenhouseSearch.Recipe zombud = new GreenhouseSearch.Recipe(1, 1, new int[] {0, 1, 2}, new int[] {4, 2, 2}, false, 25);
        boolean[] open = new boolean[SIZE * SIZE];
        Arrays.fill(open, true);
        GreenhouseSearch search = new GreenhouseSearch(open, 3, new GreenhouseSearch.Recipe[] {zombud},
            new GreenhouseSearch.Aim[] {new GreenhouseSearch.Aim(0, Long.MAX_VALUE, 0)}, new int[3],
            new long[] {0, 1_000_000, 300_000}, 1);

        assertNull(search.retype(layout, 300_000, Long.MAX_VALUE));

        // Make Dead Plants the dear crop instead: the same lattice now has a cheaper typing with fewer of them.
        GreenhouseSearch repriced = new GreenhouseSearch(open, 3, new GreenhouseSearch.Recipe[] {zombud},
            new GreenhouseSearch.Aim[] {new GreenhouseSearch.Aim(0, Long.MAX_VALUE, 0)}, new int[3],
            new long[] {1_000_000, 10_000, 10_000}, 1);
        byte[] cheaper = repriced.retype(layout, 2_000_000, Long.MAX_VALUE);
        assertNotNull(cheaper);
        int before = 0;
        int after = 0;
        for (int p = 0; p < layout.length; p++) {
            if (layout[p] == 0) before++;
            if (cheaper[p] == 0) after++;
        }
        assertTrue(after + " Dead Plants instead of " + before, after < before);
    }

    // ----- live preview -----

    /** Collected previews with the fake time each arrived at. */
    private static final class Previews {
        final List<SolveResult> results = new ArrayList<>();
        final List<Long> arrivals = new ArrayList<>();
    }

    private static Previews preview(FakeClock clock, SolveRequest request, long budgetMillis, long maxIterations) {
        Previews previews = new Previews();
        SolveResult last = GreenhouseSolver.solve(request, budgetMillis, SEED, new GreenhouseSolver.Options(
            maxIterations, () -> false, clock, false, null, snapshot -> {
                previews.results.add(snapshot);
                previews.arrivals.add(clock.now());
            }));
        previews.results.add(last);
        previews.arrivals.add(-1L);
        return previews;
    }

    @Test
    public void previewsArriveInOrderThrottledAndEndWithTheFinalPlan() {
        // One reading is 1 ms, so a quarter second is 250 readings.
        FakeClock clock = new FakeClock(1_000_000);
        Previews previews = preview(clock, SolveRequest.of(Goal.maximize("Cindershade")), 8_000, Long.MAX_VALUE);

        int n = previews.results.size() - 1;
        assertTrue("previews " + n, n >= 2);
        double previous = -1;
        for (int i = 0; i < n; i++) {
            SolveResult snapshot = previews.results.get(i);
            assertNotNull(snapshot.grid());
            assertEquals(1, snapshot.goals().size());
            double achieved = goal(snapshot, "Cindershade").achieved();
            assertTrue("a preview never shows a worse plan", achieved >= previous - EPS);
            previous = achieved;
            if (i > 0) {
                assertTrue(snapshot.elapsedMillis() >= previews.results.get(i - 1).elapsedMillis());
            }
        }
        for (int i = 1; i < n - 1; i++) {
            long gap = previews.arrivals.get(i) - previews.arrivals.get(i - 1);
            assertTrue("gap " + gap, gap >= GreenhouseSearch.PREVIEW_INTERVAL_NANOS);
        }
        // The last improvement is always delivered, and it is the plan the run ends with.
        assertEquals(previews.results.get(n).grid(), previews.results.get(n - 1).grid());
        assertEquals(previews.results.get(n).ingredients(), previews.results.get(n - 1).ingredients());
        assertEquals(16.0, goal(previews.results.get(n - 1), "Cindershade").achieved(), EPS);
    }

    @Test
    public void aShortRunStillDeliversItsFinalImprovement() {
        // Under a quarter second there is one throttled slot, yet the end result must still arrive.
        FakeClock clock = new FakeClock(100_000);
        Previews previews = preview(clock, SolveRequest.of(Goal.maximize("Dustgrain")), 600_000, 60_000);

        int n = previews.results.size() - 1;
        assertTrue("previews " + n, n >= 1);
        assertEquals(previews.results.get(n).grid(), previews.results.get(n - 1).grid());
    }

    @Test
    public void aFailingPreviewListenerDoesNotEndTheRun() {
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.fixed("Dustgrain", 6)), 600_000, SEED,
            new GreenhouseSolver.Options(ITERATIONS, () -> false, new FakeClock(1_000_000), false, null, snapshot -> {
                throw new IllegalStateException("listener failure");
            }));

        assertEquals(6.0, goal(result, "Dustgrain").achieved(), EPS);
    }

    @Test
    public void previewsCarryTheCostAndIngredientsOfTheirPlan() {
        FakeClock clock = new FakeClock(1_000_000);
        SolveRequest request = SolveRequest.of(Goal.maximize("Cindershade")).withCosts(Map.of("Ashwreath", 10.0));
        Previews previews = preview(clock, request, 3_000, Long.MAX_VALUE);

        for (SolveResult snapshot : previews.results) {
            int ashwreath = snapshot.ingredients().getOrDefault("Ashwreath", 0);
            assertEquals(ashwreath * 10.0, snapshot.totalCost(), EPS);
        }
    }

    @Test
    public void solveAsyncDeliversPreviewsOnTheWorkerThread() throws Exception {
        List<SolveResult> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> threads = java.util.Collections.synchronizedList(new ArrayList<>());

        SolveResult result = GreenhouseSolver.solveAsync(SolveRequest.of(Goal.fixed("Dustgrain", 6)), 300, 1,
            () -> false, snapshot -> {
                seen.add(snapshot);
                threads.add(Thread.currentThread().getName());
            }).get(10, TimeUnit.SECONDS);

        assertFalse(seen.isEmpty());
        assertEquals(List.of("Kung greenhouse solver"), List.copyOf(new java.util.LinkedHashSet<>(threads)));
        assertEquals(result.grid(), seen.get(seen.size() - 1).grid());
        assertFalse(result.stoppedEarly());
    }

    @Test
    public void cancellationReturnsTheBestLayoutSoFarQuickly() {
        long started = System.nanoTime();
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.maximize("Dustgrain")), 60_000, 1,
            Long.MAX_VALUE, () -> true);

        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000);
        assertNotNull(result.grid());
        assertTrue(result.notes().toString(), result.notes().stream().anyMatch(n -> n.startsWith("Search stopped early")));
        assertFalse(result.stoppedEarly());
    }

    @Test
    public void theTimeBudgetIsHonoured() {
        long started = System.nanoTime();
        SolveResult result = GreenhouseSolver.solve(SolveRequest.of(Goal.maximize("Dustgrain")), 300, 1);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertTrue("elapsed " + elapsed, elapsed < 900);
        assertTrue(goal(result, "Dustgrain").achieved() > 30);
    }

    @Test
    public void lonelilyNeedsNoIngredientsAtAll() {
        SolveResult result = solve(region(0, 0, 4, 4), Goal.maximize("Lonelily"));

        assertEquals(16.0, goal(result, "Lonelily").achieved(), EPS);
        assertTrue(result.ingredients().isEmpty());
        assertEquals(16, result.targets().size());
        assertTrue(result.notes().toString(), result.notes().stream().anyMatch(n -> n.startsWith("Lonelily needs")));
    }
}
