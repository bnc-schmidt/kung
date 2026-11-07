package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.util.LineBoxes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the greenhouse hologram: semi-transparent crop and mutation models ({@link GreenhouseModels}, submitted
 * in COLLECT_SUBMITS) plus outlines and a faint fill for the slabs, the origin marker and any cell whose model
 * is unavailable. Extraction only picks the prebuilt data, or nothing while no hologram is shown, the player is
 * not in their greenhouse or the camera is far away; the data is rebuilt when the hologram, the greenhouse corner
 * or the set of working model rungs changes, never per frame. The hologram is off until
 * {@code /kung greenhouse blueprint} shows one (and again after a finished layout turns it off).
 */
final class GreenhouseWorldRenderer {
    private static final RenderStateDataKey<List<Box>> BOXES = RenderStateDataKey.create(() -> "kung:greenhouse-hologram");
    private static final RenderStateDataKey<GreenhouseModels.Models> MODELS =
        RenderStateDataKey.create(() -> "kung:greenhouse-hologram-models");
    /** Blocks from the camera to the centre of the grid beyond which nothing is drawn. */
    private static final double RANGE = 64.0;

    private static int builtVersion = -1;
    private static int builtEpoch = -1;
    private static int builtScan = -1;
    private static int builtPalette;
    private static boolean builtHideOverWrongSoil;
    private static String builtHeld = "";
    private static BlockPos builtCorner;
    private static Frame built = Frame.EMPTY;

    private GreenhouseWorldRenderer() { }

    /** World-space box with its colours. */
    record Box(AABB bounds, int fill, int outline, float width) { }

    /** Everything drawn for the current hologram and corner. */
    private record Frame(List<Box> boxes, GreenhouseModels.Models models) {
        static final Frame EMPTY = new Frame(List.of(), GreenhouseModels.Models.EMPTY);
    }

