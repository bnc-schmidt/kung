package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseSolver.Options;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveRequest;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Shortcuts over the one full-arity solver entry points; the planner itself only needs those. */
final class GreenhouseSolveSupport {
    private GreenhouseSolveSupport() { }

    /** Real clock, no early stop. */
    static Options standard(long maxIterations, BooleanSupplier cancelled, Consumer<SolveResult> onBest) {
        return new Options(maxIterations, cancelled, System::nanoTime, false, null, onBest);
    }

    static Options options(long maxIterations, BooleanSupplier cancelled, LongSupplier clock, boolean stopOnStagnation,
                           GreenhouseSearch.Observer observer) {
        return new Options(maxIterations, cancelled, clock, stopOnStagnation, observer, null);
    }

    /** Time-budget run: uses the whole budget. */
    static SolveResult solve(SolveRequest request, long budgetMillis, long seed) {
        return solve(request, budgetMillis, seed, Long.MAX_VALUE, () -> false);
    }

    static SolveResult solve(SolveRequest request, long budgetMillis, long seed, long maxIterations,
                             BooleanSupplier cancelled) {
        return GreenhouseSolver.solve(request, budgetMillis, seed, standard(maxIterations, cancelled, null));
    }

    static CompletableFuture<SolveResult> solveAsync(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled) {
        return solveAsync(request, budgetMillis, seed, cancelled, null);
    }

    static CompletableFuture<SolveResult> solveAsync(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled, Consumer<SolveResult> onBest) {
        return GreenhouseSolver.solveAsync(request, budgetMillis, seed, cancelled, onBest, false);
    }
}
