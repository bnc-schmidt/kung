package com.github.beng420.kung.feature.waypoints;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.WaypointsConfig;
import com.github.beng420.kung.util.LineBoxes;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Draws the waypoints. The box, its outline and its beam are world geometry, so blocks in front of them hide them. What
 * must show through walls goes on the HUD instead, as {@code MIMIC} and the Invisibug line do: a square at the
 * projected block, the name and, on a route, the number and the line from the crosshair to the next waypoint.
 */
final class WaypointRenderer {
    private static final RenderStateDataKey<List<Item>> ITEMS = RenderStateDataKey.create(() -> "kung:waypoints");
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "waypoints");
    /** Corner indices per face; bit 0 picks x, bit 1 picks y, bit 2 picks z. */
    private static final int[][] FACES = {{0, 4, 6, 2}, {5, 1, 3, 7}, {0, 1, 5, 4},
        {6, 7, 3, 2}, {1, 0, 2, 3}, {4, 5, 7, 6}};
    // Route colors: the waypoint just passed, the one you are at, the one to walk to.
    static final int PREVIOUS_RGB = 0xFF0000;
    static final int CURRENT_RGB = 0xFFFFFF;
    static final int NEXT_RGB = 0x00FF00;
    private static final float OUTLINE_WIDTH = 3F;
    private static final double BEAM_HALF_WIDTH = 0.125;
    private static final double BEAM_TOP = 320.0;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MARKER_BACKGROUND = 0x99000000;

    private static boolean fillBroken;
    /** This frame's items for the HUD; the level draws read their own copy from the render state. */
    private static List<Item> hudItems = List.of();

    private WaypointRenderer() { }

    /** One waypoint ready to draw: where, in what color, and what to write beside it. */
    record Item(AABB box, int rgb, float alpha, WaypointType type, boolean throughWalls, String name, String number,
                Vec3 center, boolean routeNext) { }

    static void initialize() {
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            hudItems = items();
            context.levelState().setData(ITEMS, hudItems);
        });
        // Outlines before the translucent terrain and the fill after it, like the other box highlights.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> {
            List<Item> items = context.levelState().getDataOrDefault(ITEMS, List.of());
            if (items.stream().noneMatch(item -> item.type().outline())) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            McCompat.draw(context, RenderTypes.lines(), (pose, lines) -> {
                for (Item item : items) {
                    if (item.type().outline()) {
                        LineBoxes.box(lines, pose, item.box(), camera.x, camera.y, camera.z, 0xFF000000 | item.rgb(), OUTLINE_WIDTH);
                    }
                }
            });
        });
        LevelRenderEvents.END_MAIN.register(context -> {
            List<Item> items = context.levelState().getDataOrDefault(ITEMS, List.of());
            if (fillBroken || items.stream().noneMatch(item -> item.type().fill())) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            // Same buffer, same vertex format as the dragon markers: a surprise costs the fill, never the frame.
            McCompat.draw(context, RenderTypes.debugQuads(), (pose, quads) -> {
                try {
                    for (Item item : items) {
                        if (!item.type().fill()) continue;
                        int color = argb(item.alpha(), item.rgb());
                        fillBox(quads, pose, item.box(), camera, color);
                        if (item.type().beam()) fillBox(quads, pose, beam(item.box()), camera, color);
                    }
                } catch (RuntimeException exception) {
                    fillBroken = true;
                    KungMod.LOGGER.warn("Waypoint fill disabled: {}", exception.toString());
                }
            });
        });
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
                waypoint.name(), group.ordered() ? String.valueOf(shown.index() + 1) : "",
                new Vec3(waypoint.x() + 0.5, waypoint.y() + 0.5, waypoint.z() + 0.5),
                shown.role() == WaypointRoute.Role.NEXT));
        }
        return List.copyOf(items);
    }

    static int argb(float alpha, int rgb) {
        return Math.round(Math.clamp(alpha, 0F, 1F) * 255F) << 24 | (rgb & 0xFFFFFF);
    }

    private static AABB beam(AABB box) {
        double x = box.getCenter().x;
        double z = box.getCenter().z;
        return new AABB(x - BEAM_HALF_WIDTH, box.maxY, z - BEAM_HALF_WIDTH, x + BEAM_HALF_WIDTH, BEAM_TOP, z + BEAM_HALF_WIDTH);
    }

    private static void fillBox(com.mojang.blaze3d.vertex.VertexConsumer quads, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                AABB world, Vec3 camera, int color) {
        AABB box = world.move(-camera.x, -camera.y, -camera.z);
        for (int[] face : FACES) {
            for (int corner : face) {
                quads.addVertex(pose,
                    (float) ((corner & 1) == 0 ? box.minX : box.maxX),
                    (float) ((corner & 2) == 0 ? box.minY : box.maxY),
                    (float) ((corner & 4) == 0 ? box.minZ : box.maxZ)).setColor(color);
            }
        }
    }

    private static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        List<Item> items = hudItems;
        if (items.isEmpty() || client.level == null || McCompat.hudHidden(client) || McCompat.screen(client) != null) return;
        WaypointsConfig config = KungConfig.get().waypoints;
        for (Item item : items) {
            int[] at = project(client, graphics, item.center());
            if (at == null) continue;
            if (item.routeNext() && config.routeLine()) drawRouteLine(graphics, at[0], at[1], config);
            if (item.throughWalls()) {
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

    /** A bar from the crosshair to the point, as the Invisibug line draws it. */
    private static void drawRouteLine(GuiGraphicsExtractor graphics, int endX, int endY, WaypointsConfig config) {
        float centerX = graphics.guiWidth() / 2F;
        float centerY = graphics.guiHeight() / 2F;
        float length = (float) Math.hypot(endX - centerX, endY - centerY);
        if (length < 1F) return;
        int thickness = config.lineThickness();
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(centerX, centerY);
            graphics.pose().rotate((float) Math.atan2(endY - centerY, endX - centerX));
            graphics.pose().translate(0F, -thickness / 2F);
            graphics.pose().scale(length, thickness);
            graphics.fill(0, 0, 1, 1, config.lineColor());
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** The GUI position of a world point, or null while it is behind the camera or the projection is not finite. */
    private static int[] project(Minecraft client, GuiGraphicsExtractor graphics, Vec3 target) {
        Camera camera = McCompat.camera(client);
        Vector3fc forward = camera.forwardVector();
        Vec3 offset = target.subtract(camera.position());
        // Behind the camera the projection mirrors the point onto the screen.
        if (offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z() <= 0.05) return null;
        Vec3 screen = client.gameRenderer.projectPointToScreen(target);
        if (!Double.isFinite(screen.x) || !Double.isFinite(screen.y) || !Double.isFinite(screen.z)
            || screen.z < -1.0 || screen.z > 1.0) {
            return null;
        }
        return new int[] {(int) Math.round((screen.x + 1.0) * 0.5 * graphics.guiWidth()),
            (int) Math.round((1.0 - screen.y) * 0.5 * graphics.guiHeight())};
    }
}
