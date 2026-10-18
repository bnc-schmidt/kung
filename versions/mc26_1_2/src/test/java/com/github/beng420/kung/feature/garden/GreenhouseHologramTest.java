package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Shape;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class GreenhouseHologramTest {
    private static final double EPSILON = 1.0E-9;

    @Before
    @After
    public void reset() {
        GreenhouseHologramSettings.useConfig(null);
        GreenhouseHologram.hide();
        if (GreenhouseHologram.orientation().mirror()) GreenhouseHologram.mirror();
        while (GreenhouseHologram.orientation().rotation() != 0) GreenhouseHologram.rotate();
    }

    private static GreenhouseGrid cropAt(int row, int column, int flag, String name) {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(row, column, new GreenhouseGrid.Cell(name, flag));
        return grid;
    }

    private static List<Shape> shapesOf(GreenhouseGrid grid, GreenhouseOrientation orientation) {
        return GreenhouseHologramShapes.build(grid, orientation).shapes();
    }

    @Test
    public void showReportsWhetherThePlayerIsInTheGreenhouse() {
        GreenhouseGrid grid = cropAt(1, 1, GreenhouseGrid.INGREDIENT, "Carrot");

        GreenhouseHologram.ShowResult armed = GreenhouseHologram.show(grid, false);
        assertTrue(armed.message().startsWith("Hologram armed: walk into your greenhouse"));
        assertTrue("names the fallback", armed.message().contains("/kung greenhouse blueprint here"));
        assertFalse(armed.insideGreenhouse());
        assertTrue(GreenhouseHologram.isShowing());

        GreenhouseHologram.ShowResult inside = GreenhouseHologram.show(grid, true);
        assertEquals("Showing hologram in your greenhouse", inside.message());
        assertTrue(inside.insideGreenhouse());
    }

    @Test
    public void hideClearsTheShapesAndBumpsTheVersion() {
        GreenhouseHologram.show(cropAt(1, 1, GreenhouseGrid.INGREDIENT, "Carrot"), false);
        int shown = GreenhouseHologram.version();
        assertFalse(GreenhouseHologram.shapes().isEmpty());

        GreenhouseHologram.hide();

        assertFalse(GreenhouseHologram.isShowing());
        assertTrue(GreenhouseHologram.shapes().isEmpty());
        assertTrue(GreenhouseHologram.grid().isEmpty());
        assertNotEquals(shown, GreenhouseHologram.version());
    }

    @Test
    public void orientationIsSessionStateThatSurvivesHidingAndRebuildsTheShapes() {
        GreenhouseHologram.show(cropAt(0, 0, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        var before = GreenhouseHologram.layout();
        int version = GreenhouseHologram.version();

        assertEquals("rotated 90 degrees clockwise, not mirrored", GreenhouseHologram.rotate());
        assertNotEquals(before, GreenhouseHologram.layout());
        assertNotEquals(version, GreenhouseHologram.version());
        assertEquals("rotated 90 degrees clockwise, mirrored", GreenhouseHologram.mirror());

        GreenhouseHologram.hide();
        assertEquals(new GreenhouseOrientation(1, true), GreenhouseHologram.orientation());
        assertTrue(GreenhouseHologram.shapes().isEmpty());
        assertEquals("rotated 180 degrees clockwise, mirrored", GreenhouseHologram.rotate());
        assertTrue("rotating while hidden shows nothing", GreenhouseHologram.shapes().isEmpty());
    }

    @Test
    public void clipboardTextIsDecodedAndAnEmptyLayoutIsNoLayout() {
        GreenhouseGrid grid = cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot");

        assertTrue(GreenhouseHologram.showFromText(GreenhouseCode.encode(grid), true).isPresent());
        assertEquals(Optional.of(grid), GreenhouseHologram.grid());
        assertTrue(GreenhouseHologram.showFromText(GreenhouseLayoutCodec.encode(grid), false).isPresent());

        GreenhouseHologram.hide();
        assertTrue(GreenhouseHologram.showFromText("nothing useful", true).isEmpty());
        assertTrue(GreenhouseHologram.showFromText(GreenhouseCode.encode(new GreenhouseGrid()), true).isEmpty());
        assertTrue(GreenhouseHologram.showFromText(null, true).isEmpty());
        assertFalse(GreenhouseHologram.isShowing());
    }

    @Test
    public void theToggleKeyHidesAndShowsAndShowingAgainBringsItBack() {
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        assertTrue(GreenhouseHologram.userVisible());
        assertTrue(GreenhouseHologram.isDrawn());
        assertFalse(GreenhouseHologram.cellBoxes().isEmpty());

        assertEquals("Greenhouse hologram hidden", GreenhouseHologram.toggle());
        assertFalse(GreenhouseHologram.userVisible());
        assertTrue("still showing a layout", GreenhouseHologram.isShowing());
        assertFalse(GreenhouseHologram.isDrawn());
        assertTrue("a pick-block ray claims nothing", GreenhouseHologram.cellBoxes().isEmpty());
        assertTrue(GreenhouseHologram.soilGhosts().isEmpty());

        assertEquals("Greenhouse hologram shown", GreenhouseHologram.toggle());
        assertTrue(GreenhouseHologram.isDrawn());
        assertFalse(GreenhouseHologram.cellBoxes().isEmpty());

        GreenhouseHologram.toggle();
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        assertTrue("showing a layout makes it visible again", GreenhouseHologram.userVisible());
        assertTrue(GreenhouseHologram.isDrawn());
    }

    @Test
    public void hidingTheHologramDoesNotChangeWhatIsPlanted() {
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        GreenhouseScanner.setSnapshotForTest(GreenhouseScanLogic.evaluate(
            GreenhouseScanLogic.footprints(GreenhouseHologram.placements()),
            List.of(new GreenhouseScanLogic.Found("Carrot", 3.5, 2.5)), null, GreenhouseHologram.version()));
        assertEquals(1, GreenhouseHologram.correctlyPlaced("Carrot"));

        GreenhouseHologram.toggle();

        assertEquals("the supply still counts the placed crop", 1, GreenhouseHologram.correctlyPlaced("Carrot"));
        assertEquals(1, GreenhouseHologram.plantedCount("Carrot"));
        assertTrue(GreenhouseHologram.cellBoxes().isEmpty());
    }

    private static com.github.beng420.kung.config.category.GreenhouseConfig useDefaultConfig() {
        com.github.beng420.kung.config.category.GreenhouseConfig config =
            new com.github.beng420.kung.config.category.GreenhouseConfig();
        GreenhouseHologramSettings.useConfig(() -> config);
        return config;
    }

    /** One completed scan that found every ingredient right, fed the way the scanner feeds it. */
    private static boolean allRightScan() {
        return GreenhouseScanner.completeIfDone(true, GreenhouseHologramSettings.hideWhenComplete());
    }

    @Test
    public void aFinishedLayoutDeactivatesTheHologramThroughTheSamePathAsHide() {
        useDefaultConfig();
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 1, GreenhouseSoils.Soil.SAND)), 73);
        assertTrue(GreenhouseHologram.isDrawn());

        assertFalse("one scan is not enough", allRightScan());
        assertTrue(GreenhouseHologram.isDrawn());
        assertTrue("the second agrees: deactivated", allRightScan());

        assertFalse(GreenhouseHologram.isShowing());
        assertFalse(GreenhouseHologram.isDrawn());
        assertTrue(GreenhouseHologram.grid().isEmpty());
        assertTrue(GreenhouseHologram.shapes().isEmpty());
        assertTrue(GreenhouseHologram.placements().isEmpty());
        assertTrue("nothing is claimed", GreenhouseHologram.cellBoxes().isEmpty());
        assertTrue(GreenhouseHologram.soilGhosts().isEmpty());
        assertTrue("the scan state is gone too", GreenhouseScanner.ghosts(GreenhouseHologram.version()).isEmpty());
        assertFalse("the completion was reset", GreenhouseScanner.complete());
        assertEquals(0, GreenhouseHologram.plantedCount("Carrot"));
    }

    @Test
    public void nothingReArmsADeactivatedHologramOnlyAnExplicitShowDoes() {
        useDefaultConfig();
        GreenhouseGrid layout = cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot");
        GreenhouseHologram.show(layout, true);
        allRightScan();
        assertTrue(allRightScan());

        // Entering another greenhouse, re-entering this one and rejoining the server.
        GreenhouseScanner.switchCorner(new net.minecraft.core.BlockPos(10, 70, 20));
        GreenhouseScanner.switchCorner(new net.minecraft.core.BlockPos(-5, 73, -5));
        GreenhouseHologram.onConnectionChange();
        GreenhouseScanner.completeIfDone(false, true);
        assertFalse(GreenhouseHologram.isShowing());
        assertFalse(GreenhouseHologram.isDrawn());
        assertTrue(GreenhouseHologram.cellBoxes().isEmpty());

        GreenhouseHologram.show(layout, true);
        assertTrue("showing it again is what brings it back", GreenhouseHologram.isDrawn());
        assertFalse("and it starts without a completion", GreenhouseScanner.complete());
        assertFalse(allRightScan());
    }

    @Test
    public void withTheSettingOffAFinishedLayoutStaysShownUntilTheSettingIsSwitchedOn() {
        var config = useDefaultConfig();
        config.setHideWhenComplete(false);
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);

        assertFalse(allRightScan());
        assertFalse(allRightScan());
        assertTrue("complete but not deactivated", GreenhouseHologram.isDrawn());
        assertTrue(GreenhouseScanner.complete());
        assertFalse(GreenhouseHologram.cellBoxes().isEmpty());

        config.setHideWhenComplete(true);
        assertTrue("switched on while complete: deactivated at the next scan", allRightScan());
        assertFalse(GreenhouseHologram.isShowing());
    }

    @Test
    public void aLayoutThatIsNotFinishedIsNeverDeactivated() {
        useDefaultConfig();
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);

        for (int i = 0; i < 5; i++) assertFalse(GreenhouseScanner.completeIfDone(false, true));
        // Good scans that never come twice in a row do not count either.
        assertFalse(allRightScan());
        assertFalse(GreenhouseScanner.completeIfDone(false, true));
        assertFalse(allRightScan());
        assertTrue(GreenhouseHologram.isDrawn());
        assertFalse(GreenhouseScanner.complete());
    }

    @Test
    public void deactivatingKeepsTheSessionsOrientationForTheNextShow() {
        useDefaultConfig();
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        GreenhouseHologram.rotate();
        GreenhouseHologram.mirror();
        GreenhouseOrientation turned = GreenhouseHologram.orientation();
        allRightScan();
        assertTrue(allRightScan());

        assertEquals(turned, GreenhouseHologram.orientation());
    }

    @Test
    public void theToggleKeyIsAPlainShowAndHide() {
        useDefaultConfig();
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);

        assertEquals("Greenhouse hologram hidden", GreenhouseHologram.toggle());
        assertFalse(GreenhouseHologram.isDrawn());
        assertTrue("still a shown layout", GreenhouseHologram.isShowing());
        assertEquals("Greenhouse hologram shown", GreenhouseHologram.toggle());
        assertTrue(GreenhouseHologram.isDrawn());
        // A complete layout is not special any more: it is deactivated, not hidden behind an override.
        GreenhouseScanner.setCompleteForTest(true);
        assertTrue(GreenhouseHologram.isDrawn());
        assertEquals("Greenhouse hologram hidden", GreenhouseHologram.toggle());
    }

    @Test
    public void theToggleKeyDoesNothingAndSaysNothingWhileNoHologramIsShown() {
        assertFalse(GreenhouseHologram.isShowing());
        assertEquals("no chat message at all", Optional.empty(), GreenhouseHologram.toggleMessage());
        assertTrue("and no state change", GreenhouseHologram.userVisible());
        GreenhouseHologram.toggleVisible();
        assertTrue(GreenhouseHologram.userVisible());

        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        assertEquals(Optional.of("Greenhouse hologram hidden"), GreenhouseHologram.toggleMessage());
        assertFalse(GreenhouseHologram.userVisible());
    }

    @Test
    public void soilGhostsKeepTheirKindAndTheFourArgumentForm() {
        GreenhouseHologram.show(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"), true);
        GreenhouseScanner.setGhostsForTest(List.of(
            new GreenhouseSoilCheck.Ghost(1, 1, GreenhouseSoils.Soil.SAND),
            new GreenhouseSoilCheck.Ghost(2, 2, GreenhouseSoils.Soil.FARMLAND, GreenhouseSoilCheck.GhostKind.WRONG_STATE),
            new GreenhouseSoilCheck.Ghost(3, 3, GreenhouseSoils.Soil.SOUL_SAND,
                GreenhouseSoilCheck.GhostKind.WRONG_BLOCK)), 73);

        var ghosts = GreenhouseHologram.soilGhosts();

        assertEquals(3, ghosts.size());
        assertEquals(new GreenhouseHologram.SoilGhost(1, 1, GreenhouseSoils.Soil.SAND, 73), ghosts.get(0));
        assertEquals(GreenhouseSoilCheck.GhostKind.MISSING, ghosts.get(0).kind());
        assertEquals(GreenhouseSoilCheck.GhostKind.WRONG_STATE, ghosts.get(1).kind());
        assertEquals(GreenhouseSoils.Soil.FARMLAND, ghosts.get(1).soil());
        assertEquals(GreenhouseSoilCheck.GhostKind.WRONG_BLOCK, ghosts.get(2).kind());
        assertEquals(GreenhouseSoils.Soil.SOUL_SAND, ghosts.get(2).soil());
        assertEquals(73, ghosts.get(2).y());
        assertEquals(new net.minecraft.world.phys.AABB(-5 + 2, 73, -5 + 2, -5 + 3, 74, -5 + 3),
            ghosts.get(1).bounds(new net.minecraft.core.BlockPos(-5, 73, -5)));
    }

    private static GreenhouseGrid carrotAndNoctilume() {
        GreenhouseGrid grid = cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot");
        for (int row = 5; row <= 6; row++) {
            for (int column = 0; column <= 1; column++) {
                grid.set(row, column, new GreenhouseGrid.Cell("Noctilume", GreenhouseGrid.TARGET));
            }
        }
        return grid;
    }

    @Test
    public void blockedFootprintsAreTheOnesWithAGhostWhileTheSettingIsOnAndTheHologramIsDrawn() {
        var config = useDefaultConfig();
        GreenhouseHologram.show(carrotAndNoctilume(), true);
        assertTrue("no scan yet", GreenhouseHologram.blockedFootprints().isEmpty());

        // Raw dx 1, dz 6 lies in the 2x2 spot (dx 0..1, dz 5..6), not in the carrot's cell.
        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 6, GreenhouseSoils.Soil.FARMLAND)), 73);
        var blocked = GreenhouseHologram.blockedFootprints();
        assertEquals(1, blocked.size());
        assertTrue(blocked.get(0).mutation());
        assertEquals("Noctilume", blocked.get(0).crop());

        config.setHideModelsOverWrongSoil(false);
        assertTrue("the models are drawn over the ghost: nothing is undrawn", GreenhouseHologram.blockedFootprints().isEmpty());
        config.setHideModelsOverWrongSoil(true);
        assertEquals(1, GreenhouseHologram.blockedFootprints().size());

        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 6, GreenhouseSoils.Soil.FARMLAND)), -1);
        assertTrue("no soil layer, no ghosts drawn, nothing hidden", GreenhouseHologram.blockedFootprints().isEmpty());

        GreenhouseScanner.setGhostsForTest(List.of(new GreenhouseSoilCheck.Ghost(1, 6, GreenhouseSoils.Soil.FARMLAND)), 73);
        GreenhouseHologram.toggle();
        assertTrue("toggled off: nothing is claimed", GreenhouseHologram.blockedFootprints().isEmpty());
        GreenhouseHologram.toggle();

        GreenhouseHologram.rotate();
        assertTrue("a rotate without a re-scan: the old ghosts are not read against the new footprints",
            GreenhouseHologram.blockedFootprints().isEmpty());
    }

    @Test
    public void nothingIsPlantedBeforeAScanAndShowingForgetsOldScans() {
        GreenhouseHologram.show(cropAt(1, 1, GreenhouseGrid.INGREDIENT, "Carrot"), true);

        assertEquals(0, GreenhouseHologram.plantedCount("Carrot"));
        assertEquals(0, GreenhouseHologram.plantedCount("Brand New Crop"));
        assertTrue(GreenhouseScanner.states(GreenhouseHologram.version()) == null);
    }

    @Test
    public void hereShowsTheClipboardLayoutAndSaysDetectionWasSkipped() {
        GreenhouseGrid grid = cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot");

        GreenhouseHologram.ShowResult marked = GreenhouseHologram.showFromTextHere(GreenhouseCode.encode(grid), true)
            .orElseThrow();
        assertTrue(marked.insideGreenhouse());
        assertTrue(marked.message().contains("detection skipped"));
        assertTrue(GreenhouseHologram.isShowing());

        GreenhouseHologram.ShowResult outside = GreenhouseHologram.showFromTextHere(GreenhouseCode.encode(grid), false)
            .orElseThrow();
        assertFalse(outside.insideGreenhouse());
        assertTrue(outside.message().contains("not in a Garden plot"));
        assertTrue("still armed", GreenhouseHologram.isShowing());
    }

    @Test
    public void hereWithoutALayoutChangesNothing() {
        assertTrue(GreenhouseHologram.showFromTextHere("nothing useful", true).isEmpty());
        assertTrue(GreenhouseHologram.showFromTextHere(null, true).isEmpty());
        assertFalse(GreenhouseHologram.isShowing());
    }

    @Test
    public void aShownLayoutIsASnapshot() {
        GreenhouseGrid grid = cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot");
        GreenhouseHologram.show(grid, true);

        grid.set(5, 5, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        GreenhouseHologram.rotate();

        assertEquals(1, GreenhouseHologram.grid().orElseThrow().filled());
        assertEquals(1, GreenhouseHologram.placements().size());
        assertEquals(1, GreenhouseHologram.cellBoxes().size());
        assertEquals(2, GreenhouseHologram.shapes().size());
    }

    @Test
    public void anIngredientIsAnInsetTranslucentCubeOnTheFloor() {
        // Row 2, column 3 lands at dx = 3, dz = 2 in the default orientation.
        var layout = GreenhouseHologramShapes.build(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"),
            GreenhouseOrientation.IDENTITY);

        // The cube is only the last-rung stand-in for the crop's model now.
        Shape cube = layout.placements().getFirst().cube();
        assertEquals(3.12, cube.minX(), EPSILON);
        assertEquals(3.88, cube.maxX(), EPSILON);
        assertEquals(2.12, cube.minZ(), EPSILON);
        assertEquals(2.88, cube.maxZ(), EPSILON);
        assertEquals(0.01, cube.minY(), EPSILON);
        assertEquals(0.91, cube.maxY(), EPSILON);
        assertEquals(0x4D, cube.fill() >>> 24);
        assertEquals(0xE6, cube.outline() >>> 24);
        assertEquals(cube.fill() & 0xFFFFFF, cube.outline() & 0xFFFFFF);
        // The frame and the post are the only boxes besides the models.
        assertEquals(2, layout.shapes().size());
    }

    @Test
    public void sameCropSameColourAndDifferentCropsDiffer() {
        assertEquals(GreenhouseHologramShapes.rgb("Carrot"), GreenhouseHologramShapes.rgb("Carrot"));
        assertNotEquals(GreenhouseHologramShapes.rgb("Carrot"), GreenhouseHologramShapes.rgb("Potato"));
    }

    @Test
    public void aMutationIsOneGoldSlabOverItsWholeFootprint() {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 3; row <= 4; row++) {
            for (int column = 5; column <= 6; column++) {
                grid.set(row, column, new GreenhouseGrid.Cell("Noctilume", GreenhouseGrid.TARGET));
            }
        }

        var layout = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY);

        // Columns 5..6 are x 5..7 and rows 3..4 are z 3..5; 0.04 inset.
        Shape slab = layout.placements().getFirst().slab();
        assertEquals(5.04, slab.minX(), EPSILON);
        assertEquals(6.96, slab.maxX(), EPSILON);
        assertEquals(3.04, slab.minZ(), EPSILON);
        assertEquals(4.96, slab.maxZ(), EPSILON);
        assertEquals(0.16, slab.maxY(), EPSILON);
        assertEquals(0xFFFFD700, slab.outline());
        assertEquals(0x2E, slab.fill() >>> 24);
        // Only the origin frame and post are always drawn; the slab belongs to the mutation spot.
        assertEquals(2, layout.shapes().size());
    }

    @Test
    public void aFootprintStaysSquareWhenRotated() {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 1; row <= 3; row++) {
            for (int column = 6; column <= 8; column++) {
                grid.set(row, column, new GreenhouseGrid.Cell("Snoozling", GreenhouseGrid.TARGET));
            }
        }
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                Shape slab = GreenhouseHologramShapes.build(grid, new GreenhouseOrientation(rotation, mirror))
                    .placements().getFirst().slab();
                assertEquals(3 - 0.08, slab.maxX() - slab.minX(), EPSILON);
                assertEquals(3 - 0.08, slab.maxZ() - slab.minZ(), EPSILON);
            }
        }
    }

    @Test
    public void cellZeroZeroIsAlwaysMarkedWithAFrameAndAPost() {
        List<Shape> shapes = shapesOf(new GreenhouseGrid(), GreenhouseOrientation.IDENTITY);

        assertEquals(2, shapes.size());
        Shape frame = shapes.get(0);
        Shape post = shapes.get(1);
        // Default: cell (0, 0) is dx 0, dz 0, the north-west corner of the plot.
        assertEquals(0.02, frame.minX(), EPSILON);
        assertEquals(0.98, frame.maxX(), EPSILON);
        assertEquals(0.02, frame.minZ(), EPSILON);
        assertEquals(4.0F, frame.width(), 0);
        assertEquals(0xFFFFFFFF, frame.outline());
        assertTrue(post.maxY() > 1.5);
        // Hugging the grid's outer corner (x 0, z 0) inside the cell.
        assertEquals(0.04, post.minX(), EPSILON);
        assertEquals(0.24, post.maxX(), EPSILON);
        assertEquals(0.04, post.minZ(), EPSILON);
        assertEquals(0.24, post.maxZ(), EPSILON);
    }

    @Test
    public void theMarkerFollowsTheOrientation() {
        List<Shape> mirrored = shapesOf(new GreenhouseGrid(), GreenhouseOrientation.IDENTITY.mirrored());

        // Mirrored: cell (0, 0) is dx 9, dz 0 and the outer corner is (10, 0).
        assertEquals(9.02, mirrored.get(0).minX(), EPSILON);
        assertEquals(0.02, mirrored.get(0).minZ(), EPSILON);
        assertEquals(9.76, mirrored.get(1).minX(), EPSILON);
        assertEquals(0.04, mirrored.get(1).minZ(), EPSILON);
    }
}
