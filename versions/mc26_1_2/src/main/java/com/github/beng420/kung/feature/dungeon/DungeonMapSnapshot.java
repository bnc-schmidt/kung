package com.github.beng420.kung.feature.dungeon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import com.github.beng420.kung.util.KungDebugRecorder;

public final class DungeonMapSnapshot {

    private final Map<GridKey, ObservedPoint> points = new HashMap<>();
    private final Map<GridKey, ObservedPoint> initialRoomPoints = new HashMap<>();
    private final Set<GridKey> traversedDoors = new HashSet<>();
    private final Set<GridKey> openedLockedDoors = new HashSet<>();
    private final Set<GridKey> observedLockedDoors = new HashSet<>();
    private final Set<GridKey> visitedRooms = new HashSet<>();
    private final Set<GridKey> clearedRooms = new HashSet<>();
    private final Set<GridKey> completedRooms = new HashSet<>();
    private final Set<GridKey> mapPlayerRooms = new HashSet<>();
    private final Set<GridKey> mapVisibleRooms = new HashSet<>();
    private final Set<GridKey> mapTrapRooms = new HashSet<>();
    private final Set<GridKey> mapPuzzleRooms = new HashSet<>();
    /** Puzzle names from the tab list, lower case - they name a purple room its blocks no longer identify. */
    private final Set<String> tabPuzzles = new HashSet<>();
    private final Set<GridKey> fullyLoadedRooms = new HashSet<>();
    private final Set<GridKey> mapOpenDoors = new HashSet<>();
    private final Set<GridKey> mapRoomConnections = new HashSet<>();
    private final Set<GridKey> mimicRooms = new HashSet<>();
    private final Map<GridKey, RemoteRoom> remoteRooms = new HashMap<>();
    private final Map<GridKey, RemoteDoor> remoteDoors = new HashMap<>();
    private int lastScanNumber = -1;
    private long lastTimestamp;
    private int playerGridX;
    private int playerGridZ;
    private long revision;
    // Matching inputs only: scans, visibility and map topology, never player/checkmark changes.
    private long scanRevision;
    private long resetGeneration;
    private GridKey previousPlayerRoom;
    private GridKey startRoom;

    public void reset() {
        points.clear();
        initialRoomPoints.clear();
        traversedDoors.clear();
        openedLockedDoors.clear();
        observedLockedDoors.clear();
        visitedRooms.clear();
        clearedRooms.clear();
        completedRooms.clear();
        mapPlayerRooms.clear();
        mapVisibleRooms.clear();
        mapTrapRooms.clear();
        mapPuzzleRooms.clear();
        tabPuzzles.clear();
        fullyLoadedRooms.clear();
        mapOpenDoors.clear();
        mapRoomConnections.clear();
        mimicRooms.clear();
        remoteRooms.clear();
        remoteDoors.clear();
        lastScanNumber = -1;
        lastTimestamp = 0;
        playerGridX = 0;
        playerGridZ = 0;
        revision++;
        scanRevision++;
        resetGeneration++;
        previousPlayerRoom = null;
        startRoom = null;
        logMapChange("reset revision=" + revision);
    }