    static void initialize() {
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            Frame frame = visibleFrame(context.camera().position());
            context.levelState().setData(BOXES, frame.boxes());
            context.levelState().setData(MODELS, frame.models());
        });
        // The models go through the submit system, which sorts translucent geometry and flushes the same way on
        // 26.1.2 and 26.2, so this path needs no McCompat.draw split. Camera-relative, like the boxes below.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            GreenhouseModels.Models models = context.levelState().getDataOrDefault(MODELS, GreenhouseModels.Models.EMPTY);
            if (models.isEmpty()) return;
            GreenhouseModels.submit(context, models, context.levelState().cameraRenderState.pos);
        });
        // Outlines before water and the fill after it, like the other box highlights: see IceSprayHighlightRenderer.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> {
            List<Box> boxes = context.levelState().getDataOrDefault(BOXES, List.of());
            if (boxes.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            McCompat.draw(context, RenderTypes.lines(), (pose, lines) -> {
                for (Box box : boxes) {
                    if (box.outline() >>> 24 == 0) continue;
                    LineBoxes.box(lines, pose, box.bounds(), camera.x, camera.y, camera.z, box.outline(), box.width());
                }
            });
        });
        LevelRenderEvents.END_MAIN.register(context -> {
            List<Box> boxes = context.levelState().getDataOrDefault(BOXES, List.of());
            if (boxes.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            McCompat.draw(context, RenderTypes.debugQuads(), (pose, fill) -> {
                for (Box box : boxes) {
                    if (box.fill() >>> 24 == 0) continue;
                    LineBoxes.fill(fill, pose, box.bounds(), camera.x, camera.y, camera.z, box.fill());
                }
            });
        });
    }

    private static Frame visibleFrame(Vec3 camera) {
        // Toggled off with the key: nothing is drawn and the scan rests too.
        if (!GreenhouseHologram.isShowing() || !GreenhouseHologram.userVisible()) return Frame.EMPTY;
        Optional<BlockPos> found = GreenhouseLocator.current(Minecraft.getInstance(), false);
        if (found.isEmpty()) return Frame.EMPTY;
        BlockPos corner = found.get();
        double dx = camera.x - (corner.getX() + GreenhouseGrid.SIZE / 2.0);
        double dy = camera.y - GreenhouseLocator.HOLOGRAM_Y;
        double dz = camera.z - (corner.getZ() + GreenhouseGrid.SIZE / 2.0);
        if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) return Frame.EMPTY;
        // Compares the layout with what is planted when due (once a second, and at once after a change); it
        // bumps its version only when what is drawn changes.
        GreenhouseScanner.tick(Minecraft.getInstance(), corner, GreenhouseHologram.version(), System.nanoTime());
        // A finished layout deactivates the hologram inside that scan: nothing is left to draw.
        if (!GreenhouseHologram.isShowing()) return Frame.EMPTY;
        int palette = GreenhousePalette.fingerprint();
        boolean hideOverWrongSoil = GreenhouseHologramSettings.hideModelsOverWrongSoil();
        // What the player holds narrows the hologram, so a change of item rebuilds it (only with the setting on).
        String held = GreenhouseHologramSettings.focusOnHeldItem() ? GreenhouseHologram.heldId() : "";
        if (builtVersion != GreenhouseHologram.version() || !corner.equals(builtCorner)
            || builtEpoch != GreenhouseModels.epoch() || builtScan != GreenhouseScanner.version()
            || builtPalette != palette || builtHideOverWrongSoil != hideOverWrongSoil || !held.equals(builtHeld)) {
            builtVersion = GreenhouseHologram.version();
            builtCorner = corner;
            builtEpoch = GreenhouseModels.epoch();
            builtScan = GreenhouseScanner.version();
            builtPalette = palette;
            builtHideOverWrongSoil = hideOverWrongSoil;
            builtHeld = held;
            built = buildFrame(corner, hideOverWrongSoil);
        }
        return built;
    }

    private static Frame buildFrame(BlockPos corner, boolean hideOverWrongSoil) {
        GreenhousePalette palette = GreenhousePalette.live();
        int version = GreenhouseHologram.version();
        var states = GreenhouseScanner.states(version);
        var ghosts = GreenhouseScanner.ghosts(version);
        int soilLayer = GreenhouseScanner.soilLayer(version);
        var placements = GreenhouseHologram.placements();
        // Ghosts only exist (and are only drawn) with a known soil layer; a spot over one of them is not drawn.
        boolean[] blocked = hideOverWrongSoil && soilLayer >= 0
            ? GreenhouseSoilCheck.blocked(GreenhouseScanLogic.footprints(placements), ghosts) : null;
        var layout = GreenhouseHologram.layout();
        List<GreenhouseScanLogic.Extra> extras = GreenhouseScanner.extras(version);
        // The item in hand leaves out everything but its own spots and soil.
        var focus = GreenhouseHologram.focus();
        if (focus.isPresent()) {
            GreenhouseFocus.View view = focus.get().narrow(layout, states, blocked, ghosts);
            layout = view.layout();
            placements = layout.placements();
            states = view.states();
            blocked = view.blocked();
            ghosts = view.ghosts();
            extras = List.of();
        }
        GreenhouseModels.Prepared prepared;
        try {
            prepared = GreenhouseModels.prepare(placements, states, ghosts, soilLayer, blocked, corner, palette);
        } catch (RuntimeException failure) {
            // Registry or profile trouble: draw the coloured cubes for every cell rather than nothing.
            KungMod.LOGGER.warn("Greenhouse hologram: models could not be prepared", failure);
            prepared = new GreenhouseModels.Prepared(GreenhouseModels.Models.EMPTY,
                cubesOf(placements, blocked, corner, palette));
        }
        // The outlines, slabs, wrong and unplanned boxes, then the stand-in cubes.
        List<Box> boxes = new ArrayList<>(worldBoxes(
            GreenhouseOverlay.boxes(layout, states, extras, ghosts, soilLayer, blocked, palette), corner));
        boxes.addAll(prepared.cubes());
        return new Frame(List.copyOf(boxes), prepared.models());
    }

    private static List<Box> cubesOf(List<GreenhouseHologramShapes.Placement> placements, boolean[] blocked,
                                     BlockPos corner, GreenhousePalette palette) {
        List<Box> cubes = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            if (blocked != null && i < blocked.length && blocked[i]) continue;
            var cube = GreenhouseOverlay.cube(placements.get(i), palette);
            if (cube != null) cubes.add(worldBox(cube, corner));
        }
        return cubes;
    }

    static Box worldBox(GreenhouseHologramShapes.Shape shape, BlockPos corner) {
        return new Box(new AABB(corner.getX() + shape.minX(), GreenhouseLocator.HOLOGRAM_Y + shape.minY(),
            corner.getZ() + shape.minZ(), corner.getX() + shape.maxX(), GreenhouseLocator.HOLOGRAM_Y + shape.maxY(),
            corner.getZ() + shape.maxZ()), shape.fill(), shape.outline(), shape.width());
    }

    static List<Box> worldBoxes(List<GreenhouseHologramShapes.Shape> shapes, BlockPos corner) {
        List<Box> boxes = new ArrayList<>(shapes.size());
        for (GreenhouseHologramShapes.Shape shape : shapes) boxes.add(worldBox(shape, corner));
        return List.copyOf(boxes);
    }
}
