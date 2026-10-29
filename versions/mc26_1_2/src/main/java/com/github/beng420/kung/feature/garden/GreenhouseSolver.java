package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Plans a 10x10 greenhouse layout for a list of mutation goals. Pure Java, no game classes, so it runs on any
 * thread; see {@link GreenhouseSearch} for the model and the search.
 *
 * <p>Fixed-quantity goals outrank maximize goals and, among themselves, earlier outranks later. A fixed goal is
 * a ceiling as well as a target: the layout never has more <em>candidate sites</em> for it (empty footprints
 * whose ring meets its recipe, shared with other recipes or not) than requested, so it may fall short but
 * never overshoot. The solver never refuses: it returns the best layout it found and reports achieved against
 * requested.
 *
 * <p>Among layouts that are equal on all of that, the cheaper one wins (see {@link SolveRequest#costs()}),
 * then the one with fewer priority points, then the one with fewer crops.
 */
final class GreenhouseSolver {
    static final long DEFAULT_BUDGET_MILLIS = 1500;
    /** {@link Goal#quantity()} value that asks for as many as the plot allows. */
    static final int MAX = -1;
    /**
     * Costs are searched as whole multiples of 1/{@value} of a coin per cell: 100 cells at the cap of
     * {@link #COST_CAP} come to 1e17, far inside a long, so adding and removing a crop cancels exactly.
     */
    static final long COST_SCALE = 1000;
    /** Largest cost accepted for one planted cell. */
    static final double COST_CAP = 1e12;
    /** Cheapest base crop, used only to fence off the extra sites of a fixed Lonelily. */
    private static final String FILLER_CROP = "Wheat Seeds";
    /** Quantities above this are capped here; nothing fits that many anyway. */
    private static final int QUANTITY_LIMIT = 100_000;

    /** {@code quantity} is a count, or {@link #MAX} for "use the remaining space for this one". */
    record Goal(String mutation, int quantity) {
        static Goal fixed(String mutation, int quantity) { return new Goal(mutation, Math.max(0, quantity)); }
        static Goal maximize(String mutation) { return new Goal(mutation, MAX); }
        boolean maximize() { return quantity < 0; }
    }

    /**
     * {@code unlocked} is row-major, 100 entries; locked cells hold neither crops nor targets (null: all open).
     * {@code priorities} maps a crop name (loose matching) to 0..100, higher meaning "use this crop less".
     * {@code costs} maps a crop name to what one planted cell costs (0 when missing, limited to 0..1e12, with a
     * resolution of 0.001). Both are tie-breakers only: among layouts with the same goal counts and the same
     * ambiguity, the one with the lower total cost wins, then the one with fewer priority points (sum of
     * priority per planted crop), then the one with fewer crops. A cost never lowers a goal count.
     */
    record SolveRequest(List<Goal> goals, boolean[] unlocked, Map<String, Integer> priorities,
                        Map<String, Double> costs) {
        SolveRequest {
            goals = List.copyOf(goals);
            priorities = priorities == null ? Map.of() : Map.copyOf(priorities);
            costs = costs == null ? Map.of() : Map.copyOf(costs);
            if (unlocked == null) {
                unlocked = new boolean[GreenhouseSearch.CELLS];
                Arrays.fill(unlocked, true);
            } else if (unlocked.length != GreenhouseSearch.CELLS) {
                throw new IllegalArgumentException("unlocked needs " + GreenhouseSearch.CELLS + " entries");
            } else {
                unlocked = unlocked.clone();
            }
        }

        SolveRequest(List<Goal> goals, boolean[] unlocked, Map<String, Integer> priorities) {
            this(goals, unlocked, priorities, Map.of());
        }

        SolveRequest(List<Goal> goals, boolean[] unlocked) { this(goals, unlocked, Map.of(), Map.of()); }

        static SolveRequest of(Goal... goals) { return new SolveRequest(List.of(goals), null); }

        SolveRequest withPriorities(Map<String, Integer> priorities) {
            return new SolveRequest(goals, unlocked, priorities, costs);
        }

        SolveRequest withCosts(Map<String, Double> costs) {
            return new SolveRequest(goals, unlocked, priorities, costs);
        }
    }

    /**
     * {@code achieved} is the expected number in the layout (a site shared by several recipes gives each its
     * spawn chance divided by the chances of all of them); {@code sites} is the whole number of sites whose ring
     * meets this recipe, shared or not, which never exceeds {@code requested} unless that could not be avoided;
     * {@code requested} is {@link #MAX} for maximize.
     */
    record GoalResult(String mutation, int requested, double achieved, int sites, boolean supported, String note) { }

    /** One planned mutation: footprint anchor (top-left) and how many recipes compete for the site. */
    record Target(String mutation, int row, int column, int width, int height, int candidates) { }

    /**
     * {@code ingredients} is the shopping list (crop to cell count); {@code ambiguousTargets} counts target
     * sites that another mutation can also take; {@code unrequestedSites} counts empty sites that would grow
     * only mutations nobody asked for; {@code soils} lists the soil of every mutation the plan grows;
     * {@code stoppedEarly} is set when the search ended before its time budget because it had stopped improving
     * (only when that was asked for). {@code totalCost} is the sum over the shopping list of count times the unit
     * cost in {@code unitCosts}, which holds the cost used for each ingredient (0 when none was given).
     */
    record SolveResult(GreenhouseGrid grid, List<GoalResult> goals, List<Target> targets,
                       Map<String, Integer> ingredients, int ambiguousTargets, int unrequestedSites,
                       List<String> soils, long elapsedMillis, List<String> notes, boolean stoppedEarly,
                       double totalCost, Map<String, Double> unitCosts) {
        SolveResult(GreenhouseGrid grid, List<GoalResult> goals, List<Target> targets,
                    Map<String, Integer> ingredients, int ambiguousTargets, int unrequestedSites,
                    List<String> soils, long elapsedMillis, List<String> notes, boolean stoppedEarly) {
            this(grid, goals, targets, ingredients, ambiguousTargets, unrequestedSites, soils, elapsedMillis, notes,
                stoppedEarly, 0, Map.of());
        }
    }

    /**
     * Run settings behind the public overloads. {@code clock} reads nanoseconds (tests inject their own);
     * {@code stopOnStagnation} lets the search end early once it has stopped improving; {@code observer} is for
     * tuning and may be null; {@code onBest} (may be null) receives a complete result every time the best plan
     * improves, on the thread that runs the solve, at most once per quarter second plus a last one at the end.
     */
    record Options(long maxIterations, BooleanSupplier cancelled, LongSupplier clock, boolean stopOnStagnation,
                   GreenhouseSearch.Observer observer, Consumer<SolveResult> onBest) {
        Options(long maxIterations, BooleanSupplier cancelled, LongSupplier clock, boolean stopOnStagnation,
                GreenhouseSearch.Observer observer) {
            this(maxIterations, cancelled, clock, stopOnStagnation, observer, null);
        }

        /** The settings of the public overloads: real clock, no early stop. */
        static Options standard(long maxIterations, BooleanSupplier cancelled, Consumer<SolveResult> onBest) {
            return new Options(maxIterations, cancelled, System::nanoTime, false, null, onBest);
        }
    }

    private GreenhouseSolver() { }

    /** Text for a failed run: the message, else the exception class (an Error often has no message). */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** Time-budget run: uses the whole budget. */
    static SolveResult solve(SolveRequest request, long budgetMillis, long seed) {
        return solve(request, budgetMillis, seed, Long.MAX_VALUE, () -> false);
    }

    /**
     * {@link #solve(SolveRequest, long, long, long, BooleanSupplier)} on a daemon thread, for callers that must
     * not block (the render thread). Cancel by making {@code cancelled} return true.
     */
    static CompletableFuture<SolveResult> solveAsync(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled) {
        return solveAsync(request, budgetMillis, seed, cancelled, null, false);
    }

    /**
     * Like the four-argument form, and {@code onBest} receives a complete snapshot (grid, goals, ingredients,
     * cost) on the worker thread whenever the best plan improves: at most one call per quarter second, and the
     * last improvement is always delivered before the future completes.
     */
    static CompletableFuture<SolveResult> solveAsync(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled, Consumer<SolveResult> onBest) {
        return solveAsync(request, budgetMillis, seed, cancelled, onBest, false);
    }

    /** With {@code stopOnStagnation} the run may also end early once it has stopped improving (opt-in). */
    static CompletableFuture<SolveResult> solveAsync(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled, Consumer<SolveResult> onBest,
                                                     boolean stopOnStagnation) {
        CompletableFuture<SolveResult> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                result.complete(solve(request, budgetMillis, seed, new Options(Long.MAX_VALUE, cancelled,
                    System::nanoTime, stopOnStagnation, null, onBest)));
            } catch (Throwable failure) {
                // An Error (stack overflow, out of memory) would otherwise end the thread with no result and
                // leave the caller waiting for a future that never completes.
                result.completeExceptionally(failure);
            }
        }, "Kung greenhouse solver");
        worker.setDaemon(true);
        // The render thread runs alongside this for up to a minute; the search only needs spare cycles.
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return result;
    }

    /**
     * Runs the search until the time budget, {@code maxIterations} or {@code cancelled} ends it; the calling
     * thread's interrupt flag also stops it. The same request, seed and iteration cap (reached before the
     * budget) always give the same result; with only a time budget, machine speed decides how many annealing
     * rounds fit.
     */
    static SolveResult solve(SolveRequest request, long budgetMillis, long seed, long maxIterations,
                             BooleanSupplier cancelled) {
        return solve(request, budgetMillis, seed, Options.standard(maxIterations, cancelled, null));
    }

    static SolveResult solve(SolveRequest request, long budgetMillis, long seed, Options options) {
        BooleanSupplier cancelled = options.cancelled();
        long started = options.clock().getAsLong();
        long budgetNanos = Math.max(1, budgetMillis) * 1_000_000L;
        List<String> notes = new ArrayList<>();
        if (GreenhouseMutations.all().isEmpty()) {
            notes.add(GreenhouseMutations.loadError());
            List<GoalResult> unavailable = new ArrayList<>();
            for (Goal goal : request.goals()) {
                unavailable.add(new GoalResult(goal.mutation(), goal.quantity(), 0, 0, false,
                    "Mutation data unavailable"));
            }
            return bare(unavailable, notes, started, options);
        }

        // Unknown and unsupported goals are reported but never searched.
        GoalResult[] results = new GoalResult[request.goals().size()];
        List<GreenhouseMutations.Mutation> resolved = new ArrayList<>();
        List<Goal> included = new ArrayList<>();
        List<Integer> includedAt = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < results.length; i++) {
            Goal goal = request.goals().get(i);
            GreenhouseMutations.Mutation mutation = GreenhouseMutations.find(goal.mutation()).orElse(null);
            if (mutation == null) {
                results[i] = new GoalResult(goal.mutation(), goal.quantity(), 0, 0, false, "Unknown mutation");
            } else if (!mutation.supported()) {
                results[i] = new GoalResult(mutation.name(), goal.quantity(), 0, 0, false,
                    "Unsupported: " + mutation.unsupported());
            } else if (!seen.add(mutation.name())) {
                results[i] = new GoalResult(mutation.name(), goal.quantity(), 0, 0, true,
                    "Listed more than once; the first entry steers the layout");
            } else {
                resolved.add(mutation);
                included.add(goal);
                includedAt.add(i);
            }
        }
        if (included.isEmpty()) {
            notes.add(request.goals().isEmpty() ? "No goals given" : "No supported goal to plan");
            return bare(List.of(results), notes, started, options);
        }

        // Only crops some goal needs matter: any other crop can add candidates but never remove one.
        Map<String, Integer> cropIndex = new LinkedHashMap<>();
        for (GreenhouseMutations.Mutation mutation : resolved) {
            for (String crop : mutation.needs().keySet()) cropIndex.putIfAbsent(crop, cropIndex.size());
        }
        // A fixed Lonelily has to be limited by planting crops around the sites it should not get, so give it
        // a filler crop when no goal brings any.
        boolean fixedEmptyRing = false;
        for (int i = 0; i < resolved.size(); i++) {
            fixedEmptyRing |= resolved.get(i).emptyRing() && !included.get(i).maximize();
        }
        if (cropIndex.isEmpty() && fixedEmptyRing) cropIndex.put(FILLER_CROP, 0);
        // Recipes that can fire with those crops: every goal, plus any other recipe they fully cover.
        List<GreenhouseMutations.Mutation> possible = new ArrayList<>();
        for (GreenhouseMutations.Mutation mutation : GreenhouseMutations.all()) {
            if (!mutation.supported()) continue;
            boolean covered = !mutation.emptyRing() && cropIndex.keySet().containsAll(mutation.needs().keySet());
            if (resolved.contains(mutation) || covered) possible.add(mutation);
        }
        GreenhouseSearch.Recipe[] recipes = new GreenhouseSearch.Recipe[possible.size()];
        for (int r = 0; r < recipes.length; r++) {
            GreenhouseMutations.Mutation mutation = possible.get(r);
            int[] needed = new int[mutation.needs().size()];
            int[] counts = new int[needed.length];
            int i = 0;
            for (Map.Entry<String, Integer> need : mutation.needs().entrySet()) {
                needed[i] = cropIndex.get(need.getKey());
                counts[i++] = need.getValue();
            }
            recipes[r] = new GreenhouseSearch.Recipe(mutation.width(), mutation.height(), needed, counts,
                mutation.emptyRing(), mutation.chancePct());
        }

        // One tier per fixed goal in the order given, then every maximize goal together as the last tier.
        List<GreenhouseSearch.Aim> aims = new ArrayList<>();
        List<Integer> aimGoal = new ArrayList<>();
        int tier = 0;
        for (int i = 0; i < included.size(); i++) {
            if (included.get(i).maximize()) continue;
            long cap = (long) Math.min(included.get(i).quantity(), QUANTITY_LIMIT) * GreenhouseSearch.UNIT;
            aims.add(new GreenhouseSearch.Aim(possible.indexOf(resolved.get(i)), cap, tier++));
            aimGoal.add(i);
        }
        int maximizing = 0;
        for (int i = 0; i < included.size(); i++) {
            if (!included.get(i).maximize()) continue;
            aims.add(new GreenhouseSearch.Aim(possible.indexOf(resolved.get(i)), Long.MAX_VALUE, tier));
            aimGoal.add(i);
            maximizing++;
        }
        if (maximizing > 1) notes.add("Several maximize goals share the leftover space equally");

        // Priorities and costs: unknown names are reported and skipped; crops no goal needs cannot be planted.
        int[] cropPriority = new int[cropIndex.size()];
        for (Map.Entry<String, Integer> entry : new TreeMap<>(request.priorities()).entrySet()) {
            String crop = GreenhouseCrops.canonical(entry.getKey());
            if (!GreenhouseCrops.NAMES.contains(crop)) {
                notes.add("Unknown crop in priorities, ignored: " + entry.getKey());
                continue;
            }
            int value = Math.max(0, Math.min(100, entry.getValue()));
            if (value != entry.getValue()) notes.add("Priority for " + crop + " limited to " + value);
            Integer index = cropIndex.get(crop);
            if (index != null) cropPriority[index] = value;
        }
        // A mutation planted as an ingredient (Snoozling 3x3, Noctilume 2x2) takes its whole footprint, and each of
        // its cells counts in a ring, so the search places it as a block of that many cells.
        int[] blockSizes = new int[cropIndex.size()];
        for (Map.Entry<String, Integer> entry : cropIndex.entrySet()) {
            GreenhouseMutations.Mutation grown = GreenhouseMutations.find(entry.getKey()).orElse(null);
            blockSizes[entry.getValue()] = grown != null && grown.width() == grown.height() ? grown.width() : 1;
        }
        long[] cropCost = new long[cropIndex.size()];
        for (Map.Entry<String, Double> entry : new TreeMap<>(request.costs()).entrySet()) {
            String crop = GreenhouseCrops.canonical(entry.getKey());
            if (!GreenhouseCrops.NAMES.contains(crop)) {
                notes.add("Unknown crop in costs, ignored: " + entry.getKey());
                continue;
            }
            double given = entry.getValue();
            double value = Double.isNaN(given) ? 0 : Math.max(0, Math.min(COST_CAP, given));
            if (value != given) notes.add("Cost for " + crop + " limited to " + quantity(value));
            Integer index = cropIndex.get(crop);
            // The given cost is one planted item; a block's cells share it.
            if (index != null) cropCost[index] = Math.round(value * COST_SCALE / (blockSizes[index] * blockSizes[index]));
        }

        Plan plan = new Plan(results, resolved, included, includedAt, possible, aims, aimGoal,
            new ArrayList<>(cropIndex.keySet()), cropCost, blockSizes, List.copyOf(notes), request.unlocked());
        GreenhouseSearch search = new GreenhouseSearch(request.unlocked(), cropIndex.size(), recipes,
            aims.toArray(new GreenhouseSearch.Aim[0]), cropPriority, cropCost, blockSizes, seed);
        Consumer<byte[]> preview = null;
        if (options.onBest() != null) {
            // The running search's state must not be disturbed, so snapshots are scored on a twin.
            GreenhouseSearch twin = new GreenhouseSearch(request.unlocked(), cropIndex.size(), recipes,
                aims.toArray(new GreenhouseSearch.Aim[0]), cropPriority, cropCost, blockSizes, seed);
            preview = labels -> {
                SolveResult snapshot = plan.build(labels, twin,
                    (options.clock().getAsLong() - started) / 1_000_000L, List.of(), false);
                try {
                    options.onBest().accept(snapshot);
                } catch (RuntimeException ignored) {
                    // A faulty listener must not end the search.
                }
            };
        }
        // Leave room for pruning and building the result, which are not interruptible.
        long reserve = Math.min(50_000_000L, budgetNanos / 10);
        byte[] labels = search.search(new GreenhouseSearch.Limits(started + budgetNanos - reserve,
            options.maxIterations(), cancelled, options.clock(), options.stopOnStagnation(), options.observer(),
            preview));
        List<String> ending = new ArrayList<>();
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            ending.add("Search stopped early; this is the best layout found so far");
        }
        if (search.stagnated()) {
            ending.add("Stopped early: no improvement for " + String.format(Locale.ROOT, "%.1f s",
                search.idleNanos() / 1e9) + ", the last " + search.consistentRestarts()
                + " restarts all ended on the same plan");
        }
        return plan.build(labels, search, (options.clock().getAsLong() - started) / 1_000_000L,
            ending, search.stagnated());
    }

    /** A layout and what {@link GreenhouseSearch#evaluate} reads back from it. */
    record Placed(byte[] labels, GreenhouseSearch.Evaluation evaluation) { }

    /**
     * Pushes a layout into the plot corner it is nearest to ({@link GreenhouseCompaction}), so the free space
     * stays in one block. A move keeps every ring but can add a site in space the layout leaves (a footprint
     * that used to hang over the plot edge now fits), so the first move, best first, whose read-back reports the
     * same counts is taken; if none does the layout stays where it is. Reads back through {@code search}, which
     * is left holding whichever layout was read last.
     */
    static Placed compacted(byte[] labels, GreenhouseSearch search, boolean[] unlocked) {
        GreenhouseSearch.Evaluation evaluation = search.evaluate(labels);
        boolean[] occupied = new boolean[GreenhouseSearch.CELLS];
        for (int p = 0; p < occupied.length; p++) occupied[p] = labels[p] != GreenhouseSearch.EMPTY;
        for (GreenhouseSearch.Site site : evaluation.sites()) {
            for (int p : site.cells()) occupied[p] = true;
        }
        for (GreenhouseCompaction.Shift shift : GreenhouseCompaction.shifts(occupied, unlocked)) {
            if (shift.equals(GreenhouseCompaction.Shift.NONE)) break;
            byte[] moved = new byte[GreenhouseSearch.CELLS];
            Arrays.fill(moved, GreenhouseSearch.EMPTY);
            for (int p = 0; p < moved.length; p++) {
                if (labels[p] != GreenhouseSearch.EMPTY) moved[shift.move(p)] = labels[p];
            }
            GreenhouseSearch.Evaluation after = search.evaluate(moved);
            if (sameOutcome(evaluation, after)) return new Placed(moved, after);
        }
        return new Placed(labels, evaluation);
    }

    private static boolean sameOutcome(GreenhouseSearch.Evaluation a, GreenhouseSearch.Evaluation b) {
        if (a.unrequested() != b.unrequested() || a.ambiguous() != b.ambiguous()
            || a.sites().size() != b.sites().size() || !Arrays.equals(a.candidates(), b.candidates())) {
            return false;
        }
        for (int g = 0; g < a.goalTotals().length; g++) {
            if (Math.abs(a.goalTotals()[g] - b.goalTotals()[g]) > 1e-9) return false;
        }
        return true;
    }

    /** Everything resolved from a request, and how to turn a layout into a {@link SolveResult}. */
    private record Plan(GoalResult[] template, List<GreenhouseMutations.Mutation> resolved, List<Goal> included,
                        List<Integer> includedAt, List<GreenhouseMutations.Mutation> possible,
                        List<GreenhouseSearch.Aim> aims, List<Integer> aimGoal, List<String> cropNames,
                        long[] cropCost, int[] blockSizes, List<String> baseNotes, boolean[] unlocked) {

        /** The grid (crops and every cell of every target footprint) and the targets read off it. */
        private record Layout(GreenhouseGrid grid, List<Target> targets) { }

        /**
         * Builds the result for {@code labels}, read back through {@code search}, with the layout moved by
         * {@link GreenhouseSolver#compacted}. {@code ending} are the notes that belong to a finished run (cancelled, stagnated);
         * they go before the notes derived from the layout.
         */
        SolveResult build(byte[] labels, GreenhouseSearch search, long elapsedMillis, List<String> ending,
                          boolean stoppedEarly) {
            Placed placed = compacted(labels, search, unlocked);
            return build(placed.labels(), placed.evaluation(), elapsedMillis, ending, stoppedEarly);
        }

        private Layout layout(byte[] labels, GreenhouseSearch.Evaluation evaluation) {
            GreenhouseGrid grid = new GreenhouseGrid();
            for (int p = 0; p < GreenhouseSearch.CELLS; p++) {
                if (labels[p] == GreenhouseSearch.EMPTY) continue;
                grid.set(p / GreenhouseGrid.SIZE, p % GreenhouseGrid.SIZE,
                    new GreenhouseGrid.Cell(cropNames.get(labels[p]), GreenhouseGrid.INGREDIENT));
            }
            // A site is named after the highest-priority goal it can grow.
            List<Target> targets = new ArrayList<>();
            for (GreenhouseSearch.Site site : evaluation.sites()) {
                int named = 0;
                while ((site.recipes() >> aims.get(named).recipe() & 1L) == 0) named++;
                GreenhouseMutations.Mutation mutation = possible.get(aims.get(named).recipe());
                for (int p : site.cells()) {
                    grid.set(p / GreenhouseGrid.SIZE, p % GreenhouseGrid.SIZE,
                        new GreenhouseGrid.Cell(mutation.name(), GreenhouseGrid.TARGET));
                }
                int anchor = Arrays.stream(site.cells()).min().orElse(0);
                targets.add(new Target(mutation.name(), anchor / GreenhouseGrid.SIZE, anchor % GreenhouseGrid.SIZE,
                    mutation.width(), mutation.height(), Long.bitCount(site.recipes())));
            }
            targets.sort((x, y) -> x.row() != y.row() ? Integer.compare(x.row(), y.row())
                : Integer.compare(x.column(), y.column()));
            return new Layout(grid, targets);
        }

        private SolveResult build(byte[] labels, GreenhouseSearch.Evaluation evaluation, long elapsedMillis,
                                  List<String> ending, boolean stoppedEarly) {
            Layout layout = layout(labels, evaluation);
            GoalResult[] results = template.clone();
            double[] achieved = new double[included.size()];
            int[] sites = new int[included.size()];
            for (int a = 0; a < aims.size(); a++) {
                achieved[aimGoal.get(a)] = evaluation.goalTotals()[a];
                sites[aimGoal.get(a)] = evaluation.candidates()[a];
            }
            for (int k = 0; k < included.size(); k++) {
                Goal goal = included.get(k);
                results[includedAt.get(k)] = new GoalResult(resolved.get(k).name(), goal.quantity(), achieved[k],
                    sites[k], true, shortfall(goal, achieved[k], sites[k]));
            }
            for (int i = 0; i < results.length; i++) {
                if (!results[i].supported() || results[i].note().isEmpty()
                    || !results[i].note().startsWith("Listed")) continue;
                for (int k = 0; k < included.size(); k++) {
                    if (resolved.get(k).name().equals(results[i].mutation())) {
                        results[i] = new GoalResult(results[i].mutation(), results[i].requested(), achieved[k],
                            sites[k], true, results[i].note());
                    }
                }
            }

            Map<String, Integer> counted = new LinkedHashMap<>();
            long costUnits = 0;
            for (int p = 0; p < GreenhouseSearch.CELLS; p++) {
                if (labels[p] == GreenhouseSearch.EMPTY) continue;
                counted.merge(cropNames.get(labels[p]), 1, Integer::sum);
                costUnits += cropCost[labels[p]];
            }
            // The shopping list counts items: a block of cells is one plant.
            counted.replaceAll((crop, cells) -> {
                int side = blockSizes[cropNames.indexOf(crop)];
                return (cells + side * side - 1) / (side * side);
            });
            Map<String, Integer> ingredients = new LinkedHashMap<>();
            counted.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> ingredients.put(entry.getKey(), entry.getValue()));
            Map<String, Double> unitCosts = new LinkedHashMap<>();
            for (String crop : ingredients.keySet()) {
                int side = blockSizes[cropNames.indexOf(crop)];
                unitCosts.put(crop, cropCost[cropNames.indexOf(crop)] * side * side / (double) COST_SCALE);
            }

            Set<String> soils = new LinkedHashSet<>();
            for (int a = 0; a < aims.size(); a++) {
                if (achieved[aimGoal.get(a)] > 0) soils.addAll(resolved.get(aimGoal.get(a)).soil());
            }
            List<String> notes = new ArrayList<>(baseNotes);
            notes.addAll(ending);
            if (evaluation.ambiguous() > 0) {
                notes.add(evaluation.ambiguous() + " target site(s) can also grow another mutation; counts are expected values");
            }
            if (resolved.stream().anyMatch(GreenhouseMutations.Mutation::emptyRing)) {
                notes.add("Lonelily needs a ring without crops, so those cells stay free of ingredients");
            }
            return new SolveResult(layout.grid(), List.of(results), List.copyOf(layout.targets()),
                Collections.unmodifiableMap(ingredients),
                evaluation.ambiguous(), evaluation.unrequested(), List.copyOf(soils), elapsedMillis, List.copyOf(notes),
                stoppedEarly, costUnits / (double) COST_SCALE, Collections.unmodifiableMap(unitCosts));
        }
    }

    /** Why a fixed goal is not fully met, or an empty note when it is. */
    private static String shortfall(Goal goal, double achieved, int sites) {
        if (goal.maximize()) return "";
        int wanted = goal.quantity();
        if (sites > wanted) {
            return sites + " candidate sites exceed the " + wanted + " requested and the search could not avoid it";
        }
        if (achieved + 1e-9 >= wanted) return "";
        if (sites == wanted) {
            return "Capped at " + wanted + " candidate sites so none exceed the request; "
                + quantity(achieved) + " expected because other recipes share them";
        }
        return "Best layout found has " + quantity(achieved) + " of " + wanted + " requested";
    }

    /** A result with an empty layout, for requests nothing could be planned for. */
    private static SolveResult bare(List<GoalResult> goals, List<String> notes, long started, Options options) {
        return new SolveResult(new GreenhouseGrid(), List.copyOf(goals), List.of(), Map.of(), 0, 0, List.of(),
            (options.clock().getAsLong() - started) / 1_000_000L, List.copyOf(notes), false);
    }

    /** "16" for whole numbers, "7.50" otherwise. */
    static String quantity(double value) {
        return Math.abs(value - Math.rint(value)) < 1e-9 ? Long.toString(Math.round(value))
            : String.format(Locale.ROOT, "%.2f", value);
    }
}
