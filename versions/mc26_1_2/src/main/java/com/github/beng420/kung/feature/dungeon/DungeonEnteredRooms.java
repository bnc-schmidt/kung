package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.CellKey;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.DoorRenderInfo;
import com.github.beng420.kung.feature.dungeon.DungeonLiveMapWriter.MatchRenderPlan;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The Modrinth build's map: a room shows up only once it is entered - a player was inside,
 * Hypixel's own map has it in colour, or it carries a checkmark. World scans still identify
 * a room, but nothing about an unentered room (shape, name, "?") reaches the screen. Every exit
 * of an entered room still shows, as what you see from inside: its kind, not the room behind.
 */
final class DungeonEnteredRooms {
    private static MatchRenderPlan source;
    private static MatchRenderPlan entered;

    private DungeonEnteredRooms() { }

    /** Cached per plan, so the render layout cache keeps working. */
    static MatchRenderPlan view(MatchRenderPlan plan) {
        if (plan != source) {
            entered = filter(plan);
            source = plan;
        }
        return entered;
    }

    static boolean entered(MatchRenderPlan plan, CellKey room) {
        return plan.visitedRooms().contains(room) || plan.clearedRooms().contains(room)
            || plan.completedRooms().contains(room);
    }

    /** A door sits between two room cells (one scan coordinate odd). */
    static boolean enteredDoor(MatchRenderPlan plan, CellKey door) {
        return entered(plan, door.firstSide()) && entered(plan, door.secondSide());
    }

    static boolean exitOfEnteredRoom(MatchRenderPlan plan, CellKey door) {
        return entered(plan, door.firstSide()) || entered(plan, door.secondSide());
    }

    /** Into an unentered room a door keeps its kind (open, wither, blood - all visible in the world) and nothing else. */
    private static Map<CellKey, DoorRenderInfo> exits(MatchRenderPlan plan) {
        Map<CellKey, DoorRenderInfo> exits = new HashMap<>();
        plan.externalDoors().forEach((door, info) -> {
            if (door.equals(plan.fairyEntranceDoor()) && fairyEntered(plan, door)) {
                // Once the Fairy room is known its entrance reads as a wither door, so the way to Blood
                // stays one chain of wither doors.
                exits.put(door, new DoorRenderInfo(DungeonDoorKind.WITHER, info.targetType(), false, info.targetVisited()));
            } else if (enteredDoor(plan, door)) {
                exits.put(door, info);
            } else if (exitOfEnteredRoom(plan, door)) {
                exits.put(door, new DoorRenderInfo(info.kind(), RoomType.UNKNOWN, false, false));
            }
        });
        return exits;
    }

    private static boolean fairyEntered(MatchRenderPlan plan, CellKey door) {
        for (CellKey side : List.of(door.firstSide(), door.secondSide())) {
            if (plan.roomTypes().get(side) == RoomType.FAIRY && entered(plan, side)) return true;
        }
        return false;
    }

    /**
     * The cell behind each exit nobody has entered, drawn as a 1x1 "?" so you see where rooms still
     * are. Even a room that turns out 2x2 starts as one cell: its shape is unknown until entered.
     */
    static Set<CellKey> unexplored(MatchRenderPlan view) {
        Set<CellKey> cells = new HashSet<>();
        for (CellKey door : view.externalDoors().keySet()) {
            for (CellKey side : List.of(door.firstSide(), door.secondSide())) {
                if (!entered(view, side) && DungeonScanUtils.isValidRoomGrid(side.x(), side.z())) cells.add(side);
            }
        }
        return cells;
    }

    static MatchRenderPlan filter(MatchRenderPlan plan) {
        Predicate<CellKey> room = cell -> entered(plan, cell);
        Predicate<CellKey> door = cell -> enteredDoor(plan, cell);
        return new MatchRenderPlan(
            plan.matches().stream().filter(match -> DungeonRoomPrediction.cells(match).stream().anyMatch(room)).toList(),
            plan.predictedRooms().stream().filter(match -> DungeonRoomPrediction.cells(match).stream().anyMatch(room)).toList(),
            only(plan.matchedRoomCells(), room),
            only(plan.internalDoors(), door),
            exits(plan),
            onlyKeys(plan.roomTypes(), room),
            onlyKeys(plan.roomOwners(), room),
            onlyKeys(plan.hints(), room),
            only(plan.remoteRoomCells(), room),
            onlyKeys(plan.remoteRoomProgress(), room),
            plan.visitedRooms(),
            plan.clearedRooms(),
            plan.completedRooms(),
            only(plan.openedSpecialDoorCells(), door),
            plan.fairyEntranceDoor() != null && door.test(plan.fairyEntranceDoor()) ? plan.fairyEntranceDoor() : null,
            List.of(),
            onlyKeys(plan.mapPuzzleNames(), room)
        );
    }

    /**
     * Totals may only be shown once every room is entered: each grid cell is an entered room,
     * or fully loaded, empty and not shown on Hypixel's map. A cell that was never fully loaded
     * could still hide a room, so it keeps the totals hidden.
     */
    static boolean allEntered(DungeonMapSnapshot snapshot, MatchRenderPlan plan) {
        Set<DungeonMapSnapshot.GridKey> mapVisible = snapshot.mapVisibleRooms();
        for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
            for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                CellKey cell = new CellKey(roomGridX, roomGridZ);
                if (entered(plan, cell)) continue;
                if (!snapshot.isFullyLoadedRoom(roomGridX, roomGridZ) || plan.roomOwners().containsKey(cell)
                    || plan.hints().containsKey(cell)
                    || mapVisible.contains(new DungeonMapSnapshot.GridKey(roomGridX, roomGridZ))) return false;
                DungeonScanPoint point = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
                if (point != null && !DungeonRoomClassifier.isEmptyCore(point.coreHash())) return false;
            }
        }
        return true;
    }

    private static Set<CellKey> only(Set<CellKey> cells, Predicate<CellKey> keep) {
        return cells.stream().filter(keep).collect(Collectors.toSet());
    }

    private static <V> Map<CellKey, V> onlyKeys(Map<CellKey, V> map, Predicate<CellKey> keep) {
        Map<CellKey, V> kept = new HashMap<>();
        map.forEach((cell, value) -> {
            if (keep.test(cell)) kept.put(cell, value);
        });
        return kept;
    }
}
