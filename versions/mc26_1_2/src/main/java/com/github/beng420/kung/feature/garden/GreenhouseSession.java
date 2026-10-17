package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseCosts.CostMode;
import com.github.beng420.kung.feature.garden.GreenhouseCosts.Quote;
import com.github.beng420.kung.runtime.KungPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Everything the Greenhouse planner remembers while Minecraft runs, owned by the client thread: the settings,
 * the grid on show, the last result, the status line and the running solve. The screen is only a view onto
 * this, so closing it neither loses the layout nor cancels a solve; Stop, the client stopping or a worker that
 * never answers do. The worker callback holds this session, never a screen.
 *
 * <p>Plain Java: the game is reached through {@link Host}, the solver through {@link Starter} and time through
 * a clock, so the state machine is unit-tested without threads or Minecraft.
 */
final class GreenhouseSession {
    enum Tone { MUTED, SUCCESS, WARNING, ERROR }

    /** The few things that need the running game. */
    interface Host {
        /** Runs the task on the client thread; called from the solver's worker. */
        void runOnClient(Runnable task);

        /** A short message for a solve that ended while no planner screen was open. */
        void toast(String message);

        void warn(String message, Throwable cause);
    }

    /**
     * Starts a solve on a worker. {@code onBest} is called on that worker with a full snapshot whenever the best
     * layout improves; {@code stopEarly} lets the search end before the budget once it stopped improving.
     */
    @FunctionalInterface
    interface Starter {
        CompletableFuture<GreenhouseSolver.SolveResult> start(GreenhouseSolver.SolveRequest request, long budgetMillis,
                                                              long seed, BooleanSupplier cancelled,
                                                              Consumer<GreenhouseSolver.SolveResult> onBest,
                                                              boolean stopEarly);
    }

    static final String HINT = "Add mutation targets, then press Solve.";
    /** Shown instead of changing the grid while a solve runs: its result would replace the change. */
    static final String BUSY = "Solver is running: stop it first";
    /** A second click this soon after Solve is a double-click on the same rectangle, not a Stop. */
    static final long STOP_GUARD_NANOS = 400_000_000L;
    /** A worker that still has not answered this long after Stop is given up on, so Solve works again. */
    static final long ABANDON_NANOS = 5_000_000_000L;
    private static GreenhouseSession instance;

    private final Path file;
    private final Host host;
    private final Starter starter;
    private final LongSupplier clock;
    private final GreenhouseState state;
    /** Where the Bazaar prices come from; null in tests of the rest, then every Bazaar cost is an estimate. */
    private final GreenhousePrices prices;
    private GreenhousePlan plan;
    private GreenhouseSummary summary;
    /** The plan and result on show when the running solve started; previews are undone back to these. */
    private GreenhousePlan runPlan;
    private GreenhouseSummary runSummary;
    /** The plan the last preview put on show; a live preview is never saved and the final result replaces it. */
    private GreenhousePlan previewPlan;
    private boolean previewing;
    /** The weighting and quotes the running solve was started with; switching the mode mid-run changes neither. */
    private CostMode runMode = CostMode.BAZAAR;
    private Map<String, Quote> runQuotes = Map.of();
    private Map<String, Quote> quotesCache = Map.of();
    private CostMode quotesMode;
    private int quotesVersion = Integer.MIN_VALUE;
    private String priceLine = "";
    private long priceLineKey = Long.MIN_VALUE;
    private String status = HINT;
    private Tone tone = Tone.MUTED;
    /** The status is still the run's own text ("Solving...", "Searching..."): previews may update it, nothing else. */
    private boolean statusIsRunText;
    private boolean viewing;
    private boolean running;
    /** Stop was pressed: the solver is winding down and its best layout will still be applied. */
    private boolean stopping;
    private int runId;
    private long runStartNanos;
    private long stopRequestedNanos;
    private long budgetMillis;
    private AtomicBoolean stopFlag = new AtomicBoolean();
    private boolean saveFailureLogged;

