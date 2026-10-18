package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pick block (middle click) on a hologram cell: when the player's view ray meets a cell of the shown hologram
 * before anything real, vanilla's pick is cancelled and {@link GreenhouseSupplyRunner#pick} gets the cell's crop
 * and flag; a soil ghost (a missing or wrong soil block the scan draws) goes to {@link GreenhouseSupplyRunner#pickSoil}.
 * Everything else, including a real block or entity in front of the cell, is left to vanilla. The
 * ray test is plain geometry (unit-tested); {@code MinecraftPickBlockMixin} is the only caller of
 * {@link #tryPick(Minecraft)}. {@link #aimed} runs the same ray test without any side effect, for the crosshair label
 * ({@link GreenhouseCrosshairLabel}). Client thread only.
 */
public final class GreenhousePickHook {
    /**
     * A hologram crop cell, or with a {@code soil} a soil ghost, in world coordinates. {@code state} is the short
     * word the crosshair label puts after the name ("" for a crop still to place); the pick ignores it.
     */
    record Cell(String crop, int flag, GreenhouseSoils.Soil soil, String state, AABB bounds) {
        Cell(String crop, int flag, AABB bounds) {
            this(crop, flag, null, "", bounds);
        }

        static Cell ofSoil(GreenhouseSoils.Soil soil, GreenhouseSoilCheck.GhostKind kind, AABB bounds) {
            return new Cell(soil.displayName(), GreenhouseGrid.INGREDIENT, soil, soilState(kind), bounds);
        }
    }

    /** The nearest cell or soil ghost the ray meets, {@code distance} blocks from the eye; {@code soil} for a ghost. */
    record Hit(String crop, int flag, GreenhouseSoils.Soil soil, String state, double distance) {
        Hit(String crop, int flag, double distance) {
            this(crop, flag, null, "", distance);
        }
    }

    /** What the crosshair label shows: the name and the short state word after it ("" when there is none). */
    record Aim(String name, String state) {
        String text() {
            return state.isEmpty() ? name : name + " (" + state + ")";
        }
    }

    /** The player's view: {@code realDistance} is the vanilla hit (block or entity), infinite when it is a miss. */
    record View(Vec3 eye, Vec3 look, double reach, double realDistance) { }

    /** The game behind {@link #tryPick(Game, Consumer)}, so the conditions are unit-tested with a fake. */
    interface Game {
        /** Whether the hologram is drawn (shown and not toggled off): nothing else is picked. */
        boolean hologramShowing();

        /** A menu or any other screen has the mouse, so the click is not a world click. */
        boolean screenOpen();

        /** Null without a player. */
        View view();

        /** The confirmed greenhouse corner; empty while the player is not in their greenhouse. */
        Optional<BlockPos> corner();

        /** The shown hologram's cell volumes, corner-relative. */
        List<GreenhouseHologramShapes.CellBox> cells();

        /** The soil blocks the latest scan found missing or wrong. */
        default List<GreenhouseHologram.SoilGhost> soilGhosts() { return List.of(); }

        /** The footprints the hologram draws no model for because of a soil ghost (see {@link GreenhousePickHook#worldCells}). */
        default List<GreenhouseScanLogic.Footprint> blockedFootprints() { return List.of(); }

        /**
         * Scans the plot now, whatever the scan interval says, so the counts below are the plot as it is at the
         * click (the inventory is live, the scan is up to a second old). Called with the confirmed corner.
         */
        default void refreshScan(BlockPos corner) { }
    }

    /**
     * A real hit this much nearer than the hologram still loses: a ghost's top face coincides with the real block's,
     * and {@code client.hitResult} comes from the previous frame's eye and rotation, so an exact tie test would
     * fail at random while the player moves.
     */
    static final double TIE_EPSILON = 0.05;

    private static boolean failed;

    private GreenhousePickHook() { }

    /** Called by the pick mixin before vanilla's pick; true means the pick was taken and vanilla must not run. */
    public static boolean tryPick(Minecraft client) {
        return tryPick(new LiveGame(client), hit -> {
            if (hit.soil() != null) GreenhouseSupplyRunner.pickSoil(hit.soil());
            else GreenhouseSupplyRunner.pick(hit.crop(), hit.flag());
        });
    }

    /**
     * Never throws: this runs inside Minecraft's tick on a middle click, and a failure here must leave the click to
     * vanilla (false) instead of crashing the game. The first failure is logged.
     */
    static boolean tryPick(Game game, Consumer<Hit> action) {
        try {
            if (!game.hologramShowing() || game.screenOpen()) return false;
            View view = game.view();
            if (view == null) return false;
            Optional<BlockPos> corner = game.corner();
            if (corner.isEmpty()) return false;
            game.refreshScan(corner.get());
            Optional<Hit> hit = nearest(view,
                worldCells(game.cells(), game.soilGhosts(), game.blockedFootprints(), corner.get()));
            hit.ifPresent(action);
            return hit.isPresent();
        } catch (RuntimeException failure) {
            if (!failed) KungMod.LOGGER.warn("Greenhouse pick-block hook failed; leaving the click to vanilla", failure);
            failed = true;
            return false;
        }
    }

    /**
     * World volumes of the cells that show something. A cell whose plant is already right draws nothing, so its
     * volume is left out and the ray reaches the real crop or farmland under it.
     */
    static List<Cell> worldCells(List<GreenhouseHologramShapes.CellBox> boxes, BlockPos corner) {
        return worldCells(boxes, List.of(), corner);
    }

    static List<Cell> worldCells(List<GreenhouseHologramShapes.CellBox> boxes,
                                 List<GreenhouseHologram.SoilGhost> ghosts, BlockPos corner) {
        return worldCells(boxes, ghosts, List.of(), corner);
    }

    /**
     * Crop cells plus soil ghosts. A ghost is always pickable. A cell with a ghost in its column becomes a SOIL hit
     * over the cell's own volume: the ghost (one block at the soil layer) sits right under the cell's volume and the
     * translucent crop head is what the player aims at, so the whole column has to answer with the soil; nothing
     * can be planted on a missing soil anyway, so the soil is what that spot needs first. A cell of a
     * {@code blocked} footprint (the renderer draws no model or slab for it, see
     * {@link GreenhouseSoilCheck#blocked}) without a ghost of its own claims nothing: a 2x2 or 3x3 mutation with a
     * ghost under one cell has its other cells undrawn, and neither pick nor label may name what is not there.
     */
    static List<Cell> worldCells(List<GreenhouseHologramShapes.CellBox> boxes,
                                 List<GreenhouseHologram.SoilGhost> ghosts,
                                 List<GreenhouseScanLogic.Footprint> blocked, BlockPos corner) {
        List<Cell> cells = new ArrayList<>(boxes.size() + ghosts.size());
        for (GreenhouseHologramShapes.CellBox box : boxes) {
            if (!box.visible()) continue;
            AABB bounds = box.bounds(corner);
            GreenhouseHologram.SoilGhost under = ghosts.stream().filter(ghost -> {
                AABB soil = ghost.bounds(corner);
                return soil.minX == bounds.minX && soil.minZ == bounds.minZ;
            }).findFirst().orElse(null);
            double centreX = (box.minX() + box.maxX()) / 2;
            double centreZ = (box.minZ() + box.maxZ()) / 2;
            if (under == null && blocked.stream().anyMatch(footprint -> footprint.covers(centreX, centreZ))) continue;
            cells.add(under == null
                ? new Cell(box.crop(), box.flag(), null, cropState(box.state(), box.flag()), bounds)
                : Cell.ofSoil(under.soil(), under.kind(), bounds));
        }
        for (GreenhouseHologram.SoilGhost ghost : ghosts) {
            cells.add(Cell.ofSoil(ghost.soil(), ghost.kind(), ghost.bounds(corner)));
        }
        return cells;
    }

    /**
     * What the crosshair label can name: the pickable cells plus the unplanned crops (the pink boxes), which a pick
     * ignores. {@code extras} are raw offsets from the corner.
     */
    static List<Cell> labelCells(List<GreenhouseHologramShapes.CellBox> boxes, List<GreenhouseHologram.SoilGhost> ghosts,
                                 List<GreenhouseScanLogic.Footprint> blocked, List<GreenhouseScanLogic.Extra> extras,
                                 BlockPos corner) {
        List<Cell> cells = new ArrayList<>(worldCells(boxes, ghosts, blocked, corner));
        for (GreenhouseScanLogic.Extra extra : extras) {
            cells.add(new Cell(extra.crop(), GreenhouseGrid.INGREDIENT, null, "extra",
                new AABB(corner.getX() + extra.dx(), GreenhouseLocator.HOLOGRAM_Y, corner.getZ() + extra.dz(),
                    corner.getX() + extra.dx() + 1, GreenhouseLocator.HOLOGRAM_Y + 1, corner.getZ() + extra.dz() + 1)));
        }
        return cells;
    }

    /** The label word for a crop cell by what the scan found: nothing for a crop to place, a hint otherwise. */
    static String cropState(GreenhouseScanLogic.State state, int flag) {
        return switch (state) {
            case EMPTY, CORRECT -> flag == GreenhouseGrid.TARGET ? "mutation spot" : "";
            case WRONG -> "wrong crop planted";
            case UNVERIFIED -> "unrecognised crop planted";
        };
    }

    /** The label word for a soil marker. */
    static String soilState(GreenhouseSoilCheck.GhostKind kind) {
        return switch (kind) {
            case MISSING -> "soil, to place";
            case WRONG_BLOCK -> "soil, wrong block";
            case WRONG_STATE -> "soil, wrong state";
        };
    }

    /**
     * The cell nearest to the eye along the view ray within reach, unless the real hit is nearer by more than
     * {@link #TIE_EPSILON} (the hologram stands on the same floor the ray would otherwise hit). A cell the eye is
     * inside is not hit, as {@link AABB#clip} defines it.
     */
    static Optional<Hit> nearest(View view, List<Cell> cells) {
        Vec3 end = view.eye().add(view.look().scale(view.reach()));
        Hit best = null;
        for (Cell cell : cells) {
            Optional<Vec3> at = cell.bounds().clip(view.eye(), end);
            if (at.isEmpty()) continue;
            double distance = view.eye().distanceTo(at.get());
            if (best == null || distance < best.distance()) {
                best = new Hit(cell.crop(), cell.flag(), cell.soil(), cell.state(), distance);
            }
        }
        return best == null || best.distance() > view.realDistance() + TIE_EPSILON ? Optional.empty() : Optional.of(best);
    }

    /**
     * What the crosshair is on, by the rules of {@link #nearest} (reach, and a real block in front wins). Pure and
     * free of side effects: no scan, no click, nothing sent; the HUD label may call it every frame.
     */
    static Optional<Aim> aimed(View view, List<Cell> cells) {
        return nearest(view, cells).map(hit -> new Aim(hit.crop(), hit.state()));
    }

    /** The running client: the player's eye and view, vanilla's hit, the hologram and the greenhouse corner. */
    record LiveGame(Minecraft client) implements Game {
        // Drawn, not just shown: hidden by the key, it claims no click and costs no scan.
        @Override public boolean hologramShowing() { return GreenhouseHologram.isDrawn(); }

        @Override public boolean screenOpen() { return McCompat.screen(client) != null; }

        @Override public View view() {
            Player player = client.player;
            if (player == null) return null;
            HitResult real = client.hitResult;
            Vec3 eye = player.getEyePosition();
            double realDistance = real == null || real.getType() == HitResult.Type.MISS
                ? Double.POSITIVE_INFINITY : eye.distanceTo(real.getLocation());
            return new View(eye, player.getViewVector(1.0F), player.blockInteractionRange(), realDistance);
        }

        @Override public Optional<BlockPos> corner() { return GreenhouseLocator.current(client, false); }

        @Override public void refreshScan(BlockPos corner) { GreenhouseScanner.scanNow(client, corner, GreenhouseHologram.version()); }

        @Override public List<GreenhouseHologramShapes.CellBox> cells() { return GreenhouseHologram.cellBoxes(); }

        @Override public List<GreenhouseHologram.SoilGhost> soilGhosts() { return GreenhouseHologram.soilGhosts(); }

        @Override public List<GreenhouseScanLogic.Footprint> blockedFootprints() {
            return GreenhouseHologram.blockedFootprints();
        }
    }
}
