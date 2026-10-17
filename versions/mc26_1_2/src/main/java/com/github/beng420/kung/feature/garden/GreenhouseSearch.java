package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

/**
 * Annealing engine behind {@link GreenhouseSolver}. A layout is one label per plot cell: {@link #EMPTY} or an
 * ingredient crop index. A <em>site</em> is a footprint (1x1, 2x2 or 3x3) of empty unlocked cells together with
 * the ring of cells around it; it grows a mutation when the ring crop counts meet a recipe, and which one is
 * random among all recipes the ring meets, so each candidate gets a share of the site proportional to its spawn
 * chance (the shares of one site sum to one). Totals are kept as integers in {@link #UNIT} fractions, so
 * incremental updates never drift.
 *
 * <p>A fixed goal never gets more <em>candidate sites</em> (sites whose ring meets its recipe, shared or not)
 * than it asked for: the first thing the search minimises is the total excess over those limits.
 */
final class GreenhouseSearch {
    static final int SIZE = GreenhouseGrid.SIZE;
    static final int CELLS = SIZE * SIZE;
    /**
     * One expected mutation. 720720 is divisible by 1..16, so equal-chance splits are exact; chance-weighted
     * shares round down by less than one unit per site, which the 2^20 factor makes about 1e-12 of a mutation.
     * Caps (up to 100000 mutations) and totals (100 sites) still fit a long with room to spare.
     */
    static final long UNIT = 720_720L << 20;
    /** Differences below this many units (a billionth of a mutation) are ties, which also hides float noise. */
    private static final double TIE = 1e-9 * UNIT;
    static final byte EMPTY = -1;
    /** One annealing round: a geometric cooling from {@link #HOT} to {@link #COLD}, in expected mutations. */
    static final int ROUND_ITERATIONS = 40_000;
    private static final double HOT = 1.0;
    private static final double COLD = 0.04;
    /** Rounds that restart from the best layout begin cooler and keep less guidance. */
    private static final double REHOT = 0.35;
    private static final double RECOLD = 0.03;
    private static final double GUIDANCE = 0.5;
    private static final double REGUIDANCE = 0.2;
    /** Share of proposals that shift a region or exchange two crop types; the rest are single, swap and stamp. */
    private static final double SHIFT_SHARE = 0.1;
    private static final double EXCHANGE_SHARE = 0.003;
    private static final int CHECK_MASK = 127;
    /** Compaction rounds are too cold to give up an expected mutation but may still trade crops. */
    private static final double COMPACT_HOT = 0.02;
    /** A typed lattice already works; annealing only has to fit more around it. */
    private static final double SEED_HOT = 0.12;
    private static final double SEED_COLD = 0.01;
    private static final double COMPACT_COLD = 0.004;
    /**
     * What one site over a fixed goal's limit costs while annealing is still warm, in expected mutations of the
     * lowest tier: soft, so a plan may pass through a spare site on its way to a better one (a hard barrier cut
     * the chance of the best mixed plan to a third). Below {@link #EXCESS_HARD_BELOW} the excess is ranked first
     * instead, so rounds end without any. The exact key ranks it first always.
     */
    private static final double EXCESS_SOFT = 3.0 * UNIT;
    private static final double EXCESS_HARD_BELOW = 0.3;
    /** Tie-breaker value of one fewer ambiguous site / priority point / planted crop, in expected mutations. */
    private static final double AMBIGUITY_PENALTY = 0.05 * UNIT;
    /**
     * Annealing weight of cost: the dearest crop's cell weighs this many expected mutations, and cheaper cells
     * in proportion. The exact key compares the cost itself, so the weight only sets how readily the walk
     * trades cost, never whether the goal counts come first.
     */
    private static final double COST_SPAN = 0.02;
    /** One priority point; a full-priority (100) crop weighs 0.1 mutations. */
    private static final double PRIORITY_PENALTY = 0.001 * UNIT;
    private static final double CROP_PENALTY = 0.01 * UNIT;
    /** Ring cells of the largest footprint (3x3). */
    private static final int MAX_RING = 16;
    /** Components before the tiers in the value and key vectors: the excess over fixed goals' site limits. */
    private static final int LEAD = 1;
    /** Components after the tiers: ambiguous sites, total cost, priority points, crops. */
    private static final int TRAIL = 4;
    /** Exact re-typing, see {@link #retype}: node budgets per promising restart and for the final best plan. */
    private static final long RETYPE_ROUND_NODES = 40_000;
    private static final long RETYPE_FINAL_NODES = 400_000;
    /** Node budget for typing a freshly laid lattice, see {@link #seedLattice}. */
    private static final long SEED_NODES = 150_000;
    /**
     * What one search node is assumed to cost when the final re-typing must fit the time that is left. Measured
     * at 2-4 microseconds on one-goal plans and up to ten times that with six goals, so this is a guide only:
     * the search also looks at the clock and the cancel flag every {@link #RETYPE_CHECK_NODES} nodes.
     */
    private static final long RETYPE_NANOS_PER_NODE = 4_000;
    /** The exact search checks for cancellation and its deadline after this many nodes (a power of two). */
    private static final int RETYPE_CHECK_NODES = 512;
    /** The best plan reaches the preview consumer at most this often (the last one always arrives). */
    static final long PREVIEW_INTERVAL_NANOS = 250_000_000L;
    /*
     * Early stopping, see stagnant(). Derived from 60 s runs without early stop (seeds 1-20 of: max Dustgrain,
     * max Cindershade, 3x Cindershade + max Dustgrain, max Snoozling, Zombud 2 + max Glasscorn; held out on seeds
     * 21-30 of: 4x Cindershade + 2x Snoozling + max Dustgrain, 2x Snoozling + max Choconut, max Noctilume,
     * Dustgrain 10 + Choconut 10 + max Scourroot), logging every strict improvement and every round.
     *
     * Idle time alone is not a safe signal. Hard cases find their last improvement at random, about once per
     * 15 s once they are close (the 57th Dustgrain next to 3 Cindershade came after 1.2 to 28.7 s, and one run in
     * twenty had not found it after 60 s), so after 3-15 s idle an idle-only rule missed the 60 s plan in 45-70%
     * of those runs. What separates "converged" from "unlucky" is whether independent restarts keep landing on
     * the same plan: fresh rounds ended on the final best plan in 87-100% of rounds for the easy cases and in 0.4%,
     * 0.4%, 0.1% and 12% for the hard ones (53% for Zombud 2 + Glasscorn). With 20 consecutive fresh rounds on
     * the best plan required, 140 runs had no plan miss against their 60 s result; easy cases stopped at
     * 3.0 s (Cindershade, Snoozling, Noctilume) to 8 s median (Dustgrain, 87% per round), hard ones never stopped
     * early. Only the last tie-breakers (the crop count, and a price or priority points when those are set) can
     * still differ, by one crop in 30-45% of the stopped Dustgrain and Cindershade runs, so they do not count as
     * progress: the plan is the excess, the goal tiers and the ambiguity, which keeps the rule working when
     * costs are on. The 3 s minimum runtime and the idle clock (max of 3 s and once the time of the last
     * improvement) were never the binding condition in these runs and stay as slack.
     */
    /** Never stop before this much search time. */
    static final long MIN_RUNTIME_NANOS = 3_000_000_000L;
    /** The best plan must also have stood this long ... */
    static final long MIN_IDLE_NANOS = 3_000_000_000L;
    /** ... and this multiple of the time it took to find it. */
    static final double IDLE_FACTOR = 1.0;
    /** This many fresh restarts in a row must have ended on the best plan. */
    static final int MIN_CONSISTENT_RESTARTS = 20;

