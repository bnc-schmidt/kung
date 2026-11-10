package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.MinecraftBootstrapTest;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.junit.Test;

public final class DungeonMapRotationTest extends MinecraftBootstrapTest {
    private static final float MIDDLE = 65;
    private static final float HEAD_X = 40;
    private static final float HEAD_Y = 90;
    private static final double DELTA = 1e-3;

    /** The pose drawGrid builds in front of the map: head to the middle, view direction to the top. */
    private static Matrix3x2f view(float yaw) {
        return new Matrix3x2f().translate(MIDDLE, MIDDLE).rotate(-DungeonMapFeature.mapTurn(yaw))
            .translate(-HEAD_X, -HEAD_Y);
    }

    @Test public void theHeadLandsInTheMiddleAndTheViewDirectionPointsUp() {
        for (float yaw = -180; yaw <= 180; yaw += 22.5F) {
            Matrix3x2f view = view(yaw);
            Vector2f head = view.transformPosition(HEAD_X, HEAD_Y, new Vector2f());
            assertEquals("head x at yaw " + yaw, MIDDLE, head.x, DELTA);
            assertEquals("head y at yaw " + yaw, MIDDLE, head.y, DELTA);
            // Minecraft: yaw 0 looks south (+z, down the map), yaw 90 looks west (-x).
            double radians = Math.toRadians(yaw);
            Vector2f ahead = view.transformPosition(HEAD_X + (float) -Math.sin(radians) * 10,
                HEAD_Y + (float) Math.cos(radians) * 10, new Vector2f());
            assertEquals("ahead x at yaw " + yaw, MIDDLE, ahead.x, DELTA);
            assertEquals("ahead y at yaw " + yaw, MIDDLE - 10, ahead.y, DELTA);
        }
    }

    @Test public void aLabelTurnedBackStaysUpright() {
        for (float yaw = -180; yaw <= 180; yaw += 22.5F) {
            Matrix3x2f label = view(yaw).translate(30, 50).rotate(DungeonMapFeature.mapTurn(yaw)).translate(-30, -50);
            Vector2f right = label.transformDirection(1, 0, new Vector2f());
            assertEquals("text direction x at yaw " + yaw, 1, right.x, DELTA);
            assertEquals("text direction y at yaw " + yaw, 0, right.y, DELTA);
        }
    }
}
