package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhousePickHook.Cell;
import com.github.beng420.kung.feature.garden.GreenhousePickHook.Hit;
import com.github.beng420.kung.feature.garden.GreenhousePickHook.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public final class GreenhousePickHookTest {
    private static final double INF = Double.POSITIVE_INFINITY;

    private static Cell cell(String crop, int flag, double x) {
        return new Cell(crop, flag, new AABB(x, 0, 0, x + 1, 1, 1));
    }

    /** From x = -5 along +x at the cells' mid height, 10 blocks of reach. */
    private static View along(double reach, double realDistance) {
        return new View(new Vec3(-5, 0.5, 0.5), new Vec3(1, 0, 0), reach, realDistance);
    }

    @Test
    public void theNearestOfSeveralCellsOnTheRayIsPicked() {
        List<Cell> cells = List.of(cell("Potato", 1, 4), cell("Carrot", 1, 0), cell("Cactus", 2, 2));

        Hit hit = GreenhousePickHook.nearest(along(10, INF), cells).orElseThrow();

        assertEquals("Carrot", hit.crop());
        assertEquals(1, hit.flag());
        assertEquals(5.0, hit.distance(), 1e-9);
    }

    @Test
    public void aRayThatMissesEveryCellPicksNothing() {
        View up = new View(new Vec3(-5, 0.5, 0.5), new Vec3(0, 1, 0), 10, INF);
        View wide = new View(new Vec3(-5, 0.5, 5), new Vec3(1, 0, 0), 10, INF);

        assertTrue(GreenhousePickHook.nearest(up, List.of(cell("Carrot", 1, 0))).isEmpty());
        assertTrue(GreenhousePickHook.nearest(wide, List.of(cell("Carrot", 1, 0))).isEmpty());
        assertTrue(GreenhousePickHook.nearest(along(10, INF), List.of()).isEmpty());
    }

    @Test
    public void aRealHitNearerThanTheCellLeavesThePickToVanilla() {
        List<Cell> cells = List.of(cell("Carrot", 1, 0));

        assertTrue(GreenhousePickHook.nearest(along(10, 3.0), cells).isEmpty());
        assertTrue(GreenhousePickHook.nearest(along(10, 4.999), cells).isEmpty());
        assertEquals("Carrot", GreenhousePickHook.nearest(along(10, 5.0), cells).orElseThrow().crop());
        assertEquals("Carrot", GreenhousePickHook.nearest(along(10, 8.0), cells).orElseThrow().crop());
    }

    @Test
    public void aRealHitBehindTheCellDoesNotHideIt() {
        // The floor under a cell is hit where the ray leaves the cell, never before it enters.
        Hit hit = GreenhousePickHook.nearest(along(10, 5.5), List.of(cell("Carrot", 1, 0))).orElseThrow();

        assertEquals(5.0, hit.distance(), 1e-9);
    }

    @Test
    public void aCellBeyondReachIsNotPicked() {
        List<Cell> cells = List.of(cell("Carrot", 1, 0));

        assertTrue(GreenhousePickHook.nearest(along(4.9, INF), cells).isEmpty());
        assertTrue(GreenhousePickHook.nearest(along(5.1, INF), cells).isPresent());
    }

    @Test
    public void aMutationSpotIsPickedToo() {
        Hit hit = GreenhousePickHook.nearest(along(10, INF), List.of(cell("Ashwreath", GreenhouseGrid.TARGET, 0)))
            .orElseThrow();

        assertEquals("Ashwreath", hit.crop());
        assertEquals(GreenhouseGrid.TARGET, hit.flag());
    }

    @Test
    public void aDiagonalRayHitsTheFirstFaceItReaches() {
        double side = Math.sqrt(0.5);
        View diagonal = new View(new Vec3(3.5, 3.0, 0.5), new Vec3(-side, -side, 0), 10, INF);
        List<Cell> cells = List.of(new Cell("Behind", 1, new AABB(-3, -3, 0, -2, -2, 1)),
            new Cell("Carrot", 1, new AABB(0, 0, 0, 1, 1, 1)));

        Hit hit = GreenhousePickHook.nearest(diagonal, cells).orElseThrow();

        assertEquals("Carrot", hit.crop());
        assertEquals(2.5 * Math.sqrt(2), hit.distance(), 1e-9);
    }

    @Test
    public void cellVolumesFollowTheGreenhouseCorner() {
        var box = new GreenhouseHologramShapes.CellBox(0, 0, "Carrot", 1, 2, 0, 3, 3, 1, 4);

        Cell cell = GreenhousePickHook.worldCells(List.of(box), new BlockPos(100, 0, 200)).get(0);

        assertEquals("Carrot", cell.crop());
        assertEquals(new AABB(102, GreenhouseLocator.HOLOGRAM_Y, 203, 103, GreenhouseLocator.HOLOGRAM_Y + 1, 204), cell.bounds());
    }

    @Test
    public void aCellWhosePlantIsAlreadyRightIsLeftToTheRealWorld() {
        var done = new GreenhouseHologramShapes.CellBox(0, 0, "Carrot", 1, 0, 0, 0, 1, 1, 1).withVisible(false);
        var open = new GreenhouseHologramShapes.CellBox(0, 1, "Potato", 1, 3, 0, 0, 4, 1, 1);
        BlockPos corner = new BlockPos(100, 0, 200);

        List<Cell> cells = GreenhousePickHook.worldCells(List.of(done, open), corner);

        assertEquals(List.of("Potato"), cells.stream().map(Cell::crop).toList());

        // The ray passes the finished cell and picks the next one behind it.
        double y = GreenhouseLocator.HOLOGRAM_Y + 0.5;
        View along = new View(new Vec3(95, y, 200.5), new Vec3(1, 0, 0), 20, INF);
        assertEquals("Potato", GreenhousePickHook.nearest(along, cells).orElseThrow().crop());

        FakeGame game = new FakeGame();
        game.cells.clear();
        game.cells.add(done);
        List<Hit> picked = new ArrayList<>();
        assertFalse("vanilla picks the real crop under a finished cell", GreenhousePickHook.tryPick(game, picked::add));
        assertTrue(picked.isEmpty());
    }

    private static GreenhouseHologram.SoilGhost ghost(int dx, int dz, GreenhouseSoils.Soil soil) {
        return new GreenhouseHologram.SoilGhost(dx, dz, soil, GreenhouseLocator.FLOOR_Y);
    }

    @Test
    public void aSoilGhostIsPickedAndTheNearestOfGhostAndCellWins() {
        BlockPos corner = new BlockPos(100, 0, 200);
        // A visible crop cell at dx 3 and a ghost at dx 5, both in the row z = 200; looking along +x at cell height.
        var crop = new GreenhouseHologramShapes.CellBox(0, 3, "Carrot", 1, 3, 0, 0, 4, 1, 1);
        var ghost = ghost(5, 0, GreenhouseSoils.Soil.SOUL_SAND);
        List<Cell> cells = GreenhousePickHook.worldCells(List.of(crop), List.of(ghost), corner);
        double y = GreenhouseLocator.HOLOGRAM_Y + 0.5;
        View alongCells = new View(new Vec3(95, y, 200.5), new Vec3(1, 0, 0), 30, INF);

        assertEquals("Carrot", GreenhousePickHook.nearest(alongCells, cells).orElseThrow().crop());

        // Level with the soil layer the ray meets only the ghost.
        View alongSoil = new View(new Vec3(95, GreenhouseLocator.FLOOR_Y + 0.5, 200.5), new Vec3(1, 0, 0), 30, INF);
        Hit hit = GreenhousePickHook.nearest(alongSoil, cells).orElseThrow();
        assertEquals(GreenhouseSoils.Soil.SOUL_SAND, hit.soil());
        assertEquals("Soul Sand", hit.crop());
        assertEquals(10.0, hit.distance(), 1e-9);

        // A real block in front of the ghost leaves the click to vanilla; a tie goes to the ghost.
        assertTrue(GreenhousePickHook.nearest(new View(alongSoil.eye(), alongSoil.look(), 30, 9.0), cells).isEmpty());
        assertTrue(GreenhousePickHook.nearest(new View(alongSoil.eye(), alongSoil.look(), 30, 10.0), cells).isPresent());
        // Out of reach.
        assertTrue(GreenhousePickHook.nearest(new View(alongSoil.eye(), alongSoil.look(), 9.5, INF), cells).isEmpty());
    }

    @Test
    public void aCellOverAMissingSoilYieldsToTheSoilGhost() {
        BlockPos corner = new BlockPos(100, 0, 200);
        var over = new GreenhouseHologramShapes.CellBox(0, 0, "Ashwreath", 1, 2, 0, 0, 3, 1, 1);
        var elsewhere = new GreenhouseHologramShapes.CellBox(0, 1, "Carrot", 1, 4, 0, 0, 5, 1, 1);

        List<Cell> cells = GreenhousePickHook.worldCells(List.of(over, elsewhere),
            List.of(ghost(2, 0, GreenhouseSoils.Soil.SOUL_SAND)), corner);

        assertEquals(List.of("Carrot", "Soul Sand"), cells.stream().map(Cell::crop).toList());
        // From straight above the ghost's column the ray reaches the ghost, not the cell over it.
        View down = new View(new Vec3(102.5, GreenhouseLocator.HOLOGRAM_Y + 4.0, 200.5), new Vec3(0, -1, 0), 10, INF);
        assertEquals(GreenhouseSoils.Soil.SOUL_SAND, GreenhousePickHook.nearest(down, cells).orElseThrow().soil());
    }

    @Test
    public void aSoilGhostGoesToTheSoilEntryAndACellToTheCropEntry() {
        FakeGame game = new FakeGame();
        game.cells.clear();
        game.ghosts.add(ghost(0, 0, GreenhouseSoils.Soil.MYCELIUM));
        List<Hit> picked = new ArrayList<>();
        // Eye 3 above the ghost's top (layer 73 + 1), looking straight down.
        game.eyeHeight = GreenhouseLocator.FLOOR_Y + 1 + 3.0;

        assertTrue(GreenhousePickHook.tryPick(game, picked::add));

        assertEquals(1, picked.size());
        assertEquals(GreenhouseSoils.Soil.MYCELIUM, picked.get(0).soil());
        assertEquals(3.0, picked.get(0).distance(), 1e-9);
    }

    @Test
    public void theMixinTargetIsStillAPrivateNoArgVoidMethod() throws Exception {
        // Mixins only fail at startup, so a Minecraft update that renames or reshapes the method fails here first.
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, getClass().getClassLoader());
        var method = minecraft.getDeclaredMethod("pickBlockOrEntity");

        assertEquals(void.class, method.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isPrivate(method.getModifiers()));
    }

    /** A hologram with one cell at the corner block, the player looking down at it from above. */
    private static final class FakeGame implements GreenhousePickHook.Game {
        boolean showing = true;
        boolean screen;
        boolean player = true;
        Optional<BlockPos> corner = Optional.of(new BlockPos(100, 0, 200));
        double realDistance = INF;
        double eyeHeight = GreenhouseLocator.HOLOGRAM_Y + 3.0;
        final List<GreenhouseHologramShapes.CellBox> cells = new ArrayList<>(
            List.of(new GreenhouseHologramShapes.CellBox(0, 0, "Carrot", 1, 0, 0, 0, 1, 1, 1)));
        RuntimeException failure;
        final List<GreenhouseHologram.SoilGhost> ghosts = new ArrayList<>();
        int viewCalls;
        int cornerCalls;

        @Override public boolean hologramShowing() { return showing; }

        @Override public boolean screenOpen() { return screen; }

        @Override public View view() {
            viewCalls++;
            if (failure != null) throw failure;
            return player ? new View(new Vec3(100.5, eyeHeight, 200.5), new Vec3(0, -1, 0), 4.5, realDistance) : null;
        }

        @Override public Optional<BlockPos> corner() {
            cornerCalls++;
            return corner;
        }

        @Override public List<GreenhouseHologramShapes.CellBox> cells() { return cells; }

        @Override public List<GreenhouseHologram.SoilGhost> soilGhosts() { return ghosts; }
    }

    @Test
    public void aCellUnderTheCrosshairIsTakenAndHandedToTheSupply() {
        FakeGame game = new FakeGame();
        List<Hit> picked = new ArrayList<>();

        assertTrue(GreenhousePickHook.tryPick(game, picked::add));

        assertEquals(1, picked.size());
        assertEquals("Carrot", picked.get(0).crop());
        assertEquals(1, picked.get(0).flag());
        assertEquals(2.0, picked.get(0).distance(), 1e-9);
    }

    @Test
    public void nothingIsTakenWithoutAShownHologramAnOpenScreenAPlayerOrACorner() {
        List<Hit> picked = new ArrayList<>();
        FakeGame hidden = new FakeGame();
        hidden.showing = false;
        FakeGame screen = new FakeGame();
        screen.screen = true;
        FakeGame noPlayer = new FakeGame();
        noPlayer.player = false;
        FakeGame noCorner = new FakeGame();
        noCorner.corner = Optional.empty();

        for (FakeGame game : List.of(hidden, screen, noPlayer, noCorner)) {
            assertFalse(GreenhousePickHook.tryPick(game, picked::add));
        }

        assertTrue(picked.isEmpty());
        assertEquals("a hidden hologram is not even probed", 0, hidden.viewCalls + hidden.cornerCalls);
        assertEquals(0, screen.viewCalls + screen.cornerCalls);
        assertEquals("no player, no corner lookup", 0, noPlayer.cornerCalls);
    }

    @Test
    public void aFailureInsideTheHookLeavesTheClickToVanillaInsteadOfCrashing() {
        List<Hit> picked = new ArrayList<>();
        FakeGame broken = new FakeGame();
        broken.failure = new IllegalStateException("scanner state");

        assertFalse(GreenhousePickHook.tryPick(broken, picked::add));
        assertFalse("also on the second failure", GreenhousePickHook.tryPick(broken, picked::add));
        assertTrue(picked.isEmpty());

        // The action itself throwing (first-pick registration, chat) is contained the same way.
        assertFalse(GreenhousePickHook.tryPick(new FakeGame(), hit -> {
            throw new IllegalStateException("registration");
        }));
    }

    @Test
    public void aMissARealBlockInFrontOrAnEmptyHologramLeavesVanillaAlone() {
        List<Hit> picked = new ArrayList<>();
        FakeGame aside = new FakeGame();
        aside.corner = Optional.of(new BlockPos(110, 0, 200));
        FakeGame blocked = new FakeGame();
        blocked.realDistance = 1.0;
        FakeGame tooHigh = new FakeGame();
        tooHigh.eyeHeight = GreenhouseLocator.HOLOGRAM_Y + 9.0;
        FakeGame empty = new FakeGame();
        empty.cells.clear();

        for (FakeGame game : List.of(aside, blocked, tooHigh, empty)) {
            assertFalse(GreenhousePickHook.tryPick(game, picked::add));
        }

        assertTrue(picked.isEmpty());
    }
}