    public void addScan(
        int scanNumber,
        long timestamp,
        int currentPlayerGridX,
        int currentPlayerGridZ,
        List<DungeonScanPoint> scanPoints
    ) {
        lastScanNumber = scanNumber;
        lastTimestamp = timestamp;
        observePlayerGrid(currentPlayerGridX, currentPlayerGridZ);
        boolean changed = false;

        for (DungeonScanPoint point : scanPoints) {
            if (!point.loaded()) {
                continue;
            }

            GridKey key = new GridKey(point.gridX(), point.gridZ());
            if (point.kind() == DungeonScanPointKind.ROOM && point.roomFullyLoaded()) {
                GridKey room = new GridKey(point.gridX() / 2, point.gridZ() / 2);
                if (fullyLoadedRooms.add(room)) {
                    changed = true;
                    logMapChange("room-fully-loaded room=" + gridText(room));
                }
            }
            recordInitialRoomPoint(key, point, scanNumber);
            ObservedPoint previous = points.get(key);
            if (previous == null || shouldReplace(previous, point)) {
                ObservedPoint initial = initialRoomPoints.get(key);
                if (initial != null) DungeonKnownRoomCatalog.observePreloadTransition(initial.point(), point);
                if (previous != null) DungeonKnownRoomCatalog.observePreloadTransition(previous.point(), point);
                recordObservedCoreTransition(previous, point);
                recordDoorTransition(previous, point);
                points.put(key, new ObservedPoint(point));
                logMapChange("point scan=" + scanNumber
                    + " grid=" + gridText(key)
                    + " previous=" + pointText(previous == null ? null : previous.point())
                    + " next=" + pointText(point));
                changed = true;
            }
        }
        if (changed) {
            revision++;
            scanRevision++;
        }
    }

    public void observePlayerGrid(int currentPlayerGridX, int currentPlayerGridZ) {
        if (playerGridX != currentPlayerGridX || playerGridZ != currentPlayerGridZ) {
            GridKey previousGrid = new GridKey(playerGridX, playerGridZ);
            playerGridX = currentPlayerGridX;
            playerGridZ = currentPlayerGridZ;
            revision++;
            logMapChange("player-grid " + gridText(previousGrid)
                + " -> " + gridText(new GridKey(currentPlayerGridX, currentPlayerGridZ))
                + " revision=" + revision);
        }

        if (!DungeonScanUtils.isValidRoomGrid(currentPlayerGridX, currentPlayerGridZ)) {
            return;
        }

        GridKey currentPlayerRoom = new GridKey(currentPlayerGridX, currentPlayerGridZ);
        observeVisitedRoom(currentPlayerRoom);
        if (startRoom == null) {
            startRoom = toScanRoom(currentPlayerRoom);
            revision++;
            logMapChange("start-room room=" + gridText(currentPlayerRoom)
                + " scan=" + gridText(startRoom)
                + " revision=" + revision);
        }

        if (previousPlayerRoom != null && isAdjacent(previousPlayerRoom, currentPlayerRoom)) {
            GridKey door = doorBetween(previousPlayerRoom, currentPlayerRoom);
            if (traversedDoors.add(door)) {
                revision++;
                logMapChange("traversed-door from=" + gridText(previousPlayerRoom)
                    + " to=" + gridText(currentPlayerRoom)
                    + " door=" + gridText(door)
                    + " revision=" + revision);
            }
        }

        previousPlayerRoom = currentPlayerRoom;
    }

    /** A teleport crosses rooms without a door: the next observation must not infer a traversal. */
    void forgetPreviousPlayerRoom() {
        previousPlayerRoom = null;
    }

