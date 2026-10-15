package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static boolean isLockedDoorInfo(DoorRenderInfo door) {
        return door != null
            && (door.kind() == DungeonDoorKind.WITHER
                || door.kind() == DungeonDoorKind.BLOOD);
    }

    private static boolean isLockedObservedPoint(DungeonMapSnapshot.ObservedPoint observedPoint) {
        return observedPoint != null
            && (observedPoint.point().doorKind() == DungeonDoorKind.WITHER
                || observedPoint.point().doorKind() == DungeonDoorKind.BLOOD);
    }

    private static boolean isRealRoom(DungeonMapSnapshot snapshot, int gridX, int gridZ) {
        DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(gridX, gridZ);
        return observedPoint != null
            && observedPoint.point().kind() == DungeonScanPointKind.ROOM
            && !DungeonRoomClassifier.isEmptyCore(observedPoint.point().coreHash());
    }

    private static boolean isHorizontalDoor(int gridX, int gridZ) {
        return !isEven(gridX) && isEven(gridZ);
    }

    private static boolean isEven(int value) {
        return (value & 1) == 0;
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
        static MatchRenderPlan from(DungeonMapSnapshot snapshot) {
            return from(snapshot, KnownDungeonRoomRepository.INSTANCE);
        }

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

                    DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(roomGridX * 2, roomGridZ * 2);
                    if (observedPoint == null || observedPoint.point().kind() != DungeonScanPointKind.ROOM) {
                        continue;
                    }
                    if (DungeonRoomClassifier.isEmptyCore(observedPoint.point().coreHash())) {
                        continue;
                    }

                    roomOwners.put(roomCell, "cell:" + roomGridX + "," + roomGridZ);

                    DungeonKnownRoomCatalog.KnownCoreHint hint =
                        roomRepository.knownHintForPoint(snapshot, observedPoint.point());
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
                DungeonMapSnapshot.ObservedPoint observed = snapshot.pointAt(roomCell.x() * 2, roomCell.z() * 2);
                if (observed == null || observed.point().kind() != DungeonScanPointKind.ROOM) continue;
                RoomType current = roomTypes.getOrDefault(roomCell, RoomType.UNKNOWN);
                RoomIdentity identity = roomIdentities.get(roomCell);
                if ((current == RoomType.NORMAL || current == RoomType.UNKNOWN)
                    && (identity == null || DungeonKnownRoomCatalog.canonicalRoomType(identity.name(), RoomType.RARE,
                        observed.point().coreHash(), observed.point().stableCoreHash()) == RoomType.RARE)
                    && DungeonRoomClassifier.classifyRoom(observed.point().coreHash()) == RoomType.RARE) {
                    roomTypes.put(roomCell, RoomType.RARE);
                    if (identity != null) {
                        roomIdentities.put(roomCell, RoomIdentity.of(identity.name(), RoomType.RARE, identity.secrets()));
                    }
                }
            }

            for (DungeonMapSnapshot.RemoteRoom remoteRoom : snapshot.remoteRooms()) {
                CellKey roomCell = new CellKey(remoteRoom.roomGridX(), remoteRoom.roomGridZ());
                String remoteOwner = "remote:" + compactName(remoteRoom.source()) + ":" + roomCell.x() + "," + roomCell.z();
                remoteRoomCells.add(roomCell);
                RoomType remoteType = remoteRoom.type() == null ? RoomType.UNKNOWN : remoteRoom.type();
                String remoteName = remoteRoom.name() == null ? "" : remoteRoom.name().trim();
                DungeonMapSnapshot.ObservedPoint observed = snapshot.pointAt(roomCell.x() * 2, roomCell.z() * 2);
                int coreHash = observed == null ? 0 : observed.point().coreHash();
                int stableHash = observed == null ? 0 : observed.point().stableCoreHash();
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
                    roomTypes.put(roomCell, RoomType.RARE);
                    RoomIdentity identity = roomIdentities.get(roomCell);
                    if (identity != null) {
                        roomIdentities.put(roomCell, RoomIdentity.of(identity.name(), RoomType.RARE, identity.secrets()));
                    }
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

                    DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(gridX, gridZ);
                    if (observedPoint == null || !observedPoint.point().doorKind().visible()) {
                        continue;
                    }

                    CellKey doorCell = new CellKey(gridX, gridZ);
                    if (internalDoors.contains(doorCell)) {
                        continue;
                    }

                    String firstOwner = firstDoorSideOwner(roomOwners, gridX, gridZ);
                    String secondOwner = secondDoorSideOwner(roomOwners, gridX, gridZ);
                    if (firstOwner == null && secondOwner == null) {
                        continue;
                    }
                    if (firstOwner != null && firstOwner.equals(secondOwner)) {
                        continue;
                    }
                    if (firstOwner == null || secondOwner == null) {
                        externalDoors.put(doorCell, doorInfoFor(
                            observedPoint.point().doorKind(),
                            roomTypes,
                            visitedRooms,
                            gridX,
                            gridZ
                        ));
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
                if (!hasDistinctDoorOwners(roomOwners, gridX, gridZ)) {
                    continue;
                }
                openedDoorCells.add(doorCell);
                openedSpecialDoorCells.add(doorCell);
                externalDoors.put(doorCell, doorInfoFor(
                    DungeonDoorKind.OPEN,
                    roomTypes,
                    visitedRooms,
                    gridX,
                    gridZ
                ));
            }

            for (DungeonMapSnapshot.GridKey traversedDoor : snapshot.traversedDoors()) {
                int gridX = traversedDoor.gridX();
                int gridZ = traversedDoor.gridZ();
                CellKey doorCell = new CellKey(gridX, gridZ);
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ) || internalDoors.contains(doorCell)) {
                    continue;
                }
                if (!hasDistinctDoorOwners(roomOwners, gridX, gridZ)) {
                    continue;
                }

                DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(gridX, gridZ);
                boolean observedVisibleDoor = observedPoint != null && observedPoint.point().doorKind().visible();
                if (!observedVisibleDoor && touchesRoomType(roomTypes, gridX, gridZ, RoomType.PUZZLE)) {
                    continue;
                }
                if (isLockedObservedPoint(observedPoint)
                    && !snapshot.openedLockedDoors().contains(traversedDoor)) {
                    continue;
                }

                openedDoorCells.add(doorCell);

                String firstOwner = firstDoorSideOwner(roomOwners, gridX, gridZ);
                String secondOwner = secondDoorSideOwner(roomOwners, gridX, gridZ);
                DoorPair doorPair = DoorPair.of(firstOwner, secondOwner);
                detectedDoorByRoomPair.putIfAbsent(doorPair, doorCell);
            }
            for (CellKey doorCell : detectedDoorByRoomPair.values()) {
                DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(doorCell.x(), doorCell.z());
                DungeonDoorKind doorKind = openedDoorCells.contains(doorCell)
                    || observedPoint == null || !observedPoint.point().doorKind().visible()
                    || observedPoint.point().doorKind() == DungeonDoorKind.OPEN
                    ? DungeonDoorKind.OPEN
                    : observedPoint.point().doorKind();
                externalDoors.put(doorCell, doorInfoFor(
                    doorKind,
                    roomTypes,
                    visitedRooms,
                    doorCell.x(),
                    doorCell.z()
                ));
            }

            for (DungeonMapSnapshot.GridKey mapOpenDoor : snapshot.mapOpenDoors()) {
                int gridX = mapOpenDoor.gridX();
                int gridZ = mapOpenDoor.gridZ();
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                    continue;
                }
                CellKey doorCell = new CellKey(gridX, gridZ);
                if (internalDoors.contains(doorCell) || !hasDistinctDoorOwners(roomOwners, gridX, gridZ)) {
                    continue;
                }

                DoorRenderInfo previousInfo = externalDoors.get(doorCell);
                DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(gridX, gridZ);
                boolean knownLockedDoor = isLockedDoorInfo(previousInfo)
                    || snapshot.observedLockedDoors().contains(mapOpenDoor)
                    || isLockedObservedPoint(observedPoint);
                if (knownLockedDoor) {
                    continue;
                }
                openedDoorCells.add(doorCell);
                externalDoors.put(doorCell, doorInfoFor(
                    DungeonDoorKind.OPEN,
                    roomTypes,
                    visitedRooms,
                    gridX,
                    gridZ
                ));
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
                DoorRenderInfo remoteInfo = doorInfoFor(
                    remoteKind,
                    roomTypes,
                    visitedRooms,
                    doorCell.x(),
                    doorCell.z()
                );
                if (remoteDoor.targetType() != null && remoteDoor.targetType() != RoomType.UNKNOWN) {
                    remoteInfo = new DoorRenderInfo(
                        remoteInfo.kind(),
                        remoteDoor.targetType(),
                        remoteInfo.colorAsSpecial(),
                        remoteInfo.targetVisited() || remoteDoor.targetVisited()
                    );
                }
                externalDoors.merge(doorCell, remoteInfo, MatchRenderPlan::mergeDoorInfo);
                DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(doorCell.x(), doorCell.z());
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

        private static String firstDoorSideOwner(Map<CellKey, String> roomOwners, int gridX, int gridZ) {
            if (isHorizontalDoor(gridX, gridZ)) {
                return roomOwners.get(new CellKey((gridX - 1) / 2, gridZ / 2));
            }
            return roomOwners.get(new CellKey(gridX / 2, (gridZ - 1) / 2));
        }

        private static AdjacentRooms adjacentRoomsForDoor(int gridX, int gridZ) {
            if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                return null;
            }
            if (isHorizontalDoor(gridX, gridZ)) {
                return new AdjacentRooms(
                    new CellKey((gridX - 1) / 2, gridZ / 2),
                    new CellKey((gridX + 1) / 2, gridZ / 2)
                );
            }
            return new AdjacentRooms(
                new CellKey(gridX / 2, (gridZ - 1) / 2),
                new CellKey(gridX / 2, (gridZ + 1) / 2)
            );
        }

        private static String secondDoorSideOwner(Map<CellKey, String> roomOwners, int gridX, int gridZ) {
            if (isHorizontalDoor(gridX, gridZ)) {
                return roomOwners.get(new CellKey((gridX + 1) / 2, gridZ / 2));
            }
            return roomOwners.get(new CellKey(gridX / 2, (gridZ + 1) / 2));
        }

        private static boolean hasDistinctDoorOwners(Map<CellKey, String> roomOwners, int gridX, int gridZ) {
            String firstOwner = firstDoorSideOwner(roomOwners, gridX, gridZ);
            String secondOwner = secondDoorSideOwner(roomOwners, gridX, gridZ);
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
            for (DungeonMapSnapshot.ObservedPoint observedPoint : snapshot.points()) {
                if (observedPoint.point().kind() != DungeonScanPointKind.DOOR) {
                    continue;
                }

                int gridX = observedPoint.point().gridX();
                int gridZ = observedPoint.point().gridZ();
                if (!DungeonScanUtils.isDoorScanPoint(gridX, gridZ)) {
                    continue;
                }

                CellKey doorCell = new CellKey(gridX, gridZ);
                DungeonMapSnapshot.GridKey gridKey = new DungeonMapSnapshot.GridKey(gridX, gridZ);
                DungeonDoorKind observedKind = observedPoint.point().doorKind();
                boolean observedLocked = observedKind == DungeonDoorKind.WITHER || observedKind == DungeonDoorKind.BLOOD;
                boolean openedLocked = snapshot.openedLockedDoors().contains(gridKey);
                if (!observedLocked && !openedLocked) {
                    continue;
                }

                DungeonDoorKind renderKind = observedLocked ? observedKind : DungeonDoorKind.OPEN;
                internalDoors.remove(doorCell);
                externalDoors.merge(
                    doorCell,
                    doorInfoFor(renderKind, roomTypes, visitedRooms, gridX, gridZ),
                    MatchRenderPlan::mergeDoorInfo
                );
                if (openedLocked) {
                    openedSpecialDoorCells.add(doorCell);
                }
            }
        }

        private static boolean touchesRoomType(
            Map<CellKey, RoomType> roomTypes,
            int gridX,
            int gridZ,
            RoomType roomType
        ) {
            return firstDoorSideType(roomTypes, gridX, gridZ) == roomType
                || secondDoorSideType(roomTypes, gridX, gridZ) == roomType;
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
                    CellKey separator = separatorBetween(room, neighbor);
                    if (separator == null || hasBlockingRoomBoundary(snapshot, separator)) {
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

                AdjacentRooms adjacentRooms = adjacentRoomsForDoor(gridX, gridZ);
                if (adjacentRooms == null) {
                    continue;
                }
                String firstOwner = roomOwners.get(adjacentRooms.first());
                String secondOwner = roomOwners.get(adjacentRooms.second());
                if (firstOwner == null || secondOwner == null) {
                    continue;
                }
                RoomIdentity firstIdentity = roomIdentities.get(adjacentRooms.first());
                RoomIdentity secondIdentity = roomIdentities.get(adjacentRooms.second());
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
            DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(separator.x(), separator.z());
            if (observedPoint == null || observedPoint.point().kind() != DungeonScanPointKind.DOOR) {
                return false;
            }
            DungeonDoorKind doorKind = observedPoint.point().doorKind();
            return doorKind == DungeonDoorKind.WITHER || doorKind == DungeonDoorKind.BLOOD;
        }

        private static CellKey separatorBetween(CellKey first, CellKey second) {
            int distanceX = Math.abs(first.x() - second.x());
            int distanceZ = Math.abs(first.z() - second.z());
            if (distanceX + distanceZ != 1) {
                return null;
            }
            return new CellKey(first.x() + second.x(), first.z() + second.z());
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

        boolean isInternalDoor(int scanGridX, int scanGridZ) {
            return internalDoors.contains(new CellKey(scanGridX, scanGridZ));
        }

        boolean sameRoomOwner(CellKey first, CellKey second) {
            String firstOwner = roomOwners.get(first);
            String secondOwner = roomOwners.get(second);
            return firstOwner != null && firstOwner.equals(secondOwner);
        }

        RoomType roomTypeAt(int roomGridX, int roomGridZ) {
            return roomTypes.getOrDefault(new CellKey(roomGridX, roomGridZ), RoomType.UNKNOWN);
        }

        boolean hasRoomType(RoomType roomType) {
            return roomTypes.containsValue(roomType);
        }

        int lockedSpecialDoorCount() {
            int count = 0;
            for (Map.Entry<CellKey, DoorRenderInfo> entry : externalDoors.entrySet()) {
                if (isCountedSpecialDoor(entry.getKey(), entry.getValue())) {
                    count++;
                }
            }
            return count;
        }

        int knownNonStartSpecialDoorCount() {
            Set<CellKey> doors = new HashSet<>();
            for (Map.Entry<CellKey, DoorRenderInfo> entry : externalDoors.entrySet()) {
                DoorRenderInfo door = entry.getValue();
                if ((door.kind() == DungeonDoorKind.WITHER || door.kind() == DungeonDoorKind.BLOOD)
                    && !isExcludedBloodRushDoor(entry.getKey(), door)) {
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
            int count = 0;
            for (CellKey doorCell : bloodRushDoorPath()) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (isCountedBloodRushSpecialDoor(doorCell, door, false)) {
                    count++;
                }
            }
            return count;
        }

        int bloodRushTotalSpecialDoorCount() {
            int count = 0;
            for (CellKey doorCell : bloodRushDoorPath()) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (isCountedBloodRushSpecialDoor(doorCell, door, true)) {
                    count++;
                }
            }
            return count;
        }

        int minimumVisibleBloodRushSpecialDoorCount(DungeonMapSnapshot snapshot) {
            if (!bloodRushDoorPath().isEmpty()) {
                return bloodRushTotalSpecialDoorCount();
            }
            return visibleRawNonStartSpecialDoorCount(snapshot);
        }

        int minimumVisibleBloodRushLockedSpecialDoorCount(DungeonMapSnapshot snapshot) {
            if (!bloodRushDoorPath().isEmpty()) {
                return bloodRushLockedSpecialDoorCount();
            }
            return visibleRawNonStartSpecialDoorCount(snapshot);
        }

        Set<CellKey> bloodRushOpenedSpecialDoorCells() {
            Set<CellKey> doors = new HashSet<>();
            for (CellKey doorCell : bloodRushDoorPath()) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (openedSpecialDoorCells.contains(doorCell)
                    && isCountedBloodRushSpecialDoor(doorCell, door, true)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        Set<CellKey> minimumVisibleOpenedSpecialDoorCells(DungeonMapSnapshot snapshot) {
            return openedRawNonStartSpecialDoorCells(snapshot);
        }

        DoorRenderInfo doorAt(int scanGridX, int scanGridZ) {
            return externalDoors.get(new CellKey(scanGridX, scanGridZ));
        }

        boolean isBloodRushSpecialDoor(int scanGridX, int scanGridZ) {
            CellKey doorCell = new CellKey(scanGridX, scanGridZ);
            return bloodRushDoorPath().contains(doorCell)
                && isCountedBloodRushSpecialDoor(doorCell, externalDoors.get(doorCell), false);
        }

        boolean bloodIsNext() {
            DoorRenderInfo remainingDoor = null;
            for (CellKey doorCell : bloodRushDoorPath()) {
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
                    + " cells="
                    + cellsText(cells)
                    + " visited="
                    + ownerHasCell(cells, visitedRooms)
                    + " cleared="
                    + ownerHasCell(cells, clearedRooms)
                    + " completed="
                    + ownerHasCell(cells, completedRooms));
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
                    + isRawExcludedBloodRushDoor(doorCell, door == null ? null : door.kind())
                    + " openedSpecial="
                    + openedSpecialDoorCells.contains(doorCell)
                    + " sides="
                    + doorSidesText(doorCell));
            }

            List<CellKey> rawSpecialDoors = new ArrayList<>(rawNonStartSpecialDoors(snapshot));
            sortCells(rawSpecialDoors);
            for (CellKey doorCell : rawSpecialDoors) {
                DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(doorCell.x(), doorCell.z());
                boolean opened = snapshot.openedLockedDoors().contains(new DungeonMapSnapshot.GridKey(doorCell.x(), doorCell.z()));
                DungeonDoorKind observedKind = observedPoint == null ? null : observedPoint.point().doorKind();
                lines.add("raw-special door="
                    + cellText(doorCell)
                    + " observedKind="
                    + (observedKind == null ? "none" : observedKind)
                    + " opened="
                    + opened
                    + " rawExcluded="
                    + isRawExcludedBloodRushDoor(doorCell, observedKind)
                    + " sides="
                    + doorSidesText(doorCell));
            }
            return lines;
        }

        String bloodRushSpecialDoorSummary() {
            List<CellKey> path = bloodRushDoorPath();
            if (path.isEmpty()) {
                return "[]";
            }

            StringBuilder builder = new StringBuilder("[");
            int count = 0;
            for (CellKey doorCell : path) {
                DoorRenderInfo door = externalDoors.get(doorCell);
                if (!isCountedBloodRushSpecialDoor(doorCell, door, true)) {
                    continue;
                }
                if (count > 0) {
                    builder.append(" | ");
                }
                builder.append(doorCell.x())
                    .append(',')
                    .append(doorCell.z())
                    .append(':')
                    .append(door.kind())
                    .append(":target=")
                    .append(door.targetType());
                count++;
            }
            if (count == 0) {
                return "[]";
            }
            return builder.append(']').toString();
        }

        private boolean isCountedSpecialDoor(CellKey doorCell, DoorRenderInfo door) {
            return door != null
                && (door.kind() == DungeonDoorKind.WITHER || door.kind() == DungeonDoorKind.BLOOD)
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
            List<CellKey> path = bloodRushDoorPath();
            if (path.isEmpty()) {
                return false;
            }

            Set<String> visibleOwners = ownerSet(mapVisibleRoomCells(snapshot));
            Set<String> requiredOwners = new HashSet<>();
            for (CellKey doorCell : path) {
                if (firstDoorSideType(roomTypes, doorCell.x(), doorCell.z()) == RoomType.UNKNOWN
                    || secondDoorSideType(roomTypes, doorCell.x(), doorCell.z()) == RoomType.UNKNOWN) return false;
                addVisibleRequiredDoorOwner(requiredOwners, doorCell, true);
                addVisibleRequiredDoorOwner(requiredOwners, doorCell, false);
            }
            return visibleOwners.containsAll(requiredOwners);
        }

        private boolean bloodRushPathScanned(DungeonMapSnapshot snapshot) {
            if (bloodRushPath.isEmpty()) return false;
            for (CellKey door : bloodRushPath) {
                for (CellKey room : List.of(firstDoorSide(door.x(), door.z()), secondDoorSide(door.x(), door.z()))) {
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

        int remoteRoomSecretsFoundOnly(int roomGridX, int roomGridZ) {
            RoomProgress progress = remoteRoomProgress.get(new CellKey(roomGridX, roomGridZ));
            return progress == null ? 0 : progress.found();
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
                    DungeonMapSnapshot.ObservedPoint observedPoint = snapshot.pointAt(scanGridX, scanGridZ);
                    if (observedPoint == null || observedPoint.point().kind() != DungeonScanPointKind.ROOM) {
                        continue;
                    }
                    if (DungeonRoomClassifier.isEmptyCore(observedPoint.point().coreHash())) {
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

        private void addVisibleRequiredDoorOwner(Set<String> requiredOwners, CellKey doorCell, boolean firstSide) {
            String owner = firstSide
                ? firstDoorSideOwner(roomOwners, doorCell.x(), doorCell.z())
                : secondDoorSideOwner(roomOwners, doorCell.x(), doorCell.z());
            if (owner == null) {
                return;
            }
            requiredOwners.add(owner);
        }

        private String ownerFor(CellKey cell) {
            String owner = roomOwners.get(cell);
            return owner != null ? owner : "cell:" + cell.x() + "," + cell.z();
        }

        private String doorSidesText(CellKey doorCell) {
            String firstOwner = firstDoorSideOwner(roomOwners, doorCell.x(), doorCell.z());
            String secondOwner = secondDoorSideOwner(roomOwners, doorCell.x(), doorCell.z());
            RoomType firstType = firstDoorSideType(roomTypes, doorCell.x(), doorCell.z());
            RoomType secondType = secondDoorSideType(roomTypes, doorCell.x(), doorCell.z());
            return ownerTypeText(firstOwner, firstType) + "<->" + ownerTypeText(secondOwner, secondType);
        }

        private static String ownerTypeText(String owner, RoomType type) {
            return (owner == null ? "null" : owner) + ":" + (type == null ? RoomType.UNKNOWN : type);
        }

        private static boolean ownerHasCell(List<CellKey> cells, Set<CellKey> stateCells) {
            for (CellKey cell : cells) {
                if (stateCells.contains(cell)) {
                    return true;
                }
            }
            return false;
        }

        private static void sortCells(List<CellKey> cells) {
            cells.sort((first, second) -> {
                int zCompare = Integer.compare(first.z(), second.z());
                return zCompare != 0 ? zCompare : Integer.compare(first.x(), second.x());
            });
        }

        private static String cellsText(List<CellKey> cells) {
            if (cells.isEmpty()) {
                return "[]";
            }
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < cells.size(); index++) {
                if (index > 0) {
                    builder.append('|');
                }
                builder.append(cellText(cells.get(index)));
            }
            return builder.append(']').toString();
        }

        private static String ownersText(Set<String> owners) {
            if (owners.isEmpty()) {
                return "[]";
            }
            List<String> sortedOwners = new ArrayList<>(owners);
            sortedOwners.sort(String::compareTo);
            return "[" + String.join("|", sortedOwners) + "]";
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

        private List<CellKey> bloodRushDoorPath() {
            return bloodRushPath;
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
                if (!isBloodRushPathConnectionDoor(doorCell, door)) {
                    continue;
                }
                String firstOwner = firstDoorSideOwner(roomOwners, doorCell.x(), doorCell.z());
                String secondOwner = secondDoorSideOwner(roomOwners, doorCell.x(), doorCell.z());
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
            if (door.kind() == DungeonDoorKind.WITHER || door.kind() == DungeonDoorKind.BLOOD) {
                return true;
            }
            return includeOpened && openedSpecialDoorCells.contains(doorCell);
        }

        private static boolean isBloodRushPathConnectionDoor(CellKey doorCell, DoorRenderInfo door) {
            return door != null
                && door.kind() != DungeonDoorKind.NONE;
        }

        private boolean isExcludedBloodRushDoor(CellKey doorCell, DoorRenderInfo door) {
            return door == null
                || touchesRoomType(doorCell, RoomType.START)
                || doorCell.equals(fairyEntranceDoor);
        }

        private Set<CellKey> rawNonStartSpecialDoors(DungeonMapSnapshot snapshot) {
            Set<CellKey> doors = new HashSet<>();
            for (DungeonMapSnapshot.GridKey openedDoor : snapshot.openedLockedDoors()) {
                CellKey doorCell = new CellKey(openedDoor.gridX(), openedDoor.gridZ());
                if (!isRawExcludedBloodRushDoor(doorCell)) {
                    doors.add(doorCell);
                }
            }
            for (DungeonMapSnapshot.ObservedPoint observedPoint : snapshot.points()) {
                if (observedPoint.point().kind() != DungeonScanPointKind.DOOR) {
                    continue;
                }
                DungeonDoorKind doorKind = observedPoint.point().doorKind();
                if (doorKind != DungeonDoorKind.WITHER && doorKind != DungeonDoorKind.BLOOD) {
                    continue;
                }
                CellKey doorCell = new CellKey(observedPoint.point().gridX(), observedPoint.point().gridZ());
                if (!isRawExcludedBloodRushDoor(doorCell, doorKind)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        private Set<CellKey> visibleRawNonStartSpecialDoors(DungeonMapSnapshot snapshot) {
            Set<CellKey> doors = new HashSet<>();
            for (DungeonMapSnapshot.ObservedPoint observedPoint : snapshot.points()) {
                if (observedPoint.point().kind() != DungeonScanPointKind.DOOR) {
                    continue;
                }
                DungeonDoorKind doorKind = observedPoint.point().doorKind();
                if (doorKind != DungeonDoorKind.WITHER && doorKind != DungeonDoorKind.BLOOD) {
                    continue;
                }
                CellKey doorCell = new CellKey(observedPoint.point().gridX(), observedPoint.point().gridZ());
                if (!isRawExcludedBloodRushDoor(doorCell, doorKind)) {
                    doors.add(doorCell);
                }
            }
            return doors;
        }

        private boolean isRawExcludedBloodRushDoor(CellKey doorCell) {
            DoorRenderInfo door = externalDoors.get(doorCell);
            DungeonDoorKind doorKind = door == null ? null : door.kind();
            return isRawExcludedBloodRushDoor(doorCell, doorKind);
        }

        private boolean isRawExcludedBloodRushDoor(CellKey doorCell, DungeonDoorKind doorKind) {
            DoorRenderInfo door = externalDoors.get(doorCell);
            return touchesRoomType(doorCell, RoomType.START)
                || doorCell.equals(fairyEntranceDoor);
        }

        private boolean touchesRoomType(CellKey doorCell, RoomType roomType) {
            return firstDoorSideType(roomTypes, doorCell.x(), doorCell.z()) == roomType
                || secondDoorSideType(roomTypes, doorCell.x(), doorCell.z()) == roomType;
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
            int gridX,
            int gridZ
        ) {
            CellKey firstSide = firstDoorSide(gridX, gridZ);
            CellKey secondSide = secondDoorSide(gridX, gridZ);
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

        private static RoomType firstDoorSideType(Map<CellKey, RoomType> roomTypes, int gridX, int gridZ) {
            if (isHorizontalDoor(gridX, gridZ)) {
                return roomTypes.getOrDefault(new CellKey((gridX - 1) / 2, gridZ / 2), RoomType.UNKNOWN);
            }
            return roomTypes.getOrDefault(new CellKey(gridX / 2, (gridZ - 1) / 2), RoomType.UNKNOWN);
        }

        private static RoomType secondDoorSideType(Map<CellKey, RoomType> roomTypes, int gridX, int gridZ) {
            if (isHorizontalDoor(gridX, gridZ)) {
                return roomTypes.getOrDefault(new CellKey((gridX + 1) / 2, gridZ / 2), RoomType.UNKNOWN);
            }
            return roomTypes.getOrDefault(new CellKey(gridX / 2, (gridZ + 1) / 2), RoomType.UNKNOWN);
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
                if (!touchesRoomType(roomTypes, cell.x(), cell.z(), RoomType.FAIRY)) continue;
                CellKey target = cell.equals(entrance)
                    ? (roomTypes.get(firstDoorSide(cell.x(), cell.z())) == RoomType.FAIRY
                        ? firstDoorSide(cell.x(), cell.z()) : secondDoorSide(cell.x(), cell.z()))
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
                .filter(door -> firstDoorSideType(roomTypes, door.x(), door.z()) == RoomType.FAIRY
                    || secondDoorSideType(roomTypes, door.x(), door.z()) == RoomType.FAIRY)
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
                for (CellKey neighbor : roomNeighbors(current)) {
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
            return doors.contains(new CellKey(first.x() + second.x(), first.z() + second.z()));
        }

        private static List<CellKey> roomNeighbors(CellKey room) {
            return List.of(
                new CellKey(room.x() + 1, room.z()),
                new CellKey(room.x() - 1, room.z()),
                new CellKey(room.x(), room.z() + 1),
                new CellKey(room.x(), room.z() - 1)
            );
        }

        private static CellKey outsideFairySide(CellKey door, Map<CellKey, RoomType> roomTypes) {
            CellKey firstSide = firstDoorSide(door.x(), door.z());
            if (roomTypes.get(firstSide) != RoomType.FAIRY) {
                return firstSide;
            }
            return secondDoorSide(door.x(), door.z());
        }

        private static CellKey firstDoorSide(int gridX, int gridZ) {
            if (isHorizontalDoor(gridX, gridZ)) {
                return new CellKey((gridX - 1) / 2, gridZ / 2);
            }
            return new CellKey(gridX / 2, (gridZ - 1) / 2);
        }

        private static CellKey secondDoorSide(int gridX, int gridZ) {
            if (isHorizontalDoor(gridX, gridZ)) {
                return new CellKey((gridX + 1) / 2, gridZ / 2);
            }
            return new CellKey(gridX / 2, (gridZ + 1) / 2);
        }
    }

    static record CellKey(int x, int z) {
        List<CellKey> positiveNeighbors() {
            return List.of(new CellKey(x + 1, z), new CellKey(x, z + 1));
        }
    }

    static record DoorRenderInfo(DungeonDoorKind kind, RoomType targetType, boolean colorAsSpecial, boolean targetVisited) {
    }

    private record AdjacentRooms(CellKey first, CellKey second) {
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
