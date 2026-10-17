package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.CellBox;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Layout;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

/** Where the hologram's models stand and how big they are; drawing them is not testable. */
public final class GreenhouseHologramModelsTest {
    private static final double EPSILON = 1.0E-9;

    private static GreenhouseGrid with(int row, int column, int flag, String name) {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(row, column, new GreenhouseGrid.Cell(name, flag));
        return grid;
    }

    private static void block(GreenhouseGrid grid, String name, int row, int column, int size) {
        for (int r = row; r < row + size; r++) {
            for (int c = column; c < column + size; c++) {
                grid.set(r, c, new GreenhouseGrid.Cell(name, GreenhouseGrid.TARGET));
            }
        }
    }

    @Test
    public void aCropIsAHeadSizedModelOnTheCentreOfItsCell() {
        // Row 2, column 3 is dx = 7, dz = 3 in the default orientation.
        Layout layout = GreenhouseHologramShapes.build(with(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"),
            GreenhouseOrientation.IDENTITY);

        assertEquals(1, layout.placements().size());
        Placement crop = layout.placements().getFirst();
        assertEquals("Carrot", crop.crop());
        assertFalse(crop.mutation());
        assertEquals(7.5, crop.x(), EPSILON);
        assertEquals(3.5, crop.z(), EPSILON);
        assertEquals(GreenhouseHologramShapes.LIFT, crop.y(), EPSILON);
        assertEquals(1.1875, crop.scale(), EPSILON);
        assertEquals(GreenhouseCropLook.of("Carrot"), crop.look());
        assertNotNull("the coloured cube is the last-rung stand-in", crop.cube());
    }

    @Test
    public void bonusCellsAreModelledToo() {
        Layout layout = GreenhouseHologramShapes.build(with(0, 0, GreenhouseGrid.BONUS, "Sunflower"),
            GreenhouseOrientation.IDENTITY);

        assertEquals(1, layout.placements().size());
        assertEquals("Sunflower", layout.placements().getFirst().crop());
    }

    @Test
    public void cropModelsFollowTheOrientation() {
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                GreenhouseOrientation orientation = new GreenhouseOrientation(rotation, mirror);
                Placement crop = GreenhouseHologramShapes.build(with(2, 3, GreenhouseGrid.INGREDIENT, "Potato"),
                    orientation).placements().getFirst();
                GreenhouseOrientation.Offset at = orientation.offset(2, 3);
                assertEquals(at.dx() + 0.5, crop.x(), EPSILON);
                assertEquals(at.dz() + 0.5, crop.z(), EPSILON);
            }
        }
    }

    @Test
    public void aMutationIsOneHeadScaledToItsFootprintAndCentredOnIt() {
        GreenhouseGrid grid = new GreenhouseGrid();
        block(grid, "Cindershade", 0, 0, 1);
        block(grid, "Snoozling", 1, 6, 3);
        block(grid, "Noctilume", 5, 0, 2);

        Layout layout = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY);

        assertEquals("one model per footprint, none for the target cells", 3, layout.placements().size());
        double standingOnTheSlab = GreenhouseHologramShapes.LIFT + GreenhouseHologramShapes.SLAB_HEIGHT;
        for (Placement placement : layout.placements()) {
            assertTrue(placement.crop(), placement.mutation());
            assertNull("the slab marks the spot, no cube", placement.cube());
            assertEquals(standingOnTheSlab, placement.y(), EPSILON);
            assertTrue(GreenhouseCropLook.of(placement.crop()).head() != null);
        }
        // Footprints come in row-major order of their top-left cells.
        Placement small = layout.placements().get(0);
        assertEquals("Cindershade", small.crop());
        assertEquals(1.0, small.scale(), EPSILON);
        assertEquals(9.5, small.x(), EPSILON); // row 0 is dx 9
        assertEquals(0.5, small.z(), EPSILON);
        Placement large = layout.placements().get(1);
        assertEquals("Snoozling", large.crop());
        assertEquals(3.0, large.scale(), EPSILON);
        assertEquals(7.5, large.x(), EPSILON); // rows 1..3 are x 6..9
        assertEquals(7.5, large.z(), EPSILON); // columns 6..8 are z 6..9
        Placement medium = layout.placements().get(2);
        assertEquals("Noctilume", medium.crop());
        assertEquals(2.0, medium.scale(), EPSILON);
        assertEquals(4.0, medium.x(), EPSILON); // rows 5..6 are x 3..5
        assertEquals(1.0, medium.z(), EPSILON); // columns 0..1 are z 0..2
    }

    @Test
    public void aMutationStaysCentredOnItsFootprintWhenRotatedAndMirrored() {
        GreenhouseGrid grid = new GreenhouseGrid();
        block(grid, "Snoozling", 1, 6, 3);
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                GreenhouseOrientation orientation = new GreenhouseOrientation(rotation, mirror);
                Placement placement = GreenhouseHologramShapes.build(grid, orientation).placements().getFirst();
                // The centre cell of the 3x3 is row 2, column 7.
                GreenhouseOrientation.Offset centre = orientation.offset(2, 7);
                assertEquals(centre.dx() + 0.5, placement.x(), EPSILON);
                assertEquals(centre.dz() + 0.5, placement.z(), EPSILON);
                assertEquals(3.0, placement.scale(), EPSILON);
            }
        }
    }

    @Test
    public void cropsAndMutationsMix() {
        GreenhouseGrid grid = new GreenhouseGrid();
        block(grid, "Noctilume", 4, 4, 2);
        grid.set(0, 0, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        grid.set(9, 9, new GreenhouseGrid.Cell("Brown Mushroom", GreenhouseGrid.INGREDIENT));

        Layout layout = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY);

        assertEquals(3, layout.placements().size());
        assertEquals(2, layout.placements().stream().filter(p -> !p.mutation()).count());
        assertEquals("every filled cell, targets included", 6, layout.cells().size());
        // frame + post; the slab belongs to the mutation's placement
        assertEquals(2, layout.shapes().size());
        assertNotNull(layout.placements().stream().filter(p -> p.mutation()).findFirst().orElseThrow().slab());
    }

    @Test
    public void cellBoxesCoverEveryFilledCellWithTheirIdentity() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(2, 3, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(5, 5, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));
        grid.set(9, 0, new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS));

        var cells = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY).cells();

        assertEquals(3, cells.size());
        CellBox carrot = cells.getFirst();
        assertEquals(2, carrot.row());
        assertEquals(3, carrot.column());
        assertEquals("Carrot", carrot.crop());
        assertEquals(GreenhouseGrid.INGREDIENT, carrot.flag());
        assertEquals(7, carrot.minX(), EPSILON);
        assertEquals(8, carrot.maxX(), EPSILON);
        assertEquals(3, carrot.minZ(), EPSILON);
        assertEquals(4, carrot.maxZ(), EPSILON);
        assertEquals(0, carrot.minY(), EPSILON);
        assertEquals(1, carrot.maxY(), EPSILON);
        assertEquals(GreenhouseGrid.TARGET, cells.get(1).flag());
        assertEquals("Sunflower", cells.get(2).crop());
        assertEquals(GreenhouseGrid.BONUS, cells.get(2).flag());
    }

    @Test
    public void cellBoxesFollowTheOrientationAndNeverOverlap() {
        GreenhouseGrid full = new GreenhouseGrid();
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                full.set(row, column, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
            }
        }
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[] {false, true}) {
                GreenhouseOrientation orientation = new GreenhouseOrientation(rotation, mirror);
                Set<GreenhouseOrientation.Offset> seen = new HashSet<>();
                for (CellBox box : GreenhouseHologramShapes.build(full, orientation).cells()) {
                    GreenhouseOrientation.Offset at = orientation.offset(box.row(), box.column());
                    assertEquals(at.dx(), box.minX(), EPSILON);
                    assertEquals(at.dz(), box.minZ(), EPSILON);
                    seen.add(at);
                }
                assertEquals(100, seen.size());
            }
        }
    }

    @Test
    public void cellBoxesInWorldCoordinatesSitOnTheHologramFloor() {
        CellBox box = GreenhouseHologramShapes.build(with(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"),
            GreenhouseOrientation.IDENTITY).cells().getFirst();

        AABB world = box.bounds(new BlockPos(-5, 73, -5));

        assertEquals(new AABB(2, 74, -2, 3, 75, -1), world);
    }

    @Test
    public void aCellBoxShowsSomethingUnlessTheRightPlantAlreadyStandsThere() {
        CellBox box = GreenhouseHologramShapes.build(with(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"),
            GreenhouseOrientation.IDENTITY).cells().getFirst();

        assertTrue(box.visible());
        // The ten-argument form, as callers had it, still means "shows something".
        assertTrue(new CellBox(0, 0, "Carrot", 1, 0, 0, 0, 1, 1, 1).visible());
        assertFalse(box.withVisible(false).visible());
        assertEquals(box, box.withVisible(false).withVisible(true));
    }

    @Test
    public void visibleCellsFollowThePlotScan() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Potato", GreenhouseGrid.INGREDIENT));
        grid.set(0, 2, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        grid.set(0, 3, new GreenhouseGrid.Cell("Cactus", GreenhouseGrid.INGREDIENT));
        block(grid, "Noctilume", 5, 5, 2);
        Layout layout = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY);
        // Placements: the four crops in row-major order, then the mutation.
        assertEquals(5, layout.placements().size());

        var states = java.util.List.of(GreenhouseScanLogic.State.CORRECT, GreenhouseScanLogic.State.EMPTY,
            GreenhouseScanLogic.State.WRONG, GreenhouseScanLogic.State.UNVERIFIED, GreenhouseScanLogic.State.CORRECT);
        var cells = GreenhouseHologramShapes.visibleCells(layout, states);

        assertEquals(8, cells.size());
        assertFalse("the right plant: nothing drawn", cells.get(0).visible());
        assertTrue("empty: the model", cells.get(1).visible());
        assertTrue("wrong: the red box", cells.get(2).visible());
        assertTrue("unverified: the orange outline", cells.get(3).visible());
        for (int i = 4; i < 8; i++) {
            assertEquals(GreenhouseGrid.TARGET, cells.get(i).flag());
            assertFalse("the mutation stands on its spot: all four cells", cells.get(i).visible());
        }
    }

    @Test
    public void withoutAMatchingScanEveryCellShowsItsModel() {
        Layout layout = GreenhouseHologramShapes.build(with(2, 3, GreenhouseGrid.INGREDIENT, "Carrot"),
            GreenhouseOrientation.IDENTITY);

        assertEquals(layout.cells(), GreenhouseHologramShapes.visibleCells(layout, null));
        assertTrue(GreenhouseHologramShapes.visibleCells(layout, null).getFirst().visible());
    }

    @Test
    public void anEmptyGridHasNoModelsOrCells() {
        Layout layout = GreenhouseHologramShapes.build(new GreenhouseGrid(), GreenhouseOrientation.IDENTITY);

        assertTrue(layout.placements().isEmpty());
        assertTrue(layout.cells().isEmpty());
        assertEquals(2, layout.shapes().size());
    }
}
