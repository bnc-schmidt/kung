package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public final class DungeonMimicMarkerTest {
    @Test public void aPointAheadOfTheCameraHasPositiveDepth() {
        // Camera looks along +Z; the chest stands 40 blocks ahead and 30 up.
        assertEquals(40.0, DungeonMimicEspFeature.depthInFront(new Vec3(0, 30, 40), 0f, 0f, 1f), 1e-6);
    }

    @Test public void aPointBehindTheCameraHasNoDepth() {
        // The projection mirrors such a point onto the screen, so the marker must not be drawn.
        assertTrue(DungeonMimicEspFeature.depthInFront(new Vec3(0, 30, -40), 0f, 0f, 1f) <= 0.05);
    }

    @Test public void aPointBesideTheCameraHasNoDepth() {
        assertEquals(0.0, DungeonMimicEspFeature.depthInFront(new Vec3(40, 0, 0), 0f, 0f, 1f), 1e-6);
    }

    @Test public void oneMarkerPerMimicRoomAndNeverForAChestOutsideThePlayersRoom() {
        // M7 trace 18 Sep: the Mimic room and an unrelated second trapped chest both appeared in the candidates.
        var mimic = new BlockPos(-185, 69, -153);
        var secondInSameRoom = new BlockPos(-173, 81, -113);
        var otherRoom = new BlockPos(-33, 82, -53);
        Map<BlockPos, String> targets = DungeonMimicEspFeature.markerTargets(
            List.of(otherRoom, mimic, secondInSameRoom),
            pos -> pos.equals(otherRoom) ? null : "cell",
            pos -> "match:1");
        assertEquals(List.of(mimic), List.copyOf(targets.keySet()));
        assertEquals("cell", targets.get(mimic));
    }

    @Test public void chestsInDifferentRoomsAreEachMarkedOnlyWhenTheyShareThePlayersRoom() {
        var first = new BlockPos(-185, 69, -153);
        var second = new BlockPos(-47, 69, -146);
        // Both match the player's room by owner, but the room key tells them apart.
        Map<BlockPos, String> targets = DungeonMimicEspFeature.markerTargets(
            List.of(first, second), pos -> "owner", pos -> pos.equals(first) ? "match:1" : "match:2");
        assertEquals(List.of(first, second), List.copyOf(targets.keySet()));
    }

    @Test public void noChestsMeansNoMarker() {
        assertTrue(DungeonMimicEspFeature.markerTargets(List.of(), pos -> "cell", pos -> "match:1").isEmpty());
    }
}
