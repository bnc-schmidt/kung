package com.github.beng420.kung.util;

import net.minecraft.world.phys.Vec3;

/** Where a point lies relative to the camera's view direction. Shared by features the Modrinth jar keeps and cuts. */
public final class ViewDepth {
    private ViewDepth() { }

    /** How far the point lies in front of the camera along its view direction; zero or less means behind it. */
    public static double depthInFront(Vec3 fromCamera, float forwardX, float forwardY, float forwardZ) {
        return fromCamera.x * forwardX + fromCamera.y * forwardY + fromCamera.z * forwardZ;
    }
}
