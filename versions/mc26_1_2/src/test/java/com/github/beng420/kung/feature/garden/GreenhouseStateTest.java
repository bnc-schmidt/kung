package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseSolver.Goal;
import com.github.beng420.kung.feature.garden.GreenhouseSolver.SolveRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GreenhouseStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static GreenhouseState sample() {
        GreenhouseState state = new GreenhouseState();
        state.add("Cindershade");
        state.setCount(0, 52);
        state.add("Noctilume");
        state.setMode(1, GreenhouseState.Mode.MAX);
        state.setSeconds(12);
        state.setPriority("Fire", 30);
        state.beginPaint(0);
        state.paint(1);
        state.paint(11);
        state.endPaint();
        return state;
    }

    @Test
    public void startsWithEveryCellOpenAndDefaultBudget() {
        GreenhouseState state = new GreenhouseState();

        assertEquals(100, state.unlockedCount());
        assertEquals(30, state.seconds());
        assertFalse(state.prioritiesOpen());
        assertTrue(state.targets().isEmpty());
        assertTrue(state.priorities().isEmpty());
    }

    @Test
    public void everySearchableMutationCanBeAddedAndLonelilyIsNotOffered() {
        GreenhouseState state = new GreenhouseState();
        List<GreenhouseMutations.Mutation> searchable = GreenhouseMutations.supported().stream()
            .filter(GreenhouseState::searchable).toList();

        for (GreenhouseMutations.Mutation mutation : searchable) {
            assertTrue(mutation.name(), state.add(mutation.name()));
        }

        assertEquals(searchable.size(), state.targets().size());
        assertEquals(GreenhouseMutations.supported().size() - GreenhouseState.SEARCH_EXCLUDED.size(), searchable.size());
        // Lonelily is a recipe the solver handles, but it is not a goal worth offering.
        assertTrue(GreenhouseMutations.find("Lonelily").orElseThrow().supported());
        assertTrue(GreenhouseMutations.find("Lonelily").orElseThrow().emptyRing());
        assertFalse(state.add("Lonelily"));
        assertFalse(state.add("lonelily"));
        assertFalse(state.isSelected("Lonelily"));
        assertEquals(0, state.request().goals().stream().filter(g -> g.mutation().equals("Lonelily")).count());
    }

    @Test
    public void aSavedLonelilyTargetIsDroppedOnLoadLikeAnUnsupportedOne() {
        String json = "{\"version\":1,\"targets\":[{\"mutation\":\"Zombud\",\"mode\":\"target\",\"count\":3},"
            + "{\"mutation\":\"Lonelily\",\"mode\":\"max\",\"count\":1},"
            + "{\"mutation\":\"Godseed\",\"mode\":\"target\",\"count\":1},"
            + "{\"mutation\":\"Cindershade\",\"mode\":\"target\",\"count\":2}]}";

        GreenhouseState state = GreenhouseState.fromJson(json);

        assertEquals(List.of("Zombud", "Cindershade"),
            state.targets().stream().map(GreenhouseState.Target::mutation).toList());
        assertFalse(state.isSelected("Lonelily"));
    }

    @Test
    public void addingAnExistingTargetKeepsOneEntryAndItsSettings() {
        GreenhouseState state = new GreenhouseState();
        state.add("Cindershade");
        state.setCount(0, 12);
        state.setMode(0, GreenhouseState.Mode.MAX);

        assertFalse(state.add("Cindershade"));
        assertFalse(state.add("cindershade"));

        assertEquals(1, state.targets().size());
        assertEquals(new GreenhouseState.Target("Cindershade", GreenhouseState.Mode.MAX, 12), state.targets().get(0));
        assertEquals(0, state.indexOf("CINDERSHADE"));
        assertEquals(-1, state.indexOf("Zombud"));
    }

    @Test
    public void steppingTheCountStaysWithinOneToOneHundredThousand() {
        GreenhouseState state = new GreenhouseState();
        state.add("Cindershade");

        state.stepCount(0, 1);
        assertEquals(2, state.targets().get(0).count());
        state.stepCount(0, 10);
        assertEquals(12, state.targets().get(0).count());
        state.stepCount(0, -10);
        state.stepCount(0, -1);
        assertEquals(1, state.targets().get(0).count());
        state.stepCount(0, -10);
        assertEquals(1, state.targets().get(0).count());
        state.setCount(0, 99_995);
        state.stepCount(0, 10);
        assertEquals(100_000, state.targets().get(0).count());
        state.stepCount(0, 1);
        assertEquals(100_000, state.targets().get(0).count());
    }

    @Test
    public void theCollapsedFlagIsSavedAndOlderFilesStartCollapsed() throws IOException {
        GreenhouseState state = new GreenhouseState();
        state.setPrioritiesOpen(true);
        Path file = temporary.getRoot().toPath().resolve("greenhouse.json");

        state.save(file);

        assertTrue(GreenhouseState.load(file).prioritiesOpen());
        state.setPrioritiesOpen(false);
        assertFalse(GreenhouseState.fromJson(state.toJson()).prioritiesOpen());
        // A file written before the column could collapse has no such field.
        String old = "{\"version\":1,\"unlocked\":\"" + "1".repeat(100) + "\",\"targets\":[],\"seconds\":5,\"priorities\":{}}";
        GreenhouseState loaded = GreenhouseState.fromJson(old);
        assertFalse(loaded.prioritiesOpen());
        // A stored budget is kept; only a missing one takes the new default.
        assertEquals(5, loaded.seconds());
        assertEquals(30, GreenhouseState.fromJson("{\"version\":1}").seconds());
    }

    @Test
    public void addingKeepsOrderAndRejectsDuplicatesUnknownAndUnsupported() {
        GreenhouseState state = new GreenhouseState();

        assertTrue(state.add("Cindershade"));
        assertTrue(state.add("noctilume"));
        assertFalse(state.add("CINDERSHADE"));
        assertTrue(state.add("Do not eat shroom"));
        assertFalse(state.add("do-not-eat-shroom"));
        assertFalse(state.add("Not a mutation"));
        assertFalse(state.add("Godseed"));

        assertEquals(List.of("Cindershade", "Noctilume", "Do-not-eat-shroom"),
            state.targets().stream().map(GreenhouseState.Target::mutation).toList());
        assertEquals(1, state.targets().get(0).count());
        assertEquals(GreenhouseState.Mode.TARGET, state.targets().get(0).mode());
        assertTrue(state.contains("noctilume"));
    }

    @Test
    public void selectedNamesFollowAddRemoveAndLoad() {
        GreenhouseState state = new GreenhouseState();

        assertFalse(state.isSelected("Cindershade"));
        state.add("cindershade");
        state.add("Noctilume");
        assertTrue(state.isSelected("Cindershade"));
        assertTrue(state.isSelected("Noctilume"));
        // The render path asks by exact name; loose spellings are for contains().
        assertFalse(state.isSelected("cindershade"));
        assertTrue(state.contains("cindershade"));
        state.remove(0);
        assertFalse(state.isSelected("Cindershade"));
        assertTrue(state.isSelected("Noctilume"));
        state.remove(9);
        assertTrue(state.isSelected("Noctilume"));
        assertTrue(state.add("Cindershade"));

        GreenhouseState loaded = GreenhouseState.fromJson(state.toJson());
        assertTrue(loaded.isSelected("Cindershade"));
        assertTrue(loaded.isSelected("Noctilume"));
        assertFalse(loaded.isSelected("Zombud"));
    }

    @Test
    public void countsCropsOnLockedCells() {
        GreenhouseState state = new GreenhouseState();
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(5, 5, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));

        assertEquals(0, state.cropsOnLockedCells(grid));
        state.beginPaint(0);
        state.paint(1);
        state.paint(2);
        state.endPaint();
        assertEquals(2, state.cropsOnLockedCells(grid));
        state.beginPaint(55);
        state.endPaint();
        assertEquals(3, state.cropsOnLockedCells(grid));
        assertEquals(0, state.cropsOnLockedCells(new GreenhouseGrid()));
        state.unlockAll();
        assertEquals(0, state.cropsOnLockedCells(grid));
        // Rotating the layout moves a crop onto a cell that stays locked, which is what the screen warns about.
        state.beginPaint(9);
        state.endPaint();
        assertEquals(0, state.cropsOnLockedCells(grid));
        assertEquals(1, state.cropsOnLockedCells(grid.rotatedRight()));
    }

    @Test
    public void removeDropsTheRowAndLeavesTheOthers() {
        GreenhouseState state = new GreenhouseState();
        state.add("Cindershade");
        state.add("Noctilume");
        state.add("Zombud");

        state.remove(1);
        state.remove(7);

        assertEquals(List.of("Cindershade", "Zombud"),
            state.targets().stream().map(GreenhouseState.Target::mutation).toList());
        assertTrue(state.add("Noctilume"));
    }

    @Test
    public void modeSwitchKeepsTheCountAndCountIsClamped() {
        GreenhouseState state = new GreenhouseState();
        state.add("Cindershade");

        state.setCount(0, 40);
        state.setMode(0, GreenhouseState.Mode.MAX);
        assertEquals(40, state.targets().get(0).count());
        state.setMode(0, GreenhouseState.Mode.TARGET);
        state.setCount(0, 0);
        assertEquals(1, state.targets().get(0).count());
        state.setCount(0, 1_000_000);
        assertEquals(100_000, state.targets().get(0).count());
        state.setCount(0, -5);
        assertEquals(1, state.targets().get(0).count());
    }

    @Test
    public void budgetIsClampedToOneToSixtySeconds() {
        GreenhouseState state = new GreenhouseState();

        state.setSeconds(0);
        assertEquals(1, state.seconds());
        state.setSeconds(600);
        assertEquals(60, state.seconds());
        state.setSeconds(30);
        assertEquals(30, state.seconds());
    }

    @Test
    public void prioritiesKeepOnlyNonZeroValuesInRange() {
        GreenhouseState state = new GreenhouseState();

        state.setPriority("fire", 250);
        state.setPriority("Carrot", 40);
        state.setPriority("Potato", 0);
        assertEquals(Map.of("Fire", 100, "Carrot", 40), state.priorities());
        state.setPriority("Fire", -3);
        assertEquals(Map.of("Carrot", 40), state.priorities());
        assertEquals(0, state.priority("Fire"));
        state.resetPriorities();
        assertTrue(state.priorities().isEmpty());
    }

    @Test
    public void requestCarriesGoalsInOrderMaskAndOnlyNonZeroPriorities() {
        SolveRequest request = sample().request();

        assertEquals(List.of(Goal.fixed("Cindershade", 52), Goal.maximize("Noctilume")), request.goals());
        assertEquals(Map.of("Fire", 30), request.priorities());
        boolean[] expected = new boolean[100];
        Arrays.fill(expected, true);
        expected[0] = false;
        expected[1] = false;
        expected[11] = false;
        assertArrayEquals(expected, request.unlocked());
    }

    @Test
    public void requestMaskIsACopy() {
        GreenhouseState state = new GreenhouseState();
        SolveRequest request = state.request();

        state.beginPaint(5);
        state.endPaint();

        assertTrue(request.unlocked()[5]);
        assertFalse(state.isUnlocked(5));
    }

    @Test
    public void dragPaintsTheStateOfTheFirstCellOnly() {
        GreenhouseState state = new GreenhouseState();

        state.beginPaint(10);
        assertFalse(state.isUnlocked(10));
        assertTrue(state.paint(11));
        assertFalse(state.paint(11));
        state.paint(12);
        state.endPaint();
        assertEquals(97, state.unlockedCount());

        // The next stroke starts on a locked cell, so it unlocks everything it reaches, locked or not.
        state.beginPaint(11);
        assertTrue(state.isUnlocked(11));
        state.paint(12);
        state.paint(13);
        state.endPaint();
        assertTrue(state.isUnlocked(12));
        assertTrue(state.isUnlocked(13));
        assertFalse(state.isUnlocked(10));

        // Outside a stroke nothing changes.
        assertFalse(state.paint(50));
        assertTrue(state.isUnlocked(50));
    }

    @Test
    public void unlockAllReopensEveryCell() {
        GreenhouseState state = sample();

        state.unlockAll();

        assertEquals(100, state.unlockedCount());
    }

    @Test
    public void mutationFilterIgnoresCaseAndSeparators() {
        List<GreenhouseMutations.Mutation> all = GreenhouseMutations.all();

        assertEquals(List.of("Cindershade"),
            GreenhouseState.matching(all, "CINDER").stream().map(GreenhouseMutations.Mutation::name).toList());
        assertEquals(List.of("Do-not-eat-shroom"),
            GreenhouseState.matching(all, "not eat").stream().map(GreenhouseMutations.Mutation::name).toList());
        assertTrue(GreenhouseState.matching(all, "zzz").isEmpty());
        int offered = (int) all.stream().filter(GreenhouseState::searchable).count();
        assertEquals(offered, GreenhouseState.matching(all, "").size());
        assertEquals(offered, GreenhouseState.matching(all, null).size());
    }

    @Test
    public void mutationsWithoutARingRecipeNeverAppearInTheList() {
        List<GreenhouseMutations.Mutation> all = GreenhouseMutations.all();

        for (String query : new String[] {"godseed", "shellfruit", "jerryflower", "Jerry", "seed", "fruit"}) {
            assertTrue(query, GreenhouseState.matching(all, query).stream().allMatch(GreenhouseMutations.Mutation::supported));
        }
        assertTrue(GreenhouseState.matching(all, "godseed").isEmpty());
        assertTrue(GreenhouseState.matching(all, "shellfruit").isEmpty());
        assertTrue(GreenhouseState.matching(all, "jerryflower").isEmpty());
        // Lonelily is solvable but left out of the list on purpose.
        assertTrue(GreenhouseState.matching(all, "lonelily").isEmpty());
        assertTrue(GreenhouseState.matching(all, "lonely").isEmpty());
        assertTrue(GreenhouseState.matching(all, "").stream().noneMatch(m -> m.name().equals("Lonelily")));
        assertEquals(1, GreenhouseState.matching(all, "zombud").size());
        assertTrue(GreenhouseState.matching(all, "").stream().allMatch(GreenhouseMutations.Mutation::supported));
    }

    @Test
    public void saveAndLoadRoundTripEverything() throws IOException {
        Path file = temporary.getRoot().toPath().resolve("kung").resolve("greenhouse.json");
        GreenhouseState saved = sample();

        saved.save(file);
        GreenhouseState loaded = GreenhouseState.load(file);

        assertEquals(saved.toJson(), loaded.toJson());
        assertEquals(saved.targets(), loaded.targets());
        assertEquals(97, loaded.unlockedCount());
        assertFalse(loaded.isUnlocked(11));
        assertEquals(12, loaded.seconds());
        assertEquals(Map.of("Fire", 30), loaded.priorities());
        assertFalse(Files.exists(file.resolveSibling("greenhouse.json.tmp")));
    }

    @Test
    public void missingAndCorruptFilesFallBackToDefaults() throws IOException {
        Path file = temporary.getRoot().toPath().resolve("greenhouse.json");
        assertDefaults(GreenhouseState.load(file));

        for (String text : List.of("", "not json", "[1, 2]", "{\"version\": 7}", "{\"version\": 1, \"unlocked\": 5}",
            "{\"version\": 1, \"targets\": {\"a\": 1}}", "\u0000\u0001")) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            assertDefaults(GreenhouseState.load(file));
        }
        Files.write(file, new byte[70_000]);
        assertDefaults(GreenhouseState.load(file));
    }

    @Test
    public void badPartsFallBackIndividually() {
        String json = "{\"version\":1,\"unlocked\":\"" + "1".repeat(99) + "x\","
            + "\"targets\":[{\"mutation\":\"Cindershade\",\"mode\":\"max\",\"count\":9},"
            + "{\"mutation\":\"Godseed\",\"mode\":\"target\",\"count\":3},"
            + "{\"mutation\":\"Cindershade\",\"mode\":\"target\",\"count\":4},null,"
            + "{\"mutation\":\"Zombud\",\"mode\":\"weird\",\"count\":-4}],"
            + "\"seconds\":900,\"priorities\":{\"Fire\":500,\"Nope\":5,\"Carrot\":0}}";

        GreenhouseState state = GreenhouseState.fromJson(json);

        assertEquals(100, state.unlockedCount());
        assertEquals(List.of(new GreenhouseState.Target("Cindershade", GreenhouseState.Mode.MAX, 9),
            new GreenhouseState.Target("Zombud", GreenhouseState.Mode.TARGET, 1)), state.targets());
        assertEquals(60, state.seconds());
        assertEquals(Map.of("Fire", 100), state.priorities());
    }

    @Test
    public void theShownLayoutAndItsSummaryRoundTrip() {
        GreenhouseState state = new GreenhouseState();
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(1, 2, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(5, 5, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));
        grid.set(9, 9, new GreenhouseGrid.Cell("Mystery Crop", 7));
        GreenhouseSummary summary = new GreenhouseSummary(List.of(
            new GreenhouseSummary.Goal("Cindershade 1 / 1", GreenhouseSummary.Level.REACHED, ""),
            new GreenhouseSummary.Goal("Zombud 0.50 / 1", GreenhouseSummary.Level.SHORT, "Best layout found has 0.50 of 1")),
            List.of("a note", "another"), 4_250, 2, 1);
        state.setLayout(grid);
        state.setSummary(summary);

        GreenhouseState loaded = GreenhouseState.fromJson(state.toJson());

        assertEquals(grid, loaded.layout());
        assertEquals(summary, loaded.summary());
        assertNull(GreenhouseState.fromJson(new GreenhouseState().toJson()).layout());
        assertNull(GreenhouseState.fromJson(new GreenhouseState().toJson()).summary());
    }

    @Test
    public void aStoredLayoutThatBreaksThePasteRulesOrTheSizeCapIsIgnored() {
        String entries200 = String.join(",", java.util.Collections.nCopies(201, "[0,0,\"Carrot\",1]"));
        for (String layout : new String[] {"[[0,0,\"-\",1]]", "[[0,0,\"" + "B".repeat(41) + "\",1]]", "[[0,10,\"Carrot\",1]]",
            "[[0,0,\"Carrot\",-1]]", "[[0,0,\"Carrot\"],[1]]", "\"text\"", "{}", "[" + entries200 + "]"}) {
            GreenhouseState loaded = GreenhouseState.fromJson("{\"version\":1,\"layout\":" + layout + "}");

            assertNull(layout, loaded.layout());
            assertDefaults(loaded);
        }
        assertEquals(1, GreenhouseState.fromJson("{\"version\":1,\"layout\":[[0,0,\"Carrot\",1]]}").layout().filled());
    }

    @Test
    public void costModeAndStopEarlyDefaultToBazaarAndOffAndRoundTrip() {
        GreenhouseState fresh = new GreenhouseState();
        assertEquals(GreenhouseCosts.CostMode.BAZAAR, fresh.costMode());
        assertFalse(fresh.stopEarly());

        fresh.setCostMode(GreenhouseCosts.CostMode.IRONMAN);
        fresh.setStopEarly(true);
        GreenhouseState loaded = GreenhouseState.fromJson(fresh.toJson());

        assertEquals(GreenhouseCosts.CostMode.IRONMAN, loaded.costMode());
        assertTrue(loaded.stopEarly());
        fresh.setCostMode(null);
        assertEquals(GreenhouseCosts.CostMode.BAZAAR, fresh.costMode());
    }

    @Test
    public void filesWithoutOrWithBadCostSettingsKeepTheDefaults() {
        GreenhouseState old = GreenhouseState.fromJson(
            "{\"version\":1,\"seconds\":12,\"unlocked\":\"" + "1".repeat(100) + "\"}");
        assertEquals(GreenhouseCosts.CostMode.BAZAAR, old.costMode());
        assertFalse(old.stopEarly());
        assertEquals(12, old.seconds());

        for (String mode : new String[] {"\"HARD\"", "\"bazaar\"", "5", "null", "\"\""}) {
            GreenhouseState bad = GreenhouseState.fromJson("{\"version\":1,\"costMode\":" + mode + "}");
            assertEquals(mode, GreenhouseCosts.CostMode.BAZAAR, bad.costMode());
        }
        assertEquals(GreenhouseCosts.CostMode.IRONMAN,
            GreenhouseState.fromJson("{\"version\":1,\"costMode\":\"IRONMAN\",\"stopEarly\":true}").costMode());
    }

    /** A file as the first planner wrote it: no orientation field, a hole at (2, 3), a Noctilume 2x2 and a Carrot. */
    private static String oldFile(String extra) {
        StringBuilder mask = new StringBuilder("1".repeat(100));
        mask.setCharAt(2 * 10 + 3, '0');
        return "{\"version\":1," + extra + "\"unlocked\":\"" + mask + "\",\"layout\":[[0,0,\"Carrot\",1],"
            + "[1,1,\"Noctilume\",0],[1,2,\"Noctilume\",0],[2,1,\"Noctilume\",0],[2,2,\"Noctilume\",0]]}";
    }

    @Test
    public void anOldFileIsTurnedAQuarterClockwiseSoItKeepsItsPlaceOnThePlot() {
        GreenhouseState loaded = GreenhouseState.fromJson(oldFile(""));

        // new[r][c] = old[9 - c][r]: the hole at (2, 3) lands at (3, 7).
        assertEquals(99, loaded.unlockedCount());
        assertFalse(loaded.isUnlocked(3 * 10 + 7));
        assertTrue(loaded.isUnlocked(2 * 10 + 3));
        GreenhouseGrid layout = loaded.layout();
        assertEquals(new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT), layout.get(0, 9));
        assertNull(layout.get(0, 0));
        for (int row = 1; row <= 2; row++) {
            for (int column = 7; column <= 8; column++) {
                assertEquals(new GreenhouseGrid.Cell("Noctilume", GreenhouseGrid.TARGET), layout.get(row, column));
            }
        }
        assertEquals(5, layout.filled());
        // The 2x2 footprint is still one block after the turn.
        assertEquals(List.of(new GreenhousePlan.Block("Noctilume", 1, 7, 2, 2)), GreenhousePlan.of(layout).blocks());
    }

    @Test
    public void aTurnedCellStandsAtTheWorldOffsetItHadUnderTheOldMapping() {
        GreenhouseGrid old = GreenhouseState.fromJson(oldFile("\"orientationVersion\":2,")).layout();
        GreenhouseGrid turned = GreenhouseState.fromJson(oldFile("")).layout();
        // Old default: dx = 9 - row, dz = column. New default: dx = column, dz = row (GreenhouseOrientation.IDENTITY).
        for (int row = 0; row < 10; row++) {
            for (int column = 0; column < 10; column++) {
                int dx = 9 - row;
                int dz = column;
                GreenhouseOrientation.Offset moved = GreenhouseOrientation.IDENTITY.offset(dz, dx);
                assertEquals(dx, moved.dx());
                assertEquals(dz, moved.dz());
                assertEquals(old.get(row, column), turned.get(dz, dx));
            }
        }
    }

    @Test
    public void filesAtOrientationVersionTwoOrLaterAreNotTurned() {
        for (String version : new String[] {"2", "3", "99"}) {
            GreenhouseState loaded = GreenhouseState.fromJson(oldFile("\"orientationVersion\":" + version + ","));

            assertFalse(version, loaded.isUnlocked(2 * 10 + 3));
            assertEquals(version, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT), loaded.layout().get(0, 0));
        }
        // An explicit 1 (or 0) is the same as the field being absent.
        for (String version : new String[] {"1", "0", "-3"}) {
            GreenhouseState loaded = GreenhouseState.fromJson(oldFile("\"orientationVersion\":" + version + ","));
            assertFalse(version, loaded.isUnlocked(3 * 10 + 7));
            assertEquals(version, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT), loaded.layout().get(0, 9));
        }
    }

    @Test
    public void savingWritesVersionTwoAndReloadingNeverTurnsAgain() throws IOException {
        GreenhouseState first = GreenhouseState.fromJson(oldFile(""));
        Path file = temporary.getRoot().toPath().resolve("greenhouse.json");

        first.save(file);
        String written = Files.readString(file, StandardCharsets.UTF_8);
        GreenhouseState second = GreenhouseState.load(file);
        second.save(file);
        GreenhouseState third = GreenhouseState.load(file);

        assertTrue(written, written.contains("\"orientationVersion\":2"));
        assertEquals(written, second.toJson());
        assertEquals(written, third.toJson());
        assertEquals(first.layout(), third.layout());
        assertFalse(third.isUnlocked(3 * 10 + 7));
        assertTrue("a new file carries the version too",
            new GreenhouseState().toJson().contains("\"orientationVersion\":" + GreenhouseState.ORIENTATION_VERSION));
    }

    @Test
    public void turningAnOldFileSurvivesCorruptPartsAndNeverThrows() {
        // A bad mask opens every cell and a bad layout is none; the turn must not trip over either.
        GreenhouseState badMask = GreenhouseState.fromJson("{\"version\":1,\"unlocked\":\"1x\",\"layout\":[[0,0,\"Carrot\",1]]}");
        assertEquals(100, badMask.unlockedCount());
        assertEquals(new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT), badMask.layout().get(0, 9));

        GreenhouseState badLayout = GreenhouseState.fromJson(
            "{\"version\":1,\"unlocked\":\"" + "0".repeat(10) + "1".repeat(90) + "\",\"layout\":[[0,10,\"Carrot\",1]]}");
        assertNull(badLayout.layout());
        // Mask row 0 (ten locked cells) became column 9.
        assertEquals(90, badLayout.unlockedCount());
        for (int row = 0; row < 10; row++) assertFalse(badLayout.isUnlocked(row * 10 + 9));

        // No layout and no mask at all.
        GreenhouseState nothing = GreenhouseState.fromJson("{\"version\":1}");
        assertNull(nothing.layout());
        assertEquals(100, nothing.unlockedCount());

        // A version that is not a number breaks the document like any other wrong type: defaults.
        assertDefaults(GreenhouseState.fromJson(oldFile("\"orientationVersion\":\"two\",")));
        assertDefaults(GreenhouseState.fromJson(oldFile("\"orientationVersion\":[2],")));
    }

    @Test
    public void nullDocumentGivesDefaults() {
        assertNotNull(GreenhouseState.fromJson(null));
        assertDefaults(GreenhouseState.fromJson(null));
    }

    private static void assertDefaults(GreenhouseState state) {
        assertEquals(new GreenhouseState().toJson(), state.toJson());
    }
}
