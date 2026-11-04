package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.LineBoxes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class IceSprayHighlightRenderer {
    private static final RenderStateDataKey<List<AABB>> BOXES = RenderStateDataKey.create(() -> "kung:ice-spray");
    private static final int OUTLINE = 0xFF99DDFF;
    private static final int FILL = 0x3399DDFF; // 51 / 255 = 20% alpha per face.
    private static Set<Integer> lastExtractedTargets = Set.of();
    private static int lastDrawnCount;

    private IceSprayHighlightRenderer() { }

    static AABB scaledBounds(AABB bounds, int percent) {
        double extra = (Math.clamp(percent, 100, 200) / 100.0 - 1) / 2;
        // Retain the existing two-centimetre clearance at normal size to avoid coincident outlines.
        return bounds.inflate(Math.max(0.02, bounds.getXsize() * extra),
            Math.max(0.02, bounds.getYsize() * extra), Math.max(0.02, bounds.getZsize() * extra));
    }

    static void initialize() {
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            List<AABB> boxes = new ArrayList<>();
            Set<Integer> targets = new HashSet<>();
            var camera = context.camera();
            Vec3 position = camera.position();
            float partialTick = camera.getCameraEntityPartialTicks(context.deltaTracker());
            int size = KungConfig.get().dungeon.iceSprayBoxSize();
            for (Entity entity : DungeonDebuffFeature.INSTANCE.highlightedEntities()) {
                if (!entity.shouldRender(position.x, position.y, position.z)) continue;
                boxes.add(scaledBounds(entity.getBoundingBox(), size)
                    .move(entity.getPosition(partialTick).subtract(entity.position())));
                targets.add(entity.getId());
            }
            if (!targets.equals(lastExtractedTargets)) {
                KungDebugRecorder.event("dungeon-debuff", "ice-boxes extracted=" + boxes.size()
                    + " entityIds=" + targets + " size=" + size + " camera=" + position + " bounds=" + boxes);
                lastExtractedTargets = Set.copyOf(targets);
            }
            context.levelState().setData(BOXES, List.copyOf(boxes));
        });
        // Outlines before water, like vanilla's block outline: water writes depth, so lines drawn after it vanish
        // behind its surface. The faint fill stays after water: drawn first, its depth would cut a hole into water
        // behind the box. 26.2 queues both into its feature pass, which runs before translucent terrain anyway.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> {
            List<AABB> boxes = context.levelState().getDataOrDefault(BOXES, List.of());
            if (boxes.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            McCompat.draw(context, RenderTypes.lines(), (pose, lines) -> {
                for (AABB box : boxes) LineBoxes.box(lines, pose, box, camera.x, camera.y, camera.z, OUTLINE, 2.0F);
            });
        });
        LevelRenderEvents.END_MAIN.register(context -> {
            List<AABB> boxes = context.levelState().getDataOrDefault(BOXES, List.of());
            if (boxes.size() != lastDrawnCount) {
                KungDebugRecorder.event("dungeon-debuff", "ice-boxes draw=" + boxes.size());
                lastDrawnCount = boxes.size();
            }
            if (boxes.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            McCompat.draw(context, RenderTypes.debugQuads(), (pose, fill) -> {
                for (AABB box : boxes) LineBoxes.fill(fill, pose, box, camera.x, camera.y, camera.z, FILL);
            });
        });
    }
}
