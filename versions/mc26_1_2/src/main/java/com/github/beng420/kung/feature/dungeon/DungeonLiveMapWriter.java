package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class DungeonLiveMapWriter {
    private DungeonLiveMapWriter() {
    }

    private static boolean isSpecialDoorTarget(RoomType roomType) {
        return roomType != null
            && roomType != RoomType.NORMAL
            && roomType != RoomType.RARE
            && roomType != RoomType.START
            && roomType != RoomType.UNKNOWN;
    }

    private static boolean isLockedObservedPoint(DungeonScanPoint observedPoint) {
        return observedPoint != null && observedPoint.doorKind().locked();
    }

    private static boolean isRealRoom(DungeonMapSnapshot snapshot, int gridX, int gridZ) {
        DungeonScanPoint observedPoint = snapshot.pointAt(gridX, gridZ);
        return observedPoint != null && observedPoint.isRealRoom();
    }

    static record MatchRenderPlan(
        List<DungeonKnownRoomCatalog.MatchedRoom> matches,
        List<DungeonKnownRoomCatalog.MatchedRoom> predictedRooms,
        Set<CellKey> matchedRoomCells,
        Set<CellKey> internalDoors,
        Map<CellKey, DoorRenderInfo> externalDoors,
        Map<CellKey, RoomType> roomTypes,
        Map<CellKey, String> roomOwners,
        Map<CellKey, DungeonKnownRoomCatalog.KnownCoreHint> hints,
        Set<CellKey> remoteRoomCells,
        Map<CellKey, RoomProgress> remoteRoomProgress,
        Set<CellKey> visitedRooms,
        Set<CellKey> clearedRooms,
        Set<CellKey> completedRooms,
        Set<CellKey> openedSpecialDoorCells,
        CellKey fairyEntranceDoor,
        List<CellKey> bloodRushPath,
        Map<CellKey, String> mapPuzzleNames
    ) {
        DungeonKnownRoomCatalog.MatchedRoom matchContaining(int roomGridX, int roomGridZ) {
            for (DungeonKnownRoomCatalog.MatchedRoom match : matches) {
                if (match.contains(roomGridX, roomGridZ)) {
                    return match;
                }
            }
            return null;
        }

        static MatchRenderPlan from(DungeonMapSnapshot snapshot, DungeonRoomRepository roomRepository) {
            List<DungeonKnownRoomCatalog.MatchedRoom> matches = roomRepository.matchKnownRooms(snapshot);
            Set<CellKey> matchedRoomCells = new HashSet<>();
            Set<CellKey> internalDoors = new HashSet<>();
            Map<CellKey, DoorRenderInfo> externalDoors = new HashMap<>();
            Map<CellKey, RoomType> roomTypes = new HashMap<>();
            Map<CellKey, String> roomOwners = new HashMap<>();
            Map<CellKey, RoomIdentity> roomIdentities = new HashMap<>();
            Map<CellKey, DungeonKnownRoomCatalog.KnownCoreHint> hints = new HashMap<>();
            Set<CellKey> remoteRoomCells = new HashSet<>();
            Map<CellKey, RoomProgress> remoteRoomProgress = new HashMap<>();
            Set<CellKey> visitedRooms = new HashSet<>();
            Set<CellKey> clearedRooms = new HashSet<>();
            Set<CellKey> completedRooms = new HashSet<>();
            int matchIndex = 0;
            for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
                for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                    if (snapshot.isVisitedRoom(roomGridX, roomGridZ)) {
                        visitedRooms.add(new CellKey(roomGridX, roomGridZ));
                    }
                    if (snapshot.isClearedRoom(roomGridX, roomGridZ)) {
                        clearedRooms.add(new CellKey(roomGridX, roomGridZ));
                    }
                    if (snapshot.isCompletedRoom(roomGridX, roomGridZ)) {
                        completedRooms.add(new CellKey(roomGridX, roomGridZ));
                    }
                }
            }

            for (DungeonKnownRoomCatalog.MatchedRoom match : matches) {
                String roomOwner = "match:" + matchIndex++;
                RoomIdentity identity = RoomIdentity.of(match.template().name(), match.template().type(), match.template().secrets());
                boolean matchVisited = false;
                for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
                    CellKey roomCell = new CellKey(component.roomGridX(), component.roomGridZ());
                    matchedRoomCells.add(roomCell);
                    roomTypes.put(roomCell, match.template().type());
                    roomOwners.put(roomCell, roomOwner);
                    roomIdentities.put(roomCell, identity);
                    matchVisited = matchVisited || visitedRooms.contains(roomCell);
                }

                if (matchVisited) {
                    for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
                        visitedRooms.add(new CellKey(component.roomGridX(), component.roomGridZ()));
                    }
                }

                for (DungeonKnownRoomCatalog.MatchedComponent first : match.components()) {
                    for (DungeonKnownRoomCatalog.MatchedComponent second : match.components()) {
                        int distanceX = Math.abs(first.roomGridX() - second.roomGridX());
                        int distanceZ = Math.abs(first.roomGridZ() - second.roomGridZ());
                        if (distanceX + distanceZ == 1) {
                            internalDoors.add(new CellKey(
                                first.roomGridX() + second.roomGridX(),
                                first.roomGridZ() + second.roomGridZ()
                            ));
                        }
                    }
                }
            }

            for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
                for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                    CellKey roomCell = new CellKey(roomGridX, roomGridZ);
                    if (matchedRoomCells.contains(roomCell)) {
                        continue;
                    }

                    DungeonScanPoint observedPoint = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
                    if (observedPoint == null || !observedPoint.isRealRoom()) {
                        continue;
                    }

                    roomOwners.put(roomCell, "cell:" + roomGridX + "," + roomGridZ);

                    DungeonKnownRoomCatalog.KnownCoreHint hint =
                        roomRepository.knownHintForPoint(snapshot, observedPoint);
                    if (hint != null) {
                        hints.put(roomCell, hint);
                        roomTypes.put(roomCell, hint.type());
                        roomOwners.put(roomCell, "hint:" + roomGridX + "," + roomGridZ);
                        roomIdentities.put(roomCell, RoomIdentity.of(hint.name(), hint.type(), hint.secrets()));
                    }

                    if (snapshot.isStartRoom(roomGridX * 2, roomGridZ * 2)
                        && isRealRoom(snapshot, roomGridX * 2, roomGridZ * 2)) {
                        roomTypes.put(roomCell, RoomType.START);
                        roomOwners.put(roomCell, "start");
                        roomIdentities.put(roomCell, RoomIdentity.of("start", RoomType.START, 0));
                    }
                }
            }

            for (DungeonMapSnapshot.GridKey mapRoom : snapshot.mapVisibleRooms()) {
                CellKey roomCell = new CellKey(mapRoom.gridX(), mapRoom.gridZ());
                roomTypes.putIfAbsent(roomCell, snapshot.isMapTrapRoom(mapRoom.gridX(), mapRoom.gridZ()) ? RoomType.TRAP
                    : snapshot.isMapPuzzleRoom(mapRoom.gridX(), mapRoom.gridZ()) ? RoomType.PUZZLE : RoomType.UNKNOWN);
                roomOwners.putIfAbsent(roomCell, "map:" + mapRoom.gridX() + "," + mapRoom.gridZ());
            }

            // A verified type hash can identify a rare room before its name/secrets are known.
            // Also refine older NORMAL catalog records without changing their learned metadata.
            for (CellKey roomCell : roomOwners.keySet()) {
                DungeonScanPoint observed = snapshot.pointAt(roomCell.x() * 2, roomCell.z() * 2);
                if (observed == null || observed.kind() != DungeonScanPointKind.ROOM) continue;
                RoomType current = roomTypes.getOrDefault(roomCell, RoomType.UNKNOWN);
                RoomIdentity identity = roomIdentities.get(roomCell);
                if ((current == RoomType.NORMAL || current == RoomType.UNKNOWN)
                    && (identity == null || DungeonKnownRoomCatalog.canonicalRoomType(identity.name(), RoomType.RARE,
                        observed.coreHash(), observed.stableCoreHash()) == RoomType.RARE)
                    && DungeonRoomClassifier.classifyRoom(observed.coreHash()) == RoomType.RARE) {
                    promoteToRare(roomCell, roomTypes, roomIdentities);
                }
            }

            for (DungeonMapSnapshot.RemoteRoom remoteRoom : snapshot.remoteRooms()) {
                CellKey roomCell = new CellKey(remoteRoom.roomGridX(), remoteRoom.roomGridZ());
                String remoteOwner = "remote:" + compactName(remoteRoom.source()) + ":" + roomCell.x() + "," + roomCell.z();
                remoteRoomCells.add(roomCell);
                RoomType remoteType = remoteRoom.type() == null ? RoomType.UNKNOWN : remoteRoom.type();
                String remoteName = remoteRoom.name() == null ? "" : remoteRoom.name().trim();
                DungeonScanPoint observed = snapshot.pointAt(roomCell.x() * 2, roomCell.z() * 2);
                int coreHash = observed == null ? 0 : observed.coreHash();
                int stableHash = observed == null ? 0 : observed.stableCoreHash();
                remoteType = DungeonKnownRoomCatalog.canonicalRoomType(remoteName, remoteType, coreHash, stableHash);
                int remoteSecretsMax = remoteRoom.roomSecretsMax() > 0
                    ? remoteRoom.roomSecretsMax()
                    : remoteRoom.secrets();
                remoteRoomProgress.merge(
                    roomCell,
                    new RoomProgress(remoteRoom.roomSecretsFound(), remoteSecretsMax),
                    RoomProgress::max
                );
                boolean hasIdentity = !remoteName.isBlank() || remoteType != RoomType.UNKNOWN;
                if (hasIdentity && !matchedRoomCells.contains(roomCell)) {
                    DungeonKnownRoomCatalog.KnownCoreHint hint =
                        new DungeonKnownRoomCatalog.KnownCoreHint(
                            remoteName.isBlank() ? "Remote room" : remoteName,
                            remoteType,
                            Math.max(0, remoteRoom.secrets()),
                            Math.max(0, remoteRoom.crypts())
                        );
                    hints.putIfAbsent(roomCell, hint);
                    if (roomTypes.getOrDefault(roomCell, RoomType.UNKNOWN) == RoomType.UNKNOWN) {
                        roomTypes.put(roomCell, remoteType);
                        roomOwners.put(roomCell, remoteOwner);
                        roomIdentities.put(roomCell, RoomIdentity.of(hint.name(), hint.type(), hint.secrets()));
                    } else {
                        roomTypes.putIfAbsent(roomCell, remoteType);
                        roomOwners.putIfAbsent(roomCell, remoteOwner);
                        roomIdentities.putIfAbsent(
                            roomCell,
                            RoomIdentity.of(hint.name(), hint.type(), hint.secrets())
                        );
                    }
                } else {
                    roomTypes.putIfAbsent(roomCell, RoomType.UNKNOWN);
                    roomOwners.putIfAbsent(roomCell, remoteOwner);
                }
                RoomIdentity localIdentity = roomIdentities.get(roomCell);
                if (remoteType == RoomType.RARE && roomTypes.get(roomCell) == RoomType.NORMAL
                    && (localIdentity == null
                        || DungeonKnownRoomCatalog.canonicalRoomType(localIdentity.name(), RoomType.RARE,
                            coreHash, stableHash) == RoomType.RARE)) {
                    promoteToRare(roomCell, roomTypes, roomIdentities);
                }
                if (remoteRoom.visited()) {
                    visitedRooms.add(roomCell);
                }
                if (remoteRoom.cleared()) {
                    clearedRooms.add(roomCell);
                }
                if (remoteRoom.completed()) {
                    completedRooms.add(roomCell);
                }
            }

            mergeConnectedSameIdentityRooms(snapshot, roomOwners, roomIdentities, internalDoors);
            mergeMapRoomConnections(snapshot, roomOwners, roomIdentities, internalDoors);

            Map<DoorPair, CellKey> detectedDoorByRoomPair = new HashMap<>();
            Set<CellKey> openedDoorCells = new HashSet<>();
            Set<CellKey> openedSpecialDoorCells = new HashSet<>();
            for (int gridZ = 0; gridZ < DungeonScanUtils.SCAN_GRID_SIZE; gridZ++) {
                for (int gridX = 0; gridX < DungeonScanUtils.SCAN_GRID_SIZE; gridX++) {
                    if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                        continue;
                    }

                    DungeonScanPoint observedPoint = snapshot.pointAt(gridX, gridZ);
                    if (observedPoint == null || !observedPoint.doorKind().visible()) {
                        continue;
                    }

                    CellKey doorCell = new CellKey(gridX, gridZ);
                    if (internalDoors.contains(doorCell)) {
                        continue;
                    }

                    String firstOwner = roomOwners.get(doorCell.firstSide());
                    String secondOwner = roomOwners.get(doorCell.secondSide());
                    if (firstOwner == null && secondOwner == null) {
                        continue;
                    }
                    if (firstOwner != null && firstOwner.equals(secondOwner)) {
                        continue;
                    }
                    if (firstOwner == null || secondOwner == null) {
                        externalDoors.put(doorCell, doorInfoFor(observedPoint.doorKind(), roomTypes, visitedRooms, doorCell));
                        continue;
                    }

                    DoorPair doorPair = DoorPair.of(firstOwner, secondOwner);
                    detectedDoorByRoomPair.putIfAbsent(doorPair, doorCell);
                }
            }

            for (DungeonMapSnapshot.GridKey openedLockedDoor : snapshot.openedLockedDoors()) {
                int gridX = openedLockedDoor.gridX();
                int gridZ = openedLockedDoor.gridZ();
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                    continue;
                }
                CellKey doorCell = new CellKey(gridX, gridZ);
                if (internalDoors.contains(doorCell)) {
                    continue;
                }
                if (!hasDistinctDoorOwners(roomOwners, doorCell)) {
                    continue;
                }
                openedDoorCells.add(doorCell);
                openedSpecialDoorCells.add(doorCell);
                externalDoors.put(doorCell, doorInfoFor(DungeonDoorKind.OPEN, roomTypes, visitedRooms, doorCell));
            }

            for (DungeonMapSnapshot.GridKey traversedDoor : snapshot.traversedDoors()) {
                int gridX = traversedDoor.gridX();
                int gridZ = traversedDoor.gridZ();
                CellKey doorCell = new CellKey(gridX, gridZ);
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ) || internalDoors.contains(doorCell)) {
                    continue;
                }
                if (!hasDistinctDoorOwners(roomOwners, doorCell)) {
                    continue;
                }

                DungeonScanPoint observedPoint = snapshot.pointAt(gridX, gridZ);
                boolean observedVisibleDoor = observedPoint != null && observedPoint.doorKind().visible();
                if (!observedVisibleDoor && touchesRoomType(roomTypes, doorCell, RoomType.PUZZLE)) {
                    continue;
                }
                if (isLockedObservedPoint(observedPoint) && !snapshot.isOpenedLockedDoor(traversedDoor)) {
                    continue;
                }

                openedDoorCells.add(doorCell);

                String firstOwner = roomOwners.get(doorCell.firstSide());
                String secondOwner = roomOwners.get(doorCell.secondSide());
                DoorPair doorPair = DoorPair.of(firstOwner, secondOwner);
                detectedDoorByRoomPair.putIfAbsent(doorPair, doorCell);
            }
            for (CellKey doorCell : detectedDoorByRoomPair.values()) {
                DungeonScanPoint observedPoint = snapshot.pointAt(doorCell.x(), doorCell.z());
                DungeonDoorKind doorKind = openedDoorCells.contains(doorCell)
                    || observedPoint == null || !observedPoint.doorKind().visible()
                    || observedPoint.doorKind() == DungeonDoorKind.OPEN
                    ? DungeonDoorKind.OPEN
                    : observedPoint.doorKind();
                externalDoors.put(doorCell, doorInfoFor(doorKind, roomTypes, visitedRooms, doorCell));
            }

            for (DungeonMapSnapshot.GridKey mapOpenDoor : snapshot.mapOpenDoors()) {
                int gridX = mapOpenDoor.gridX();
                int gridZ = mapOpenDoor.gridZ();
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                    continue;
                }
                CellKey doorCell = new CellKey(gridX, gridZ);
                if (internalDoors.contains(doorCell) || !hasDistinctDoorOwners(roomOwners, doorCell)) {
                    continue;
                }

                DoorRenderInfo previousInfo = externalDoors.get(doorCell);
                DungeonScanPoint observedPoint = snapshot.pointAt(gridX, gridZ);
                boolean knownLockedDoor = previousInfo != null && previousInfo.kind().locked()
                    || snapshot.observedLockedDoors().contains(mapOpenDoor)
                    || isLockedObservedPoint(observedPoint);
                if (knownLockedDoor) {
                    continue;
                }
                openedDoorCells.add(doorCell);
                externalDoors.put(doorCell, doorInfoFor(DungeonDoorKind.OPEN, roomTypes, visitedRooms, doorCell));
            }

            for (DungeonMapSnapshot.RemoteDoor remoteDoor : snapshot.remoteDoors()) {
                if (!DungeonScanUtils.isDoorScanPoint(remoteDoor.scanGridX(), remoteDoor.scanGridZ())) {
                    continue;
                }
                CellKey doorCell = new CellKey(remoteDoor.scanGridX(), remoteDoor.scanGridZ());
                if (internalDoors.contains(doorCell)) {
                    continue;
                }
                DungeonDoorKind remoteKind = remoteDoor.kind() == null ? DungeonDoorKind.NONE : remoteDoor.kind();
                if (remoteKind == DungeonDoorKind.NONE) {
                    continue;
                }
                DoorRenderInfo remoteInfo = doorInfoFor(remoteKind, roomTypes, visitedRooms, doorCell);
                if (remoteDoor.targetType() != null && remoteDoor.targetType() != RoomType.UNKNOWN) {
                    remoteInfo = new DoorRenderInfo(
                        remoteInfo.kind(),
                        remoteDoor.targetType(),
                        remoteInfo.colorAsSpecial(),
                        remoteInfo.targetVisited() || remoteDoor.targetVisited()
                    );
                }
                externalDoors.merge(doorCell, remoteInfo, MatchRenderPlan::mergeDoorInfo);
                DungeonScanPoint observedPoint = snapshot.pointAt(doorCell.x(), doorCell.z());
                if (remoteKind == DungeonDoorKind.OPEN
                    && remoteInfo.targetType() == RoomType.BLOOD
                    && !isLockedObservedPoint(observedPoint)) {
                    openedSpecialDoorCells.add(doorCell);
                }
            }

            applyLocalLockedDoorEvidence(
                snapshot,
                internalDoors,
                externalDoors,
                roomTypes,
                visitedRooms,
                openedSpecialDoorCells
            );

            expandVisitedRoomGroups(visitedRooms, roomOwners);
            expandVisitedRoomGroups(clearedRooms, roomOwners);
            expandVisitedRoomGroups(completedRooms, roomOwners);
            CellKey fairyEntranceDoor = fairyEntranceDoor(externalDoors, roomTypes, roomOwners);
            orientFairyDoors(externalDoors, roomTypes, visitedRooms, fairyEntranceDoor);
            markSpecialDoorEntrances(externalDoors, fairyEntranceDoor);

            return new MatchRenderPlan(
                matches,
                roomRepository.predictedRoomShapes(snapshot),
                matchedRoomCells,
                internalDoors,
                externalDoors,
                roomTypes,
                roomOwners,
                hints,
                remoteRoomCells,
                remoteRoomProgress,
                visitedRooms,
                clearedRooms,
                completedRooms,
                openedSpecialDoorCells,
                fairyEntranceDoor,
                KungBuild.MODRINTH ? List.of() : findBloodRushDoorPath(externalDoors, roomTypes, roomOwners),
                mapPuzzleNames(snapshot, matches, hints, roomTypes, matchedRoomCells)
            );
        }

        private static void promoteToRare(
            CellKey roomCell,
            Map<CellKey, RoomType> roomTypes,
            Map<CellKey, RoomIdentity> roomIdentities
        ) {
            roomTypes.put(roomCell, RoomType.RARE);
            RoomIdentity identity = roomIdentities.get(roomCell);
            if (identity != null) {
                roomIdentities.put(roomCell, RoomIdentity.of(identity.name(), RoomType.RARE, identity.secrets()));
            }
        }

        /**
         * A solved puzzle can change its blocks (Blaze), so its scan matches nothing; the map still colors
         * it purple and the tab names every puzzle. One such room and one name no identified puzzle took:
         * that name. Anything less certain stays "Puzzle".
         */
        static Map<CellKey, String> mapPuzzleNames(
            DungeonMapSnapshot snapshot,
            List<DungeonKnownRoomCatalog.MatchedRoom> matches,
            Map<CellKey, DungeonKnownRoomCatalog.KnownCoreHint> hints,
            Map<CellKey, RoomType> roomTypes,
            Set<CellKey> matchedRoomCells
        ) {
            List<CellKey> unnamed = new ArrayList<>();
            roomTypes.forEach((cell, type) -> {
                if (type == RoomType.PUZZLE && snapshot.isMapPuzzleRoom(cell.x(), cell.z())
                    && !matchedRoomCells.contains(cell) && !hints.containsKey(cell)) unnamed.add(cell);
            });
            Set<String> left = new HashSet<>(snapshot.tabPuzzles());
            for (var match : matches) {
                if (match.template().type() == RoomType.PUZZLE) left.remove(tabPuzzleName(match.template().name()));
            }
            for (var hint : hints.values()) {
                if (hint.type() == RoomType.PUZZLE) left.remove(tabPuzzleName(hint.name()));
            }
            if (unnamed.size() != 1 || left.size() != 1) return Map.of();
            return Map.of(unnamed.getFirst(), catalogPuzzleName(left.iterator().next()));
        }

        /** The tab calls the Blaze room "Higher Or Lower"; every other puzzle has the catalog's name. */
        static String tabPuzzleName(String catalogName) {
            String name = catalogName.toLowerCase(java.util.Locale.ROOT);
            return name.equals("blaze") ? "higher or lower" : name;
        }

        static String catalogPuzzleName(String tabName) {
            if (tabName.equals("higher or lower")) return "Blaze";
            StringBuilder name = new StringBuilder();
            for (String word : tabName.split(" ")) {
                if (word.isEmpty()) continue;
                if (!name.isEmpty()) name.append(' ');
                name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return name.toString();
        }

        private static boolean hasDistinctDoorOwners(Map<CellKey, String> roomOwners, CellKey door) {
            String firstOwner = roomOwners.get(door.firstSide());
            String secondOwner = roomOwners.get(door.secondSide());
            return firstOwner != null && secondOwner != null && !firstOwner.equals(secondOwner);
        }

        private static void applyLocalLockedDoorEvidence(
            DungeonMapSnapshot snapshot,
            Set<CellKey> internalDoors,
            Map<CellKey, DoorRenderInfo> externalDoors,
            Map<CellKey, RoomType> roomTypes,
            Set<CellKey> visitedRooms,
            Set<CellKey> openedSpecialDoorCells
        ) {
            for (DungeonScanPoint observedPoint : snapshot.points()) {
                if (observedPoint.kind() != DungeonScanPointKind.DOOR) {
                    continue;
                }

                int gridX = observedPoint.gridX();
                int gridZ = observedPoint.gridZ();
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                    continue;
                }

                CellKey doorCell = new CellKey(gridX, gridZ);
                DungeonMapSnapshot.GridKey gridKey = new DungeonMapSnapshot.GridKey(gridX, gridZ);
                DungeonDoorKind observedKind = observedPoint.doorKind();
                boolean observedLocked = observedKind.locked();
                boolean openedLocked = snapshot.isOpenedLockedDoor(gridKey);
                if (!observedLocked && !openedLocked) {
                    continue;
                }

                DungeonDoorKind renderKind = observedLocked ? observedKind : DungeonDoorKind.OPEN;
                internalDoors.remove(doorCell);
                externalDoors.merge(
                    doorCell,
                    doorInfoFor(renderKind, roomTypes, visitedRooms, doorCell),
                    MatchRenderPlan::mergeDoorInfo
                );
                if (openedLocked) {
                    openedSpecialDoorCells.add(doorCell);
                }
            }
        }

        private static boolean touchesRoomType(Map<CellKey, RoomType> roomTypes, CellKey door, RoomType roomType) {
            return roomTypes.get(door.firstSide()) == roomType || roomTypes.get(door.secondSide()) == roomType;
        }

        private static void mergeConnectedSameIdentityRooms(
            DungeonMapSnapshot snapshot,
            Map<CellKey, String> roomOwners,
            Map<CellKey, RoomIdentity> roomIdentities,
            Set<CellKey> internalDoors
        ) {
            Map<String, String> parent = new HashMap<>();
            Map<String, Integer> sizes = new HashMap<>();
            for (String owner : roomOwners.values()) {
                parent.put(owner, owner);
                sizes.merge(owner, 1, Integer::sum);
            }

            for (Map.Entry<CellKey, String> entry : roomOwners.entrySet()) {
                CellKey room = entry.getKey();
                RoomIdentity identity = roomIdentities.get(room);
                if (identity == null) {
                    continue;
                }

                for (CellKey neighbor : room.positiveNeighbors()) {
                    String neighborOwner = roomOwners.get(neighbor);
                    if (neighborOwner == null || !identity.equals(roomIdentities.get(neighbor))) {
                        continue;
                    }
                    CellKey separator = room.doorTo(neighbor);
                    if (hasBlockingRoomBoundary(snapshot, separator)) {
                        continue;
                    }
                    if (union(parent, sizes, entry.getValue(), neighborOwner)) internalDoors.add(separator);
                }
            }

            for (Map.Entry<CellKey, String> entry : new ArrayList<>(roomOwners.entrySet())) {
                roomOwners.put(entry.getKey(), find(parent, entry.getValue()));
            }
        }

        private static void mergeMapRoomConnections(
            DungeonMapSnapshot snapshot,
            Map<CellKey, String> roomOwners,
            Map<CellKey, RoomIdentity> roomIdentities,
            Set<CellKey> internalDoors
        ) {
            Map<String, String> parent = new HashMap<>();
            Map<String, Integer> sizes = new HashMap<>();
            for (String owner : roomOwners.values()) {
                parent.put(owner, owner);
                sizes.merge(owner, 1, Integer::sum);
            }

            for (DungeonMapSnapshot.GridKey mapDoor : snapshot.mapRoomConnections()) {
                int gridX = mapDoor.gridX();
                int gridZ = mapDoor.gridZ();
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                    continue;
                }
                CellKey separator = new CellKey(gridX, gridZ);
                if (hasBlockingRoomBoundary(snapshot, separator)) {
                    continue;
                }

                String firstOwner = roomOwners.get(separator.firstSide());
                String secondOwner = roomOwners.get(separator.secondSide());
                if (firstOwner == null || secondOwner == null) {
                    continue;
                }
                RoomIdentity firstIdentity = roomIdentities.get(separator.firstSide());
                RoomIdentity secondIdentity = roomIdentities.get(separator.secondSide());
                if (firstIdentity != null && secondIdentity != null && !firstIdentity.equals(secondIdentity)) {
                    continue;
                }

                if (union(parent, sizes, firstOwner, secondOwner)) internalDoors.add(separator);
            }

            for (Map.Entry<CellKey, String> entry : new ArrayList<>(roomOwners.entrySet())) {
                roomOwners.put(entry.getKey(), find(parent, entry.getValue()));
            }
        }

        private static boolean hasBlockingRoomBoundary(DungeonMapSnapshot snapshot, CellKey separator) {
            if (snapshot.hasMapRoomBoundary(separator.x(), separator.z())) return true;
            DungeonScanPoint observedPoint = snapshot.pointAt(separator.x(), separator.z());
            if (observedPoint == null || observedPoint.kind() != DungeonScanPointKind.DOOR) {
                return false;
            }
            return observedPoint.doorKind().locked();
        }

        private static String find(Map<String, String> parent, String owner) {
            String current = parent.getOrDefault(owner, owner);
            if (current.equals(owner)) {
                return current;
            }
            String root = find(parent, current);
            parent.put(owner, root);
            return root;
        }

        private static boolean union(Map<String, String> parent, Map<String, Integer> sizes, String first, String second) {
            String firstRoot = find(parent, first);
            String secondRoot = find(parent, second);
            if (firstRoot.equals(secondRoot)) return true;
            int combinedSize = sizes.get(firstRoot) + sizes.get(secondRoot);
            // Enforce the room limit before propagating clear/secret ownership, not only while drawing.
            if (combinedSize > 4) return false;
            parent.put(secondRoot, firstRoot);
            sizes.put(firstRoot, combinedSize);
            return true;
        }

        private static void expandVisitedRoomGroups(Set<CellKey> visitedRooms, Map<CellKey, String> roomOwners) {
            Set<String> visitedOwners = new HashSet<>();
            for (CellKey room : visitedRooms) {
                String owner = roomOwners.get(room);
                if (owner != null) {
                    visitedOwners.add(owner);
                }
            }
            for (Map.Entry<CellKey, String> entry : roomOwners.entrySet()) {
                if (visitedOwners.contains(entry.getValue())) {
                    visitedRooms.add(entry.getKey());
                }
            }
        }

        boolean isMatchedRoomCell(int roomGridX, int roomGridZ) {
            return matchedRoomCells.contains(new CellKey(roomGridX, roomGridZ));
        }

        boolean sameRoomOwner(CellKey first, CellKey second) {
            String firstOwner = roomOwners.get(first);
            String secondOwner = roomOwners.get(second);
            return firstOwner != null && firstOwner.equals(secondOwner);
        }

        RoomType roomTypeAt(int roomGridX, int roomGridZ) {
            return roomTypeAt(new CellKey(roomGridX, roomGridZ));
        }

        private RoomType roomTypeAt(CellKey room) {
            return roomTypes.getOrDefault(room, RoomType.UNKNOWN);
        }

        boolean hasRoomType(RoomType roomType) {
            return roomTypes.containsValue(roomType);
        }

        int knownNonStartSpecialDoorCount() {
            Set<CellKey> doors = new HashSet<>();
            for (Map.Entry<CellKey, DoorRenderInfo> entry : externalDoors.entrySet()) {
                DoorRenderInfo door = entry.getValue();
                if (door.kind().locked() && !isExcludedBloodRushDoor(entry.getKey(), door)) {
                    doors.add(entry.getKey());
                }
            }
            for (CellKey openedDoor : openedSpecialDoorCells) {
                DoorRenderInfo door = externalDoors.get(openedDoor);
                if (door != null
                    && !isExcludedBloodRushDoor(openedDoor, door)) {
                    doors.add(openedDoor);
                }
            }
            return doors.size();
        }

        int rawNonStartSpecialDoorCount(DungeonMapSnapshot snapshot) {
            return rawNonStartSpecialDoors(snapshot).size();
        }

        int visibleRawNonStartSpecialDoorCount(DungeonMapSnapshot snapshot) {
            return visibleRawNonStartSpecialDoors(snapshot).size();
        }

        boolean visibleRawNonStartDoorCountMatchesRemaining(DungeonMapSnapshot snapshot) {
            int visibleDoors = visibleRawNonStartSpecialDoorCount(snapshot);
            int rawKnownDoors = rawNonStartSpecialDoorCount(snapshot);
            int openedKnownDoors = openedRawNonStartSpecialDoorCount(snapshot);
            return visibleDoors == Math.max(0, rawKnownDoors - openedKnownDoors);
        }

        boolean rawNonStartSpecialDoorEstimateExact(DungeonMapSnapshot snapshot) {
            int rawDoorCount = rawNonStartSpecialDoorCount(snapshot);
            int pathDoorCount = bloodRushTotalSpecialDoorCount();
            return hasRoomType(RoomType.BLOOD)
                && (bloodRushPathVisibleEnough(snapshot) || bloodRushPathScanned(snapshot))
                && rawDoorCount > 0
                && pathDoorCount > 0
                && rawDoorCount == pathDoorCount
                && visibleRawNonStartDoorCountMatchesRemaining(snapshot);
        }

        int openedRawNonStartSpecialDoorCount(DungeonMapSnapshot snapshot) {
            return openedRawNonStartSpecialDoorCells(snapshot).size();
        }

        Set<CellKey> openedRawNonStartSpecialDoorCells(DungeonMapSnapshot snapshot) {
            Set<CellKey> nonStartDoors = rawNonStartSpecialDoors(snapshot);
            Set<CellKey> doors = new HashSet<>();
            for (DungeonMapSnapshot.GridKey openedDoor : snapshot.openedLockedDoors()) {
                CellKey doorCell = new CellKey(openedDoor.gridX(), openedDoor.gridZ());
                if (nonStartDoors.contains(doorCell)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        int bloodRushLockedSpecialDoorCount() {
            return bloodRushSpecialDoorCount(false);
        }

        int bloodRushTotalSpecialDoorCount() {
            return bloodRushSpecialDoorCount(true);
        }

        private int bloodRushSpecialDoorCount(boolean includeOpened) {
            return (int) bloodRushPath.stream()
                .filter(doorCell -> isCountedBloodRushSpecialDoor(doorCell, externalDoors.get(doorCell), includeOpened))
                .count();
        }

        int minimumVisibleBloodRushSpecialDoorCount(DungeonMapSnapshot snapshot) {
            if (!bloodRushPath.isEmpty()) {
                return bloodRushTotalSpecialDoorCount();
            }
            return visibleRawNonStartSpecialDoorCount(snapshot);
        }

        int minimumVisibleBloodRushLockedSpecialDoorCount(DungeonMapSnapshot snapshot) {
            if (!bloodRushPath.isEmpty()) {
                return bloodRushLockedSpecialDoorCount();
            }
            return visibleRawNonStartSpecialDoorCount(snapshot);
        }

        Set<CellKey> bloodRushOpenedSpecialDoorCells() {
            Set<CellKey> doors = new HashSet<>();
            for (CellKey doorCell : bloodRushPath) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (openedSpecialDoorCells.contains(doorCell)
                    && isCountedBloodRushSpecialDoor(doorCell, door, true)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        DoorRenderInfo doorAt(int scanGridX, int scanGridZ) {
            return externalDoors.get(new CellKey(scanGridX, scanGridZ));
        }

        boolean bloodIsNext() {
            DoorRenderInfo remainingDoor = null;
            for (CellKey doorCell : bloodRushPath) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (!isCountedBloodRushSpecialDoor(doorCell, door, false)) {
                    continue;
                }
                if (remainingDoor != null) {
                    return false;
                }
                remainingDoor = door;
            }
            return remainingDoor != null
                && remainingDoor.kind() == DungeonDoorKind.BLOOD;
        }

        boolean hasLockedBloodSpecialDoor() {
            for (Map.Entry<CellKey, DoorRenderInfo> entry : externalDoors.entrySet()) {
                DoorRenderInfo door = entry.getValue();
                if (isCountedSpecialDoor(entry.getKey(), door)
                    && door.kind() == DungeonDoorKind.BLOOD) {
                    return true;
                }
            }
            return false;
        }

        /**
         * For a room no catalog entry names, its core and stable hash as the scan saw them: what a catalog entry needs,
         * and what a report of a room that stays "?" must show. Empty for a room that has a name.
         */
        private String unmatchedHashText(DungeonMapSnapshot snapshot, CellKey cell,
                                         DungeonKnownRoomCatalog.KnownCoreHint hint) {
            if (cell == null || hint != null || matchContaining(cell.x(), cell.z()) != null) return "";
            DungeonScanPoint point = snapshot.pointAt(cell.x() * 2, cell.z() * 2);
            return point == null ? "" : " unmatched core=" + point.coreHash() + " stable=" + point.stableCoreHash();
        }

        List<String> topologyDebugLines(DungeonMapSnapshot snapshot) {
            List<String> lines = new ArrayList<>();
            lines.add("summary revision="
                + snapshot.revision()
                + " owners="
                + observedRoomCount()
                + " roomCells="
                + roomOwners.size()
                + " matches="
                + matches.size()
                + " hints="
                + hints.size()
                + " internalDoors="
                + internalDoors.size()
                + " externalDoors="
                + externalDoors.size()
                + " rawSpecialDoors="
                + rawNonStartSpecialDoorCount(snapshot)
                + " visibleSpecialDoors="
                + visibleRawNonStartSpecialDoorCount(snapshot)
                + " openedSpecialDoors="
                + openedRawNonStartSpecialDoorCount(snapshot)
                + " bloodRushPath="
                + bloodRushSpecialDoorSummary());

            int matchIndex = 0;
            for (DungeonKnownRoomCatalog.MatchedRoom match : matches) {
                List<CellKey> cells = new ArrayList<>();
                Set<String> owners = new HashSet<>();
                boolean visited = false;
                boolean cleared = false;
                boolean completed = false;
                for (DungeonKnownRoomCatalog.MatchedComponent component : match.components()) {
                    CellKey cell = new CellKey(component.roomGridX(), component.roomGridZ());
                    cells.add(cell);
                    owners.add(ownerFor(cell));
                    visited = visited || visitedRooms.contains(cell);
                    cleared = cleared || clearedRooms.contains(cell);
                    completed = completed || completedRooms.contains(cell);
                }
                sortCells(cells);
                lines.add("match[" + matchIndex++ + "] name="
                    + compactName(match.template().name())
                    + " type="
                    + match.template().type()
                    + " secrets="
                    + match.template().secrets()
                    + " cells="
                    + cellsText(cells)
                    + " owners="
                    + ownersText(owners)
                    + " visited="
                    + visited
                    + " cleared="
                    + cleared
                    + " completed="
                    + completed);
            }

            Map<String, List<CellKey>> cellsByOwner = new HashMap<>();
            for (Map.Entry<CellKey, String> entry : roomOwners.entrySet()) {
                cellsByOwner.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
            }
            List<String> owners = new ArrayList<>(cellsByOwner.keySet());
            owners.sort(String::compareTo);
            for (String owner : owners) {
                List<CellKey> cells = cellsByOwner.get(owner);
                sortCells(cells);
                CellKey firstCell = cells.isEmpty() ? null : cells.get(0);
                RoomType type = firstCell == null ? RoomType.UNKNOWN : roomTypes.getOrDefault(firstCell, RoomType.UNKNOWN);
                DungeonKnownRoomCatalog.KnownCoreHint hint = firstCell == null ? null : hints.get(firstCell);
                lines.add("room owner="
                    + owner
                    + " type="
                    + type
                    + " hint="
                    + (hint == null ? "none" : compactName(hint.name()) + "/" + hint.secrets())
                    + unmatchedHashText(snapshot, firstCell, hint)
                    + " cells="
                    + cellsText(cells)
                    + " visited="
                    + !Collections.disjoint(cells, visitedRooms)
                    + " cleared="
                    + !Collections.disjoint(cells, clearedRooms)
                    + " completed="
                    + !Collections.disjoint(cells, completedRooms));
            }

            List<CellKey> internal = new ArrayList<>(internalDoors);
            sortCells(internal);
            for (CellKey doorCell : internal) {
                lines.add("edge kind=INTERNAL door="
                    + cellText(doorCell)
                    + " sides="
                    + doorSidesText(doorCell));
            }

            List<CellKey> external = new ArrayList<>(externalDoors.keySet());
            sortCells(external);
            for (CellKey doorCell : external) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                lines.add("edge kind=EXTERNAL door="
                    + cellText(doorCell)
                    + " doorKind="
                    + (door == null ? "null" : door.kind())
                    + " target="
                    + (door == null ? "null" : door.targetType())
                    + " targetVisited="
                    + (door != null && door.targetVisited())
                    + " colorAsSpecial="
                    + (door != null && door.colorAsSpecial())
                    + " countedSpecial="
                    + isCountedSpecialDoor(doorCell, door)
                    + " countedBloodRush="
                    + isCountedBloodRushSpecialDoor(doorCell, door, true)
                    + " rawExcluded="
                    + isRawExcludedBloodRushDoor(doorCell)
                    + " openedSpecial="
                    + openedSpecialDoorCells.contains(doorCell)
                    + " sides="
                    + doorSidesText(doorCell));
            }

            List<CellKey> rawSpecialDoors = new ArrayList<>(rawNonStartSpecialDoors(snapshot));
            sortCells(rawSpecialDoors);
            for (CellKey doorCell : rawSpecialDoors) {
                DungeonScanPoint observedPoint = snapshot.pointAt(doorCell.x(), doorCell.z());
                boolean opened = snapshot.isOpenedLockedDoor(new DungeonMapSnapshot.GridKey(doorCell.x(), doorCell.z()));
                DungeonDoorKind observedKind = observedPoint == null ? null : observedPoint.doorKind();
                lines.add("raw-special door="
                    + cellText(doorCell)
                    + " observedKind="
                    + (observedKind == null ? "none" : observedKind)
                    + " opened="
                    + opened
                    + " rawExcluded="
                    + isRawExcludedBloodRushDoor(doorCell)
                    + " sides="
                    + doorSidesText(doorCell));
            }
            return lines;
        }

        String bloodRushSpecialDoorSummary() {
            return bloodRushPath.stream()
                .filter(doorCell -> isCountedBloodRushSpecialDoor(doorCell, externalDoors.get(doorCell), true))
                .map(doorCell -> {
                    DoorRenderInfo door = externalDoors.get(doorCell);
                    return cellText(doorCell) + ":" + door.kind() + ":target=" + door.targetType();
                })
                .collect(Collectors.joining(" | ", "[", "]"));
        }

        private boolean isCountedSpecialDoor(CellKey doorCell, DoorRenderInfo door) {
            return door != null
                && door.kind().locked()
                && !isExcludedBloodRushDoor(doorCell, door);
        }

        boolean bloodRushDoorEstimateExact(DungeonMapSnapshot snapshot) {
            return hasRoomType(RoomType.BLOOD)
                && bloodRushPathVisibleEnough(snapshot);
        }

        int observedRoomCount() {
            return new HashSet<>(roomOwners.values()).size();
        }

        int roomCellCount() {
            return roomOwners.size();
        }

        int visitedRoomCount() {
            return ownerCount(visitedRooms);
        }

        int clearedRoomCount() {
            return ownerCount(clearedRooms);
        }

        int completedRoomCount() {
            return ownerCount(completedRooms);
        }

        int ownerCount(Set<CellKey> cells) {
            return ownerSet(cells).size();
        }

        int mapVisibleRoomCount(DungeonMapSnapshot snapshot) {
            return ownerCount(mapVisibleRoomCells(snapshot));
        }

        int recognizedMapVisibleRoomCount(DungeonMapSnapshot snapshot) {
            Set<String> recognizedOwners = new HashSet<>();
            for (DungeonMapSnapshot.GridKey mapRoom : snapshot.mapVisibleRooms()) {
                CellKey cell = new CellKey(mapRoom.gridX(), mapRoom.gridZ());
                if (roomTypes.getOrDefault(cell, RoomType.UNKNOWN) != RoomType.UNKNOWN) {
                    recognizedOwners.add(ownerFor(cell));
                }
            }
            return recognizedOwners.size();
        }

        boolean bloodRushPathVisibleEnough(DungeonMapSnapshot snapshot) {
            List<CellKey> path = bloodRushPath;
            if (path.isEmpty()) {
                return false;
            }

            Set<String> visibleOwners = ownerSet(mapVisibleRoomCells(snapshot));
            Set<String> requiredOwners = new HashSet<>();
            for (CellKey doorCell : path) {
                if (roomTypeAt(doorCell.firstSide()) == RoomType.UNKNOWN
                    || roomTypeAt(doorCell.secondSide()) == RoomType.UNKNOWN) return false;
                for (CellKey side : List.of(doorCell.firstSide(), doorCell.secondSide())) {
                    String owner = roomOwners.get(side);
                    if (owner != null) {
                        requiredOwners.add(owner);
                    }
                }
            }
            return visibleOwners.containsAll(requiredOwners);
        }

        private boolean bloodRushPathScanned(DungeonMapSnapshot snapshot) {
            if (bloodRushPath.isEmpty()) return false;
            for (CellKey door : bloodRushPath) {
                for (CellKey room : List.of(door.firstSide(), door.secondSide())) {
                    if (roomTypes.getOrDefault(room, RoomType.UNKNOWN) == RoomType.UNKNOWN
                        || !isRealRoom(snapshot, room.x() * 2, room.z() * 2)) return false;
                }
            }
            return true;
        }

        DungeonKnownRoomCatalog.KnownCoreHint hintAt(int roomGridX, int roomGridZ) {
            return hints.get(new CellKey(roomGridX, roomGridZ));
        }

        boolean isRemoteRoom(int roomGridX, int roomGridZ) {
            return remoteRoomCells.contains(new CellKey(roomGridX, roomGridZ));
        }

        int remoteRoomSecretsFound(int roomGridX, int roomGridZ, int fallback) {
            RoomProgress progress = remoteRoomProgress.get(new CellKey(roomGridX, roomGridZ));
            return progress == null ? fallback : Math.max(fallback, progress.found());
        }

        int remoteRoomSecretsMax(int roomGridX, int roomGridZ, int fallback) {
            RoomProgress progress = remoteRoomProgress.get(new CellKey(roomGridX, roomGridZ));
            return progress == null || progress.max() <= 0 ? fallback : Math.max(fallback, progress.max());
        }

        boolean isVisitedRoom(int roomGridX, int roomGridZ) {
            return visitedRooms.contains(new CellKey(roomGridX, roomGridZ));
        }

        boolean isClearedRoom(int roomGridX, int roomGridZ) {
            return clearedRooms.contains(new CellKey(roomGridX, roomGridZ));
        }

        boolean isCompletedRoom(int roomGridX, int roomGridZ) {
            return completedRooms.contains(new CellKey(roomGridX, roomGridZ));
        }

        boolean hasUnknownRooms(DungeonMapSnapshot snapshot) {
            for (int roomGridZ = 0; roomGridZ <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridZ++) {
                for (int roomGridX = 0; roomGridX <= DungeonScanUtils.SCAN_GRID_SIZE / 2; roomGridX++) {
                    CellKey roomCell = new CellKey(roomGridX, roomGridZ);
                    if (matchedRoomCells.contains(roomCell) || hints.containsKey(roomCell)) {
                        continue;
                    }

                    if (snapshot.isMapTrapRoom(roomGridX, roomGridZ)) return true;

                    int scanGridX = roomGridX * 2;
                    int scanGridZ = roomGridZ * 2;
                    DungeonScanPoint observedPoint = snapshot.pointAt(scanGridX, scanGridZ);
                    if (observedPoint == null || observedPoint.kind() != DungeonScanPointKind.ROOM) {
                        continue;
                    }
                    if (DungeonRoomClassifier.isEmptyCore(observedPoint.coreHash())) {
                        continue;
                    }
                    if (snapshot.isStartRoom(scanGridX, scanGridZ)) {
                        continue;
                    }
                    return true;
                }
            }
            return false;
        }

        private Set<String> ownerSet(Set<CellKey> cells) {
            Set<String> owners = new HashSet<>();
            if (cells == null) {
                return owners;
            }
            for (CellKey cell : cells) {
                owners.add(ownerFor(cell));
            }
            return owners;
        }

        private String ownerFor(CellKey cell) {
            String owner = roomOwners.get(cell);
            return owner != null ? owner : "cell:" + cell.x() + "," + cell.z();
        }

        private String doorSidesText(CellKey doorCell) {
            CellKey first = doorCell.firstSide();
            CellKey second = doorCell.secondSide();
            return ownerTypeText(roomOwners.get(first), roomTypes.get(first)) + "<->"
                + ownerTypeText(roomOwners.get(second), roomTypes.get(second));
        }

        private static String ownerTypeText(String owner, RoomType type) {
            return (owner == null ? "null" : owner) + ":" + (type == null ? RoomType.UNKNOWN : type);
        }

        private static void sortCells(List<CellKey> cells) {
            cells.sort(CellKey.ORDER);
        }

        private static String cellsText(List<CellKey> cells) {
            return cells.stream().map(MatchRenderPlan::cellText).collect(Collectors.joining("|", "[", "]"));
        }

        private static String ownersText(Set<String> owners) {
            return owners.stream().sorted().collect(Collectors.joining("|", "[", "]"));
        }

        private static String cellText(CellKey cell) {
            return cell.x() + "," + cell.z();
        }

        private static String compactName(String name) {
            if (name == null || name.isBlank()) {
                return "<unknown>";
            }
            return name.replace(' ', '_');
        }

        private static Set<CellKey> mapVisibleRoomCells(DungeonMapSnapshot snapshot) {
            Set<CellKey> cells = new HashSet<>();
            for (DungeonMapSnapshot.GridKey mapRoom : snapshot.mapVisibleRooms()) {
                cells.add(new CellKey(mapRoom.gridX(), mapRoom.gridZ()));
            }
            return cells;
        }

        private static List<CellKey> findBloodRushDoorPath(
            Map<CellKey, DoorRenderInfo> externalDoors,
            Map<CellKey, RoomType> roomTypes,
            Map<CellKey, String> roomOwners
        ) {
            Set<String> startOwners = ownersWithType(RoomType.START, roomTypes, roomOwners);
            Set<String> bloodOwners = ownersWithType(RoomType.BLOOD, roomTypes, roomOwners);
            if (startOwners.isEmpty() || bloodOwners.isEmpty()) {
                return List.of();
            }

            Map<String, List<DoorPathEdge>> graph = new HashMap<>();
            List<CellKey> orderedDoors = new ArrayList<>(externalDoors.keySet());
            sortCells(orderedDoors);
            for (CellKey doorCell : orderedDoors) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (door == null || door.kind() == DungeonDoorKind.NONE) {
                    continue;
                }
                String firstOwner = roomOwners.get(doorCell.firstSide());
                String secondOwner = roomOwners.get(doorCell.secondSide());
                if (firstOwner == null || secondOwner == null || firstOwner.equals(secondOwner)) {
                    continue;
                }
                graph.computeIfAbsent(firstOwner, ignored -> new ArrayList<>())
                    .add(new DoorPathEdge(secondOwner, doorCell));
                graph.computeIfAbsent(secondOwner, ignored -> new ArrayList<>())
                    .add(new DoorPathEdge(firstOwner, doorCell));
            }

            ArrayDeque<String> queue = new ArrayDeque<>();
            Set<String> visitedOwners = new HashSet<>();
            Map<String, String> parentOwner = new HashMap<>();
            Map<String, CellKey> parentDoor = new HashMap<>();
            for (String startOwner : startOwners) {
                queue.add(startOwner);
                visitedOwners.add(startOwner);
            }

            String foundBloodOwner = null;
            while (!queue.isEmpty()) {
                String owner = queue.removeFirst();
                if (bloodOwners.contains(owner)) {
                    foundBloodOwner = owner;
                    break;
                }
                for (DoorPathEdge edge : graph.getOrDefault(owner, List.of())) {
                    if (!visitedOwners.add(edge.toOwner())) {
                        continue;
                    }
                    parentOwner.put(edge.toOwner(), owner);
                    parentDoor.put(edge.toOwner(), edge.doorCell());
                    queue.add(edge.toOwner());
                }
            }

            if (foundBloodOwner == null) {
                return List.of();
            }

            List<CellKey> path = new ArrayList<>();
            String owner = foundBloodOwner;
            while (!startOwners.contains(owner)) {
                CellKey doorCell = parentDoor.get(owner);
                String previousOwner = parentOwner.get(owner);
                if (doorCell == null || previousOwner == null) {
                    return List.of();
                }
                path.add(0, doorCell);
                owner = previousOwner;
            }
            return List.copyOf(path);
        }

        private static Set<String> ownersWithType(RoomType roomType, Map<CellKey, RoomType> roomTypes,
            Map<CellKey, String> roomOwners) {
            Set<String> owners = new HashSet<>();
            for (Map.Entry<CellKey, RoomType> entry : roomTypes.entrySet()) {
                if (entry.getValue() != roomType) {
                    continue;
                }
                String owner = roomOwners.get(entry.getKey());
                if (owner != null) {
                    owners.add(owner);
                }
            }
            return owners;
        }

        private boolean isCountedBloodRushSpecialDoor(CellKey doorCell, DoorRenderInfo door, boolean includeOpened) {
            if (isExcludedBloodRushDoor(doorCell, door)) {
                return false;
            }
            if (door.kind().locked()) {
                return true;
            }
            return includeOpened && openedSpecialDoorCells.contains(doorCell);
        }

        private boolean isExcludedBloodRushDoor(CellKey doorCell, DoorRenderInfo door) {
            return door == null
                || touchesRoomType(roomTypes, doorCell, RoomType.START)
                || doorCell.equals(fairyEntranceDoor);
        }

        private Set<CellKey> rawNonStartSpecialDoors(DungeonMapSnapshot snapshot) {
            Set<CellKey> doors = visibleRawNonStartSpecialDoors(snapshot);
            for (DungeonMapSnapshot.GridKey openedDoor : snapshot.openedLockedDoors()) {
                CellKey doorCell = new CellKey(openedDoor.gridX(), openedDoor.gridZ());
                if (!isRawExcludedBloodRushDoor(doorCell)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        private Set<CellKey> visibleRawNonStartSpecialDoors(DungeonMapSnapshot snapshot) {
            Set<CellKey> doors = new HashSet<>();
            for (DungeonScanPoint observedPoint : snapshot.points()) {
                if (observedPoint.kind() != DungeonScanPointKind.DOOR || !observedPoint.doorKind().locked()) {
                    continue;
                }
                CellKey doorCell = new CellKey(observedPoint.gridX(), observedPoint.gridZ());
                if (!isRawExcludedBloodRushDoor(doorCell)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        private boolean isRawExcludedBloodRushDoor(CellKey doorCell) {
            return touchesRoomType(roomTypes, doorCell, RoomType.START)
                || doorCell.equals(fairyEntranceDoor);
        }

        private record DoorPathEdge(String toOwner, CellKey doorCell) {
        }

        private record RoomIdentity(String name, RoomType type, int secrets) {
            static RoomIdentity of(String name, RoomType type, int secrets) {
                return new RoomIdentity(name == null ? "" : name, type == null ? RoomType.UNKNOWN : type, secrets);
            }
        }

        private record RoomProgress(int found, int max) {
            private RoomProgress {
                found = Math.max(0, found);
                max = Math.max(0, max);
                if (max > 0) {
                    found = Math.min(found, max);
                }
            }

            private static RoomProgress max(RoomProgress first, RoomProgress second) {
                int max = Math.max(first.max(), second.max());
                return new RoomProgress(Math.max(first.found(), second.found()), max);
            }
        }

        private static DoorRenderInfo doorInfoFor(
            DungeonDoorKind kind,
            Map<CellKey, RoomType> roomTypes,
            Set<CellKey> visitedRooms,
            CellKey door
        ) {
            CellKey firstSide = door.firstSide();
            CellKey secondSide = door.secondSide();
            RoomType firstType = roomTypes.getOrDefault(firstSide, RoomType.UNKNOWN);
            RoomType secondType = roomTypes.getOrDefault(secondSide, RoomType.UNKNOWN);
            CellKey targetSide = preferredDoorTargetSide(firstSide, firstType, secondSide, secondType);
            RoomType targetType = roomTypes.getOrDefault(targetSide, preferredDoorTargetType(firstType, secondType));
            return new DoorRenderInfo(kind, targetType, false, visitedRooms.contains(targetSide));
        }

        private static DoorRenderInfo mergeDoorInfo(DoorRenderInfo previous, DoorRenderInfo next) {
            RoomType targetType = previous.targetType();
            if (targetType == RoomType.FAIRY
                && next.targetType() != RoomType.UNKNOWN
                && next.targetType() != RoomType.FAIRY) {
                targetType = next.targetType();
            } else if (!isSpecialDoorTarget(targetType)
                && isSpecialDoorTarget(next.targetType())
                && next.targetType() != RoomType.FAIRY) {
                targetType = next.targetType();
            }
            DungeonDoorKind kind = previous.kind() == DungeonDoorKind.NONE ? next.kind() : previous.kind();
            return new DoorRenderInfo(
                kind,
                targetType,
                previous.colorAsSpecial() || next.colorAsSpecial(),
                previous.targetVisited() || next.targetVisited()
            );
        }

        private static RoomType preferredDoorTargetType(RoomType firstType, RoomType secondType) {
            if (isSpecialDoorTarget(firstType)) {
                return firstType;
            }
            if (isSpecialDoorTarget(secondType)) {
                return secondType;
            }
            if (firstType == RoomType.START || secondType == RoomType.START) {
                return RoomType.START;
            }
            return RoomType.NORMAL;
        }

        private static CellKey preferredDoorTargetSide(
            CellKey firstSide,
            RoomType firstType,
            CellKey secondSide,
            RoomType secondType
        ) {
            if (isSpecialDoorTarget(firstType)) {
                return firstSide;
            }
            if (isSpecialDoorTarget(secondType)) {
                return secondSide;
            }
            if (firstType == RoomType.UNKNOWN && secondType != RoomType.UNKNOWN) {
                return firstSide;
            }
            if (secondType == RoomType.UNKNOWN && firstType != RoomType.UNKNOWN) {
                return secondSide;
            }
            return secondSide;
        }

        private static void markSpecialDoorEntrances(
            Map<CellKey, DoorRenderInfo> externalDoors,
            CellKey fairyEntranceDoor
        ) {
            for (Map.Entry<CellKey, DoorRenderInfo> entry : externalDoors.entrySet()) {
                DoorRenderInfo door = entry.getValue();
                boolean colorAsSpecial = isSpecialDoorTarget(door.targetType())
                    && (door.targetType() != RoomType.FAIRY || entry.getKey().equals(fairyEntranceDoor));
                entry.setValue(new DoorRenderInfo(door.kind(), door.targetType(), colorAsSpecial, door.targetVisited()));
            }
        }

        private static void orientFairyDoors(Map<CellKey, DoorRenderInfo> doors,
            Map<CellKey, RoomType> roomTypes, Set<CellKey> visitedRooms, CellKey entrance) {
            for (Map.Entry<CellKey, DoorRenderInfo> entry : doors.entrySet()) {
                CellKey cell = entry.getKey();
                if (!touchesRoomType(roomTypes, cell, RoomType.FAIRY)) continue;
                CellKey target = cell.equals(entrance)
                    ? (roomTypes.get(cell.firstSide()) == RoomType.FAIRY ? cell.firstSide() : cell.secondSide())
                    : outsideFairySide(cell, roomTypes);
                entry.setValue(new DoorRenderInfo(entry.getValue().kind(),
                    roomTypes.getOrDefault(target, RoomType.UNKNOWN), false, visitedRooms.contains(target)));
            }
        }

        private static CellKey fairyEntranceDoor(
            Map<CellKey, DoorRenderInfo> doors,
            Map<CellKey, RoomType> roomTypes,
            Map<CellKey, String> roomOwners
        ) {
            List<CellKey> fairyDoors = new ArrayList<>(doors.keySet().stream()
                .filter(door -> touchesRoomType(roomTypes, door, RoomType.FAIRY))
                .toList());
            sortCells(fairyDoors);
            if (fairyDoors.isEmpty()) {
                return null;
            }

            List<CellKey> openFairyDoors = new ArrayList<>();
            for (CellKey door : fairyDoors) {
                DoorRenderInfo info = doors.get(door);
                if (info != null && info.kind() == DungeonDoorKind.OPEN) {
                    openFairyDoors.add(door);
                }
            }
            Map<CellKey, Integer> distanceFromStart = distanceFromStart(doors.keySet(), roomTypes, roomOwners);
            CellKey bestDoor = null;
            int bestDistance = Integer.MAX_VALUE;
            for (CellKey door : fairyDoors) {
                CellKey outsideFairy = outsideFairySide(door, roomTypes);
                int distance = distanceFromStart.getOrDefault(outsideFairy, Integer.MAX_VALUE);
                if (distance < bestDistance) {
                    bestDoor = door;
                    bestDistance = distance;
                }
            }
            // Prefer the side connected to Start. Opening an exit must not move the entrance.
            return bestDoor != null ? bestDoor : openFairyDoors.size() == 1 ? openFairyDoors.getFirst() : null;
        }

        private static Map<CellKey, Integer> distanceFromStart(
            Set<CellKey> doors,
            Map<CellKey, RoomType> roomTypes,
            Map<CellKey, String> roomOwners
        ) {
            Map<CellKey, Integer> distances = new HashMap<>();
            ArrayDeque<CellKey> queue = new ArrayDeque<>();
            for (Map.Entry<CellKey, RoomType> entry : roomTypes.entrySet()) {
                if (entry.getValue() == RoomType.START) {
                    distances.put(entry.getKey(), 0);
                    queue.add(entry.getKey());
                }
            }

            while (!queue.isEmpty()) {
                CellKey current = queue.removeFirst();
                int nextDistance = distances.get(current) + 1;
                for (CellKey neighbor : current.neighbors()) {
                    if (!roomTypes.containsKey(neighbor)
                        || roomTypes.get(neighbor) == RoomType.FAIRY
                        || distances.containsKey(neighbor)
                        || !roomsAreConnected(current, neighbor, doors, roomOwners)) {
                        continue;
                    }
                    distances.put(neighbor, nextDistance);
                    queue.add(neighbor);
                }
            }
            return distances;
        }

        private static boolean roomsAreConnected(
            CellKey first,
            CellKey second,
            Set<CellKey> doors,
            Map<CellKey, String> roomOwners
        ) {
            String firstOwner = roomOwners.get(first);
            String secondOwner = roomOwners.get(second);
            if (firstOwner != null && firstOwner.equals(secondOwner)) {
                return true;
            }
            return doors.contains(first.doorTo(second));
        }

        private static CellKey outsideFairySide(CellKey door, Map<CellKey, RoomType> roomTypes) {
            return roomTypes.get(door.firstSide()) != RoomType.FAIRY ? door.firstSide() : door.secondSide();
        }
    }

    static record CellKey(int x, int z) {
        /** Rows first, then columns. */
        static final Comparator<CellKey> ORDER = Comparator.comparingInt(CellKey::z).thenComparingInt(CellKey::x);

        /** For a door point (one odd coordinate): the room cell on its low side. */
        CellKey firstSide() {
            return new CellKey(x / 2, z / 2);
        }

        /** For a door point (one odd coordinate): the room cell on its high side. */
        CellKey secondSide() {
            return new CellKey((x + 1) / 2, (z + 1) / 2);
        }

        /** The door point between this room cell and an adjacent one, or null when they are not adjacent. */
        CellKey doorTo(CellKey other) {
            return Math.abs(x - other.x) + Math.abs(z - other.z) == 1 ? new CellKey(x + other.x, z + other.z) : null;
        }

        List<CellKey> neighbors() {
            return List.of(new CellKey(x - 1, z), new CellKey(x + 1, z), new CellKey(x, z - 1), new CellKey(x, z + 1));
        }

        List<CellKey> positiveNeighbors() {
            return List.of(new CellKey(x + 1, z), new CellKey(x, z + 1));
        }
    }

    static record DoorRenderInfo(DungeonDoorKind kind, RoomType targetType, boolean colorAsSpecial, boolean targetVisited) {
    }

    private record DoorPair(String firstOwner, String secondOwner) {
        static DoorPair of(String firstOwner, String secondOwner) {
            if (firstOwner.compareTo(secondOwner) <= 0) {
                return new DoorPair(firstOwner, secondOwner);
            }
            return new DoorPair(secondOwner, firstOwner);
        }
    }
}