    GreenhouseSession(Path file, Host host, Starter starter, LongSupplier nanoClock) {
        this(file, host, starter, nanoClock, null);
    }

    GreenhouseSession(Path file, Host host, Starter starter, LongSupplier nanoClock, GreenhousePrices prices) {
        this.file = file;
        this.host = host;
        this.starter = starter;
        this.clock = nanoClock;
        this.prices = prices;
        state = GreenhouseState.load(file);
        GreenhouseGrid layout = state.layout();
        plan = GreenhousePlan.of(layout == null ? new GreenhouseGrid() : layout);
        summary = layout == null ? null : state.summary();
        if (layout != null && layout.filled() > 0) say("Restored last layout", Tone.MUTED);
    }

    /** The session of this game run, created on first use. */
    static GreenhouseSession get() {
        if (instance == null) {
            instance = new GreenhouseSession(KungPaths.fileLayout().settingsDirectory().resolve("greenhouse.json"),
                GreenhouseClientHost.INSTANCE, GreenhouseSolver::solveAsync, System::nanoTime, GreenhousePrices.get());
            GreenhouseClientHost.install(instance);
        }
        return instance;
    }

    GreenhouseState state() { return state; }
    GreenhousePlan plan() { return plan; }

    /** The last solve's lines, or null when none is known (a pasted or cleared grid has none). */
    GreenhouseSummary summary() { return summary; }

    String status() { return status; }
    Tone tone() { return tone; }

    void say(String message, Tone tone) {
        status = message;
        this.tone = tone;
        statusIsRunText = false;
    }

    /** The running solve's own status text, which a later message from anywhere else replaces for good. */
    private void sayRun(String message) {
        say(message, Tone.MUTED);
        statusIsRunText = true;
    }

    /** Like {@link #say} for a change to the shown grid, which ignores the mask: warns if crops now sit on locked cells. */
    void sayAboutGrid(String message, Tone tone) {
        int locked = state.cropsOnLockedCells(plan.grid());
        if (locked == 0) {
            say(message, tone);
        } else {
            say(message + ". " + locked + (locked == 1 ? " crop sits" : " crops sit") + " on locked cells", Tone.WARNING);
        }
    }

    // --- prices ------------------------------------------------------------------------------------------------

    /** Starts a Bazaar fetch when the prices are missing or old; never blocks (a no-op without a price source). */
    void refreshPrices() {
        if (prices != null) prices.refreshIfStale();
    }

    /** Quotes for the weighting chosen now (what the next solve would use), rebuilt only when the prices or mode change. */
    Map<String, Quote> quotes() {
        CostMode mode = state.costMode();
        int version = prices == null ? -1 : prices.version();
        if (mode != quotesMode || version != quotesVersion) {
            quotesMode = mode;
            quotesVersion = version;
            quotesCache = GreenhouseCosts.quotes(mode, prices);
        }
        return quotesCache;
    }

    /** "Bazaar prices: fresh 2 min ago", "Bazaar offline: using estimates" or "Ironman: by rarity"; cached per change. */
    String priceLine() {
        CostMode mode = state.costMode();
        GreenhousePrices.Status status = prices == null ? null : prices.status();
        long age = prices == null ? -1 : prices.ageMillis();
        boolean refreshing = prices != null && prices.refreshing();
        long key = mode.ordinal() | (status == null ? 15L : status.ordinal()) << 4 | (refreshing ? 1L : 0L) << 8
            | (age < 0 ? 0L : age / 15_000L + 1) << 9;
        if (key != priceLineKey) {
            priceLineKey = key;
            priceLine = GreenhousePriceLine.of(mode, status, age, refreshing);
        }
        return priceLine;
    }

    // --- who is looking ----------------------------------------------------------------------------------------

