package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Layout;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Footprint;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.GhostKind;
import com.github.beng420.kung.feature.garden.GreenhouseSoils.Soil;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class GreenhouseFocusTest {
    private static final String WHEAT_SEEDS_ID = "SEEDS";

    /** Wheat Seeds, Carrot and Melon Seeds side by side; returns the layout built the way the hologram builds it. */
    private static Layout layout() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        grid.set(0, 2, new GreenhouseGrid.Cell("Melon Seeds", GreenhouseGrid.INGREDIENT));
        return GreenhouseHologramShapes.build(grid, GreenhouseOrientation.IDENTITY);
    }

    private static int indexOf(Layout layout, String crop) {
        for (int i = 0; i < layout.placements().size(); i++) {
            if (layout.placements().get(i).crop().equals(crop)) return i;
        }
        throw new AssertionError(crop);
    }

    /** A soil ghost in the cell of the crop's footprint. */
    private static Ghost ghostUnder(Layout layout, String crop, Soil soil) {
        Footprint footprint = GreenhouseScanLogic.footprints(layout.placements()).get(indexOf(layout, crop));
        return new Ghost((int) Math.floor(footprint.minX()), (int) Math.floor(footprint.minZ()), soil, GhostKind.MISSING);
    }

    private static List<State> states(Layout layout, State wheat, State carrot, State melon) {
        State[] out = new State[3];
        out[indexOf(layout, "Wheat Seeds")] = wheat;
        out[indexOf(layout, "Carrot")] = carrot;
        out[indexOf(layout, "Melon Seeds")] = melon;
        return List.of(out);
    }

    @Test
    public void everyItemThatPlantsACropBelongsToIt() {
        assertEquals(Set.of("Melon Seeds"), GreenhouseFocus.held("MELON").orElseThrow().crops());
        assertEquals(Set.of("Melon Seeds"), GreenhouseFocus.held("MELON_SEEDS").orElseThrow().crops());
        assertEquals(Set.of("Pumpkin Seeds"), GreenhouseFocus.held("PUMPKIN").orElseThrow().crops());
        assertEquals(Set.of("Pumpkin Seeds"), GreenhouseFocus.held("PUMPKIN_SEEDS").orElseThrow().crops());
        assertEquals(Set.of("Wheat Seeds"), GreenhouseFocus.held(WHEAT_SEEDS_ID).orElseThrow().crops());
        // A mutation is held as its own item, and a variant is spelled either way.
        assertEquals(Set.of("Duskbloom"), GreenhouseFocus.held("DUSKBLOOM").orElseThrow().crops());
        assertEquals(Set.of("Cocoa Beans"), GreenhouseFocus.held("INK_SACK:3").orElseThrow().crops());
        assertEquals(Set.of("Cocoa Beans"), GreenhouseFocus.held("INK_SACK-3").orElseThrow().crops());
        assertEquals(Soil.NONE, GreenhouseFocus.held("MELON").orElseThrow().soil());
    }

    @Test
    public void aSoilBlockBelongsToItsSoilAndNoCrop() {
        GreenhouseFocus.Held dirt = GreenhouseFocus.held("DIRT").orElseThrow();
        assertEquals(Soil.DIRT, dirt.soil());
        assertTrue(dirt.crops().isEmpty());
        assertEquals(Soil.MYCELIUM, GreenhouseFocus.held("MYCEL").orElseThrow().soil());
        assertEquals(Soil.SAND, GreenhouseFocus.held("SAND").orElseThrow().soil());
    }

    @Test
    public void anythingElseInHandLeavesTheHologramAlone() {
        assertTrue(GreenhouseFocus.held("").isEmpty());
        assertTrue(GreenhouseFocus.held(null).isEmpty());
        assertTrue(GreenhouseFocus.held("HYPERION").isEmpty());
        Layout layout = layout();
        assertTrue(GreenhouseFocus.of("HYPERION", layout.placements(), null, List.of()).isEmpty());
    }

    @Test
    public void aHeldCropShowsOnlyItsOwnSpots() {
        Layout layout = layout();
        List<State> states = states(layout, State.EMPTY, State.EMPTY, State.WRONG);
        GreenhouseFocus.Focus focus = GreenhouseFocus.of(WHEAT_SEEDS_ID, layout.placements(), states, List.of())
            .orElseThrow();

        assertTrue(focus.shown()[indexOf(layout, "Wheat Seeds")]);
        assertFalse(focus.shown()[indexOf(layout, "Carrot")]);
        assertFalse(focus.shown()[indexOf(layout, "Melon Seeds")]);
        assertEquals(2, focus.hidden().size());

        boolean[] blocked = new boolean[3];
        blocked[indexOf(layout, "Wheat Seeds")] = true;
        GreenhouseFocus.View view = focus.narrow(layout, states, blocked, List.of());
        assertEquals(1, view.layout().placements().size());
        assertEquals("Wheat Seeds", view.layout().placements().get(0).crop());
        assertTrue("the marker and cell volumes go with the rest", view.layout().shapes().isEmpty());
        assertEquals(List.of(State.EMPTY), view.states());
        assertTrue(view.blocked()[0]); // the flags stay with their placement
        assertEquals(1, view.blocked().length);
    }

    @Test
    public void bothItemsOfTheMelonShowTheMelonSpots() {
        Layout layout = layout();
        for (String id : List.of("MELON", "MELON_SEEDS")) {
            GreenhouseFocus.Focus focus = GreenhouseFocus.of(id, layout.placements(), null, List.of()).orElseThrow();
            assertTrue(id, focus.shown()[indexOf(layout, "Melon Seeds")]);
            assertFalse(id, focus.shown()[indexOf(layout, "Wheat Seeds")]);
        }
    }

    @Test
    public void withoutAScanEverySpotIsStillToPlace() {
        Layout layout = layout();
        GreenhouseFocus.Focus focus = GreenhouseFocus.of(WHEAT_SEEDS_ID, layout.placements(), null, List.of()).orElseThrow();
        assertNull(focus.narrow(layout, null, null, List.of()).states());
        assertNull(focus.narrow(layout, null, null, List.of()).blocked());
    }

    @Test
    public void aFinishedCropOrOneTheLayoutDoesNotUseKeepsTheWholeHologram() {
        Layout layout = layout();
        List<State> done = states(layout, State.CORRECT, State.EMPTY, State.EMPTY);
        // Every Wheat Seeds spot is right: the player is looking for the next thing to place.
        assertTrue(GreenhouseFocus.of(WHEAT_SEEDS_ID, layout.placements(), done, List.of()).isEmpty());
        // Cactus is not in the layout at all.
        assertTrue(GreenhouseFocus.of("CACTUS", layout.placements(), null, List.of()).isEmpty());
        // A wrong crop on the spot still needs the player.
        List<State> wrong = states(layout, State.WRONG, State.EMPTY, State.EMPTY);
        assertTrue(GreenhouseFocus.of(WHEAT_SEEDS_ID, layout.placements(), wrong, List.of()).isPresent());
    }

    @Test
    public void aHeldSoilShowsOnlyItsBlocksAndHidesEveryCrop() {
        Layout layout = layout();
        Ghost farmland = ghostUnder(layout, "Wheat Seeds", Soil.FARMLAND);
        Ghost dirt = ghostUnder(layout, "Carrot", Soil.DIRT);
        Ghost sand = ghostUnder(layout, "Melon Seeds", Soil.SAND);
        List<Ghost> ghosts = List.of(farmland, dirt, sand);

        GreenhouseFocus.Focus focus = GreenhouseFocus.of("DIRT", layout.placements(), null, ghosts).orElseThrow();

        // Dirt is placed on both Dirt and Farmland spots; the Sand spot waits for Sand.
        assertEquals(List.of(farmland, dirt), focus.shownGhosts(ghosts));
        assertEquals(0, focus.narrow(layout, null, null, ghosts).layout().placements().size());
        assertEquals(3, focus.hidden().size());
    }

    @Test
    public void aHeldSoilWithNothingToFixKeepsTheWholeHologram() {
        Layout layout = layout();
        Ghost sand = ghostUnder(layout, "Melon Seeds", Soil.SAND);
        assertTrue(GreenhouseFocus.of("DIRT", layout.placements(), null, List.of(sand)).isEmpty());
        assertTrue(GreenhouseFocus.of("DIRT", layout.placements(), null, List.of()).isEmpty());
    }

    @Test
    public void aCropKeepsTheSoilUnderItsOwnSpotsOnly() {
        Layout layout = layout();
        Ghost underWheat = ghostUnder(layout, "Wheat Seeds", Soil.FARMLAND);
        Ghost underCarrot = ghostUnder(layout, "Carrot", Soil.FARMLAND);
        List<Ghost> ghosts = List.of(underWheat, underCarrot);
        // Every wheat spot is placed right, yet the soil under one is missing: that still needs the player.
        List<State> states = states(layout, State.CORRECT, State.EMPTY, State.EMPTY);

        GreenhouseFocus.Focus focus = GreenhouseFocus.of(WHEAT_SEEDS_ID, layout.placements(), states, ghosts).orElseThrow();

        assertEquals(List.of(underWheat), focus.shownGhosts(ghosts));
        assertEquals(List.of(underWheat), focus.narrow(layout, states, null, ghosts).ghosts());
    }
}
