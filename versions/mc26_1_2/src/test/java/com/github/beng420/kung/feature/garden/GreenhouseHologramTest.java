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
        // Row 2, column 3 lands at dx = 7, dz = 3 in the default orientation.
        var layout = GreenhouseHologramShapes.build(cropAt(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"),
            GreenhouseOrientation.IDENTITY);

        // The cube is only the last-rung stand-in for the crop's model now.
        Shape cube = layout.placements().getFirst().cube();
        assertEquals(7.12, cube.minX(), EPSILON);
        assertEquals(7.88, cube.maxX(), EPSILON);
        assertEquals(3.12, cube.minZ(), EPSILON);
        assertEquals(3.88, cube.maxZ(), EPSILON);
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

        // Rows 3..4 are x 5..7 and columns 5..6 are z 5..7; 0.04 inset.
        Shape slab = layout.placements().getFirst().slab();
        assertEquals(5.04, slab.minX(), EPSILON);
        assertEquals(6.96, slab.maxX(), EPSILON);
        assertEquals(5.04, slab.minZ(), EPSILON);
        assertEquals(6.96, slab.maxZ(), EPSILON);
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
        // Default: cell (0, 0) is dx 9, dz 0.
        assertEquals(9.02, frame.minX(), EPSILON);
        assertEquals(9.98, frame.maxX(), EPSILON);
        assertEquals(0.02, frame.minZ(), EPSILON);
        assertEquals(4.0F, frame.width(), 0);
        assertEquals(0xFFFFFFFF, frame.outline());
        assertTrue(post.maxY() > 1.5);
        // Hugging the grid's outer corner (x 10, z 0) inside the cell.
        assertEquals(9.76, post.minX(), EPSILON);
        assertEquals(9.96, post.maxX(), EPSILON);
        assertEquals(0.04, post.minZ(), EPSILON);
        assertEquals(0.24, post.maxZ(), EPSILON);
    }

    @Test
    public void theMarkerFollowsTheOrientation() {
        List<Shape> mirrored = shapesOf(new GreenhouseGrid(), GreenhouseOrientation.IDENTITY.mirrored());

        // Mirrored: cell (0, 0) is dx 9, dz 9 and the outer corner is (10, 10).
        assertEquals(9.02, mirrored.get(0).minX(), EPSILON);
        assertEquals(9.02, mirrored.get(0).minZ(), EPSILON);
        assertEquals(9.76, mirrored.get(1).minZ(), EPSILON);
    }
}