    void observeStartRoom(int roomGridX, int roomGridZ) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) return;
        GridKey entrance = new GridKey(roomGridX * 2, roomGridZ * 2);
        if (!entrance.equals(startRoom)) {
            startRoom = entrance;
            revision++;
            logMapChange("start-room source=instance scan=" + gridText(entrance) + " revision=" + revision);
        }
    }

    public void observeVisitedRoom(int roomGridX, int roomGridZ) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) {
            return;
        }
        observeVisitedRoom(new GridKey(roomGridX, roomGridZ));
    }

    public void observeMapPlayerRoom(int roomGridX, int roomGridZ) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) {
            return;
        }

        GridKey room = new GridKey(roomGridX, roomGridZ);
        boolean mapPlayerChanged = mapPlayerRooms.add(room);
        boolean changed = mapPlayerChanged;
        if (visitedRooms.add(room)) {
            changed = true;
            logMapChange("visited-room source=map-player room=" + gridText(room));
        }
        if (mapPlayerChanged) {
            logMapChange("map-player-room room=" + gridText(room));
        }
        if (changed) {
            revision++;
        }
    }

    public void observeMapVisibleRoom(int roomGridX, int roomGridZ) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) {
            return;
        }
        GridKey room = new GridKey(roomGridX, roomGridZ);
        if (mapVisibleRooms.add(room)) {
            revision++;
            scanRevision++;
            logMapChange("map-visible-room room=" + gridText(room)
                + " revision=" + revision);
        }
    }

    void observeMapTrapRoom(int roomGridX, int roomGridZ) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) return;
        observeMapVisibleRoom(roomGridX, roomGridZ);
        GridKey room = new GridKey(roomGridX, roomGridZ);
        if (mapTrapRooms.add(room)) {
            revision++;
            scanRevision++;
            logMapChange("map-room-type room=" + gridText(room) + " type=TRAP");
        }
    }

    boolean isMapTrapRoom(int roomGridX, int roomGridZ) {
        return mapTrapRooms.contains(new GridKey(roomGridX, roomGridZ));
    }

    void observeMapPuzzleRoom(int roomGridX, int roomGridZ) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) return;
        observeMapVisibleRoom(roomGridX, roomGridZ);
        GridKey room = new GridKey(roomGridX, roomGridZ);
        if (mapPuzzleRooms.add(room)) {
            revision++;
            scanRevision++;
            logMapChange("map-room-type room=" + gridText(room) + " type=PUZZLE");
        }
    }

    boolean isMapPuzzleRoom(int roomGridX, int roomGridZ) {
        return mapPuzzleRooms.contains(new GridKey(roomGridX, roomGridZ));
    }

    void observeTabPuzzles(Set<String> names) {
        if (tabPuzzles.equals(names)) return;
        tabPuzzles.clear();
        tabPuzzles.addAll(names);
        revision++;
    }

    Set<String> tabPuzzles() {
        return Set.copyOf(tabPuzzles);
    }

    public void observeMapOpenDoor(int scanGridX, int scanGridZ) {
        if (!isValidDoorGrid(scanGridX, scanGridZ)) {
            return;
        }
        GridKey door = new GridKey(scanGridX, scanGridZ);
        if (mapOpenDoors.add(door)) {
            revision++;
            scanRevision++;
            logMapChange("map-open-door door=" + gridText(door)
                + " revision=" + revision);
        }
    }

    public void observeMapRoomConnection(int scanGridX, int scanGridZ) {
        if (!isValidDoorGrid(scanGridX, scanGridZ)) {
            return;
        }
        GridKey door = new GridKey(scanGridX, scanGridZ);
        if (mapRoomConnections.add(door)) {
            revision++;
            scanRevision++;
            logMapChange("map-room-connection door=" + gridText(door)
                + " revision=" + revision);
        }
    }

    /** A narrow map connector is a room boundary; broad connectors join room cells. */
    public boolean isMapExternalDoor(int scanGridX, int scanGridZ) {
        GridKey door = new GridKey(scanGridX, scanGridZ);
        return mapOpenDoors.contains(door) && !mapRoomConnections.contains(door);
    }

    boolean isFullyLoadedRoom(int roomGridX, int roomGridZ) {
        return fullyLoadedRooms.contains(new GridKey(roomGridX, roomGridZ));
    }

    boolean allowsRoomPrediction(int roomGridX, int roomGridZ) {
        // Confirmed visibility lasts for this instance, even after its chunks unload.
        GridKey room = new GridKey(roomGridX, roomGridZ);
        return !mapVisibleRooms.contains(room) && !fullyLoadedRooms.contains(room);
    }

    boolean hasMapRoomBoundary(int scanGridX, int scanGridZ) {
        // Missing connector pixels are not evidence of a wall during partial map updates.
        return isMapExternalDoor(scanGridX, scanGridZ);
    }

    public void observeMimicRoom(int roomGridX, int roomGridZ, String source) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) {
            return;
        }
        GridKey room = new GridKey(roomGridX, roomGridZ);
        if (mimicRooms.add(room)) {
            revision++;
            logMapChange("mimic-room source=" + (source == null ? "unknown" : source)
                + " room=" + gridText(room)
                + " revision=" + revision);
        }
    }

    public void forgetMimicRoom(int roomGridX, int roomGridZ, String source) {
        GridKey room = new GridKey(roomGridX, roomGridZ);
        if (mimicRooms.remove(room)) {
            revision++;
            logMapChange("mimic-room-removed source=" + (source == null ? "unknown" : source)
                + " room=" + gridText(room)
                + " revision=" + revision);
        }
    }

    public void clearMimicRooms(String source) {
        if (mimicRooms.isEmpty()) {
            return;
        }
        int count = mimicRooms.size();
        mimicRooms.clear();
        revision++;
        logMapChange("mimic-rooms-cleared source=" + (source == null ? "unknown" : source)
            + " count=" + count
            + " revision=" + revision);
    }

    private void observeVisitedRoom(GridKey roomGrid) {
        if (visitedRooms.add(roomGrid)) {
            revision++;
            logMapChange("visited-room room=" + gridText(roomGrid)
                + " revision=" + revision);
        }
    }

    public void observeRoomClearState(int roomGridX, int roomGridZ, DungeonMapClearState clearState) {
        if (!DungeonScanUtils.isValidRoomGrid(roomGridX, roomGridZ)) {
            return;
        }

        GridKey room = new GridKey(roomGridX, roomGridZ);
        boolean changed = false;
        if (clearState == DungeonMapClearState.UNCLEARED) {
            if (completedRooms.remove(room)) {
                revision++;
                logMapChange("clear-state room=" + gridText(room)
                    + " state=" + clearState
                    + " completed=false revision=" + revision);
            }
            return;
        }

        if (clearedRooms.add(room)) {
            changed = true;
            logMapChange("clear-state room=" + gridText(room)
                + " cleared=true state=" + clearState);
        }
        if (clearState == DungeonMapClearState.COMPLETED) {
            if (completedRooms.add(room)) {
                changed = true;
                logMapChange("clear-state room=" + gridText(room)
                    + " completed=true state=" + clearState);
            }
        } else {
            if (completedRooms.remove(room)) {
                changed = true;
                logMapChange("clear-state room=" + gridText(room)
                    + " completed=false state=" + clearState);
            }
        }
        if (changed) {
            revision++;
            logMapChange("clear-state revision=" + revision);
        }
    }

    public ObservedPoint pointAt(int gridX, int gridZ) {
        return points.get(new GridKey(gridX, gridZ));
    }

    public List<ObservedPoint> points() {
        return List.copyOf(points.values());
    }

    public ObservedPoint initialRoomPointAt(int roomGridX, int roomGridZ) {
        return initialRoomPoints.get(new GridKey(roomGridX * 2, roomGridZ * 2));
    }

    public boolean isStartRoom(int gridX, int gridZ) {
        return startRoom != null && startRoom.gridX() == gridX && startRoom.gridZ() == gridZ;
    }

    public boolean isVisitedRoom(int roomGridX, int roomGridZ) {
        return visitedRooms.contains(new GridKey(roomGridX, roomGridZ));
    }

    public boolean isClearedRoom(int roomGridX, int roomGridZ) {
        return clearedRooms.contains(new GridKey(roomGridX, roomGridZ));
    }

    public boolean isCompletedRoom(int roomGridX, int roomGridZ) {
        return completedRooms.contains(new GridKey(roomGridX, roomGridZ));
    }

    public Set<GridKey> mapPlayerRooms() {
        return Set.copyOf(mapPlayerRooms);
    }

    public Set<GridKey> mapVisibleRooms() {
        return Set.copyOf(mapVisibleRooms);
    }

    public Set<GridKey> mapOpenDoors() {
        return Set.copyOf(mapOpenDoors);
    }

    public Set<GridKey> mapRoomConnections() {
        return Set.copyOf(mapRoomConnections);
    }

    public Set<GridKey> mimicRooms() {
        return Set.copyOf(mimicRooms);
    }

    public GridKey startRoom() {
        return startRoom;
    }

    public Set<GridKey> traversedDoors() {
        return Set.copyOf(traversedDoors);
    }

    public Set<GridKey> openedLockedDoors() {
        return Set.copyOf(openedLockedDoors);
    }

    public Set<GridKey> observedLockedDoors() {
        return Set.copyOf(observedLockedDoors);
    }

    public List<RemoteRoom> remoteRooms() {
        return List.copyOf(remoteRooms.values());
    }

    public List<RemoteDoor> remoteDoors() {
        return List.copyOf(remoteDoors.values());
    }

    public void replaceRemoteLiveData(List<RemoteRoom> rooms, List<RemoteDoor> doors) {
        Map<GridKey, RemoteRoom> nextRooms = new HashMap<>();
        if (rooms != null) {
            for (RemoteRoom room : rooms) {
                if (room == null || !DungeonScanUtils.isValidRoomGrid(room.roomGridX(), room.roomGridZ())) {
                    continue;
                }
                RemoteRoom normalized = normalizeRemoteRoom(room, room.source() == null ? "" : room.source());
                GridKey key = new GridKey(normalized.roomGridX(), normalized.roomGridZ());
                nextRooms.merge(key, normalized, DungeonMapSnapshot::mergeRemoteRoom);
            }
        }

        Map<GridKey, RemoteDoor> nextDoors = new HashMap<>();
        if (doors != null) {
            for (RemoteDoor door : doors) {
                if (door == null || !isValidDoorGrid(door.scanGridX(), door.scanGridZ())) {
                    continue;
                }
                RemoteDoor normalized = normalizeRemoteDoor(door, door.source() == null ? "" : door.source());
                GridKey key = new GridKey(normalized.scanGridX(), normalized.scanGridZ());
                nextDoors.merge(key, normalized, DungeonMapSnapshot::mergeRemoteDoor);
            }
        }

        if (remoteRooms.equals(nextRooms) && remoteDoors.equals(nextDoors)) {
            return;
        }
        remoteRooms.clear();
        remoteRooms.putAll(nextRooms);
        remoteDoors.clear();
        remoteDoors.putAll(nextDoors);
        revision++;
        logMapChange("remote-live-replace rooms=" + remoteRooms.size()
            + " doors=" + remoteDoors.size()
            + " revision=" + revision);
    }

    public void mergeRemoteRooms(String source, List<RemoteRoom> rooms) {
        if (source == null || source.isBlank() || rooms == null || rooms.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (RemoteRoom room : rooms) {
            if (room == null || !DungeonScanUtils.isValidRoomGrid(room.roomGridX(), room.roomGridZ())) {
                continue;
            }
            RemoteRoom normalized = normalizeRemoteRoom(room, source);
            GridKey key = new GridKey(normalized.roomGridX(), normalized.roomGridZ());
            RemoteRoom previous = remoteRooms.get(key);
            RemoteRoom merged = mergeRemoteRoom(previous, normalized);
            remoteRooms.put(key, merged);
            if (!merged.equals(previous)) {
                changed = true;
                logMapChange("remote-room source=" + source
                    + " room=" + gridText(key)
                    + " name=" + merged.name()
                    + " type=" + merged.type());
            }
        }
        if (changed) {
            revision++;
        }
    }

    /** Clamps and defaults an inbound remote room; `source` is the attribution to stamp on it. */
    private static RemoteRoom normalizeRemoteRoom(RemoteRoom room, String source) {
        int roomSecretsMax = room.roomSecretsMax() > 0 ? room.roomSecretsMax() : room.secrets();
        return new RemoteRoom(
            room.roomGridX(),
            room.roomGridZ(),
            room.name() == null ? "" : room.name(),
            room.type() == null ? RoomType.UNKNOWN : room.type(),
            Math.max(0, room.secrets()),
            Math.max(0, room.crypts()),
            Math.clamp(room.roomSecretsFound(), 0, Math.max(0, roomSecretsMax)),
            Math.max(0, roomSecretsMax),
            room.visited(),
            room.cleared(),
            room.completed(),
            source,
            room.updatedAtMillis()
        );
    }

    /** Clamps and defaults an inbound remote door; `source` is the attribution to stamp on it. */
    private static RemoteDoor normalizeRemoteDoor(RemoteDoor door, String source) {
        return new RemoteDoor(
            door.scanGridX(),
            door.scanGridZ(),
            door.kind() == null ? DungeonDoorKind.NONE : door.kind(),
            door.targetType() == null ? RoomType.UNKNOWN : door.targetType(),
            door.targetVisited(),
            source,
            door.updatedAtMillis()
        );
    }

    private static RemoteRoom mergeRemoteRoom(RemoteRoom previous, RemoteRoom next) {
        if (previous == null) {
            return next;
        }
        String name = previous.name() == null || previous.name().isBlank() ? next.name() : previous.name();
        RoomType type = previous.type() == null || previous.type() == RoomType.UNKNOWN
            || (previous.type() == RoomType.NORMAL && next.type() == RoomType.RARE)
            ? next.type() : previous.type();
        int secrets = Math.max(previous.secrets(), next.secrets());
        int crypts = Math.max(previous.crypts(), next.crypts());
        int roomSecretsMax = Math.max(previous.roomSecretsMax(), next.roomSecretsMax());
        int roomSecretsFound = Math.max(previous.roomSecretsFound(), next.roomSecretsFound());
        return new RemoteRoom(
            previous.roomGridX(),
            previous.roomGridZ(),
            name,
            type,
            secrets,
            crypts,
            Math.clamp(roomSecretsFound, 0, Math.max(0, roomSecretsMax)),
            roomSecretsMax,
            previous.visited() || next.visited(),
            previous.cleared() || next.cleared(),
            previous.completed() || next.completed(),
            previous.updatedAtMillis() >= next.updatedAtMillis() ? previous.source() : next.source(),
            Math.max(previous.updatedAtMillis(), next.updatedAtMillis())
        );
    }

    private static RemoteDoor mergeRemoteDoor(RemoteDoor previous, RemoteDoor next) {
        if (previous == null) {
            return next;
        }
        if (next.updatedAtMillis() >= previous.updatedAtMillis()) {
            return next;
        }
        return new RemoteDoor(
            previous.scanGridX(),
            previous.scanGridZ(),
            previous.kind(),
            previous.targetType() == RoomType.UNKNOWN ? next.targetType() : previous.targetType(),
            previous.targetVisited() || next.targetVisited(),
            previous.source(),
            previous.updatedAtMillis()
        );
    }

    public int lockedSpecialDoorCount() {
        Set<GridKey> specialDoors = new HashSet<>(openedLockedDoors);
        for (ObservedPoint point : points.values()) {
            if (point.point().kind() != DungeonScanPointKind.DOOR) {
                continue;
            }
            DungeonDoorKind doorKind = point.point().doorKind();
            if (doorKind == DungeonDoorKind.WITHER || doorKind == DungeonDoorKind.BLOOD) {
                specialDoors.add(new GridKey(point.point().gridX(), point.point().gridZ()));
            }
        }
        return specialDoors.size();
    }

    public int lastScanNumber() {
        return lastScanNumber;
    }

    public long lastTimestamp() {
        return lastTimestamp;
    }

    public int playerGridX() {
        return playerGridX;
    }

    public int playerGridZ() {
        return playerGridZ;
    }

    public int observedRoomCount() {
        int count = 0;
        for (ObservedPoint point : points.values()) {
            if (point.point().kind() == DungeonScanPointKind.ROOM
                && !DungeonRoomClassifier.isEmptyCore(point.point().coreHash())) {
                count++;
            }
        }
        return count;
    }

    public long resetGeneration() {
        return resetGeneration;
    }

    public long revision() {
        return revision;
    }

    long scanRevision() { return scanRevision; }

    boolean isOpenedLockedDoor(GridKey door) {
        return openedLockedDoors.contains(door);
    }

    private boolean shouldReplace(ObservedPoint previous, DungeonScanPoint next) {
        if (next.kind() != previous.point().kind()) {
            return true;
        }

        if (next.kind() == DungeonScanPointKind.ROOM) {
            return shouldReplaceRoom(previous.point(), next);
        }

        if (next.kind() == DungeonScanPointKind.DOOR) {
            return shouldReplaceDoor(
                previous.point().doorBlockId(),
                previous.point().doorKind(),
                next.doorBlockId(),
                next.doorKind()
            );
        }

        return false;
    }

    private void recordInitialRoomPoint(GridKey key, DungeonScanPoint point, int scanNumber) {
        if (point.kind() != DungeonScanPointKind.ROOM
            || DungeonRoomClassifier.isEmptyCore(point.coreHash())
            || initialRoomPoints.containsKey(key)) {
            return;
        }
        initialRoomPoints.put(key, new ObservedPoint(point));
        logMapChange("initial-room-point scan=" + scanNumber
            + " grid=" + gridText(key)
            + " point=" + pointText(point));
    }

    private boolean shouldReplaceRoom(DungeonScanPoint previous, DungeonScanPoint next) {
        int previousCoreHash = previous.coreHash();
        int nextCoreHash = next.coreHash();
        if (previousCoreHash == nextCoreHash && previous.stableCoreHash() == next.stableCoreHash()) {
            return false;
        }

        boolean previousEmpty = DungeonRoomClassifier.isEmptyCore(previousCoreHash);
        boolean nextEmpty = DungeonRoomClassifier.isEmptyCore(nextCoreHash);
        if (previousEmpty != nextEmpty) {
            return previousEmpty;
        }
        if (previousEmpty) {
            return true;
        }

        boolean previousKnownRoom = DungeonKnownRoomCatalog.isStableKnownCoreHash(previousCoreHash)
            || DungeonKnownRoomCatalog.isStableKnownCoreHash(previous.stableCoreHash());
        boolean nextKnownRoom = DungeonKnownRoomCatalog.isStableKnownCoreHash(nextCoreHash)
            || DungeonKnownRoomCatalog.isStableKnownCoreHash(next.stableCoreHash());
        // Puzzle/block updates can change both hashes. Keep direct evidence, never a preload guess.
        if (nextKnownRoom) return true;
        if (previousKnownRoom) return false;
        if (!allowsRoomPrediction(next.gridX() / 2, next.gridZ() / 2)) return true;

        RoomType previousType = DungeonRoomClassifier.classifyRoom(previousCoreHash);
        RoomType nextType = DungeonRoomClassifier.classifyRoom(nextCoreHash);
        return previousType == nextType || previousType == RoomType.NORMAL;
    }

    private static boolean shouldReplaceDoor(
        int previousDoorBlockId,
        DungeonDoorKind previousKind,
        int nextDoorBlockId,
        DungeonDoorKind nextKind
    ) {
        if (previousDoorBlockId == nextDoorBlockId && previousKind == nextKind) {
            return false;
        }

        boolean previousVisible = previousKind.visible();
        boolean nextVisible = nextKind.visible();
        if (lockedDoor(previousKind) && !lockedDoor(nextKind)) {
            return nextKind == DungeonDoorKind.OPEN
                || nextDoorBlockId == 0;
        }
        if (previousVisible && !nextVisible) {
            return false;
        }
        if (previousVisible != nextVisible) {
            return nextVisible;
        }
        return true;
    }

    private static void recordObservedCoreTransition(ObservedPoint previous, DungeonScanPoint next) {
        if (previous == null || next.kind() != DungeonScanPointKind.ROOM) {
            return;
        }

        DungeonKnownRoomCatalog.recordObservedCoreTransition(
            previous.point().coreHash(),
            next.coreHash(),
            next.stableCoreHash()
        );
    }

    private void recordDoorTransition(ObservedPoint previous, DungeonScanPoint next) {
        if (next.kind() != DungeonScanPointKind.DOOR) {
            return;
        }
        GridKey door = new GridKey(next.gridX(), next.gridZ());
        if (lockedDoor(next.doorKind())) {
            observedLockedDoors.add(door);
        }
        if (previous == null) {
            return;
        }
        DungeonDoorKind previousKind = previous.point().doorKind();
        if (lockedDoor(previousKind)
            && !lockedDoor(next.doorKind())
            && confirmsOpenedLockedDoor(next)
            && openedLockedDoors.add(door)) {
            logMapChange("opened-locked-door grid=" + gridText(door)
                + " previous=" + previousKind
                + " next=" + next.doorKind());
        }
    }

    private static boolean lockedDoor(DungeonDoorKind kind) {
        return kind == DungeonDoorKind.WITHER || kind == DungeonDoorKind.BLOOD;
    }

    private static boolean confirmsOpenedLockedDoor(DungeonScanPoint point) {
        return point.doorKind() == DungeonDoorKind.OPEN
            || point.doorBlockId() == 0;
    }

    private static void logMapChange(String message) {
        KungDebugRecorder.event("map-change", message);
    }

    private static String gridText(GridKey key) {
        return key == null ? "null" : key.gridX() + "," + key.gridZ();
    }

    private static String pointText(DungeonScanPoint point) {
        if (point == null) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(point.kind())
            .append(":loaded=").append(point.loaded())
            .append(":world=").append(point.worldX()).append(',').append(point.worldZ());
        if (point.kind() == DungeonScanPointKind.ROOM) {
            builder.append(":core=").append(point.coreHash())
                .append(":stable=").append(point.stableCoreHash())
                .append(":type=").append(DungeonRoomClassifier.classifyRoom(point.coreHash()));
        } else if (point.kind() == DungeonScanPointKind.DOOR) {
            builder.append(":door=").append(point.doorKind())
                .append(":block=").append(point.doorBlockId());
        }
        return builder.toString();
    }

    private static boolean isValidDoorGrid(int gridX, int gridZ) {
        return gridX >= 0
            && gridZ >= 0
            && gridX < DungeonScanUtils.SCAN_GRID_SIZE
            && gridZ < DungeonScanUtils.SCAN_GRID_SIZE
            && DungeonScanUtils.isDoorScanPoint(gridX, gridZ);
    }

    private static boolean isAdjacent(GridKey firstRoom, GridKey secondRoom) {
        int distanceX = Math.abs(firstRoom.gridX() - secondRoom.gridX());
        int distanceZ = Math.abs(firstRoom.gridZ() - secondRoom.gridZ());
        return distanceX + distanceZ == 1;
    }

    private static GridKey doorBetween(GridKey firstRoom, GridKey secondRoom) {
        return new GridKey(
            firstRoom.gridX() + secondRoom.gridX(),
            firstRoom.gridZ() + secondRoom.gridZ()
        );
    }

    private static GridKey toScanRoom(GridKey roomGrid) {
        return new GridKey(roomGrid.gridX() * 2, roomGrid.gridZ() * 2);
    }

    public record GridKey(int gridX, int gridZ) {
    }

    public record ObservedPoint(DungeonScanPoint point) {
    }

    public record RemoteRoom(
        int roomGridX,
        int roomGridZ,
        String name,
        RoomType type,
        int secrets,
        int crypts,
        int roomSecretsFound,
        int roomSecretsMax,
        boolean visited,
        boolean cleared,
        boolean completed,
        String source,
        long updatedAtMillis
    ) {
    }

    public record RemoteDoor(
        int scanGridX,
        int scanGridZ,
        DungeonDoorKind kind,
        RoomType targetType,
        boolean targetVisited,
        String source,
        long updatedAtMillis
    ) {
    }
}
