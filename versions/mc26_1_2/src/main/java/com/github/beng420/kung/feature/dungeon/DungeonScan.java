package com.github.beng420.kung.feature.dungeon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;

public final class DungeonScan {
    private static final int NO_CORE_HASH = 0;
    private static final int NO_DOOR_BLOCK = 0;

    public int scanPointCount() {
        return DungeonScanUtils.SCAN_GRID_SIZE * DungeonScanUtils.SCAN_GRID_SIZE;
    }

    public List<DungeonScanPoint> scanBatch(ClientLevel level, DungeonMapSnapshot snapshot, int startIndex, int limit) {
        long deadline = System.nanoTime() + 2_000_000L;
        int total = scanPointCount();
        int count = Math.clamp(limit, 1, total);
        List<DungeonScanPoint> points = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = Math.floorMod(startIndex + offset, total);
            int gridX = index / DungeonScanUtils.SCAN_GRID_SIZE;
            int gridZ = index % DungeonScanUtils.SCAN_GRID_SIZE;
            points.add(scanPoint(level, snapshot, gridX, gridZ));
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return points;
    }

    public List<DungeonScanPoint> scanLockedDoors(ClientLevel level, DungeonMapSnapshot snapshot) {
        List<DungeonScanPoint> points = new ArrayList<>();
        for (DungeonMapSnapshot.GridKey door : snapshot.observedLockedDoors()) {
            if (!snapshot.isOpenedLockedDoor(door)) {
                points.add(scanPoint(level, snapshot, door.gridX(), door.gridZ()));
            }
        }
        return points;
    }

    private DungeonScanPoint scanPoint(ClientLevel level, DungeonMapSnapshot snapshot, int gridX, int gridZ) {
        int worldX = DungeonScanUtils.worldXForScanGrid(gridX);
        int worldZ = DungeonScanUtils.worldZForScanGrid(gridZ);
        DungeonScanPointKind kind = kindFor(gridX, gridZ);
        boolean loaded = DungeonScanUtils.isChunkLoaded(level, worldX, worldZ);

        int coreHash = NO_CORE_HASH;
        int stableCoreHash = NO_CORE_HASH;
        int doorBlockId = NO_DOOR_BLOCK;
        DungeonDoorKind doorKind = DungeonDoorKind.NONE;
        DungeonScanPoint previous = snapshot.pointAt(gridX, gridZ);
        if (loaded && kind == DungeonScanPointKind.ROOM) {
            coreHash = DungeonScanUtils.getCoreHash(level, worldX, worldZ);
            stableCoreHash = DungeonScanUtils.getStableCoreHash(level, worldX, worldZ);
        }

        if (loaded && kind == DungeonScanPointKind.DOOR) {
            doorBlockId = DungeonScanUtils.getBlockId(level, worldX, DungeonScanUtils.MIN_DOOR_SCAN_Y, worldZ);
            doorKind = DungeonScanUtils.detectDoorKind(level, gridX, gridZ, worldX, worldZ,
                previous == null ? DungeonDoorKind.NONE : previous.doorKind());
        }

        return new DungeonScanPoint(
            gridX,
            gridZ,
            worldX,
            worldZ,
            kind,
            loaded,
            coreHash,
            stableCoreHash,
            doorBlockId,
            doorKind,
            loaded && kind == DungeonScanPointKind.ROOM
                && DungeonScanUtils.isRoomFullyLoaded(level::hasChunk, worldX, worldZ)
        );
    }

    private static DungeonScanPointKind kindFor(int gridX, int gridZ) {
        if (DungeonScanUtils.isRoomScanPoint(gridX, gridZ)) {
            return DungeonScanPointKind.ROOM;
        }
        if (DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
            return DungeonScanPointKind.DOOR;
        }
        return DungeonScanPointKind.SEPARATOR;
    }
}
