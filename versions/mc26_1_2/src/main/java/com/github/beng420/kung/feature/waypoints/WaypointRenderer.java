package com.github.beng420.kung.feature.waypoints;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.WaypointsConfig;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.util.LineBoxes;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Draws the waypoints. The box and its outline are world geometry, so blocks in front of them hide them. The beam is
 * vanilla's beacon beam. A group with Walls on shows a small square at the projected block on the HUD (the icon) or,
 * with the Waypoint wall look, gets its box or outline from vanilla's gizmos, drawn always on top, so the real
 * waypoint shows through terrain. The HUD also carries the names, the number and the line from the crosshair to the
 * next waypoint of a route. Should the gizmos refuse a frame, the Waypoint wall look falls back to the icon.
 */
final class WaypointRenderer {
    private static final RenderStateDataKey<List<Item>> ITEMS = RenderStateDataKey.create(() -> "kung:waypoints");
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "waypoints");
    // Route colors: the waypoint just passed, the one you are at, the one to walk to.
    static final int PREVIOUS_RGB = 0xFF0000;
    static final int CURRENT_RGB = 0xFFFFFF;
    static final int NEXT_RGB = 0x00FF00;
    private static final float OUTLINE_WIDTH = 3F;
    private static final int BEAM_TOP = 320;
    /** Vanilla's beacon animation runs over 80 ticks. */
    private static final long BEAM_CYCLE_TICKS = 80;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MARKER_BACKGROUND = 0x99000000;

    private static boolean fillBroken;
    private static boolean gizmosBroken;
    private static boolean beamBroken;
    /** This frame's items for the HUD; the level draws read their own copy from the render state. */
    private static List<Item> hudItems = List.of();

    private WaypointRenderer() { }

    /** One waypoint ready to draw: where, in what color, and what to write beside it. */
    record Item(AABB box, int rgb, float alpha, WaypointType type, boolean throughWalls, boolean realWalls, String name,
                String number, Vec3 center, boolean routeNext) {
        /** True while the box and its outline come from the always-on-top gizmos and not from the depth-tested draws. */
        boolean viaGizmos() { return realWalls && !gizmosBroken; }

        /** True while the waypoint shows through blocks as the small square on the HUD. */
        boolean icon() { return throughWalls && !viaGizmos(); }
    }

    static void initialize() {
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            hudItems = items();
            context.levelState().setData(ITEMS, hudItems);
            emitWallGizmos(hudItems);
        });
        // Outlines before the translucent terrain and the fill after it, like the other box highlights.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> {
            List<Item> items = context.levelState().getDataOrDefault(ITEMS, List.of());
            if (items.stream().noneMatch(item -> item.type().outline() && !item.viaGizmos())) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            McCompat.draw(context, RenderTypes.lines(), (pose, lines) -> {
                for (Item item : items) {
                    if (item.type().outline() && !item.viaGizmos()) {
                        LineBoxes.box(lines, pose, item.box(), camera.x, camera.y, camera.z, 0xFF000000 | item.rgb(), OUTLINE_WIDTH);
                    }
                }
            });
        });
        LevelRenderEvents.END_MAIN.register(context -> {
            List<Item> items = context.levelState().getDataOrDefault(ITEMS, List.of());
            if (fillBroken || items.stream().noneMatch(item -> item.type().fill() && !item.viaGizmos())) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            // Same buffer, same vertex format as the dragon markers: a surprise costs the fill, never the frame.
            McCompat.draw(context, RenderTypes.debugQuads(), (pose, quads) -> {
                try {
                    for (Item item : items) {
                        if (!item.type().fill() || item.viaGizmos()) continue;
                        LineBoxes.fill(quads, pose, item.box(), camera.x, camera.y, camera.z, argb(item.alpha(), item.rgb()));
                    }
                } catch (RuntimeException exception) {
                    fillBroken = true;
                    KungMod.LOGGER.warn("Waypoint fill disabled: {}", exception.toString());
                }
            });
        });
        // The beam goes through the submit system like the beacon block entity's own, so it is vanilla's model and texture.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> submitBeams(context, context.levelState().getDataOrDefault(ITEMS, List.of())));
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, HUD_ID, (graphics, delta) -> drawHud(graphics));
    }

    private static List<Item> items() {
        if (Minecraft.getInstance().player == null) return List.of();
        List<Item> items = new ArrayList<>();
        for (WaypointsFeature.Shown shown : WaypointsFeature.INSTANCE.shown()) {
            Waypoint waypoint = shown.waypoint();
            WaypointGroup group = shown.group();
            int rgb = switch (shown.role()) {
                case PREVIOUS -> PREVIOUS_RGB;
                case CURRENT -> CURRENT_RGB;
                case NEXT -> NEXT_RGB;
                case NONE -> waypoint.rgb();
            };
            items.add(new Item(new AABB(waypoint.pos()), rgb, waypoint.alpha(), group.type(), group.throughWalls(),
                group.throughWalls() && group.wallStyle() == WaypointWallStyle.WAYPOINT, waypoint.name(), group.ordered() ? String.valueOf(shown.index() + 1) : "",
                new Vec3(waypoint.x() + 0.5, waypoint.y() + 0.5, waypoint.z() + 0.5),
                shown.role() == WaypointRoute.Role.NEXT));
        }
        return List.copyOf(items);
    }

    /** The box or the edges of every Walls waypoint, always on top. They live one frame, so each frame adds them again. */
    private static void emitWallGizmos(List<Item> items) {
        if (gizmosBroken) return;
        try {
            for (Item item : items) {
                if (!item.realWalls()) continue;
                GizmoStyle style = wallStyle(item);
                if (style != null) Gizmos.cuboid(item.box(), style).setAlwaysOnTop();
            }
        } catch (RuntimeException exception) {
            // Gizmos only exist while the frame has a collector (IllegalStateException otherwise).
            gizmosBroken = true;
            KungMod.LOGGER.warn("Waypoint walls fall back to the HUD marker: {}", exception.toString());
        }
    }

    /** The gizmo look of a Walls waypoint: filled, edges, both, or null for a style that draws nothing. */
    static GizmoStyle wallStyle(Item item) {
        int stroke = 0xFF000000 | item.rgb();
        int fill = argb(item.alpha(), item.rgb());
        if (item.type().fill() && item.type().outline()) return GizmoStyle.strokeAndFill(stroke, OUTLINE_WIDTH, fill);
        if (item.type().fill()) return GizmoStyle.fill(fill);
        if (item.type().outline()) return GizmoStyle.stroke(stroke, OUTLINE_WIDTH);
        return null;
    }

    static int argb(float alpha, int rgb) {
        return Math.round(Math.clamp(alpha, 0F, 1F) * 255F) << 24 | (rgb & 0xFFFFFF);
    }

    /** A vanilla beacon beam from every Waypoint-type waypoint up to the build limit, in the waypoint's color. */
    private static void submitBeams(LevelRenderContext context, List<Item> items) {
        Minecraft client = Minecraft.getInstance();
        if (beamBroken || client.level == null || items.stream().noneMatch(item -> item.type().beam())) return;
        try {
            Vec3 camera = context.levelState().cameraRenderState.pos;
            float time = Math.floorMod(client.level.getGameTime(), BEAM_CYCLE_TICKS)
                + client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            PoseStack pose = context.poseStack();
            for (Item item : items) {
                int height = BEAM_TOP - (int) item.box().minY;
                if (!item.type().beam() || height <= 0) continue;
                pose.pushPose();
                try {
                    pose.translate(item.box().minX - camera.x, item.box().minY - camera.y, item.box().minZ - camera.z);
                    BeaconRenderer.submitBeaconBeam(pose, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION, 1F, time, 0,
                        height, 0xFF000000 | item.rgb(), BeaconRenderer.SOLID_BEAM_RADIUS, BeaconRenderer.BEAM_GLOW_RADIUS);
                } finally {
                    pose.popPose();
                }
            }
        } catch (RuntimeException exception) {
            beamBroken = true;
            KungMod.LOGGER.warn("Waypoint beams disabled: {}", exception.toString());
        }
    }

    private static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        List<Item> items = hudItems;
        if (items.isEmpty() || client.level == null || McCompat.hudHidden(client) || McCompat.screen(client) != null) return;
        WaypointsConfig config = KungConfig.get().waypoints;
        for (Item item : items) {
            // The line goes to the next waypoint wherever it is, so it is drawn before the on-screen test below.
            if (item.routeNext() && config.routeLine()) drawRouteLine(client, graphics, item.center(), config);
            int[] at = project(client, graphics, item.center());
            if (at == null) continue;
            if (item.icon()) {
                graphics.fill(at[0] - 4, at[1] - 4, at[0] + 4, at[1] + 4, MARKER_BACKGROUND);
                graphics.fill(at[0] - 3, at[1] - 3, at[0] + 3, at[1] + 3, 0xFF000000 | item.rgb());
            }
            // Name above the square, the route number under the name; both stand clear of the square.
            int lineHeight = client.font.lineHeight + 1;
            int y = at[1] - 6 - lineHeight;
            if (!item.number().isEmpty()) {
                centered(graphics, client, item.number(), at[0], y);
                y -= lineHeight;
            }
            if (!item.name().isEmpty()) centered(graphics, client, item.name(), at[0], y);
        }
    }

    private static void centered(GuiGraphicsExtractor graphics, Minecraft client, String text, int x, int y) {
        graphics.text(client.font, text, x - client.font.width(text) / 2, y, TEXT_COLOR, true);
    }

    /**
     * A bar from the crosshair toward the point, as the Invisibug line draws it. A point on screen gets a bar to it. A
     * point beside or behind the camera has no screen position, so the bar points the way you would turn to face it.
     */
    private static void drawRouteLine(Minecraft client, GuiGraphicsExtractor graphics, Vec3 target, WaypointsConfig config) {
        float fromX = graphics.guiWidth() / 2F;
        float fromY = graphics.guiHeight() / 2F;
        float[] end = routeLineEnd(client, graphics, target);
        if (end == null) return;
        float dx = end[0] - fromX;
        float dy = end[1] - fromY;
        float length = (float) Math.hypot(dx, dy);
        if (length < 1F) return;
        // Far enough to leave the screen whatever the direction, short enough for the bar's rotation to stay exact.
        float reach = (float) Math.hypot(graphics.guiWidth(), graphics.guiHeight());
        float scale = end[2] > 0F && length <= reach ? 1F : reach / length;
        UiShapes.bar(graphics, fromX, fromY, fromX + dx * scale, fromY + dy * scale, config.lineThickness(), config.lineColor());
    }

    /**
     * Where the route line ends: the GUI position of the point and 1 when it lies ahead of the camera, or the GUI
     * position of a stand-in point one block ahead on the same side and 0 when it does not. Null when the point is
     * straight behind the camera and so has no side.
     */
    private static float[] routeLineEnd(Minecraft client, GuiGraphicsExtractor graphics, Vec3 target) {
        Camera camera = McCompat.camera(client);
        Vector3fc forward = camera.forwardVector();
        Vec3 offset = target.subtract(camera.position());
        double depth = offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z();
        if (depth > 0.05) {
            Vec3 screen = client.gameRenderer.projectPointToScreen(target);
            if (isScreenPoint(screen)) return new float[] {guiX(screen, graphics), guiY(screen, graphics), 1F};
        }
        // The part of the offset that is not along the view says which way to turn.
        double sideX = offset.x - forward.x() * depth;
        double sideY = offset.y - forward.y() * depth;
        double sideZ = offset.z - forward.z() * depth;
        double side = Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
        if (side < 1.0E-4) return null;
        Vec3 standIn = camera.position().add(forward.x() + sideX / side, forward.y() + sideY / side, forward.z() + sideZ / side);
        Vec3 screen = client.gameRenderer.projectPointToScreen(standIn);
        return isScreenPoint(screen) ? new float[] {guiX(screen, graphics), guiY(screen, graphics), 0F} : null;
    }

    private static boolean isScreenPoint(Vec3 screen) {
        return Double.isFinite(screen.x) && Double.isFinite(screen.y) && Double.isFinite(screen.z)
            && screen.z >= -1.0 && screen.z <= 1.0;
    }

    private static float guiX(Vec3 screen, GuiGraphicsExtractor graphics) {
        return (float) ((screen.x + 1.0) * 0.5 * graphics.guiWidth());
    }

    private static float guiY(Vec3 screen, GuiGraphicsExtractor graphics) {
        return (float) ((1.0 - screen.y) * 0.5 * graphics.guiHeight());
    }

    /** The GUI position of a world point, or null while it is behind the camera or the projection is not finite. */
    private static int[] project(Minecraft client, GuiGraphicsExtractor graphics, Vec3 target) {
        Camera camera = McCompat.camera(client);
        Vector3fc forward = camera.forwardVector();
        Vec3 offset = target.subtract(camera.position());
        // Behind the camera the projection mirrors the point onto the screen.
        if (offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z() <= 0.05) return null;
        Vec3 screen = client.gameRenderer.projectPointToScreen(target);
        if (!isScreenPoint(screen)) return null;
        return new int[] {Math.round(guiX(screen, graphics)), Math.round(guiY(screen, graphics))};
    }
}
