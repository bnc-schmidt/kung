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
 * or the set of working model rungs changes, never per frame. No user setting: the hologram is off until
 * {@code /kung greenhouse blueprint} shows one.
 */
final class GreenhouseWorldRenderer {
    private static final RenderStateDataKey<List<Box>> BOXES = RenderStateDataKey.create(() -> "kung:greenhouse-hologram");
    private static final RenderStateDataKey<GreenhouseModels.Models> MODELS =
        RenderStateDataKey.create(() -> "kung:greenhouse-hologram-models");
    /** Corner indices per face; bit 0 picks x, bit 1 picks y, bit 2 picks z. */
    private static final int[][] FACES = {{0, 4, 6, 2}, {5, 1, 3, 7}, {0, 1, 5, 4},
        {6, 7, 3, 2}, {1, 0, 2, 3}, {4, 5, 7, 6}};
    /** Blocks from the camera to the centre of the grid beyond which nothing is drawn. */
    private static final double RANGE = 64.0;

    private static int builtVersion = -1;
    private static int builtEpoch = -1;
    private static int builtScan = -1;
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
                    AABB relative = box.bounds().move(-camera.x, -camera.y, -camera.z);
                    for (int[] face : FACES) {
                        for (int corner : face) {
                            fill.addVertex(pose,
                                (float) ((corner & 1) == 0 ? relative.minX : relative.maxX),
                                (float) ((corner & 2) == 0 ? relative.minY : relative.maxY),
                                (float) ((corner & 4) == 0 ? relative.minZ : relative.maxZ)).setColor(box.fill());
                        }
                    }
                }
            });
        });
    }

    private static Frame visibleFrame(Vec3 camera) {
        if (!GreenhouseHologram.isShowing()) return Frame.EMPTY;
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
        if (builtVersion != GreenhouseHologram.version() || !corner.equals(builtCorner)
            || builtEpoch != GreenhouseModels.epoch() || builtScan != GreenhouseScanner.version()) {
            builtVersion = GreenhouseHologram.version();
            builtCorner = corner;
            builtEpoch = GreenhouseModels.epoch();
            builtScan = GreenhouseScanner.version();
            built = buildFrame(corner);
        }
        return built;
    }

    private static Frame buildFrame(BlockPos corner) {
        GreenhouseModels.Prepared prepared;
        try {
            prepared = GreenhouseModels.prepare(GreenhouseHologram.placements(),
                GreenhouseScanner.states(GreenhouseHologram.version()), GreenhouseScanner.ghosts(),
                GreenhouseScanner.soilLayer(), corner);
        } catch (RuntimeException failure) {
            // Registry or profile trouble: draw the coloured cubes for every cell rather than nothing.
            KungMod.LOGGER.warn("Greenhouse hologram: models could not be prepared", failure);
            prepared = new GreenhouseModels.Prepared(GreenhouseModels.Models.EMPTY, cubesOf(GreenhouseHologram.placements(), corner));
        }
        List<Box> boxes = new ArrayList<>(worldBoxes(GreenhouseHologram.shapes(), corner));
        boxes.addAll(prepared.boxes());
        return new Frame(List.copyOf(boxes), prepared.models());
    }

    private static List<Box> cubesOf(List<GreenhouseHologramShapes.Placement> placements, BlockPos corner) {
        List<Box> cubes = new ArrayList<>();
        for (GreenhouseHologramShapes.Placement placement : placements) {
            if (placement.cube() != null) cubes.add(worldBox(placement.cube(), corner));
            if (placement.slab() != null) cubes.add(worldBox(placement.slab(), corner));
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
