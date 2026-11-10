package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.github.beng420.kung.util.LineBoxes;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

final class M7DragonRenderer {
    private static final RenderStateDataKey<List<Marker>> MARKERS = RenderStateDataKey.create(() -> "kung:m7-dragons");
    private static final RenderStateDataKey<List<Trail>> TRAILS = RenderStateDataKey.create(() -> "kung:m7-dragon-trails");
    private static final RenderStateDataKey<List<M7DragonTracker.Statue>> PRIO = RenderStateDataKey.create(() -> "kung:m7-dragon-prio");
    /** Blocks ahead of the camera where a Dragon Prio line starts; the farther, the less view bobbing moves it off the crosshair. */
    private static final float PRIO_START = 2.0F;

    private static boolean fillBroken;

    private M7DragonRenderer() { }

    static void initialize() {
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            float partialTick = context.camera().getCameraEntityPartialTicks(context.deltaTracker());
            context.levelState().setData(MARKERS,
                List.copyOf(M7DragonFeature.INSTANCE.renderMarkers(partialTick)));
            context.levelState().setData(TRAILS, List.copyOf(M7DragonFeature.INSTANCE.renderTrails()));
            // Only which statues: the start of each line is worked out from the camera when it is drawn.
            context.levelState().setData(PRIO, List.copyOf(M7DragonFeature.INSTANCE.prioLines()));
        });
        LevelRenderEvents.END_MAIN.register(context -> {
            List<Marker> markers = context.levelState().getDataOrDefault(MARKERS, List.of());
            List<Trail> trails = context.levelState().getDataOrDefault(TRAILS, List.of());
            List<M7DragonTracker.Statue> prio = context.levelState().getDataOrDefault(PRIO, List.of());
            if (markers.isEmpty() && trails.isEmpty() && prio.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            // Straight ahead of this frame's camera is the crosshair, so the line turns with the view
            // in the frame it is drawn - nothing here comes from a tick.
            Vector3f start = context.levelState().cameraRenderState.orientation.transform(0, 0, -PRIO_START, new Vector3f());
            // A polyline: a cube per sample point cost a VoxelShape and 12 edges each, every frame.
            float trailWidth = 2.25F * com.github.beng420.kung.config.KungConfig.get().dungeon.dragonLineThickness() / 100F;
            McCompat.draw(context, RenderTypes.lines(), (pose, lines) -> {
                for (Marker marker : markers) {
                    if (!marker.filled()) LineBoxes.box(lines, pose, marker.bounds(), camera.x, camera.y, camera.z,
                        marker.color(), marker.width());
                }
                for (M7DragonTracker.Statue statue : prio) {
                    if (!M7DragonFeature.INSTANCE.prioPending(statue)) continue;
                    Vec3 to = statue.spawn();
                    LineBoxes.segment(lines, pose, start.x, start.y, start.z, (float) (to.x - camera.x),
                        (float) (to.y - camera.y), (float) (to.z - camera.z), statue.color(), trailWidth);
                }
                for (Trail trail : trails) {
                    List<Vec3> points = trail.points();
                    for (int index = 1; index < points.size(); index++) {
                        Vec3 from = points.get(index - 1);
                        Vec3 to = points.get(index);
                        LineBoxes.segment(lines, pose, (float) (from.x - camera.x), (float) (from.y - camera.y),
                            (float) (from.z - camera.z), (float) (to.x - camera.x), (float) (to.y - camera.y),
                            (float) (to.z - camera.z), trail.color(), trailWidth);
                    }
                }
            });
            drawFilled(context, markers, camera);
        });
    }

    /**
     * Filled cubes through debugQuads (POSITION_COLOR - position and colour, nothing else), in their
     * own buffer AFTER the lines are flushed. A wrong vertex format once crashed the game from this
     * renderer; here a format surprise costs the fill, never the frame. It disables itself after the
     * first failure instead of throwing every frame. The catch sits inside the geometry because 26.2
     * runs it later, when the frame's submits flush.
     */
    private static void drawFilled(LevelRenderContext context, List<Marker> markers, Vec3 camera) {
        if (fillBroken) return;
        McCompat.draw(context, RenderTypes.debugQuads(), (pose, quads) -> {
            try {
                for (Marker marker : markers) {
                    if (!marker.filled()) continue;
                    LineBoxes.fill(quads, pose, marker.bounds(), camera.x, camera.y, camera.z, marker.color(), marker.wallsOnly());
                }
            } catch (RuntimeException exception) {
                fillBroken = true;
                com.github.beng420.kung.KungMod.LOGGER.warn("Dragon marker fill disabled: {}", exception.toString());
            }
        });
    }

    /** {@code wallsOnly}: a filled box keeps its bottom and top open, so only the side walls are tinted. */
    record Marker(AABB bounds, int color, float width, boolean filled, boolean wallsOnly) {
        Marker(AABB bounds, int color, float width, boolean filled) { this(bounds, color, width, filled, false); }
        Marker(AABB bounds, int color, float width) { this(bounds, color, width, false); }
    }

    /** Flight path as a polyline; RenderTypes.lines draws it at its own thin width. */
    record Trail(List<Vec3> points, int color) { }
}
