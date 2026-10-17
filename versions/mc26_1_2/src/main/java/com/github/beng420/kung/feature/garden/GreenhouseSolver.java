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
 */
final class GreenhouseSolver {
    static final long DEFAULT_BUDGET_MILLIS = 1500;
    /** {@link Goal#quantity()} value that asks for as many as the plot allows. */
    static final int MAX = -1;
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
     * {@code priorities} maps a crop name (loose matching) to 0..100, higher meaning "use this crop less". It
     * is a tie-breaker only: of two layouts with the same goal counts and the same ambiguity, the one with
     * fewer priority points (sum of priority per planted crop) wins.
     */
    record SolveRequest(List<Goal> goals, boolean[] unlocked, Map<String, Integer> priorities) {
        SolveRequest {
            goals = List.copyOf(goals);
            priorities = priorities == null ? Map.of() : Map.copyOf(priorities);
            if (unlocked == null) {
                unlocked = new boolean[GreenhouseSearch.CELLS];
                Arrays.fill(unlocked, true);
            } else if (unlocked.length != GreenhouseSearch.CELLS) {
                throw new IllegalArgumentException("unlocked needs " + GreenhouseSearch.CELLS + " entries");
            } else {
                unlocked = unlocked.clone();
            }
        }

        SolveRequest(List<Goal> goals, boolean[] unlocked) { this(goals, unlocked, Map.of()); }

        static SolveRequest of(Goal... goals) { return new SolveRequest(List.of(goals), null); }

        SolveRequest withPriorities(Map<String, Integer> priorities) {
            return new SolveRequest(goals, unlocked, priorities);
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
     * {@code stoppedEarly} is set when the search ended before its time budget because it had stopped improving.
     */
    record SolveResult(GreenhouseGrid grid, List<GoalResult> goals, List<Target> targets,
                       Map<String, Integer> ingredients, int ambiguousTargets, int unrequestedSites,
                       List<String> soils, long elapsedMillis, List<String> notes, boolean stoppedEarly) { }

    /**
     * Run settings behind the public overloads. {@code clock} reads nanoseconds (tests inject their own);
     * {@code stopOnStagnation} lets the search end early once it has stopped improving; {@code observer} is for
     * tuning and may be null.
     */
    record Options(long maxIterations, BooleanSupplier cancelled, LongSupplier clock, boolean stopOnStagnation,
                   GreenhouseSearch.Observer observer) { }

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

    /** Time-budget run: ends at the budget, or earlier once it has stopped improving. */
    static SolveResult solve(SolveRequest request, long budgetMillis, long seed) {
        return solve(request, budgetMillis, seed, Long.MAX_VALUE, () -> false);
    }

    /**
     * {@link #solve(SolveRequest, long, long, long, BooleanSupplier)} on a daemon thread, for callers that must
     * not block (the render thread). Cancel by making {@code cancelled} return true.
     */
    static CompletableFuture<SolveResult> solveAsync(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled) {
        CompletableFuture<SolveResult> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                result.complete(solve(request, budgetMillis, seed, Long.MAX_VALUE, cancelled));
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
     * rounds fit, and the run may end early on stagnation (an iteration cap switches that off).
     */
    static SolveResult solve(SolveRequest request, long budgetMillis, long seed, long maxIterations,
                             BooleanSupplier cancelled) {
        return solve(request, budgetMillis, seed, new Options(maxIterations, cancelled, System::nanoTime,
            maxIterations == Long.MAX_VALUE, null));
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

        // Priorities: unknown names are reported and skipped; crops no goal needs cannot be planted anyway.
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

        GreenhouseSearch search = new GreenhouseSearch(request.unlocked(), cropIndex.size(), recipes,
            aims.toArray(new GreenhouseSearch.Aim[0]), cropPriority, seed);
        // Leave room for pruning and building the result, which are not interruptible.
        long reserve = Math.min(50_000_000L, budgetNanos / 10);
        byte[] labels = search.search(new GreenhouseSearch.Limits(started + budgetNanos - reserve,
            options.maxIterations(), cancelled, options.clock(), options.stopOnStagnation(), options.observer()));
        boolean cancelledRun = cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
        GreenhouseSearch.Evaluation evaluation = search.evaluate(labels);

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
            if (!results[i].supported() || results[i].note().isEmpty() || !results[i].note().startsWith("Listed")) continue;
            for (int k = 0; k < included.size(); k++) {
                if (resolved.get(k).name().equals(results[i].mutation())) {
                    results[i] = new GoalResult(results[i].mutation(), results[i].requested(), achieved[k],
                        sites[k], true, results[i].note());
                }
            }
        }

        GreenhouseGrid grid = new GreenhouseGrid();
        List<String> cropNames = new ArrayList<>(cropIndex.keySet());
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (int p = 0; p < GreenhouseSearch.CELLS; p++) {
            if (labels[p] == GreenhouseSearch.EMPTY) continue;
            String crop = cropNames.get(labels[p]);
            grid.set(p / GreenhouseGrid.SIZE, p % GreenhouseGrid.SIZE,
                new GreenhouseGrid.Cell(crop, GreenhouseGrid.INGREDIENT));
            counted.merge(crop, 1, Integer::sum);
        }
        Map<String, Integer> ingredients = new LinkedHashMap<>();
        counted.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(entry -> ingredients.put(entry.getKey(), entry.getValue()));

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
        targets.sort((a, b) -> a.row() != b.row() ? Integer.compare(a.row(), b.row())
            : Integer.compare(a.column(), b.column()));

        Set<String> soils = new LinkedHashSet<>();
        for (int a = 0; a < aims.size(); a++) {
            if (achieved[aimGoal.get(a)] > 0) soils.addAll(resolved.get(aimGoal.get(a)).soil());
        }
        if (cancelledRun) notes.add("Search stopped early; this is the best layout found so far");
        if (search.stagnated()) {
            notes.add("Stopped early: no improvement for " + String.format(Locale.ROOT, "%.1f s",
                search.idleNanos() / 1e9) + ", the last " + search.consistentRestarts()
                + " restarts all ended on the same plan");
        }
        if (evaluation.ambiguous() > 0) {
            notes.add(evaluation.ambiguous() + " target site(s) can also grow another mutation; counts are expected values");
        }
        if (resolved.stream().anyMatch(GreenhouseMutations.Mutation::emptyRing)) {
            notes.add("Lonelily needs a ring without crops, so those cells stay free of ingredients");
        }
        return new SolveResult(grid, List.of(results), List.copyOf(targets), Collections.unmodifiableMap(ingredients),
            evaluation.ambiguous(), evaluation.unrequested(), List.copyOf(soils),
            (options.clock().getAsLong() - started) / 1_000_000L, List.copyOf(notes), search.stagnated());
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