    void viewerOpened() { viewing = true; }
    /** A drag that was under way when the screen closed must not go on into the next screen. */
    void viewerClosed() {
        viewing = false;
        state.endPaint();
    }
    boolean viewing() { return viewing; }

    // --- the grid on show ---------------------------------------------------------------------------------------

    /**
     * Replaces the shown grid with {@code op} applied to it. The last result's lines stay when the grid is only
     * turned or mirrored, and go when it is cleared. While a solve runs nothing changes and the reason comes back
     * (also in the status line), since the solve's layout would replace the change; null when it was done.
     */
    String change(UnaryOperator<GreenhouseGrid> op, boolean keepResult, String message) {
        if (running) return refuse();
        replace(op.apply(plan.grid()), keepResult ? summary : null);
        sayAboutGrid(message, Tone.MUTED);
        persist();
        return null;
    }

    /** Shows the layout in pasted text (a Kung code or a Sky Mutations link); null when it worked, else the reason (also in the status line). */
    String loadLayout(String text) {
        if (running) return refuse();
        if (text == null || text.isBlank()) return fail("No layout link to load");
        if (text.length() > GreenhouseCode.MAX_TEXT_LENGTH) return fail("Clipboard text is too large for a layout link");
        var decoded = GreenhouseCode.decode(text);
        if (decoded.isEmpty()) return fail("Invalid layout code");
        replace(decoded.get(), null);
        int crops = decoded.get().filled();
        sayAboutGrid("Layout loaded: " + crops + (crops == 1 ? " crop" : " crops"), Tone.SUCCESS);
        persist();
        return null;
    }

    private String refuse() {
        say(BUSY, Tone.WARNING);
        return BUSY;
    }

    private String fail(String message) {
        say(message, Tone.ERROR);
        return message;
    }

    /** Shows a grid and its result without any check; callers decide whether that is allowed (not while solving). */
    void replace(GreenhouseGrid grid, GreenhouseSummary result) {
        plan = GreenhousePlan.of(grid);
        summary = result;
        state.setLayout(grid);
        state.setSummary(result);
    }

    // --- solving -----------------------------------------------------------------------------------------------

    boolean running() { return running; }
    boolean stopping() { return stopping; }
    long budgetMillis() { return budgetMillis; }

    /** Milliseconds since the running solve started. */
    long elapsedMillis() { return (clock.getAsLong() - runStartNanos) / 1_000_000L; }

    void solve() {
        if (running) return;
        if (state.targets().isEmpty()) {
            say("Add a mutation target first", Tone.WARNING);
            return;
        }
        int id = ++runId;
        AtomicBoolean stop = new AtomicBoolean();
        stopFlag = stop;
        running = true;
        stopping = false;
        runStartNanos = clock.getAsLong();
        budgetMillis = state.seconds() * 1000L;
        runPlan = plan;
        runSummary = summary;
        previewPlan = null;
        previewing = false;
        refreshPrices();
        runMode = state.costMode();
        runQuotes = GreenhouseCosts.quotes(runMode, prices);
        Map<String, Double> costs = new LinkedHashMap<>();
        runQuotes.forEach((crop, quote) -> costs.put(crop, quote.cost()));
        sayRun("Solving...");
        persist();
        CompletableFuture<GreenhouseSolver.SolveResult> future;
        try {
            future = Objects.requireNonNull(starter.start(state.request().withCosts(costs), budgetMillis, runStartNanos,
                stop::get, best -> host.runOnClient(() -> preview(id, best)), state.stopEarly()));
        } catch (Throwable failure) {
            // The worker never started (no thread, out of memory): without this the run would look live until Stop.
            abandon();
            say("Solver failed: " + GreenhouseSolver.describe(failure), Tone.ERROR);
            return;
        }
        future.whenComplete((solved, failure) -> host.runOnClient(() -> finish(id, solved, failure)));
    }