    /**
     * Ring counts per crop index; {@code emptyRing} needs no crop in the ring at all. {@code weight} is the
     * spawn chance that splits a site between the recipes it meets; it must be positive.
     */
    record Recipe(int width, int height, int[] crops, int[] counts, boolean emptyRing, int weight) {
        int total() {
            int sum = 0;
            for (int count : counts) sum += count;
            return sum;
        }
    }

    /**
     * A prioritized objective. A lower tier outranks every higher one; {@code cap} is the quantity beyond which more
     * of this mutation earns nothing ({@link Long#MAX_VALUE} for maximize goals).
     */
    record Aim(int recipe, long cap, int tier) {
        /** Most candidate sites a fixed goal may have; unlimited for maximize goals. */
        int limit() { return cap == Long.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, cap / UNIT); }
    }

    /** A site that grows at least one requested mutation: its footprint cells and the recipe bits it meets. */
    record Site(int[] cells, long recipes) { }

    /**
     * {@code goalTotals} are the expected counts, summed from the sites' exact rational shares;
     * {@code candidates} the number of sites whose ring meets each goal's recipe.
     */
    record Evaluation(double[] goalTotals, int[] candidates, List<Site> sites, int unrequested, int ambiguous) { }

    /**
     * Watches a run, for tuning and tests: every strict improvement of the best key, and the end of every round
     * with the best key that round reached ({@code fresh} rounds start from an empty plot).
     */
    interface Observer {
        void improved(long elapsedNanos, long[] key);

        void roundEnded(long elapsedNanos, boolean fresh, long[] roundKey);
    }

    /**
     * How a run ends. {@code clock} reads nanoseconds and {@code deadlineNanos} is on the same scale;
     * with {@code stopOnStagnation} the run may also end between rounds when it has stopped improving.
     * {@code onBest} (may be null) receives the labels of the best plan whenever it improves, at most once per
     * {@link #PREVIEW_INTERVAL_NANOS}, and once more at the end if the last improvement was still pending.
     */
    record Limits(long deadlineNanos, long maxIterations, BooleanSupplier cancelled, LongSupplier clock,
                  boolean stopOnStagnation, Observer observer, Consumer<byte[]> onBest) { }

    private final int crops;
    private final int stride;
    private final Recipe[] recipes;
    private final int[] recipeTotal;
    private final int[] recipeClass;
    private final int[] recipeWeight;
    private final int[] cropPriority;
    private final long[] cropCost;
    private final double costWeight;
    /** Cost or priority points are in play, so re-typing a plan can beat the annealing's assignment. */
    private final boolean typesMatter;
    /** Crop indices from cheapest to dearest (then by priority): the order the exact search tries them. */
    private final int[] typeOrder;
    private final byte[] roundLabel = new byte[CELLS];
    private long retypeReserve;
    private final Aim[] goals;
    private final int tiers;
    private final boolean[] open;
    private final int[] cellList;
    private final boolean[] cappedScratch;
    private final SplittableRandom random;

    private final int siteCount;
    private final int[] siteClass;
    private final boolean[] sized;
    private final int[][] footprint;
    private final int[][] ring;
    private final int[][] footSites = new int[CELLS][];
    private final int[][] ringSites = new int[CELLS][];
    private final int[] singleSite = new int[CELLS];
    private final int[][] classRecipes;
    private final int[][] classGoals;
    private final int[][] classSites;
    /** Per footprint class: the site whose top-left footprint cell is this cell, or -1. */
    private final int[][] siteAtAnchor;

    private final byte[] label = new byte[CELLS];
    private final int[] count;
    private final int[] filled;
    private final int[] ringCrops;
    private final long[] raw;
    private final long[] effective;
    private final double[] shape;
    private final double[] shapeTier;
    private final int[] claim = new int[CELLS];
    private final long[] total;
    private final int[] candidates;
    private final int[] goalLimit;
    private int ambiguous;
    private int cropCells;
    private int priorityPoints;
    private long costUnits;

    private final int[] mark;
    private int markStamp;
    private final int[] sizedTouched;
    private final int[] singleTouched;
    private int sizedCount;
    private int singleCount;
    private final int[] changeCell = new int[CELLS];
    private final byte[] changeOld = new byte[CELLS];
    private final byte[] changeNew = new byte[CELLS];
    private int changeCount;
    private final int[] have;
    private final int[] missing;
    private final int[] order = new int[MAX_RING];
    private final double[] partialScratch;
    private final double[] before;
    private final double[] after;

    private final byte[] bestLabel = new byte[CELLS];
    private final long[] bestKey;
    private final long[] key;
    private long iterations;

    private Limits run;
    /** Clock reading at the latest stop check; improvements are stamped with it, so they cost no clock reads. */
    private long now;
    private long searchStart;
    /** Search time of the latest strict improvement of the best key. */
    private long lastImprovement;
    private boolean stagnated;
    private long idleNanos;
    private final long[] roundKey;
    /** A strict improvement of the plan (excess, goal tiers, ambiguity) happened in the current round. */
    private boolean planImproved;
    /** Consecutive fresh rounds that ended on the best plan; reset whenever the best plan improves. */
    private int consistentRestarts;
    /** The best plan changed since it was last handed to the preview consumer. */
    private boolean pendingDelivery;
    private long lastDelivery;
    private byte[] delivered;

    /**
     * {@code priorities} holds one 0..100 penalty per crop index and {@code costUnits} the fixed-point cost of one
     * planted cell per crop index (the caller picks the scale; totals must fit a long). Both only break ties
     * between layouts with equal goal counts and ambiguity.
     */
    GreenhouseSearch(boolean[] unlocked, int cropCount, Recipe[] recipes, Aim[] goals, int[] priorities,
                     long[] costUnits, long seed) {
        this.crops = cropCount;
        this.cropPriority = Arrays.copyOf(priorities, Math.max(1, cropCount));
        this.cropCost = Arrays.copyOf(costUnits, Math.max(1, cropCount));
        long dearest = 0;
        for (long unit : cropCost) dearest = Math.max(dearest, unit);
        this.costWeight = dearest == 0 ? 0 : COST_SPAN * UNIT / dearest;
        boolean anyPriority = false;
        for (int priority : cropPriority) anyPriority |= priority > 0;
        this.typesMatter = dearest > 0 || anyPriority;
        Integer[] byPrice = new Integer[Math.max(1, cropCount)];
        for (int k = 0; k < byPrice.length; k++) byPrice[k] = k;
        Arrays.sort(byPrice, (x, y) -> cropCost[x] != cropCost[y] ? Long.compare(cropCost[x], cropCost[y])
            : Integer.compare(cropPriority[x], cropPriority[y]));
        this.typeOrder = new int[byPrice.length];
        for (int k = 0; k < byPrice.length; k++) typeOrder[k] = byPrice[k];
        this.stride = Math.max(1, cropCount);
        this.recipes = recipes.clone();
        this.goals = goals.clone();
        this.random = new SplittableRandom(seed);
        this.open = unlocked.clone();
        recipeTotal = new int[recipes.length];
        recipeWeight = new int[recipes.length];
        for (int r = 0; r < recipes.length; r++) {
            recipeTotal[r] = recipes[r].total();
            recipeWeight[r] = Math.max(1, recipes[r].weight());
        }
        int topTier = -1;
        for (Aim goal : goals) topTier = Math.max(topTier, goal.tier());
        tiers = topTier + 1;
        cellList = IntStream.range(0, CELLS).filter(p -> unlocked[p]).toArray();

        // Footprint classes by size, in first-seen order.
        List<int[]> classSizes = new ArrayList<>();
        recipeClass = new int[recipes.length];
        for (int r = 0; r < recipes.length; r++) {
            int c = 0;
            while (c < classSizes.size() && !(classSizes.get(c)[0] == recipes[r].width()
                && classSizes.get(c)[1] == recipes[r].height())) c++;
            if (c == classSizes.size()) classSizes.add(new int[] {recipes[r].width(), recipes[r].height()});
            recipeClass[r] = c;
        }
        int classTotal = classSizes.size();
        classRecipes = new int[classTotal][];
        classGoals = new int[classTotal][];
        for (int c = 0; c < classTotal; c++) {
            final int cls = c;
            classRecipes[c] = IntStream.range(0, recipes.length).filter(r -> recipeClass[r] == cls).toArray();
            classGoals[c] = IntStream.range(0, goals.length)
                .filter(g -> recipeClass[goals[g].recipe()] == cls).toArray();
        }

        // Sites: every anchor whose footprint lies on unlocked cells.
        List<int[]> footprints = new ArrayList<>();
        List<int[]> rings = new ArrayList<>();
        List<Integer> classes = new ArrayList<>();
        Arrays.fill(singleSite, -1);
        classSites = new int[classTotal][];
        siteAtAnchor = new int[classTotal][CELLS];
        for (int[] row : siteAtAnchor) Arrays.fill(row, -1);
        for (int c = 0; c < classTotal; c++) {
            int width = classSizes.get(c)[0];
            int height = classSizes.get(c)[1];
            List<Integer> ids = new ArrayList<>();
            for (int row = 0; row + height <= SIZE; row++) {
                for (int column = 0; column + width <= SIZE; column++) {
                    int[] foot = new int[width * height];
                    boolean free = true;
                    for (int dr = 0; dr < height && free; dr++) {
                        for (int dc = 0; dc < width; dc++) {
                            int p = (row + dr) * SIZE + column + dc;
                            if (!unlocked[p]) { free = false; break; }
                            foot[dr * width + dc] = p;
                        }
                    }
                    if (!free) continue;
                    List<Integer> around = new ArrayList<>();
                    for (int rr = row - 1; rr <= row + height; rr++) {
                        for (int cc = column - 1; cc <= column + width; cc++) {
                            boolean inside = rr >= row && rr < row + height && cc >= column && cc < column + width;
                            if (!inside && GreenhouseGrid.inside(rr, cc) && unlocked[rr * SIZE + cc]) {
                                around.add(rr * SIZE + cc);
                            }
                        }
                    }
                    int id = footprints.size();
                    footprints.add(foot);
                    rings.add(around.stream().mapToInt(Integer::intValue).toArray());
                    classes.add(c);
                    ids.add(id);
                    siteAtAnchor[c][foot[0]] = id;
                    if (width == 1 && height == 1) singleSite[foot[0]] = id;
                }
            }
            classSites[c] = ids.stream().mapToInt(Integer::intValue).toArray();
        }
        siteCount = footprints.size();
        footprint = footprints.toArray(new int[0][]);
        ring = rings.toArray(new int[0][]);
        siteClass = classes.stream().mapToInt(Integer::intValue).toArray();
        sized = new boolean[siteCount];
        for (int s = 0; s < siteCount; s++) sized[s] = footprint[s].length > 1;
        List<List<Integer>> footOf = new ArrayList<>();
        List<List<Integer>> ringOf = new ArrayList<>();
        for (int p = 0; p < CELLS; p++) { footOf.add(new ArrayList<>()); ringOf.add(new ArrayList<>()); }
        for (int s = 0; s < siteCount; s++) {
            for (int p : footprint[s]) footOf.get(p).add(s);
            for (int p : ring[s]) ringOf.get(p).add(s);
        }
        for (int p = 0; p < CELLS; p++) {
            footSites[p] = footOf.get(p).stream().mapToInt(Integer::intValue).toArray();
            ringSites[p] = ringOf.get(p).stream().mapToInt(Integer::intValue).toArray();
        }

        count = new int[Math.max(1, siteCount * stride)];
        filled = new int[siteCount];
        ringCrops = new int[siteCount];
        raw = new long[siteCount];
        effective = new long[siteCount];
        shape = new double[Math.max(1, siteCount * tiers)];
        shapeTier = new double[tiers];
        total = new long[goals.length];
        candidates = new int[goals.length];
        goalLimit = new int[goals.length];
        for (int g = 0; g < goals.length; g++) goalLimit[g] = goals[g].limit();
        mark = new int[siteCount];
        sizedTouched = new int[siteCount];
        singleTouched = new int[siteCount];
        have = new int[stride];
        missing = new int[stride];
        partialScratch = new double[tiers];
        cappedScratch = new boolean[tiers];
        before = new double[LEAD + tiers + TRAIL];
        after = new double[LEAD + tiers + TRAIL];
        key = new long[LEAD + tiers + TRAIL];
        bestKey = new long[LEAD + tiers + TRAIL];
        roundKey = new long[LEAD + tiers + TRAIL];
        Arrays.fill(label, EMPTY);
        recompute();
        snapshotBest();
    }

    // ----- evaluation state -----

    /** Rebuilds every counter from {@link #label}. */
    private void recompute() {
        Arrays.fill(count, 0);
        Arrays.fill(claim, 0);
        Arrays.fill(raw, 0);
        Arrays.fill(effective, 0);
        Arrays.fill(shape, 0);
        Arrays.fill(shapeTier, 0);
        Arrays.fill(total, 0);
        Arrays.fill(candidates, 0);
        ambiguous = 0;
        cropCells = 0;
        priorityPoints = 0;
        costUnits = 0;
        for (int p = 0; p < CELLS; p++) {
            if (label[p] == EMPTY) continue;
            cropCells++;
            priorityPoints += cropPriority[label[p]];
            costUnits += cropCost[label[p]];
        }
        for (int s = 0; s < siteCount; s++) {
            int stones = 0;
            for (int p : footprint[s]) if (label[p] != EMPTY) stones++;
            filled[s] = stones;
            int around = 0;
            for (int p : ring[s]) {
                if (label[p] != EMPTY) {
                    count[s * stride + label[p]]++;
                    around++;
                }
            }
            ringCrops[s] = around;
        }
        sizedCount = 0;
        singleCount = 0;
        markStamp++;
        for (int s = 0; s < siteCount; s++) touch(s);
        refreshTouched();
    }

    private void touch(int s) {
        if (mark[s] == markStamp) return;
        mark[s] = markStamp;
        if (sized[s]) sizedTouched[sizedCount++] = s; else singleTouched[singleCount++] = s;
    }

    private void refreshTouched() {
        for (int i = 0; i < sizedCount; i++) refresh(sizedTouched[i]);
        // Sized sites flip claims on cells, which queues those 1x1 sites; they run after all sized ones.
        for (int i = 0; i < singleCount; i++) refresh(singleTouched[i]);
        sizedCount = 0;
        singleCount = 0;
    }

    private boolean satisfied(int s, Recipe recipe) {
        if (recipe.emptyRing()) return ringCrops[s] == 0;
        int base = s * stride;
        int[] needCrops = recipe.crops();
        int[] needCounts = recipe.counts();
        for (int i = 0; i < needCrops.length; i++) {
            if (count[base + needCrops[i]] < needCounts[i]) return false;
        }
        return true;
    }

    private void refresh(int s) {
        long mask = 0;
        if (filled[s] == 0) {
            for (int r : classRecipes[siteClass[s]]) if (satisfied(s, recipes[r])) mask |= 1L << r;
        }
        if (sized[s] && (mask != 0) != (raw[s] != 0)) {
            int delta = mask != 0 ? 1 : -1;
            for (int p : footprint[s]) {
                claim[p] += delta;
                if (singleSite[p] >= 0) touch(singleSite[p]);
            }
        }
        raw[s] = mask;
        boolean claimed = !sized[s] && claim[footprint[s][0]] > 0;
        long effectiveMask = claimed ? 0 : mask;
        account(effective[s], -1);
        account(effectiveMask, 1);
        effective[s] = effectiveMask;
        if (tiers > 0) {
            partial(s, effectiveMask, claimed);
            for (int t = 0; t < tiers; t++) {
                shapeTier[t] += partialScratch[t] - shape[s * tiers + t];
                shape[s * tiers + t] = partialScratch[t];
            }
        }
    }

    /** Sum of the spawn chances of the recipes in {@code mask}. */
    private int chanceSum(long mask) {
        int sum = 0;
        for (long bits = mask; bits != 0; bits &= bits - 1) sum += recipeWeight[Long.numberOfTrailingZeros(bits)];
        return sum;
    }

    private void account(long mask, int sign) {
        if (mask == 0) return;
        int sum = chanceSum(mask);
        boolean goalHit = false;
        for (int g = 0; g < goals.length; g++) {
            int r = goals[g].recipe();
            if ((mask >> r & 1L) != 0) {
                // Rounded down, but a pure function of (recipe, mask): adding and removing cancel exactly.
                total[g] += sign * (UNIT * recipeWeight[r] / sum);
                candidates[g] += sign;
                goalHit = true;
            }
        }
        if (goalHit && Long.bitCount(mask) > 1) ambiguous += sign;
    }

    /**
     * Guidance only, per tier: how close an unsatisfied empty site is to that tier's nearest goal recipe,
     * squared so a nearly complete ring is worth far more than a sparse one.
     */
    private void partial(int s, long mask, boolean claimed) {
        Arrays.fill(partialScratch, 0);
        if (filled[s] != 0 || claimed) return;
        int base = s * stride;
        for (int g : classGoals[siteClass[s]]) {
            int r = goals[g].recipe();
            Recipe recipe = recipes[r];
            if ((mask >> r & 1L) != 0 || recipe.emptyRing()) continue;
            int got = 0;
            for (int i = 0; i < recipe.crops().length; i++) {
                got += Math.min(count[base + recipe.crops()[i]], recipe.counts()[i]);
            }
            double fraction = got / (double) recipeTotal[r];
            double value = fraction * fraction * UNIT;
            if (value > partialScratch[goals[g].tier()]) partialScratch[goals[g].tier()] = value;
        }
    }

    /** Candidate sites beyond the limits of the fixed goals, summed; zero means no request is exceeded. */
    private int excess() {
        int excess = 0;
        for (int g = 0; g < goals.length; g++) excess += Math.max(0, candidates[g] - goalLimit[g]);
        return excess;
    }

    /**
     * The annealing value: the excess first once {@code hard} (otherwise it is a penalty inside the lowest tier),
     * then per tier the capped expected counts plus the guidance term while the tier can still grow, then the
     * tie-breakers. Entries compare lexicographically, so guidance never trades against a higher entry.
     */
    private void valueVector(double[] out, double guidance, boolean hard) {
        Arrays.fill(out, 0);
        out[0] = hard ? -excess() * UNIT : 0;
        boolean[] capped = cappedScratch;
        Arrays.fill(capped, true);
        for (int g = 0; g < goals.length; g++) {
            int t = goals[g].tier();
            out[LEAD + t] += Math.min(total[g], goals[g].cap());
            // More partial rings help only while the goal is short of its count and of its site limit.
            if (total[g] < goals[g].cap() && candidates[g] < goalLimit[g]) capped[t] = false;
        }
        for (int t = 0; t < tiers; t++) if (!capped[t]) out[LEAD + t] += guidance * shapeTier[t];
        if (!hard) out[LEAD + tiers - 1] -= excess() * EXCESS_SOFT;
        // Last, as tie-breakers: fewer sites another mutation can take, lower cost, fewer priority points, fewer crops.
        out[LEAD + tiers] = -ambiguous * AMBIGUITY_PENALTY;
        out[LEAD + tiers + 1] = -costUnits * costWeight;
        out[LEAD + tiers + 2] = -priorityPoints * PRIORITY_PENALTY;
        out[LEAD + tiers + 3] = -cropCells * CROP_PENALTY;
    }

    /**
     * Exact lexicographic key, larger is better: fewer excess sites, then the tier sums, then fewer ambiguous
     * sites, lower total cost, fewer priority points and fewer crops.
     */
    private void fillKey(long[] out) {
        Arrays.fill(out, 0, LEAD + tiers, 0);
        out[0] = -excess();
        for (int g = 0; g < goals.length; g++) {
            out[LEAD + goals[g].tier()] += Math.min(total[g], goals[g].cap());
        }
        out[LEAD + tiers] = -ambiguous;
        out[LEAD + tiers + 1] = -costUnits;
        out[LEAD + tiers + 2] = -priorityPoints;
        out[LEAD + tiers + 3] = -cropCells;
    }

    private static boolean better(long[] candidate, long[] incumbent) {
        for (int i = 0; i < candidate.length; i++) {
            if (candidate[i] != incumbent[i]) return candidate[i] > incumbent[i];
        }
        return false;
    }

    private void snapshotBest() {
        fillKey(bestKey);
        System.arraycopy(label, 0, bestLabel, 0, CELLS);
    }

    private void noteBest() {
        fillKey(key);
        if (better(key, roundKey)) {
            System.arraycopy(key, 0, roundKey, 0, key.length);
            System.arraycopy(label, 0, roundLabel, 0, CELLS);
        }
        if (better(key, bestKey)) {
            // Saving a crop, a price or a priority point is not progress worth waiting for: only the plan
            // (excess, goal tiers, ambiguity) resets the idle clock.
            boolean plan = !samePlan(key, bestKey);
            System.arraycopy(key, 0, bestKey, 0, key.length);
            System.arraycopy(label, 0, bestLabel, 0, CELLS);
            if (plan) {
                lastImprovement = now - searchStart;
                planImproved = true;
            }
            pendingDelivery = true;
            if (run != null && run.observer() != null) {
                run.observer().improved(now - searchStart, bestKey.clone());
            }
        }
    }

    /**
     * Whether two keys agree on the plan: the excess, the goal tiers and the ambiguity, which are the entries
     * before the cost, the priority points and the crop count (see {@link #fillKey}).
     */
    private static boolean samePlan(long[] a, long[] b) {
        for (int i = 0; i < a.length - (TRAIL - 1); i++) if (a[i] != b[i]) return false;
        return true;
    }

    // ----- moves -----

    private void change(int cell, byte next) {
        changeCell[changeCount] = cell;
        changeOld[changeCount] = label[cell];
        changeNew[changeCount] = next;
        changeCount++;
    }

    /** Applies the queued changes (or, with {@code reverse}, undoes them) and refreshes the touched sites. */
    private void apply(boolean reverse) {
        markStamp++;
        for (int i = 0; i < changeCount; i++) {
            int p = changeCell[i];
            byte from = reverse ? changeNew[i] : changeOld[i];
            byte to = reverse ? changeOld[i] : changeNew[i];
            if (from == to) continue;
            label[p] = to;
            if (from == EMPTY) cropCells++;
            if (to == EMPTY) cropCells--;
            if (from != EMPTY) {
                priorityPoints -= cropPriority[from];
                costUnits -= cropCost[from];
            }
            if (to != EMPTY) {
                priorityPoints += cropPriority[to];
                costUnits += cropCost[to];
            }
            for (int s : footSites[p]) {
                filled[s] += (to != EMPTY ? 1 : 0) - (from != EMPTY ? 1 : 0);
                touch(s);
            }
            for (int s : ringSites[p]) {
                if (from != EMPTY) { count[s * stride + from]--; ringCrops[s]--; }
                if (to != EMPTY) { count[s * stride + to]++; ringCrops[s]++; }
                touch(s);
            }
        }
        refreshTouched();
    }

    private boolean singleMove() {
        int p = cellList[random.nextInt(cellList.length)];
        byte next = random.nextInt(5) < 2 ? EMPTY : (byte) random.nextInt(crops);
        if (next == label[p]) return false;
        change(p, next);
        return true;
    }

    private boolean swapMove() {
        int p = cellList[random.nextInt(cellList.length)];
        int row = p / SIZE + random.nextInt(5) - 2;
        int column = p % SIZE + random.nextInt(5) - 2;
        if (!GreenhouseGrid.inside(row, column)) return false;
        int q = row * SIZE + column;
        if (q == p || label[q] == label[p] || !open[q]) return false;
        change(p, label[q]);
        change(q, label[p]);
        return true;
    }

    /**
     * Exchanges two crop types everywhere. Recipes often mirror each other, so this carries a whole layout over
     * to equivalent ingredients, which single-cell moves cannot do without losing the plan on the way.
     */
    private boolean exchangeMove() {
        if (crops < 2) return false;
        int a = random.nextInt(crops);
        int b = random.nextInt(crops - 1);
        if (b >= a) b++;
        for (int p : cellList) {
            if (label[p] == a) change(p, (byte) b);
            else if (label[p] == b) change(p, (byte) a);
        }
        return changeCount > 0;
    }

    /**
     * Shifts a small rectangle's contents one cell; the vacated edge becomes empty. Periodic patterns need a
     * whole region to move at once to line up with a boundary, which single-cell moves cannot do.
     */
    private boolean shiftMove() {
        int height = 2 + random.nextInt(5);
        int width = 2 + random.nextInt(5);
        int top = random.nextInt(SIZE - height + 1);
        int left = random.nextInt(SIZE - width + 1);
        int direction = random.nextInt(4);
        int dr = direction == 0 ? 1 : direction == 1 ? -1 : 0;
        int dc = direction == 2 ? 1 : direction == 3 ? -1 : 0;
        for (int r = top; r < top + height; r++) {
            for (int c = left; c < left + width; c++) if (!open[r * SIZE + c]) return false;
        }
        for (int r = top; r < top + height; r++) {
            for (int c = left; c < left + width; c++) {
                int sr = r - dr;
                int sc = c - dc;
                boolean inside = sr >= top && sr < top + height && sc >= left && sc < left + width;
                byte next = inside ? label[sr * SIZE + sc] : EMPTY;
                if (next != label[r * SIZE + c]) change(r * SIZE + c, next);
            }
        }
        return changeCount > 0;
    }

    /** Clears one goal site's footprint and fills its ring with the crops its recipe needs. */
    private boolean stampMove() {
        int g = pickGoal();
        int r = goals[g].recipe();
        Recipe recipe = recipes[r];
        int[] sites = classSites[recipeClass[r]];
        if (sites.length == 0) return false;
        return stampSite(sites[random.nextInt(sites.length)], recipe);
    }

    /** Queues the changes that make site {@code s} meet {@code recipe}; false when its ring is too small. */
    private boolean stampSite(int s, Recipe recipe) {
        for (int p : footprint[s]) if (label[p] != EMPTY) change(p, EMPTY);
        int[] around = ring[s];
        if (recipe.emptyRing()) {
            for (int p : around) if (label[p] != EMPTY) change(p, EMPTY);
            return changeCount > 0;
        }
        Arrays.fill(have, 0);
        Arrays.fill(missing, 0);
        for (int p : around) if (label[p] != EMPTY) have[label[p]]++;
        int lacking = 0;
        for (int i = 0; i < recipe.crops().length; i++) {
            int need = Math.max(0, recipe.counts()[i] - have[recipe.crops()[i]]);
            missing[recipe.crops()[i]] = need;
            lacking += need;
        }
        if (lacking == 0) return changeCount > 0;
        int n = around.length;
        System.arraycopy(around, 0, order, 0, n);
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        for (int i = 0; i < n && lacking > 0; i++) {
            int p = order[i];
            byte current = label[p];
            if (current != EMPTY) {
                // Keep every crop the recipe still needs; only surplus ones may be overwritten.
                int required = 0;
                for (int k = 0; k < recipe.crops().length; k++) {
                    if (recipe.crops()[k] == current) required = recipe.counts()[k];
                }
                if (have[current] <= required) continue;
                have[current]--;
            }
            int pick = random.nextInt(lacking);
            int chosen = -1;
            for (int k = 0; k < recipe.crops().length; k++) {
                int crop = recipe.crops()[k];
                if (missing[crop] > pick) { chosen = crop; break; }
                pick -= missing[crop];
            }
            missing[chosen]--;
            lacking--;
            change(p, (byte) chosen);
        }
        return lacking == 0 && changeCount > 0;
    }

    /** Writes the queued changes straight into the labels, for building a start layout. */
    private void commit() {
        for (int i = 0; i < changeCount; i++) label[changeCell[i]] = changeNew[i];
        changeCount = 0;
    }

    /**
     * Lays out the tightest lattice of sites for one goal whose recipe fills its whole ring: anchors one footprint
     * plus one ring cell apart, at a random phase, stamped row by row (up to a fixed goal's site limit).
     * Such a lattice shares every ring cell it can, which annealing from an empty plot finds only by luck, and
     * leaves just the crop types to settle. Returns false when no goal qualifies.
     */
    private boolean seedLattice() {
        int[] eligible = new int[goals.length];
        int eligibleCount = 0;
        for (int g = 0; g < goals.length; g++) {
            Recipe recipe = recipes[goals[g].recipe()];
            int ringSize = (recipe.width() + 2) * (recipe.height() + 2) - recipe.width() * recipe.height();
            if (!recipe.emptyRing() && recipeTotal[goals[g].recipe()] == ringSize) eligible[eligibleCount++] = g;
        }
        if (eligibleCount == 0) return false;
        int g = eligible[random.nextInt(eligibleCount)];
        Recipe recipe = recipes[goals[g].recipe()];
        int cls = recipeClass[goals[g].recipe()];
        int width = recipe.width();
        int height = recipe.height();
        int ringSize = (width + 2) * (height + 2) - width * height;
        int firstRow = 1 + random.nextInt(height + 1);
        int firstColumn = 1 + random.nextInt(width + 1);
        int[] served = new int[siteCount];
        long[] masks = new long[siteCount];
        int placed = 0;
        Arrays.fill(label, EMPTY);
        for (int row = firstRow; row + height < SIZE && placed < goalLimit[g]; row += height + 1) {
            for (int column = firstColumn; column + width < SIZE && placed < goalLimit[g]; column += width + 1) {
                int s = siteAtAnchor[cls][row * SIZE + column];
                if (s < 0 || ring[s].length != ringSize) continue;
                for (int p : ring[s]) label[p] = 0;
                served[placed] = s;
                masks[placed++] = 1L << goals[g].recipe();
            }
        }
        if (placed == 0) return false;
        // Settle the crop types exactly, so the lattice starts out with every one of its sites working.
        byte[] typed = retype(label.clone(), SEED_NODES, run.deadlineNanos() - retypeReserve, served, masks,
            placed, false);
        if (typed == null) return false;
        System.arraycopy(typed, 0, label, 0, CELLS);
        return true;
    }

    private int pickGoal() {
        int unmet = 0;
        for (int g = 0; g < goals.length; g++) if (unmet(g)) unmet++;
        if (unmet == 0 || random.nextInt(4) == 0) return random.nextInt(goals.length);
        int pick = random.nextInt(unmet);
        for (int g = 0; g < goals.length; g++) {
            if (unmet(g) && pick-- == 0) return g;
        }
        return 0;
    }

    /** Whether a goal can still use another site: short of its count and of its site limit. */
    private boolean unmet(int g) {
        return total[g] < goals[g].cap() && candidates[g] < goalLimit[g];
    }

    private boolean propose() {
        changeCount = 0;
        double kind = random.nextDouble();
        boolean moved = kind < EXCHANGE_SHARE ? exchangeMove()
            : kind < EXCHANGE_SHARE + SHIFT_SHARE ? shiftMove()
            : kind < 0.45 ? singleMove() : kind < 0.65 ? swapMove() : stampMove();
        if (!moved) changeCount = 0;
        return moved;
    }

    /** One Metropolis step on the lexicographic value vector; returns whether the layout changed. */
    private boolean step(double temperature, double guidance) {
        boolean hard = temperature < EXCESS_HARD_BELOW;
        iterations++;
        if (!propose()) return false;
        valueVector(before, guidance, hard);
        apply(false);
        valueVector(after, guidance, hard);
        double delta = 0;
        for (int t = 0; t < after.length; t++) {
            if (Math.abs(after[t] - before[t]) > TIE) { delta = after[t] - before[t]; break; }
        }
        if (delta >= 0 || random.nextDouble() < Math.exp(delta / (temperature * UNIT))) {
            noteBest();
            return true;
        }
        apply(true);
        return false;
    }

    // ----- driver -----

    /**
     * Anneals until the deadline, the iteration cap, cancellation or (when enabled) stagnation, then polishes and
     * prunes the best layout. Deterministic for a seed when the iteration cap or a deterministic clock ends it.
     */
    byte[] search(Limits limits) {
        run = limits;
        now = limits.clock().getAsLong();
        searchStart = now;
        lastImprovement = 0;
        stagnated = false;
        consistentRestarts = 0;
        pendingDelivery = false;
        delivered = null;
        retypeReserve = typesMatter ? Math.min((limits.deadlineNanos() - searchStart) / 16, 1_000_000_000L) : 0;
        if (goals.length == 0 || cellList.length == 0 || crops == 0) return bestLabel.clone();
        int round = 0;
        while (!stop()) {
            long elapsed = now - searchStart;
            if (limits.stopOnStagnation() && stagnant(elapsed)) {
                stagnated = true;
                idleNanos = elapsed - lastImprovement;
                break;
            }
            long length = Math.min(ROUND_ITERATIONS, limits.maxIterations() - iterations);
            if (round > 0) {
                double perIteration = elapsed / (double) Math.max(1, iterations);
                long fits = (long) ((limits.deadlineNanos() - retypeReserve - now) / perIteration * 0.85);
                length = Math.min(length, fits);
                if (length < 2_000) break;
            }
            Arrays.fill(roundKey, Long.MIN_VALUE);
            planImproved = false;
            // Rounds cycle: fresh start, reheat the best, fresh start, compact the best, fresh start, a lattice
            // start (a reheat when no goal has a full ring), fresh start, compact the best.
            boolean fresh = round % 2 == 0;
            boolean seeded = false;
            if (fresh) {
                Arrays.fill(label, EMPTY);
                recompute();
                anneal(length, HOT, COLD, GUIDANCE);
            } else {
                if (round % 8 == 5) seeded = seedLattice();
                if (!seeded) System.arraycopy(bestLabel, 0, label, 0, CELLS);
                recompute();
                boolean compact = round % 4 == 3;
                if (seeded) noteBest();
                anneal(length, seeded ? SEED_HOT : compact ? COMPACT_HOT : REHOT,
                    seeded ? SEED_COLD : compact ? COMPACT_COLD : RECOLD, compact || seeded ? 0 : REGUIDANCE);
            }
            if ((fresh || seeded) && worthRetyping()) {
                retypeAndAdopt(roundLabel.clone(), RETYPE_ROUND_NODES, limits.deadlineNanos() - retypeReserve);
            }
            if (planImproved) consistentRestarts = fresh && samePlan(roundKey, bestKey) ? 1 : 0;
            else if (fresh) consistentRestarts = samePlan(roundKey, bestKey) ? consistentRestarts + 1 : 0;
            if (limits.observer() != null) limits.observer().roundEnded(now - searchStart, fresh, roundKey.clone());
            round++;
        }
        if (typesMatter && crops > 0 && goals.length > 0 && !cancelled()) {
            long affordable = Math.max(0, (limits.deadlineNanos() - now) / RETYPE_NANOS_PER_NODE);
            retypeAndAdopt(bestLabel.clone(), Math.min(RETYPE_FINAL_NODES, affordable), limits.deadlineNanos());
        }
        System.arraycopy(bestLabel, 0, label, 0, CELLS);
        recompute();
        polish(Math.max(0, Math.min(20_000, limits.maxIterations() - iterations)));
        System.arraycopy(bestLabel, 0, label, 0, CELLS);
        recompute();
        prune();
        // The last improvement may have come after the latest throttled delivery, or from the prune.
        if (run.onBest() != null && (pendingDelivery || !Arrays.equals(bestLabel, delivered))
            && !Arrays.equals(bestLabel, emptyLabels())) {
            deliver();
        }
        return bestLabel.clone();
    }

    private static byte[] emptyLabels() {
        byte[] none = new byte[CELLS];
        Arrays.fill(none, EMPTY);
        return none;
    }

    /**
     * Whether the search is unlikely to improve any more: it has run at least {@link #MIN_RUNTIME_NANOS}, the
     * latest best plan has stood for {@link #MIN_IDLE_NANOS} and {@link #IDLE_FACTOR} times the time it took
     * to find, and the last {@link #MIN_CONSISTENT_RESTARTS} fresh restarts all ended on that plan.
     * Only asked between rounds.
     */
    private boolean stagnant(long elapsed) {
        return elapsed >= MIN_RUNTIME_NANOS && consistentRestarts >= MIN_CONSISTENT_RESTARTS
            && elapsed - lastImprovement >= Math.max(MIN_IDLE_NANOS, (long) (IDLE_FACTOR * lastImprovement));
    }

    /** True when {@link #search} ended because of the stagnation rule. */
    boolean stagnated() { return stagnated; }

    /** How long the best plan had stood when the stagnation rule stopped the run. */
    long idleNanos() { return idleNanos; }

    /** Fresh restarts in a row that ended on the best plan, as of the end of the search. */
    int consistentRestarts() { return consistentRestarts; }

    private boolean stop() {
        now = run.clock().getAsLong();
        if (pendingDelivery && (delivered == null || now - lastDelivery >= PREVIEW_INTERVAL_NANOS)) deliver();
        return iterations >= run.maxIterations() || now >= run.deadlineNanos() - retypeReserve || cancelled();
    }

    private boolean cancelled() {
        return run.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    /** Whether the exact search must give up: cancelled, or past {@code deadline}. Reads the clock. */
    private boolean retypeMustStop(long deadline) {
        if (run == null) return false;
        now = run.clock().getAsLong();
        return now >= deadline || cancelled();
    }

    /** Whether the latest restart reached the best plan's goals and a cost close enough to be worth re-typing. */
    private boolean worthRetyping() {
        if (!typesMatter || roundKey[0] == Long.MIN_VALUE) return false;
        int costIndex = LEAD + tiers + 1;
        for (int i = 0; i < costIndex; i++) if (roundKey[i] != bestKey[i]) return false;
        long roundCost = -roundKey[costIndex];
        long bestCost = -bestKey[costIndex];
        return roundCost <= bestCost + bestCost / 4;
    }

    /** Re-types {@code layout} exactly and keeps the result when it beats the best plan. */
    private void retypeAndAdopt(byte[] layout, long nodeLimit, long deadline) {
        byte[] retyped = retype(layout, nodeLimit, deadline);
        if (retyped == null) return;
        System.arraycopy(retyped, 0, label, 0, CELLS);
        recompute();
        noteBest();
    }

    /** Hands the best plan to the preview consumer, if there is one. */
    private void deliver() {
        pendingDelivery = false;
        if (run.onBest() == null) return;
        lastDelivery = now;
        delivered = bestLabel.clone();
        run.onBest().accept(delivered.clone());
    }

    private void anneal(long length, double hot, double cold, double guidance) {
        double ratio = Math.pow(cold / hot, 1.0 / Math.max(1, length));
        double temperature = hot;
        for (long i = 0; i < length; i++) {
            double progress = i / (double) length;
            step(temperature, guidance * Math.max(0, 1 - 1.4 * progress));
            temperature *= ratio;
            if ((i & CHECK_MASK) == 0 && stop()) return;
        }
    }

    private void polish(long length) {
        for (long i = 0; i < length; i++) {
            step(1e-9, 0);
            if ((i & CHECK_MASK) == 0 && stop()) return;
        }
    }

    /** Empties every crop the key does not need, so a plan carries no stray ingredients. */
    private void prune() {
        boolean removed = true;
        for (int pass = 0; pass < 4 && removed; pass++) {
            removed = false;
            for (int p : cellList) {
                if (label[p] == EMPTY) continue;
                long[] was = new long[key.length];
                fillKey(was);
                changeCount = 0;
                change(p, EMPTY);
                apply(false);
                fillKey(key);
                if (better(key, was)) removed = true; else apply(true);
            }
        }
        snapshotBest();
    }

    // ----- exact re-typing -----

    /**
     * Keeps every site that serves a goal exactly as satisfied as it is, but searches (depth first, cheapest
     * crop first, with a bound from the incidences each crop still owes) for the assignment of crop types to
     * the cells around those sites that has the lowest cost, then the fewest priority points. The set of
     * planted cells never changes. Returns the better labels, or null when none was found within
     * {@code nodeLimit} search nodes. Annealing finds good footprints and counts; this finds how few expensive
     * crops those footprints really need, which single-cell moves reach only by luck because every site's count
     * is an exact balance.
     */
    byte[] retype(byte[] start, long nodeLimit, long deadline) {
        if (nodeLimit <= 0) return null;
        System.arraycopy(start, 0, label, 0, CELLS);
        recompute();
        long goalMask = 0;
        for (Aim goal : goals) goalMask |= 1L << goal.recipe();
        int[] served = new int[siteCount];
        long[] masks = new long[siteCount];
        int servedCount = 0;
        for (int s = 0; s < siteCount; s++) {
            if ((effective[s] & goalMask) == 0) continue;
            served[servedCount] = s;
            masks[servedCount++] = effective[s] & goalMask;
        }
        return retype(start, nodeLimit, deadline, served, masks, servedCount, true);
    }

    /**
     * The search itself, for {@code served} sites that must meet the recipes in {@code masks}. The labels in
     * {@code start} give the planted cells (the engine's {@link #label} is overwritten with them).
     * With {@code mustImprove} only an assignment that beats the current cost and priority points counts;
     * otherwise the first assignment that works does, and better ones replace it. The search ends at
     * {@code nodeLimit} nodes, at {@code deadline}, or when cancelled, and then returns the best assignment
     * found so far (always a complete, valid one) or null.
     */
    private byte[] retype(byte[] start, long nodeLimit, long deadline, int[] served, long[] masks, int servedCount,
                          boolean mustImprove) {
        if (nodeLimit <= 0 || servedCount == 0) return null;
        System.arraycopy(start, 0, label, 0, CELLS);
        int[] protectedIndex = new int[siteCount];
        Arrays.fill(protectedIndex, -1);
        for (int i = 0; i < servedCount; i++) protectedIndex[served[i]] = i;
        int[][] need = new int[servedCount][stride];
        for (int i = 0; i < servedCount; i++) {
            for (int r = 0; r < recipes.length; r++) {
                if ((masks[i] >> r & 1L) == 0) continue;
                Recipe recipe = recipes[r];
                if (recipe.emptyRing()) return null;
                for (int j = 0; j < recipe.crops().length; j++) {
                    need[i][recipe.crops()[j]] = Math.max(need[i][recipe.crops()[j]], recipe.counts()[j]);
                }
            }
        }
        boolean[] free = new boolean[CELLS];
        int[] cells = new int[CELLS];
        int cellCount = 0;
        for (int p = 0; p < CELLS; p++) {
            if (label[p] == EMPTY) continue;
            for (int s : ringSites[p]) {
                if (protectedIndex[s] >= 0) {
                    free[p] = true;
                    cells[cellCount++] = p;
                    break;
                }
            }
        }
        if (cellCount == 0) return null;
        int[][] have = new int[servedCount][stride];
        int[] left = new int[servedCount];
        for (int i = 0; i < servedCount; i++) {
            for (int p : ring[served[i]]) {
                if (label[p] == EMPTY) continue;
                if (free[p]) left[i]++; else have[i][label[p]]++;
            }
        }
        int[][] feeds = new int[cellCount][];
        for (int c = 0; c < cellCount; c++) {
            int n = 0;
            int[] tmp = new int[ringSites[cells[c]].length];
            for (int s : ringSites[cells[c]]) if (protectedIndex[s] >= 0) tmp[n++] = protectedIndex[s];
            feeds[c] = Arrays.copyOf(tmp, n);
        }
        Retyper search = new Retyper(cells, cellCount, feeds, need, have, left, nodeLimit, deadline);
        if (mustImprove) {
            // Only the free cells are re-typed, so only their share of the cost and points can be beaten.
            long freeCost = 0;
            int freePriority = 0;
            for (int c = 0; c < cellCount; c++) {
                freeCost += cropCost[label[cells[c]]];
                freePriority += cropPriority[label[cells[c]]];
            }
            search.bestCost = freeCost;
            search.bestPriority = freePriority;
        } else {
            search.bestCost = Long.MAX_VALUE / 2;
            search.bestPriority = Integer.MAX_VALUE;
        }
        search.dfs(0, 0, 0);
        if (!search.found) return null;
        byte[] result = start.clone();
        for (int c = 0; c < cellCount; c++) result[cells[c]] = (byte) search.bestType[c];
        return result;
    }

    /** The depth-first search behind {@link #retype}; all state is local to one call. */
    private final class Retyper {
        final int[] cells;
        final int cellCount;
        final int[][] feeds;
        final int[][] need;
        final int[][] have;
        final int[] left;
        final int[] type;
        final long nodeLimit;
        final long deadline;
        boolean stopped;
        long nodes;
        long bestCost;
        int bestPriority;
        final int[] bestType;
        boolean found;
        final int[] owed;
        final int[] bucket = new int[MAX_RING + 2];

        Retyper(int[] cells, int cellCount, int[][] feeds, int[][] need, int[][] have, int[] left, long nodeLimit,
                long deadline) {
            this.cells = cells;
            this.cellCount = cellCount;
            this.feeds = feeds;
            this.need = need;
            this.have = have;
            this.left = left;
            this.nodeLimit = nodeLimit;
            this.deadline = deadline;
            this.type = new int[cellCount];
            this.bestType = new int[cellCount];
            this.owed = new int[stride];
        }

        void dfs(int index, long cost, int priority) {
            if (stopped || nodes++ >= nodeLimit) return;
            if ((nodes & (RETYPE_CHECK_NODES - 1)) == 0 && retypeMustStop(deadline)) {
                stopped = true;
                return;
            }
            if (index == cellCount) {
                if (cost < bestCost || (cost == bestCost && priority < bestPriority)) {
                    bestCost = cost;
                    bestPriority = priority;
                    System.arraycopy(type, 0, bestType, 0, cellCount);
                    found = true;
                }
                return;
            }
            for (int t : typeOrder) {
                long nextCost = cost + cropCost[t];
                int nextPriority = priority + cropPriority[t];
                if (nextCost > bestCost || (nextCost == bestCost && nextPriority >= bestPriority)) continue;
                boolean feasible = true;
                for (int site : feeds[index]) {
                    have[site][t]++;
                    left[site]--;
                }
                for (int site : feeds[index]) {
                    for (int k = 0; k < stride && feasible; k++) {
                        if (need[site][k] > 0 && have[site][k] + left[site] < need[site][k]) feasible = false;
                    }
                    if (!feasible) break;
                }
                if (feasible) {
                    long bound = nextCost + remainingCost(index + 1);
                    if (bound < bestCost || (bound == bestCost && nextPriority < bestPriority)) {
                        type[index] = t;
                        dfs(index + 1, nextCost, nextPriority);
                    }
                }
                for (int site : feeds[index]) {
                    have[site][t]--;
                    left[site]++;
                }
                if (stopped || nodes >= nodeLimit) return;
            }
        }

        /** Fewest cells that could still cover what each priced crop is owed, at the dearest price they ask. */
        long remainingCost(int from) {
            long total = 0;
            for (int k = 0; k < stride; k++) {
                if (cropCost[k] == 0) continue;
                int owedTotal = 0;
                for (int site = 0; site < need.length; site++) owedTotal += Math.max(0, need[site][k] - have[site][k]);
                if (owedTotal == 0) continue;
                Arrays.fill(bucket, 0);
                for (int c = from; c < cellCount; c++) {
                    int feed = 0;
                    for (int site : feeds[c]) if (need[site][k] > have[site][k]) feed++;
                    bucket[Math.min(feed, bucket.length - 1)]++;
                }
                int covered = 0;
                int used = 0;
                for (int feed = bucket.length - 1; feed > 0 && covered < owedTotal; feed--) {
                    for (int n = bucket[feed]; n > 0 && covered < owedTotal; n--) {
                        covered += feed;
                        used++;
                    }
                }
                if (covered < owedTotal) return Long.MAX_VALUE / 4;
                total += cropCost[k] * used;
            }
            return total;
        }
    }

    // ----- read-back -----

    /** Loads a layout and reports what grows where, for building results. */
    Evaluation evaluate(byte[] labels) {
        System.arraycopy(labels, 0, label, 0, CELLS);
        recompute();
        long goalMask = 0;
        for (Aim goal : goals) goalMask |= 1L << goal.recipe();
        List<Site> hits = new ArrayList<>();
        double[] exact = new double[goals.length];
        int[] sitesPerGoal = new int[goals.length];
        int unrequested = 0;
        for (int s = 0; s < siteCount; s++) {
            long mask = effective[s];
            if (mask == 0) continue;
            if ((mask & goalMask) == 0) {
                unrequested++;
                continue;
            }
            hits.add(new Site(footprint[s].clone(), mask));
            double sum = chanceSum(mask);
            for (int g = 0; g < goals.length; g++) {
                if ((mask >> goals[g].recipe() & 1L) == 0) continue;
                exact[g] += recipeWeight[goals[g].recipe()] / sum;
                sitesPerGoal[g]++;
            }
        }
        return new Evaluation(exact, sitesPerGoal, hits, unrequested, ambiguous);
    }
}
