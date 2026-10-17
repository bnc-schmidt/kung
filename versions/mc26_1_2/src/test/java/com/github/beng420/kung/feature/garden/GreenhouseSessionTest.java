package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSession.Tone;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveRequest;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GreenhouseSessionTest {
    private static final long MILLI = 1_000_000L;
    private static SolveResult solved;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** A real, small solve, made once: the session only needs a result to apply. */
    private static synchronized SolveResult solved() {
        if (solved == null) {
            solved = GreenhouseSolver.solve(SolveRequest.of(GreenhouseSolver.Goal.fixed("Dustgrain", 6)), 1_000, 1);
        }
        return solved;
    }

    /** A session whose worker is a future the test completes, whose client thread is the caller and whose clock it moves. */
    private static final class Fixture implements GreenhouseSession.Host {
        final List<String> toasts = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final AtomicLong clock = new AtomicLong(5_000L * MILLI);
        final GreenhouseSession session;
        CompletableFuture<SolveResult> worker;
        /** When set, starting the worker throws it; when null and {@code startNothing}, it returns no future. */
        Throwable startFailure;
        boolean startNothing;
        BooleanSupplier cancelled;
        SolveRequest request;
        long budget;
        int starts;

        Fixture(Path file) {
            session = new GreenhouseSession(file, this, this::start, clock::get);
        }

        private CompletableFuture<SolveResult> start(SolveRequest request, long budgetMillis, long seed,
                                                     BooleanSupplier cancelled) {
            starts++;
            if (startFailure instanceof RuntimeException failure) throw failure;
            if (startFailure instanceof Error failure) throw failure;
            if (startNothing) return null;
            this.request = request;
            this.budget = budgetMillis;
            this.cancelled = cancelled;
            worker = new CompletableFuture<>();
            return worker;
        }

        @Override public void runOnClient(Runnable task) { task.run(); }
        @Override public void toast(String message) { toasts.add(message); }
        @Override public void warn(String message, Throwable cause) { warnings.add(message); }

        void advance(long millis) { clock.addAndGet(millis * MILLI); }

        Fixture solving() {
            session.state().add("Dustgrain");
            session.state().setCount(0, 6);
            session.solve();
            return this;
        }
    }

    private Path file() { return temporary.getRoot().toPath().resolve("kung").resolve("greenhouse.json"); }

    @Test
    public void aFinishedSolveShowsItsLayoutSummaryAndStatusAndIsSaved() {
        Fixture fixture = new Fixture(file()).solving();

        assertTrue(fixture.session.running());
        assertEquals(1, fixture.starts);
        assertEquals(30_000L, fixture.budget);
        assertEquals(1, fixture.request.goals().size());
        assertEquals("Solving...", fixture.session.status());
        fixture.worker.complete(solved());

        assertFalse(fixture.session.running());
        assertEquals(solved().grid(), fixture.session.plan().grid());
        assertNotNull(fixture.session.summary());
        assertTrue(fixture.session.summary().firstLine(), fixture.session.summary().firstLine().startsWith("Dustgrain "));
        assertTrue(fixture.session.status(), fixture.session.status().startsWith("Solved in "));
        assertEquals(Tone.SUCCESS, fixture.session.tone());
        assertTrue(Files.isRegularFile(file()));
    }

    @Test
    public void aRestartRestoresTheLayoutSummaryAndSettings() {
        Fixture first = new Fixture(file()).solving();
        first.worker.complete(solved());
        first.session.state().setSeconds(12);
        first.session.persist();

        Fixture second = new Fixture(file());

        assertEquals(first.session.plan().grid(), second.session.plan().grid());
        assertEquals(first.session.summary(), second.session.summary());
        assertEquals("Restored last layout", second.session.status());
        assertEquals(Tone.MUTED, second.session.tone());
        assertEquals(12, second.session.state().seconds());
        assertEquals(1, second.session.state().targets().size());
        assertFalse(second.session.running());
        assertEquals(first.session.plan().crops(), second.session.plan().crops());
    }

    @Test
    public void closingTheScreenDoesNotCancelAndTheFinishToastsOnlyWhenNobodyIsLooking() {
        Fixture fixture = new Fixture(file());
        fixture.session.viewerOpened();
        fixture.solving();

        fixture.session.viewerClosed();
        assertTrue(fixture.session.running());
        assertFalse(fixture.cancelled.getAsBoolean());
        fixture.worker.complete(solved());

        assertEquals(1, fixture.toasts.size());
        assertTrue(fixture.toasts.get(0), fixture.toasts.get(0).startsWith("Finished: Dustgrain "));

        Fixture watched = new Fixture(temporary.getRoot().toPath().resolve("other.json"));
        watched.session.viewerOpened();
        watched.solving();
        watched.worker.complete(solved());

        assertTrue(watched.toasts.isEmpty());
        assertTrue(GreenhouseSession.needsToast(false));
        assertFalse(GreenhouseSession.needsToast(true));
    }

    @Test
    public void reopeningDuringARunSeesItRunning() {
        Fixture fixture = new Fixture(file());
        fixture.session.viewerOpened();
        fixture.solving();
        fixture.session.viewerClosed();
        fixture.advance(1_500);
        fixture.session.viewerOpened();

        assertTrue(fixture.session.running());
        assertEquals(1_500L, fixture.session.elapsedMillis());
        assertEquals(30_000L, fixture.session.budgetMillis());
        assertEquals(1, fixture.starts);
    }

    @Test
    public void stopIsIgnoredJustAfterSolveThenAsksForTheBestLayoutWhichIsStillApplied() {
        Fixture fixture = new Fixture(file()).solving();

        fixture.session.stop();
        assertFalse(fixture.cancelled.getAsBoolean());
        assertFalse(fixture.session.stopping());
        fixture.advance(399);
        fixture.session.stop();
        assertFalse(fixture.session.stopping());
        fixture.advance(2);
        fixture.session.stop();

        assertTrue(fixture.session.stopping());
        assertTrue(fixture.cancelled.getAsBoolean());
        assertEquals("Stopping...", fixture.session.status());
        assertTrue(fixture.session.running());
        fixture.worker.complete(solved());

        assertFalse(fixture.session.running());
        assertFalse(fixture.session.stopping());
        assertEquals(solved().grid(), fixture.session.plan().grid());
        assertTrue(fixture.session.status(), fixture.session.status().startsWith("Stopped after "));
        assertTrue(fixture.toasts.get(0), fixture.toasts.get(0).startsWith("Stopped: "));
    }

    @Test
    public void aSolveThatConvergedStopsEarlyWithoutClaimingTheUserStoppedIt() {
        Fixture fixture = new Fixture(file()).solving();
        SolveResult real = solved();

        fixture.worker.complete(new SolveResult(real.grid(), real.goals(), real.targets(), real.ingredients(),
            real.ambiguousTargets(), real.unrequestedSites(), real.soils(), real.elapsedMillis(), real.notes(), true));

        assertFalse(fixture.session.running());
        assertTrue(fixture.session.status(), fixture.session.status().startsWith("Stopped early: converged after "));
        assertEquals(1, fixture.toasts.size());
        assertTrue(fixture.toasts.get(0), fixture.toasts.get(0).startsWith("Finished: "));
        assertFalse(fixture.toasts.get(0).contains("Stopped"));
    }

    @Test
    public void aRunThatIsAbandonedOrWasShutDownIgnoresItsLateResult() {
        Fixture fixture = new Fixture(file()).solving();
        String before = fixture.session.status();

        fixture.session.shutdown();
        assertTrue(fixture.cancelled.getAsBoolean());
        assertFalse(fixture.session.running());
        fixture.worker.complete(solved());

        assertNull(fixture.session.summary());
        assertEquals(before, fixture.session.status());
        assertTrue(fixture.toasts.isEmpty());

        Fixture stuck = new Fixture(temporary.getRoot().toPath().resolve("stuck.json")).solving();
        stuck.advance(1_000);
        stuck.session.stop();
        stuck.advance(4_000);
        stuck.session.stop();
        assertTrue(stuck.session.running());
        stuck.advance(1_001);
        stuck.session.stop();

        assertFalse(stuck.session.running());
        assertTrue(stuck.session.status(), stuck.session.status().startsWith("Solve abandoned"));
        stuck.worker.complete(solved());
        assertNull(stuck.session.summary());
        // And a new run can start.
        stuck.session.solve();
        assertTrue(stuck.session.running());
        assertEquals(2, stuck.starts);
    }

    @Test
    public void solveWhileRunningDoesNotStartASecondRunAndNeedsATarget() {
        Fixture empty = new Fixture(file());
        empty.session.solve();

        assertFalse(empty.session.running());
        assertEquals(0, empty.starts);
        assertEquals(Tone.WARNING, empty.session.tone());

        Fixture fixture = new Fixture(temporary.getRoot().toPath().resolve("two.json")).solving();
        fixture.session.solve();
        assertEquals(1, fixture.starts);
    }

    @Test
    public void aFailedRunShowsTheReasonAndToasts() {
        Fixture fixture = new Fixture(file()).solving();

        fixture.worker.completeExceptionally(new StackOverflowError());

        assertFalse(fixture.session.running());
        assertEquals("Solver failed: StackOverflowError", fixture.session.status());
        assertEquals(Tone.ERROR, fixture.session.tone());
        assertEquals(List.of("Solver failed: StackOverflowError"), fixture.toasts);
        assertNull(fixture.session.summary());
    }

    @Test
    public void turningMirroringAndClearingKeepOrDropTheResultAndAreSaved() {
        Fixture fixture = new Fixture(file()).solving();
        fixture.worker.complete(solved());
        GreenhouseGrid shown = fixture.session.plan().grid();
        GreenhouseSummary summary = fixture.session.summary();

        fixture.session.change(GreenhouseGrid::rotatedLeft, true, "Rotated left");
        assertEquals(shown.rotatedLeft(), fixture.session.plan().grid());
        assertEquals(summary, fixture.session.summary());
        assertEquals(shown.rotatedLeft(), new Fixture(file()).session.plan().grid());

        fixture.session.change(GreenhouseGrid::cleared, false, "Layout cleared");
        assertEquals(0, fixture.session.plan().grid().filled());
        assertNull(fixture.session.summary());
        Fixture reopened = new Fixture(file());
        assertEquals(0, reopened.session.plan().grid().filled());
        assertNull(reopened.session.summary());
        assertEquals(GreenhouseSession.HINT, reopened.session.status());
    }

    @Test
    public void mirroringOntoLockedCellsWarnsInTheStatus() {
        Fixture fixture = new Fixture(file()).solving();
        fixture.worker.complete(solved());
        for (int i = 0; i < GreenhouseState.CELLS; i++) {
            fixture.session.state().beginPaint(i);
            fixture.session.state().endPaint();
        }

        fixture.session.change(GreenhouseGrid::mirrored, true, "Mirrored");

        assertTrue(fixture.session.status(), fixture.session.status().contains("on locked cells"));
        assertEquals(Tone.WARNING, fixture.session.tone());
    }

    @Test
    public void pastingChecksTheTextAndKeepsTheGoodLayout() {
        Fixture fixture = new Fixture(file());
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(2, 3, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(4, 4, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));

        assertEquals("No layout link to load", fixture.session.loadLayout("  "));
        assertEquals("No layout link to load", fixture.session.loadLayout(null));
        assertEquals("Clipboard text is too large for a layout link", fixture.session.loadLayout(
            "a".repeat(GreenhouseCode.MAX_TEXT_LENGTH + 1)));
        assertEquals("Invalid layout code", fixture.session.loadLayout("https://example.com/nothing"));
        assertEquals("Invalid layout code", fixture.session.loadLayout("KGH1:AAAA"));
        assertEquals("Invalid layout code", fixture.session.status());
        assertEquals(Tone.ERROR, fixture.session.tone());
        assertEquals(0, fixture.session.plan().grid().filled());

        assertNull(fixture.session.loadLayout(GreenhouseLayoutCodec.encode(grid)));

        assertEquals(grid, fixture.session.plan().grid());
        assertNull(fixture.session.summary());
        assertEquals("Layout loaded: 2 crops", fixture.session.status());
        assertEquals(grid, new Fixture(file()).session.plan().grid());
    }

    @Test
    public void pastingAKungCodeLoadsItLikeASkyMutationsLink() {
        Fixture fixture = new Fixture(file());
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        grid.set(3, 7, new GreenhouseGrid.Cell("Dustgrain", GreenhouseGrid.TARGET));

        assertNull(fixture.session.loadLayout("my layout: " + GreenhouseCode.encode(grid) + " thanks"));

        assertEquals(grid, fixture.session.plan().grid());
        assertEquals("Layout loaded: 2 crops", fixture.session.status());
        assertEquals(grid, new Fixture(file()).session.plan().grid());
    }

    @Test
    public void gridChangesAreRefusedWhileASolveRunsAndWorkAgainAfterwards() {
        Fixture fixture = new Fixture(file()).solving();
        GreenhouseGrid before = fixture.session.plan().grid();
        GreenhouseGrid pasted = new GreenhouseGrid();
        pasted.set(2, 2, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));

        assertEquals(GreenhouseSession.BUSY, fixture.session.change(GreenhouseGrid::rotatedLeft, true, "Rotated left"));
        assertEquals(GreenhouseSession.BUSY, fixture.session.change(GreenhouseGrid::cleared, false, "Layout cleared"));
        assertEquals(GreenhouseSession.BUSY, fixture.session.change(GreenhouseGrid::mirrored, true, "Mirrored"));
        assertEquals(GreenhouseSession.BUSY, fixture.session.loadLayout(GreenhouseCode.encode(pasted)));

        assertEquals(before, fixture.session.plan().grid());
        assertEquals("Solver is running: stop it first", fixture.session.status());
        assertEquals(Tone.WARNING, fixture.session.tone());
        assertTrue(fixture.session.running());
        fixture.worker.complete(solved());
        // The paste was not lost silently: it was refused, and the solved layout is what is shown.
        assertEquals(solved().grid(), fixture.session.plan().grid());

        assertNull(fixture.session.loadLayout(GreenhouseCode.encode(pasted)));
        assertEquals(pasted, fixture.session.plan().grid());
        assertNull(fixture.session.change(GreenhouseGrid::mirrored, true, "Mirrored"));
    }

    @Test
    public void aFinishNeverOverwritesALayoutThatChangedSinceTheStart() {
        Fixture fixture = new Fixture(file()).solving();
        GreenhouseGrid other = new GreenhouseGrid();
        other.set(5, 5, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        fixture.session.replace(other, null);

        fixture.worker.complete(solved());

        assertFalse(fixture.session.running());
        assertEquals(other, fixture.session.plan().grid());
        assertNull(fixture.session.summary());
        assertTrue(fixture.session.status(), fixture.session.status().startsWith("Finished, not applied"));
        assertEquals(Tone.WARNING, fixture.session.tone());
        assertEquals(other, new Fixture(file()).session.plan().grid());
        assertEquals(1, fixture.toasts.size());
    }

    @Test
    public void closingTheViewEndsADragThatWasUnderWay() {
        Fixture fixture = new Fixture(file());
        fixture.session.viewerOpened();
        fixture.session.state().beginPaint(3);
        assertTrue(fixture.session.state().painting());

        fixture.session.viewerClosed();

        assertFalse(fixture.session.state().painting());
        assertFalse(fixture.session.state().paint(4));
        assertTrue(fixture.session.state().isUnlocked(4));
    }

    @Test
    public void aWorkerThatCannotStartEndsTheRunAtOnceWithAnError() {
        Fixture fixture = new Fixture(file());
        fixture.startFailure = new IllegalStateException("no thread");
        fixture.solving();

        assertFalse(fixture.session.running());
        assertEquals("Solver failed: no thread", fixture.session.status());
        assertEquals(Tone.ERROR, fixture.session.tone());

        fixture.startFailure = new OutOfMemoryError();
        fixture.session.solve();
        assertFalse(fixture.session.running());
        assertEquals("Solver failed: OutOfMemoryError", fixture.session.status());

        fixture.startFailure = null;
        fixture.startNothing = true;
        fixture.session.solve();
        assertFalse(fixture.session.running());
        assertEquals(Tone.ERROR, fixture.session.tone());

        fixture.startNothing = false;
        fixture.session.solve();
        assertTrue(fixture.session.running());
        fixture.worker.complete(solved());
        assertFalse(fixture.session.running());
        assertTrue(fixture.session.status(), fixture.session.status().startsWith("Solved in "));
    }

    @Test
    public void missingCorruptOrOldFilesRestoreNothing() throws IOException {
        Path file = file();
        Files.createDirectories(file.getParent());
        for (String text : new String[] {"", "not json", "[]", "{\"version\":1}",
            "{\"version\":1,\"layout\":\"x\"}", "{\"version\":1,\"layout\":[[0,0,\"-\",1]]}",
            "{\"version\":1,\"layout\":[[10,0,\"Carrot\",1]]}", "{\"version\":1,\"layout\":{\"a\":1}}",
            "{\"version\":1,\"layout\":[[0,0,\"Carrot\",1],[0]]}"}) {
            Files.writeString(file, text, StandardCharsets.UTF_8);

            Fixture fixture = new Fixture(file);

            assertEquals(text, 0, fixture.session.plan().grid().filled());
            assertNull(text, fixture.session.summary());
            assertEquals(text, GreenhouseSession.HINT, fixture.session.status());
        }
    }

    @Test
    public void aSavedSummaryWithoutALayoutOrOverTheCapsIsDropped() throws IOException {
        Path file = file();
        Files.createDirectories(file.getParent());
        String goal = "{\"text\":\"Carrot 1 / 1\",\"level\":\"REACHED\",\"note\":\"\"}";
        String layout = "\"layout\":[[0,0,\"Carrot\",1]]";
        String good = "\"summary\":{\"goals\":[" + goal + "],\"notes\":[\"n\"],\"elapsedMillis\":1500,\"fixed\":1,\"met\":1}";

        write(file, "{\"version\":1," + good + "}");
        assertNull(new Fixture(file).session.summary());

        write(file, "{\"version\":1," + layout + "," + good + "}");
        Fixture fine = new Fixture(file);
        assertEquals(1, fine.session.plan().grid().filled());
        assertEquals("Carrot 1 / 1", fine.session.summary().firstLine());
        assertEquals(1500L, fine.session.summary().elapsedMillis());
        assertEquals("Restored last layout", fine.session.status());

        String[] bad = {
            "\"summary\":{\"goals\":[" + goal.replace("REACHED", "NOPE") + "],\"notes\":[],\"elapsedMillis\":1,\"fixed\":0,\"met\":0}",
            "\"summary\":{\"goals\":[" + goal + "],\"notes\":[],\"elapsedMillis\":-1,\"fixed\":0,\"met\":0}",
            "\"summary\":{\"goals\":[" + goal + "],\"notes\":[],\"elapsedMillis\":1,\"fixed\":1,\"met\":2}",
            "\"summary\":{\"goals\":[" + goal + "],\"notes\":[\"" + "x".repeat(301) + "\"],\"elapsedMillis\":1,\"fixed\":1,\"met\":1}",
            "\"summary\":{\"goals\":[" + String.join(",", java.util.Collections.nCopies(65, goal))
                + "],\"notes\":[],\"elapsedMillis\":1,\"fixed\":1,\"met\":1}",
            "\"summary\":{\"goals\":[{\"text\":\"a\",\"level\":\"REACHED\"}],\"notes\":[],\"elapsedMillis\":1,\"fixed\":0,\"met\":0}",
            "\"summary\":{\"goals\":null,\"notes\":null}",
        };
        for (String summary : bad) {
            write(file, "{\"version\":1," + layout + "," + summary + "}");
            Fixture fixture = new Fixture(file);
            assertEquals(summary, 1, fixture.session.plan().grid().filled());
            assertNull(summary, fixture.session.summary());
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
