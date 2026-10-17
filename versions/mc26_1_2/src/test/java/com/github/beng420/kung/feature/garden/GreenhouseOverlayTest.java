package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Layout;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Shape;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Extra;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.GhostKind;
import com.github.beng420.kung.feature.garden.GreenhouseSoils.Soil;
import java.util.List;
import org.junit.Test;

public final class GreenhouseOverlayTest {
    private static final double EPSILON = 1.0E-9;
    private static final GreenhousePalette PALETTE = new GreenhousePalette(0.30F, 0x4D8DFF, 0xFF3B3B, 0xFF55D8, 0xFFA126);

    /** A Carrot crop at (0, 0) and a one-cell Cindershade spot at (5, 5): placements 0 and 1. */
    private static Layout layout() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(5, 5, new GreenhouseGrid.Cell("Cindershade", GreenhouseGrid.TARGET));
        Layout layout = GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY);
        assertEquals(2, layout.placements().size());
        return layout;
    }

    private static List<Shape> boxes(List<State> states, List<Extra> extras, List<Ghost> ghosts, int soilLayer) {
        return GreenhouseOverlay.boxes(layout(), states, extras, ghosts, soilLayer, PALETTE);
    }

    @Test
    public void somethingStillToPlaceIsOutlinedInBlue() {
        List<Shape> boxes = boxes(List.of(State.EMPTY, State.EMPTY), List.of(), List.of(), -1);
        Placement crop = layout().placements().get(0);

        Shape outline = boxes.get(0);
        assertEquals(crop.area().minX(), outline.minX(), EPSILON);
        assertEquals(crop.area().maxZ(), outline.maxZ(), EPSILON);
        assertEquals("blue outline", PALETTE.placeOutline(), outline.outline());
        assertEquals("no fill: the model is the fill", 0, outline.fill());
    }

    @Test
    public void aMutationSpotGetsABlueOutlineAndAThinBlueSlabInsteadOfTheGoldOne() {
        List<Shape> boxes = boxes(List.of(State.CORRECT, State.EMPTY), List.of(), List.of(), -1);

        assertEquals(2, boxes.size());
        Shape outline = boxes.get(0);
        Shape slab = boxes.get(1);
        assertEquals(PALETTE.placeOutline(), outline.outline());
        assertEquals("a translucent slab in the to-place colour", PALETTE.placeFill(), slab.fill());
        assertEquals(0, slab.outline());
        assertEquals(GreenhouseHologramShapes.LIFT + GreenhouseHologramShapes.SLAB_HEIGHT, slab.maxY(), EPSILON);
        assertTrue(slab.maxY() < 0.2);
        assertTrue("not the gold of old", (slab.fill() & 0xFFFFFF) != 0xFFD700);
    }

    @Test
    public void noScanMeansEverythingIsStillToPlace() {
        assertEquals(boxes(List.of(State.EMPTY, State.EMPTY), List.of(), List.of(), -1), boxes(null, List.of(),
            List.of(), -1));
        assertEquals(3, boxes(null, List.of(), List.of(), -1).size());
        assertEquals("a short list: the rest is still to place", 3,
            boxes(List.of(State.EMPTY), List.of(), List.of(), -1).size());
    }

    @Test
    public void aCorrectPlantDrawsNothing() {
        assertTrue(boxes(List.of(State.CORRECT, State.CORRECT), List.of(), List.of(), -1).isEmpty());
    }

    @Test
    public void aWrongCropIsAReddishBoxWithABrightRedOutline() {
        List<Shape> boxes = boxes(List.of(State.WRONG, State.CORRECT), List.of(), List.of(), -1);

        assertEquals(1, boxes.size());
        assertEquals(PALETTE.wrongFill(), boxes.getFirst().fill());
        assertEquals(PALETTE.wrongOutline(), boxes.getFirst().outline());
        assertEquals(0xFF3B3B, boxes.getFirst().fill() & 0xFFFFFF);
        assertTrue(boxes.getFirst().width() >= 3.0F);
    }

    @Test
    public void somethingUnrecognisedIsANeutralOutlineNotOrange() {
        List<Shape> boxes = boxes(List.of(State.UNVERIFIED, State.CORRECT), List.of(), List.of(), -1);

        assertEquals(1, boxes.size());
        assertEquals(0, boxes.getFirst().fill());
        assertEquals(PALETTE.unverifiedOutline(), boxes.getFirst().outline());
        assertTrue((boxes.getFirst().outline() & 0xFFFFFF) != 0xFFA126);
    }

    @Test
    public void aCropWhereTheLayoutHasNoneIsAPinkBoxOnItsCell() {
        List<Shape> boxes = boxes(List.of(State.CORRECT, State.CORRECT), List.of(new Extra(3, 4, "Cactus")), List.of(),
            -1);

        assertEquals(1, boxes.size());
        Shape pink = boxes.getFirst();
        assertEquals(PALETTE.extraFill(), pink.fill());
        assertEquals(PALETTE.extraOutline(), pink.outline());
        assertEquals(0xFF55D8, pink.fill() & 0xFFFFFF);
        assertTrue(pink.minX() > 3 && pink.maxX() < 4);
        assertTrue(pink.minZ() > 4 && pink.maxZ() < 5);
    }

    @Test
    public void soilBoxesDependOnHowTheBlockDiffers() {
        List<Ghost> ghosts = List.of(new Ghost(1, 2, Soil.FARMLAND, GhostKind.MISSING),
            new Ghost(3, 4, Soil.FARMLAND, GhostKind.WRONG_BLOCK), new Ghost(5, 6, Soil.FARMLAND, GhostKind.WRONG_STATE));
        List<State> correct = List.of(State.CORRECT, State.CORRECT);

        List<Shape> boxes = boxes(correct, List.of(), ghosts, 73);

        assertEquals(3, boxes.size());
        Shape missing = boxes.get(0);
        Shape wrongBlock = boxes.get(1);
        Shape wrongState = boxes.get(2);
        assertEquals("missing: a blue outline, the block itself is a model", 0, missing.fill());
        assertEquals(PALETTE.placeOutline(), missing.outline());
        assertEquals("another block: red", PALETTE.wrongFill(), wrongBlock.fill());
        assertEquals(PALETTE.wrongOutline(), wrongBlock.outline());
        assertEquals("farmland and dirt swapped: orange", PALETTE.wrongStateFill(), wrongState.fill());
        assertEquals(PALETTE.wrongStateOutline(), wrongState.outline());
        assertEquals(0xFFA126, wrongState.outline() & 0xFFFFFF);
    }

    @Test
    public void soilBoxesSitOnTheSoilLayerAndCoverTheBlock() {
        Ghost ghost = new Ghost(1, 2, Soil.FARMLAND, GhostKind.WRONG_STATE);

        Shape box = boxes(List.of(State.CORRECT, State.CORRECT), List.of(), List.of(ghost), 73).getFirst();

        // y is relative to the hologram floor (74): the soil layer 73 is one block below it.
        assertEquals(-1, box.minY(), 0.01);
        assertEquals(0, box.maxY(), 0.01);
        assertTrue("a hair larger than the block", box.minX() < 1 && box.maxX() > 2 && box.minZ() < 2 && box.maxZ() > 3);
        assertEquals(1.0, box.maxX() - box.minX(), 0.01);

        Shape lower = boxes(List.of(State.CORRECT, State.CORRECT), List.of(), List.of(ghost), 72).getFirst();
        assertEquals(-2, lower.minY(), 0.01);
    }

    @Test
    public void withoutASoilLayerThereAreNoSoilBoxes() {
        List<Ghost> ghosts = List.of(new Ghost(1, 2, Soil.FARMLAND, GhostKind.WRONG_BLOCK));

        assertTrue(boxes(List.of(State.CORRECT, State.CORRECT), List.of(), ghosts, -1).isEmpty());
    }

    @Test
    public void theCubeStandInIsTheCropsHueMovedTowardBlue() {
        Placement crop = layout().placements().get(0);

        Shape cube = GreenhouseOverlay.cube(crop, PALETTE);

        assertEquals(crop.cube().minX(), cube.minX(), EPSILON);
        assertEquals("a fill only: the outline box around the cell is the outline", 0, cube.outline());
        int hue = crop.cube().outline() & 0xFFFFFF;
        assertEquals(PALETTE.shifted(hue), cube.fill() & 0xFFFFFF);
        assertEquals(PALETTE.fill(PALETTE.shifted(hue), 1F), cube.fill());
        assertNull("a mutation spot has no cube", GreenhouseOverlay.cube(layout().placements().get(1), PALETTE));
    }

    @Test
    public void theColoursFollowThePalette() {
        GreenhousePalette green = new GreenhousePalette(0.6F, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF);

        Shape outline = GreenhouseOverlay.boxes(layout(), List.of(State.EMPTY, State.CORRECT), List.of(), List.of(), -1,
            green).getFirst();
        Shape wrong = GreenhouseOverlay.boxes(layout(), List.of(State.WRONG, State.CORRECT), List.of(), List.of(), -1,
            green).getFirst();

        assertEquals(0x00FF00, outline.outline() & 0xFFFFFF);
        assertEquals(0x0000FF, wrong.fill() & 0xFFFFFF);
        assertEquals(Math.round(0.6F * 1.2F * 255F), wrong.fill() >>> 24);
    }
}