    /**
     * Asks the worker to finish now; it still returns its best layout, which {@link #finish} applies. Ignored just
     * after Solve (the buttons share one rectangle). A second Stop long after the first gives up on the worker.
     */
    void stop() {
        if (!running) return;
        long now = clock.getAsLong();
        if (stopping) {
            if (now - stopRequestedNanos >= ABANDON_NANOS) {
                abandon();
                say("Solve abandoned: the solver did not answer", Tone.WARNING);
            }
            return;
        }
        if (now - runStartNanos < STOP_GUARD_NANOS) return;
        stopping = true;
        stopRequestedNanos = now;
        stopFlag.set(true);
        say("Stopping...", Tone.MUTED);
    }

    /** The client is closing: the worker is told to end and whatever it returns is ignored. */
    void shutdown() {
        abandon();
        persist();
    }

    private void abandon() {
        if (!running) return;
        stopFlag.set(true);
        running = false;
        stopping = false;
        runId++;
        revertPreview();
    }

    /**
     * A snapshot of the best layout so far, from the worker through the client thread. It becomes the shown grid
     * and result lines but is not saved, and an abandoned or already finished run's snapshot is ignored. Edits
     * are refused while solving, so the display cannot have been changed by anyone else.
     */
    private void preview(int id, GreenhouseSolver.SolveResult best) {
        if (id != runId || !running) return;
        GreenhouseSummary seen = GreenhouseSummary.of(best, runMode, runQuotes);
        plan = GreenhousePlan.of(best.grid());
        previewPlan = plan;
        summary = seen;
        previewing = true;
        // "Stopping..." stays on the status line while the last snapshots still arrive, and so does any message
        // the player's own clicks put there (a snapshot four times a second must not wipe it).
        if (!stopping && statusIsRunText) {
            String cost = best.unitCosts().isEmpty() ? "" : ", cost " + GreenhouseCosts.formatTotal(best.totalCost(), runMode);
            sayRun("Searching... best so far: " + (seen.goals().isEmpty() ? "no goals" : seen.firstLine()) + cost);
        }
    }

    /** Puts back the grid and result a run started from, when it ends without a result of its own. */
    private void revertPreview() {
        if (!previewing) return;
        plan = runPlan;
        summary = runSummary;
        previewing = false;
    }

    /** On the client thread. A run that was abandoned or replaced is ignored; any other ends here and, with no screen open, toasts. */
    private void finish(int id, GreenhouseSolver.SolveResult solved, Throwable failure) {
        if (id != runId || !running) return;
        boolean stopped = stopping;
        running = false;
        stopping = false;
        String message;
        if (failure != null || solved == null) {
            message = "Solver failed: " + (failure == null ? "no result" : GreenhouseSolver.describe(failure));
            revertPreview();
            say(message, Tone.ERROR);
        } else if (plan != runPlan && plan != previewPlan) {
            // The edits are refused while solving, so this is a guard: never overwrite what the player is looking at.
            message = "Finished, not applied: the layout changed meanwhile";
            say(message, Tone.WARNING);
        } else {
            GreenhouseSummary made = GreenhouseSummary.of(solved, runMode, runQuotes);
            previewing = false;
            replace(solved.grid(), made);
            sayAboutGrid(made.headline(stopped, solved.stoppedEarly()), made.allMet() ? Tone.SUCCESS : Tone.WARNING);
            message = (stopped ? "Stopped: " : "Finished: ") + (made.goals().isEmpty() ? "no goals" : made.firstLine());
        }
        persist();
        if (needsToast(viewing)) host.toast(message);
    }

    /** A run that ended deserves a toast only when nobody is looking at the planner. */
    static boolean needsToast(boolean viewing) { return !viewing; }

    // --- saving ------------------------------------------------------------------------------------------------

    void persist() {
        try {
            state.save(file);
        } catch (IOException exception) {
            if (!saveFailureLogged) {
                saveFailureLogged = true;
                host.warn("Could not save Kung's Greenhouse settings.", exception);
            }
        }
    }
}
