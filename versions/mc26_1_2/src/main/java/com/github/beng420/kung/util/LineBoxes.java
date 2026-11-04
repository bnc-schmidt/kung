package com.github.beng420.kung.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Box outlines and segments written straight into a lines buffer - what ShapeRenderer.renderShape
 * does, without building a VoxelShape per box per frame. Hundreds of boxes cost that every frame.
 */
public final class LineBoxes {
    private static final Vector3fc X = new Vector3f(1, 0, 0);
    private static final Vector3fc Y = new Vector3f(0, 1, 0);
    private static final Vector3fc Z = new Vector3f(0, 0, 1);

    /** Corner indices per face; bit 0 picks x, bit 1 picks y, bit 2 picks z. */
    private static final int[][] FACES = {{0, 4, 6, 2}, {5, 1, 3, 7}, {0, 1, 5, 4},
        {6, 7, 3, 2}, {1, 0, 2, 3}, {4, 5, 7, 6}};

    private LineBoxes() { }

    /** The 6 faces of a world-space box as quads, drawn relative to the camera; for the POSITION_COLOR quads type. */
    public static void fill(VertexConsumer quads, PoseStack.Pose pose, AABB box, double cameraX, double cameraY,
                            double cameraZ, int color) {
        fill(quads, pose, box.minX - cameraX, box.minY - cameraY, box.minZ - cameraZ,
            box.maxX - cameraX, box.maxY - cameraY, box.maxZ - cameraZ, color);
    }

    /** The 6 faces of a box as quads, the corners as the pose expects them. */
    public static void fill(VertexConsumer quads, PoseStack.Pose pose, double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ, int color) {
        for (int[] face : FACES) {
            for (int corner : face) {
                quads.addVertex(pose,
                    (float) ((corner & 1) == 0 ? minX : maxX),
                    (float) ((corner & 2) == 0 ? minY : maxY),
                    (float) ((corner & 4) == 0 ? minZ : maxZ)).setColor(color);
            }
        }
    }

    /** The 12 edges of a world-space box, drawn relative to the camera. */
    public static void box(VertexConsumer lines, PoseStack.Pose pose, AABB box, double cameraX, double cameraY,
                           double cameraZ, int color, float width) {
        float x0 = (float) (box.minX - cameraX), y0 = (float) (box.minY - cameraY), z0 = (float) (box.minZ - cameraZ);
        float x1 = (float) (box.maxX - cameraX), y1 = (float) (box.maxY - cameraY), z1 = (float) (box.maxZ - cameraZ);
        edge(lines, pose, x0, y0, z0, x1, y0, z0, X, color, width);
        edge(lines, pose, x0, y0, z1, x1, y0, z1, X, color, width);
        edge(lines, pose, x0, y1, z0, x1, y1, z0, X, color, width);
        edge(lines, pose, x0, y1, z1, x1, y1, z1, X, color, width);
        edge(lines, pose, x0, y0, z0, x0, y0, z1, Z, color, width);
        edge(lines, pose, x1, y0, z0, x1, y0, z1, Z, color, width);
        edge(lines, pose, x0, y1, z0, x0, y1, z1, Z, color, width);
        edge(lines, pose, x1, y1, z0, x1, y1, z1, Z, color, width);
        edge(lines, pose, x0, y0, z0, x0, y1, z0, Y, color, width);
        edge(lines, pose, x1, y0, z0, x1, y1, z0, Y, color, width);
        edge(lines, pose, x0, y0, z1, x0, y1, z1, Y, color, width);
        edge(lines, pose, x1, y0, z1, x1, y1, z1, Y, color, width);
    }

    /** One segment between two camera-relative points. */
    public static void segment(VertexConsumer lines, PoseStack.Pose pose, float x0, float y0, float z0,
                               float x1, float y1, float z1, int color, float width) {
        Vector3f normal = new Vector3f(x1 - x0, y1 - y0, z1 - z0);
        if (normal.lengthSquared() < 1.0E-8F) return;
        edge(lines, pose, x0, y0, z0, x1, y1, z1, normal.normalize(), color, width);
    }

    private static void edge(VertexConsumer lines, PoseStack.Pose pose, float x0, float y0, float z0,
                             float x1, float y1, float z1, Vector3fc normal, int color, float width) {
        lines.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, normal).setLineWidth(width);
        lines.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }
}
